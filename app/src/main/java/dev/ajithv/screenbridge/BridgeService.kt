package dev.ajithv.screenbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Arrays
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AccessibilityService that exposes the live screen (node tree + screenshots) and
 * device control (gestures, node actions, app launch) to a local agent over an
 * in-process HTTP server and an MQTT client. Both channels funnel through [handle].
 */
class BridgeService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenBridge"

        @Volatile var instance: BridgeService? = null
        @Volatile var mqttConnected = false
        @Volatile var httpRunning = false
        @Volatile var lastForegroundPkg: String? = null
        @Volatile var lastForegroundCls: String? = null

        /** First non-loopback IPv4, preferring a wlan interface. "?" if none. */
        fun localIp(): String {
            var fallback: String? = null
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.contains("wlan", true)) return ip
                        if (fallback == null) fallback = ip
                    }
                }
            } catch (_: Exception) {}
            return fallback ?: "?"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val shotExec = Executors.newSingleThreadExecutor()
    private var http: HttpServer? = null
    private var mqtt: MqttBridge? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")

        try {
            http = HttpServer(BuildConfig.HTTP_PORT, this).also { it.start() }
            httpRunning = true
            Log.i(TAG, "HTTP server listening on ${BuildConfig.HTTP_PORT}")
        } catch (e: Exception) {
            httpRunning = false
            Log.e(TAG, "HTTP server failed to start", e)
        }

        try {
            if (BuildConfig.BROKER_HOST.isNotEmpty()) {
                mqtt = MqttBridge(this).also { it.connect() }
            } else {
                Log.i(TAG, "no broker configured; MQTT disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MQTT failed to start", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { lastForegroundPkg = it.toString() }
            event.className?.let { lastForegroundCls = it.toString() }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        try { http?.stop() } catch (_: Exception) {}
        try { mqtt?.disconnect() } catch (_: Exception) {}
        http = null
        mqtt = null
        instance = null
        mqttConnected = false
        httpRunning = false
    }

    // ------------------------------------------------------------ command entry

    /** Parse and run one command object. Always returns a JSON result (never throws). */
    fun handle(cmd: JSONObject): JSONObject {
        val op = cmd.optString("op", cmd.optString("method", "")).trim().lowercase()
        val res = JSONObject()
        if (cmd.has("id")) res.put("id", cmd.get("id"))
        // _cid = transport correlation id, echoed back; kept SEPARATE from the `id` selector so an
        // MQTT client can match replies without clobbering a find/click/tap_node id selector.
        if (cmd.has("_cid")) res.put("_cid", cmd.get("_cid"))
        res.put("op", op)
        try {
            when (op) {
                "", "ping", "status" -> {
                    res.put("ok", true)
                    res.put("service", "screenbridge")
                    res.put("version", BuildConfig.VERSION_NAME)
                    res.put("http_port", BuildConfig.HTTP_PORT)
                    res.put("http", httpRunning)
                    res.put("mqtt", mqttConnected)
                    res.put("ip", localIp())
                    res.put("foreground", lastForegroundPkg)
                    res.put("activity", lastForegroundCls)
                }

                "hierarchy", "dump", "tree" -> {
                    dumpInto(res, cmd.optString("mode", "tree"),
                        cmd.optInt("maxDepth", 80), cmd.optInt("maxNodes", 4000))
                }

                "tap", "click_xy" -> {
                    val x = cmd.getInt("x"); val y = cmd.getInt("y")
                    res.put("ok", gestureTap(x, y, cmd.optLong("duration", 50)))
                }

                "longpress", "longclick_xy" -> {
                    val x = cmd.getInt("x"); val y = cmd.getInt("y")
                    res.put("ok", gestureTap(x, y, cmd.optLong("duration", 600)))
                }

                "swipe", "drag" -> {
                    res.put("ok", gestureSwipe(
                        cmd.getInt("x1"), cmd.getInt("y1"),
                        cmd.getInt("x2"), cmd.getInt("y2"),
                        cmd.optLong("duration", if (op == "drag") 800 else 300)))
                }

                "click", "click_node" -> {
                    val s = sel(cmd)
                    val n = findNode(s, allWin(cmd), cmd.optBoolean("visible_only", false))
                    if (n == null) { res.put("ok", false); res.put("error", "node not found") }
                    else {
                        val target = clickableSelfOrAncestor(n) ?: n
                        res.put("ok", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        res.put("node", nodeJson(n))
                    }
                }

                // find node by selector, then GESTURE-tap its center (or a dx/dy fraction of its
                // bounds). Handles WebView items that ignore ACTION_CLICK — one op, no round-trip.
                "tap_node" -> {
                    val s = sel(cmd)
                    val visOnly = cmd.optBoolean("visible_only", true)
                    val nodes = collect(s, allWin(cmd), visOnly)
                    val n = nodes.getOrNull(s.index)
                    if (n == null) { res.put("ok", false); res.put("error", "node not found (matches=${nodes.size})") }
                    else {
                        val r = Rect(); n.getBoundsInScreen(r)
                        val dx = cmd.optDouble("dx", 0.5); val dy = cmd.optDouble("dy", 0.5)
                        val x = (r.left + dx * (r.right - r.left)).toInt()
                        val y = (r.top + dy * (r.bottom - r.top)).toInt()
                        val lp = cmd.optBoolean("longpress", false)
                        res.put("ok", gestureTap(x, y, cmd.optLong("duration", if (lp) 600 else 50)))
                        res.put("x", x); res.put("y", y)
                        res.put("matches", nodes.size); res.put("node", nodeJson(n))
                    }
                }

                "longclick" -> {
                    val n = findNode(sel(cmd), allWin(cmd))
                    if (n == null) { res.put("ok", false); res.put("error", "node not found") }
                    else res.put("ok", n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                }

                "settext", "set_text", "input", "text" -> {
                    val value = cmd.optString("value", cmd.optString("text", ""))
                    val target = if (hasSelector(cmd)) findNode(sel(cmd), allWin(cmd))
                                 else (findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable())
                    if (target == null) { res.put("ok", false); res.put("error", "no editable node") }
                    else {
                        val b = Bundle()
                        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                        res.put("ok", target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b))
                    }
                }

                // Simulate typing into a field: focus it, clear, then grow the text ONE CHAR AT A
                // TIME (incremental ACTION_SET_TEXT). This fires a WebView's JS input/autocomplete
                // filter, which a single whole-word set-text does not. Args: a selector (or the
                // focused editable), value, per_char_ms, clear, tap_first.
                "type" -> {
                    val value = cmd.optString("value", cmd.optString("text", ""))
                    fun resolve(): AccessibilityNodeInfo? =
                        if (hasSelector(cmd)) findNode(sel(cmd), allWin(cmd), cmd.optBoolean("visible_only", false))
                        else (findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable())
                    var node = resolve()
                    if (node == null) { res.put("ok", false); res.put("error", "no editable node") }
                    else {
                        if (cmd.optBoolean("tap_first", true)) {   // focus via a real tap (opens IME)
                            val r = Rect(); node!!.getBoundsInScreen(r)
                            gestureTap((r.left + r.right) / 2, (r.top + r.bottom) / 2, 50)
                            Thread.sleep(300)
                            node = resolve() ?: node               // re-grab after focus
                        }
                        // reuse the SAME node for even pacing; re-resolve only if a keystroke fails
                        // (re-finding before every key made the cadence jittery and the filter flaky).
                        fun setText(s: String): Boolean {
                            val b = Bundle()
                            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
                            if (node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) == true) return true
                            node = resolve()
                            return node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) == true
                        }
                        val perChar = cmd.optLong("per_char_ms", 150).coerceIn(0, 2000)
                        var okAll = true
                        if (cmd.optBoolean("clear", true)) { setText(""); Thread.sleep(150) }
                        for (i in 1..value.length) {
                            if (!setText(value.substring(0, i))) okAll = false
                            Thread.sleep(perChar)
                        }
                        res.put("ok", okAll)
                        res.put("text", resolve()?.text?.toString())
                    }
                }

                "scroll" -> {
                    val dir = cmd.optString("direction", "forward").lowercase()
                    val back = dir == "backward" || dir == "up" || dir == "prev" || dir == "previous"
                    val action = if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                 else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    val target = if (hasSelector(cmd)) findNode(sel(cmd), allWin(cmd)) else firstScrollable()
                    if (target == null) { res.put("ok", false); res.put("error", "no scrollable node") }
                    else res.put("ok", target.performAction(action))
                }

                "global" -> {
                    val name = cmd.optString("action", "")
                    val code = globalAction(name)
                    if (code < 0) { res.put("ok", false); res.put("error", "unknown action $name") }
                    else res.put("ok", performGlobalAction(code))
                }
                "back"     -> res.put("ok", performGlobalAction(GLOBAL_ACTION_BACK))
                "home"     -> res.put("ok", performGlobalAction(GLOBAL_ACTION_HOME))
                "recents"  -> res.put("ok", performGlobalAction(GLOBAL_ACTION_RECENTS))
                "notifications" -> res.put("ok", performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS))

                "launch", "open" -> {
                    val pkg = cmd.optString("pkg", cmd.optString("package", ""))
                    val i = packageManager.getLaunchIntentForPackage(pkg)
                    if (i == null) { res.put("ok", false); res.put("error", "no launch intent for $pkg") }
                    else {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        // clear=true resets the app's task to its root activity (discards a stuck
                        // WebView / blank loading screen) without a force-stop -> clean start, no adb.
                        if (cmd.optBoolean("clear", false)) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(i)
                        res.put("ok", true)
                    }
                }

                "foreground" -> {
                    res.put("ok", true)
                    res.put("package", rootInActiveWindow?.packageName?.toString() ?: lastForegroundPkg)
                    res.put("activity", lastForegroundCls)
                }

                "find" -> {
                    val matches = collect(sel(cmd), allWin(cmd), cmd.optBoolean("visible_only", false))
                    val arr = JSONArray()
                    val cap = cmd.optInt("limit", 50)
                    for (n in matches.take(cap)) arr.put(nodeJson(n))
                    res.put("ok", true)
                    res.put("count", matches.size)
                    res.put("nodes", arr)
                }

                "get_text" -> {
                    val n = findNode(sel(cmd), allWin(cmd), cmd.optBoolean("visible_only", false))
                    if (n == null) { res.put("ok", false); res.put("error", "node not found") }
                    else {
                        res.put("ok", true)
                        res.put("text", n.text?.toString())
                        res.put("desc", n.contentDescription?.toString())
                        res.put("node", nodeJson(n))
                    }
                }

                // dump every window (dialogs, toasts, overlays), not just the active one.
                "windows" -> {
                    val flat = !cmd.optString("mode", "flat").equals("tree", true)
                    val arr = JSONArray()
                    try {
                        for (w in windows) {
                            val root = w.root ?: continue
                            val wo = JSONObject()
                            wo.put("window", w.id); wo.put("type", w.type)
                            wo.put("active", w.isActive); wo.put("focused", w.isFocused)
                            w.title?.let { wo.put("title", it.toString()) }
                            wo.put("package", root.packageName?.toString())
                            val wr = Rect(); w.getBoundsInScreen(wr)
                            wo.put("bounds", JSONArray(listOf(wr.left, wr.top, wr.right, wr.bottom)))
                            val counter = intArrayOf(0)
                            if (flat) {
                                val a = JSONArray(); flatten(root, a, counter, 2000); wo.put("elements", a)
                            } else {
                                wo.put("tree", treeJson(root, 0, 60, counter, 2000))
                            }
                            arr.put(wo)
                        }
                    } catch (e: Exception) { res.put("warn", e.message) }
                    res.put("ok", true); res.put("count", arr.length()); res.put("windows", arr)
                }

                // wait until a selector is present (default), gone, or the screen is visually stable.
                "wait" -> {
                    val timeout = cmd.optLong("timeout", 8000)
                    val interval = cmd.optLong("interval", 300)
                    val until = cmd.optString("until", "present").lowercase()
                    val start = System.currentTimeMillis()
                    val deadline = start + timeout
                    var done = false
                    if (until == "stable") {
                        done = waitStable(deadline, interval.coerceAtLeast(1000))
                    } else {
                        val s = sel(cmd)
                        val allw = allWin(cmd)
                        val vis = cmd.optBoolean("visible_only", true)
                        val wantGone = until == "gone" || until == "absent"
                        while (System.currentTimeMillis() < deadline) {
                            val hit = collect(s, allw, vis).getOrNull(s.index)
                            if (wantGone) { if (hit == null) { done = true; break } }
                            else if (hit != null) { done = true; res.put("node", nodeJson(hit)); break }
                            Thread.sleep(interval)
                        }
                    }
                    res.put("ok", done)
                    res.put("until", until)
                    res.put("waited_ms", System.currentTimeMillis() - start)
                }

                // OCR the current screen (ML Kit, on-device). Returns text lines with screen bounds;
                // `contains` filters to matching lines (each has cx/cy ready to tap).
                "ocr" -> {
                    val bmp = captureBitmap()
                    if (bmp == null) { res.put("ok", false); res.put("error", "capture failed") }
                    else try {
                        val lines = runOcr(bmp, strOrNull(cmd, "contains"))
                        res.put("ok", true); res.put("count", lines.length()); res.put("lines", lines)
                    } finally { bmp.recycle() }
                }

                "screenshot", "screencap", "image" -> {
                    val fmt = cmd.optString("format", "jpeg")
                    val scale = cmd.optDouble("scale", 1.0)
                    val quality = cmd.optInt("quality", 80)
                    val bytes = captureImage(fmt, scale, quality)
                    if (bytes == null) { res.put("ok", false); res.put("error", "capture failed") }
                    else {
                        res.put("ok", true)
                        res.put("format", if (fmt.equals("png", true)) "png" else "jpeg")
                        res.put("bytes", bytes.size)
                        res.put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                }

                "ops", "help" -> {
                    res.put("ok", true)
                    res.put("ops", JSONArray(listOf(
                        "ping", "hierarchy", "windows", "tap", "longpress", "swipe", "drag",
                        "click", "tap_node", "longclick", "settext", "type", "scroll", "global",
                        "back", "home", "recents", "notifications", "launch",
                        "foreground", "find", "get_text", "wait", "ocr", "screenshot")))
                    res.put("note", "any op accepts shot:true (+shot_scale/shot_quality/shot_delay) to " +
                        "return a base64 screenshot; selectors accept allWindows + visible_only; " +
                        "tap_node accepts dx/dy + longpress; wait accepts until=present|gone|stable")
                }

                else -> { res.put("ok", false); res.put("error", "unknown op: $op") }
            }
        } catch (e: Exception) {
            res.put("ok", false)
            res.put("error", (e.message ?: e.toString()))
            Log.e(TAG, "handle($op) failed", e)
        }

        // act+screenshot combo: any op can return the resulting screen in the same response.
        if (op != "screenshot" && op != "ocr" && (cmd.optBoolean("shot") ||
                cmd.opt("shot") is JSONObject || "true".equals(cmd.optString("shot"), true))) {
            try {
                val delay = cmd.optLong("shot_delay", 400)
                if (delay > 0) Thread.sleep(delay)
                val bytes = captureImage(cmd.optString("shot_format", "jpeg"),
                    cmd.optDouble("shot_scale", 0.5), cmd.optInt("shot_quality", 60))
                if (bytes != null) {
                    res.put("screenshot", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    res.put("screenshot_bytes", bytes.size)
                }
            } catch (_: Exception) {}
        }
        return res
    }

    // ------------------------------------------------------------------ gestures

    private fun gestureTap(x: Int, y: Int, dur: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceIn(1, 60000)))
            .build()
        return dispatchAndWait(gd)
    }

    private fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceIn(1, 60000)))
            .build()
        return dispatchAndWait(gd)
    }

    private fun dispatchAndWait(gd: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val dispatched = dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok[0] = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { ok[0] = false; latch.countDown() }
        }, main)
        if (!dispatched) return false
        latch.await(10, TimeUnit.SECONDS)
        return ok[0]
    }

    // --------------------------------------------------------------- screenshot

    /** Synchronously take a screenshot. Retries once past the ~1s system rate limit. */
    private fun captureBitmap(): Bitmap? {
        for (attempt in 0..1) {
            val latch = CountDownLatch(1)
            val holder = arrayOfNulls<Bitmap>(1)
            val failed = intArrayOf(0)
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        holder[0] = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "screenshot convert failed", e)
                    }
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) {
                    failed[0] = errorCode
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                    latch.countDown()
                }
            })
            latch.await(6, TimeUnit.SECONDS)
            if (holder[0] != null) return holder[0]
            // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT == 2 -> wait out the rate limit and retry
            if (attempt == 0) Thread.sleep(1100) else return null
        }
        return null
    }

    fun captureImage(format: String, scale: Double, quality: Int): ByteArray? {
        var bmp = captureBitmap() ?: return null
        try {
            if (scale in 0.05..0.999) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled !== bmp) { bmp.recycle(); bmp = scaled }
            }
            val out = ByteArrayOutputStream()
            if (format.equals("png", true)) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            else bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------- node queries

    private fun hasSelector(cmd: JSONObject): Boolean =
        cmd.has("text") || cmd.has("textContains") || cmd.has("contains") ||
        cmd.has("desc") || cmd.has("descContains") || cmd.has("id") ||
        cmd.has("cls") || cmd.has("class") || cmd.has("clickable") || cmd.has("editable")

    private fun sel(cmd: JSONObject) = Sel(cmd)

    private fun allWin(cmd: JSONObject): Boolean =
        cmd.optBoolean("allWindows", false) || cmd.optBoolean("all_windows", false)

    private fun strOrNull(j: JSONObject, k: String): String? =
        if (j.has(k) && !j.isNull(k)) j.getString(k) else null

    /** Roots to search: just the active window, or every window when [allWindows]. */
    private fun roots(allWindows: Boolean): List<AccessibilityNodeInfo> {
        if (!allWindows) return listOfNotNull(rootInActiveWindow)
        val list = ArrayList<AccessibilityNodeInfo>()
        try { for (w in windows) w.root?.let { list.add(it) } } catch (_: Exception) {}
        if (list.isEmpty()) rootInActiveWindow?.let { list.add(it) }
        return list
    }

    private fun isUsable(n: AccessibilityNodeInfo): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        return n.isVisibleToUser && r.width() > 0 && r.height() > 0
    }

    /** Nodes matching [s] (DFS, depth-bounded). Optionally across all windows / visible-only. */
    private fun collect(s: Sel, allWindows: Boolean = false, visibleOnly: Boolean = false): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (root in roots(allWindows)) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var guard = 0
            while (stack.isNotEmpty() && guard < 8000) {
                guard++
                val n = stack.removeLast()
                if (s.matches(n) && (!visibleOnly || isUsable(n))) out.add(n)
                // push children in reverse so they pop in document order -> `index` matches the
                // visual/`hierarchy` order (index 0 = first/topmost match).
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    private fun findNode(s: Sel, allWindows: Boolean = false, visibleOnly: Boolean = false): AccessibilityNodeInfo? =
        collect(s, allWindows, visibleOnly).getOrNull(s.index)

    /** Poll low-res screenshots until two consecutive frames are byte-identical (or timeout). */
    private fun waitStable(deadline: Long, interval: Long): Boolean {
        var prev: Int? = null
        while (System.currentTimeMillis() < deadline) {
            val bytes = captureImage("jpeg", 0.2, 40)
            val h = bytes?.let { Arrays.hashCode(it) }
            if (h != null && h == prev) return true
            prev = h
            Thread.sleep(interval)
        }
        return false
    }

    /** On-device OCR of [bmp]; returns text lines with screen-coord bounds (filtered by [contains]). */
    private fun runOcr(bmp: Bitmap, contains: String?): JSONArray {
        val arr = JSONArray()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 12, TimeUnit.SECONDS)
            for (block in text.textBlocks) for (line in block.lines) {
                val t = line.text
                if (contains != null && !t.contains(contains, true)) continue
                val bb = line.boundingBox ?: continue
                val o = JSONObject()
                o.put("text", t)
                o.put("bounds", JSONArray(listOf(bb.left, bb.top, bb.right, bb.bottom)))
                o.put("cx", (bb.left + bb.right) / 2)
                o.put("cy", (bb.top + bb.bottom) / 2)
                arr.put(o)
            }
        } finally {
            try { recognizer.close() } catch (_: Exception) {}
        }
        return arr
    }

    private fun firstEditable(): AccessibilityNodeInfo? =
        collect(Sel(JSONObject().put("editable", true))).firstOrNull()

    private fun firstScrollable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 8000) {
            guard++
            val n = stack.removeLast()
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var hops = 0
        while (cur != null && hops < 15) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return node // fall back to the node itself; ACTION_CLICK may still be handled
    }

    // ------------------------------------------------------------ serialization

    private fun dumpInto(res: JSONObject, mode: String, maxDepth: Int, maxNodes: Int) {
        val root = rootInActiveWindow
        if (root == null) { res.put("ok", false); res.put("error", "no active window"); return }
        res.put("ok", true)
        res.put("package", root.packageName?.toString())
        val r = Rect(); root.getBoundsInScreen(r)
        res.put("width", r.right); res.put("height", r.bottom)
        if (mode.equals("flat", true) || mode.equals("elements", true)) {
            val arr = JSONArray()
            val counter = intArrayOf(0)
            flatten(root, arr, counter, maxNodes)
            res.put("count", arr.length())
            res.put("elements", arr)
        } else {
            val counter = intArrayOf(0)
            res.put("tree", treeJson(root, 0, maxDepth, counter, maxNodes))
            res.put("count", counter[0])
        }
    }

    /** Interactable / labelled nodes only, as a flat list — the agent-friendly view. */
    private fun flatten(n: AccessibilityNodeInfo, arr: JSONArray, counter: IntArray, maxNodes: Int) {
        if (counter[0] >= maxNodes) return
        val t = n.text?.toString() ?: ""
        val d = n.contentDescription?.toString() ?: ""
        val interesting = n.isClickable || n.isLongClickable || n.isEditable ||
            n.isScrollable || n.isCheckable || t.isNotEmpty() || d.isNotEmpty()
        if (interesting) { arr.put(nodeJson(n)); counter[0]++ }
        for (i in 0 until n.childCount) n.getChild(i)?.let { flatten(it, arr, counter, maxNodes) }
    }

    private fun treeJson(n: AccessibilityNodeInfo, depth: Int, maxDepth: Int,
                         counter: IntArray, maxNodes: Int): JSONObject {
        counter[0]++
        val o = nodeJson(n)
        if (depth < maxDepth && counter[0] < maxNodes && n.childCount > 0) {
            val kids = JSONArray()
            for (i in 0 until n.childCount) {
                if (counter[0] >= maxNodes) break
                n.getChild(i)?.let { kids.put(treeJson(it, depth + 1, maxDepth, counter, maxNodes)) }
            }
            if (kids.length() > 0) o.put("children", kids)
        }
        return o
    }

    private fun nodeJson(n: AccessibilityNodeInfo): JSONObject {
        val o = JSONObject()
        n.text?.toString()?.takeIf { it.isNotEmpty() }?.let { o.put("text", it) }
        n.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { o.put("desc", it) }
        n.viewIdResourceName?.let { o.put("id", it) }
        n.className?.toString()?.let { o.put("cls", it) }
        val r = Rect(); n.getBoundsInScreen(r)
        o.put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        o.put("cx", (r.left + r.right) / 2)
        o.put("cy", (r.top + r.bottom) / 2)
        if (n.isClickable) o.put("clickable", true)
        if (n.isLongClickable) o.put("longClickable", true)
        if (n.isEditable) o.put("editable", true)
        if (n.isScrollable) o.put("scrollable", true)
        if (n.isCheckable) o.put("checkable", true)
        if (n.isChecked) o.put("checked", true)
        if (n.isSelected) o.put("selected", true)
        if (n.isFocused) o.put("focused", true)
        if (n.isPassword) o.put("password", true)
        if (!n.isEnabled) o.put("enabled", false)
        return o
    }

    private fun globalAction(name: String): Int = when (name.trim().lowercase()) {
        "back" -> GLOBAL_ACTION_BACK
        "home" -> GLOBAL_ACTION_HOME
        "recents" -> GLOBAL_ACTION_RECENTS
        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
        "quicksettings", "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
        "powerdialog", "power" -> GLOBAL_ACTION_POWER_DIALOG
        "lock", "lockscreen" -> GLOBAL_ACTION_LOCK_SCREEN
        "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
        "dpad_up" -> GLOBAL_ACTION_DPAD_UP
        "dpad_down" -> GLOBAL_ACTION_DPAD_DOWN
        "dpad_left" -> GLOBAL_ACTION_DPAD_LEFT
        "dpad_right" -> GLOBAL_ACTION_DPAD_RIGHT
        "dpad_center" -> GLOBAL_ACTION_DPAD_CENTER
        else -> -1
    }

    /** A screen-element selector parsed from a command object. */
    inner class Sel(j: JSONObject) {
        private val text = str(j, "text")
        private val textContains = str(j, "textContains") ?: str(j, "contains")
        private val desc = str(j, "desc")
        private val descContains = str(j, "descContains")
        private val label = str(j, "label")   // contains-match against text OR content-description
        private val id = str(j, "id")
        private val cls = str(j, "cls") ?: str(j, "class")
        private val clickable = if (j.has("clickable")) j.optBoolean("clickable") else null
        private val editable = if (j.has("editable")) j.optBoolean("editable") else null
        val index = j.optInt("index", 0)

        fun matches(n: AccessibilityNodeInfo): Boolean {
            val t = n.text?.toString() ?: ""
            val d = n.contentDescription?.toString() ?: ""
            val vid = n.viewIdResourceName ?: ""
            val c = n.className?.toString() ?: ""
            if (text != null && !t.equals(text, true)) return false
            if (textContains != null && !t.contains(textContains, true)) return false
            if (desc != null && !d.equals(desc, true)) return false
            if (descContains != null && !d.contains(descContains, true)) return false
            if (label != null && !(t.contains(label, true) || d.contains(label, true))) return false
            if (id != null && !vid.endsWith(id)) return false
            if (cls != null && !c.contains(cls, true)) return false
            if (clickable != null && n.isClickable != clickable) return false
            if (editable != null && n.isEditable != editable) return false
            // an empty selector matches nothing (avoid returning the root for a no-op find)
            return text != null || textContains != null || desc != null || descContains != null ||
                label != null || id != null || cls != null || clickable != null || editable != null
        }

        private fun str(j: JSONObject, k: String): String? =
            if (j.has(k) && !j.isNull(k)) j.getString(k) else null
    }
}
