package dev.ajithv.screenbridge

import android.os.Build
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * MQTT control channel. Subscribes:
 *   <base>/cmd       payload = JSON command object  -> result to <base>/result
 *   <base>/cmd/<op>  payload = JSON args (op taken from topic suffix)
 * Publishes:
 *   <base>/availability   retained online/offline (LWT)
 *   <base>/result         every command result (screenshots: compact ack only)
 *   <base>/screenshot     screenshot result incl. base64 image
 */
class MqttBridge(private val svc: BridgeService) {

    private val base = BuildConfig.MQTT_BASE
    private var client: MqttAsyncClient? = null
    // Handle commands OFF the Paho callback thread so a slow/large op never blocks message
    // delivery or keepalive (a synchronous handler once wedged the whole MQTT channel).
    private val worker = Executors.newSingleThreadExecutor()
    private val MAX_RESULT = 180_000   // bytes; larger results (hierarchy/windows) -> use HTTP

    fun connect() {
        val uri = "tcp://${BuildConfig.BROKER_HOST}:${BuildConfig.BROKER_PORT}"
        val clientId = "screenbridge-${Build.MODEL.replace("[^A-Za-z0-9]".toRegex(), "")}-" +
            (System.currentTimeMillis() % 100000)
        val c = MqttAsyncClient(uri, clientId, MemoryPersistence())

        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            if (BuildConfig.BROKER_USER.isNotEmpty()) userName = BuildConfig.BROKER_USER
            if (BuildConfig.BROKER_PASS.isNotEmpty()) password = BuildConfig.BROKER_PASS.toCharArray()
            setWill("$base/availability", "offline".toByteArray(), 1, true)
        }

        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                BridgeService.mqttConnected = true
                Log.i(BridgeService.TAG, "MQTT connected ($serverURI, reconnect=$reconnect)")
                try {
                    // `cmd/#` also matches the bare `cmd` topic, so one subscription covers both
                    // `<base>/cmd` (JSON command) and `<base>/cmd/<op>` (topic-suffix shorthand).
                    // Subscribing to both would deliver every `<base>/cmd` message twice.
                    c.subscribe("$base/cmd/#", 1)
                    publish("$base/availability", "online", true)
                } catch (e: Exception) {
                    Log.e(BridgeService.TAG, "subscribe failed", e)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                BridgeService.mqttConnected = false
                Log.w(BridgeService.TAG, "MQTT connection lost: ${cause?.message}")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload)
                worker.submit {   // never block the Paho callback thread
                    try { onMessage(topic, payload) }
                    catch (e: Exception) { Log.e(BridgeService.TAG, "mqtt msg error", e) }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        c.connect(opts)
        client = c
    }

    private fun onMessage(topic: String, payload: String) {
        val cmd = try {
            if (payload.isBlank()) JSONObject() else JSONObject(payload)
        } catch (_: Exception) {
            // payload wasn't JSON; treat it as a bare value (e.g. the op or a package)
            JSONObject().put("_raw", payload)
        }

        // Topic-suffix shorthand: <base>/cmd/tap  -> op=tap
        if (!cmd.has("op")) {
            val suffix = topic.removePrefix("$base/cmd").trim('/')
            if (suffix.isNotEmpty()) {
                cmd.put("op", suffix.substringBefore('/'))
                if (cmd.has("_raw")) {
                    val raw = cmd.getString("_raw")
                    if (raw.isNotBlank()) {
                        // let a bare payload fill the common arg for the op
                        when (cmd.optString("op")) {
                            "launch", "open" -> cmd.put("pkg", raw)
                            "settext", "set_text", "input", "text" -> cmd.put("value", raw)
                            "global" -> cmd.put("action", raw)
                        }
                    }
                    cmd.remove("_raw")
                }
            }
        }

        val res = svc.handle(cmd)
        val op = res.optString("op")
        if (op == "screenshot" && res.has("image")) {
            publish("$base/screenshot", res.toString(), false)
            // compact ack on the result topic (no giant base64 there)
            val ack = JSONObject().put("op", op).put("ok", res.optBoolean("ok"))
                .put("bytes", res.optInt("bytes")).put("topic", "$base/screenshot")
            if (res.has("id")) ack.put("id", res.get("id"))
            publish("$base/result", ack.toString(), false)
        } else {
            val s = res.toString()
            if (s.length > MAX_RESULT) {   // don't push a huge payload over MQTT (would wedge/overflow)
                val small = JSONObject().put("op", op).put("ok", false)
                    .put("error", "result too large for MQTT (${s.length} bytes); use HTTP for op '$op'")
                if (res.has("id")) small.put("id", res.get("id"))
                publish("$base/result", small.toString(), false)
            } else {
                publish("$base/result", s, false)
            }
        }
    }

    private fun publish(topic: String, text: String, retained: Boolean) {
        try {
            client?.publish(topic, MqttMessage(text.toByteArray()).apply { qos = 1; isRetained = retained })
        } catch (e: Exception) {
            Log.e(BridgeService.TAG, "publish $topic failed", e)
        }
    }

    fun disconnect() {
        try { publish("$base/availability", "offline", true) } catch (_: Exception) {}
        try { client?.disconnect() } catch (_: Exception) {}
        try { worker.shutdownNow() } catch (_: Exception) {}
        client = null
        BridgeService.mqttConnected = false
    }
}
