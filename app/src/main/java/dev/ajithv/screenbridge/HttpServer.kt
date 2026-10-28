package dev.ajithv.screenbridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Tiny HTTP front end over [BridgeService.handle]. Routes:
 *   GET  /                 -> help / status JSON
 *   GET  /ping             -> status JSON
 *   GET  /hierarchy?mode=  -> node tree (mode=tree|flat)
 *   GET  /screenshot?...   -> binary PNG/JPEG  (format, scale, quality params)
 *   GET  /rpc?op=...&...   -> run command from query params, JSON result
 *   POST /rpc  {json}      -> run command from JSON body, JSON result
 * If AUTH_TOKEN is set, every request must carry it (header X-Bridge-Token or ?token=).
 */
class HttpServer(port: Int, private val svc: BridgeService) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!authOk(session)) return err(Response.Status.UNAUTHORIZED, "unauthorized")
            when (session.uri.trimEnd('/').ifEmpty { "/" }) {
                "/" -> json(help())
                "/ping", "/status" -> json(svc.handle(JSONObject().put("op", "status")))
                "/hierarchy", "/dump", "/tree" ->
                    json(svc.handle(JSONObject()
                        .put("op", "hierarchy")
                        .put("mode", q(session, "mode", "tree"))))
                "/screenshot" -> screenshot(session)
                "/rpc" -> json(svc.handle(readCmd(session)))
                else -> err(Response.Status.NOT_FOUND, "no route ${session.uri}")
            }
        } catch (e: Exception) {
            Log.e(BridgeService.TAG, "http serve error", e)
            err(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun screenshot(session: IHTTPSession): Response {
        val fmt = q(session, "format", "png")
        val scale = q(session, "scale", "1").toDoubleOrNull() ?: 1.0
        val quality = q(session, "quality", "90").toIntOrNull() ?: 90
        val bytes = svc.captureImage(fmt, scale, quality)
            ?: return err(Response.Status.INTERNAL_ERROR, "screenshot failed")
        val mime = if (fmt.equals("png", true)) "image/png" else "image/jpeg"
        val resp = newFixedLengthResponse(
            Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    /** Build a command object from a POST JSON body, or from GET query params. */
    private fun readCmd(session: IHTTPSession): JSONObject {
        if (session.method == Method.POST || session.method == Method.PUT) {
            val files = HashMap<String, String>()
            try { session.parseBody(files) } catch (_: Exception) {}
            val body = files["postData"]
            if (!body.isNullOrBlank()) {
                try { return JSONObject(body) } catch (_: Exception) {}
            }
            // Fallback: a JSON body sent as x-www-form-urlencoded (curl -d default) arrives as a
            // single parameter key with no value — recover it so `curl -d '{...}'` just works.
            for (k in session.parameters.keys) {
                val t = k.trim()
                if (t.startsWith("{") && t.endsWith("}")) {
                    try { return JSONObject(t) } catch (_: Exception) {}
                }
            }
        }
        val cmd = JSONObject()
        for ((k, v) in session.parameters) {
            val value = v.firstOrNull() ?: continue
            if (k == "token") continue
            // coerce obvious ints so x/y/duration arrive as numbers
            val asInt = value.toIntOrNull()
            if (asInt != null) cmd.put(k, asInt) else cmd.put(k, value)
        }
        return cmd
    }

    private fun authOk(session: IHTTPSession): Boolean {
        val token = BuildConfig.AUTH_TOKEN
        if (token.isEmpty()) return true
        val header = session.headers["x-bridge-token"]
        if (header == token) return true
        return q(session, "token", "") == token
    }

    private fun q(session: IHTTPSession, key: String, def: String): String =
        session.parameters[key]?.firstOrNull() ?: def

    private fun json(o: JSONObject): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun err(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, "application/json",
            JSONObject().put("ok", false).put("error", msg).toString())

    private fun help(): JSONObject = JSONObject()
        .put("ok", true)
        .put("service", "screenbridge")
        .put("version", BuildConfig.VERSION_NAME)
        .put("ip", BridgeService.localIp())
        .put("routes", org.json.JSONArray(listOf(
            "GET /ping", "GET /hierarchy?mode=tree|flat",
            "GET /screenshot?format=png|jpeg&scale=1.0&quality=90",
            "GET /rpc?op=tap&x=..&y=..", "POST /rpc {json command}")))
}
