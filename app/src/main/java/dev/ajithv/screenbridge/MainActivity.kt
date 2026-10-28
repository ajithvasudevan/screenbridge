package dev.ajithv.screenbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Status screen: shows whether the a11y service / HTTP / MQTT are up and how to reach them. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private var promptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Screen Bridge"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, dp(16))
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(button("Open Accessibility Settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Refresh") { refresh() })

        root.addView(TextView(this).apply {
            text = HELP
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(20), 0, 0)
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (isServiceEnabled()) promptShown = false   // re-arm for next time it gets disabled
        else promptEnable()                            // not granted -> ask on launch
    }

    /** If the a11y service isn't enabled, offer to open Accessibility settings (once per visit). */
    private fun promptEnable() {
        if (promptShown || isFinishing) return
        promptShown = true
        AlertDialog.Builder(this)
            .setTitle("Enable Screen Bridge")
            .setMessage("Screen Bridge needs its Accessibility service to read and control the " +
                "screen, but it's currently OFF. Open Accessibility settings to turn it on?\n\n" +
                "OxygenOS: if the toggle is greyed out, first use App info ▸ ⋮ ▸ Allow restricted settings.")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        val ip = BridgeService.localIp()
        val port = BuildConfig.HTTP_PORT
        val sb = StringBuilder()
        sb.append("Accessibility service: ").append(if (enabled) "ENABLED ✓" else "DISABLED ✗").append('\n')
        sb.append("HTTP server: ")
            .append(if (BridgeService.httpRunning) "http://$ip:$port  (running)" else "not running")
            .append('\n')
        sb.append("MQTT: ").append(BuildConfig.BROKER_HOST).append(':').append(BuildConfig.BROKER_PORT)
            .append("  base '").append(BuildConfig.MQTT_BASE).append("'  ")
            .append(if (BridgeService.mqttConnected) "connected ✓" else "offline").append('\n')
        sb.append("Auth token: ").append(if (BuildConfig.AUTH_TOKEN.isEmpty()) "none (LAN open)" else "required")
        if (!enabled) sb.append("\n\n→ Enable \"Screen Bridge\" in Accessibility settings.\n")
            .append("   On OxygenOS first allow restricted settings:\n")
            .append("   App info ▸ ⋮ ▸ Allow restricted settings.")
        status.text = sb.toString()
    }

    private fun isServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val target = ComponentName(this, BridgeService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (s in splitter) if (ComponentName.unflattenFromString(s) == target) return true
        return false
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val HELP = """Control from the LAN (replace IP:PORT):

  curl IP:PORT/ping
  curl 'IP:PORT/hierarchy?mode=flat'
  curl IP:PORT/screenshot?format=jpeg&scale=0.5 -o s.jpg
  curl IP:PORT/rpc -d '{"op":"tap","x":540,"y":1200}'
  curl IP:PORT/rpc -d '{"op":"click","textContains":"Login"}'
  curl IP:PORT/rpc -d '{"op":"settext","id":"search_src","value":"CHANDRAG"}'
  curl IP:PORT/rpc -d '{"op":"launch","pkg":"com.moveinsync.ets"}'

MQTT: publish JSON command to  <base>/cmd
      (or <base>/cmd/<op> with args as the payload),
      result on <base>/result, image on <base>/screenshot.

ops: ping hierarchy tap longpress swipe drag click longclick
     settext scroll global back home recents notifications
     launch foreground find wait screenshot"""
    }
}
