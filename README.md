# Screen Bridge (`dev.ajithv.screenbridge`)

A generic Android **AccessibilityService** that exposes screen reading + control to a
trusted local agent over **HTTP** and **MQTT**. Built for agentic AI driving the phone
(e.g. MoveInSync bus booking).

Once the service is enabled in *Settings ▸ Accessibility*, it:
- reads the live view hierarchy (`getRootInActiveWindow`),
- performs taps / swipes / long-press (`dispatchGesture`) and node actions
  (click / set-text / scroll),
- captures the screen with `AccessibilityService.takeScreenshot()` — **no MediaProjection
  prompt**,
- runs an in-process HTTP server (NanoHTTPD, default **:8080**) and an MQTT client.

Both channels funnel every command through one dispatcher (`BridgeService.handle`).

## Command schema

A command is a JSON object `{ "op": "...", ...args }`; the result is `{ "ok": bool, ... }`.
An optional `"_cid"` (correlation id) is echoed back unchanged — kept **separate** from the `id`
*selector* below so correlation never interferes with node matching.

| op | args | effect |
|----|------|--------|
| `ping` / `status` | – | service/http/mqtt/ip/foreground |
| `hierarchy` | `mode` = `tree`\|`flat`, `maxDepth`, `maxNodes` | dump the view tree |
| `windows` | – | list all windows (id, layer, bounds, active/focused) |
| `tap` | `x`,`y`,`duration?` | gesture tap |
| `longpress` | `x`,`y`,`duration?`=600 | gesture long-press |
| `swipe` / `drag` | `x1`,`y1`,`x2`,`y2`,`duration?` | gesture swipe |
| `tap_node` | *selector* + `dx?`,`dy?`,`duration?`,`longpress?` | gesture-tap a node's center (+offset) — works where `ACTION_CLICK` is ignored |
| `click` | *selector* | ACTION_CLICK (climbs to clickable ancestor) |
| `longclick` | *selector* | ACTION_LONG_CLICK |
| `settext` | *selector?* + `value` | ACTION_SET_TEXT (default = focused editable) |
| `type` | *selector?* + `value`, `per_char_ms?`=150, `clear?`, `tap_first?` | type **incrementally**, char-by-char (fires JS/Angular input filters a one-shot `settext` misses); returns `{ok,text}` |
| `get_text` | *selector* | read a node's text / content-description |
| `scroll` | *selector?* + `direction` | ACTION_SCROLL_FORWARD/BACKWARD |
| `global` | `action` = back/home/recents/notifications/quicksettings/power/lock/screenshot/dpad_* | performGlobalAction |
| `back`/`home`/`recents`/`notifications` | – | shortcuts |
| `launch` | `pkg`, `clear?` | start an app (`clear`=true resets its task to the root activity) |
| `foreground` | – | current package/activity |
| `find` | *selector* + `limit?` | list matching nodes |
| `wait` | *selector* + `until`=`present`\|`gone`\|`stable`, `timeout?`, `interval?` | poll until the condition holds |
| `ocr` | `scale?` | on-device text recognition (ML Kit) → recognized text + boxes |
| `screenshot` | `format`=png\|jpeg, `scale`, `quality` | base64 image (MQTT/JSON) |

Any op may carry `"shot": true` to also attach a screenshot of the result.

**Selector** (any subset): `text` (exact, ci), `textContains`/`contains`, `desc`, `descContains`,
`label` (text **or** desc contains), `id` (viewIdResourceName *endsWith*), `cls`/`class` (contains),
`clickable`, `editable`, `visible_only` (on-screen only), `index` (nth match). Add `allWindows` to
search every window, not just the active one.

## HTTP

```
GET  /ping
GET  /hierarchy?mode=flat
GET  /screenshot?format=jpeg&scale=0.5&quality=80     -> binary image
GET  /rpc?op=tap&x=540&y=1200                         -> JSON result
POST /rpc   (body = JSON command)                     -> JSON result
```
If `auth_token` is set in `secrets.properties`, every request needs header
`X-Bridge-Token: <token>` (or `?token=`).

## MQTT (broker from `secrets.properties`, base topic `screenbridge`)

- `screenbridge/cmd`   — publish a JSON command; or `screenbridge/cmd/<op>` with args as payload.
- `screenbridge/result` — every result (screenshots: compact ack only).
- `screenbridge/screenshot` — screenshot result incl. base64 image.
- `screenbridge/availability` — retained `online`/`offline` (LWT).

Correlate request↔reply with `_cid` (echoed back on `screenbridge/result`). The client subscribes to
`screenbridge/cmd/#` **only** (subscribing to both `cmd` and `cmd/#` would double-deliver), and runs
each message on a worker thread so a slow or large op can't wedge the channel.

## Build (europa)

```
cd ~/screenbridge && ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/jdk17 \
  PATH=$HOME/jdk17/bin:$PATH ~/gradle-8.7/bin/gradle assembleDebug
adb -s 4d1eaeb5 install -r -g app/build/outputs/apk/debug/app-debug.apk
```

`app/secrets.properties` (gitignored): `broker_host`, `broker_port`, `broker_user`,
`broker_pass`, `mqtt_base`, `http_port`, `auth_token`. Copy `app/secrets.properties.example`
to `app/secrets.properties` and fill it in before building.

## Driving it — the MoveInSync booking example

`mis_book.py` (a Python driver that books/cancels/reports MoveInSync shuttle seats via this app
over MQTT) lives in the Home Assistant config repo at `pyhelpers/mis_book.py`, with setup and
"run from any machine" instructions in `pyhelpers/MISBOOK.md`.
