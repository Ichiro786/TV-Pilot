package dev.syed.tvpilot.brain

import android.util.Base64
import dev.syed.tvpilot.tv.TvApp
import dev.syed.tvpilot.tv.TvController
import dev.syed.tvpilot.tv.TvKey
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when the vision API itself can't be used (network, quota, bad key), so d1 can take over. */
class VisionApiException(message: String) : Exception(message)

/**
 * Looks at the TV, decides one step, does it, looks again. Each turn sends only the newest screenshot
 * plus a short text log of earlier steps, so every call stays small and fast.
 */
class VisionAgent(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val tv: TvController,
    private val apps: List<TvApp>,
    private val onStep: (String) -> Unit,
    /** A finished step, in plain words, for the live checklist. */
    private val onDone: (String) -> Unit = {},
    /** The newest screenshot (JPEG) the model looked at. */
    private val onShot: (ByteArray) -> Unit = {},
    /** The user's own rules from Settings. */
    private val instructions: String = "",
) {
    data class Result(val say: String, val chips: List<String>, val success: Boolean, val opened: TvApp?)

    private val byPkg = apps.associateBy { it.packageName }

    private val rules = instructions.trim()

    private fun systemPrompt() = (rules.takeIf { it.isNotEmpty() }?.let {
        "THE USER'S OWN RULES. These come first and override everything below whenever they apply:\n$it\n\n"
    }.orEmpty()) + """
        You operate a Google TV for the user, like a person holding the remote and looking at the screen.
        Every turn you get the TASK, a log of what you already did, and a fresh SCREENSHOT of the TV.
        Reply with ONLY one JSON object, no markdown:
        {"thought":"what you see and why this step, max 25 words","action":{...},"say":"short friendly reply, only with done/fail"}

        Actions (exactly one per turn):
          {"type":"open_app","package":"<package from APPS>"}
          {"type":"search","package":"<package>","query":"words"}   opens the app's own search results directly (only apps marked [search])
          {"type":"tap","x":0-1000,"y":0-1000}                     tap a point; x,y are on a 0-1000 grid over the screenshot (0,0 top-left)
          {"type":"key","key":"<KEY>","times":1}                   remote button: ${TvKey.entries.filter { it != TvKey.SLEEP && it != TvKey.WAKE }.joinToString { it.name }}
          {"type":"swipe","direction":"up|down|left|right"}        scroll the content (down = see what's below)
          {"type":"text","text":"words","submit":true}             type into the focused text box
          {"type":"wait","ms":1500}                                 the screen is loading (up to 6000 ms; repeat if still loading)
          {"type":"done"}                                           the task is finished (video/episode is playing, or the asked state is reached)
          {"type":"fail"}                                           impossible; explain in "say"

        How to work:
        - Look carefully before acting. Tap the CENTER of the thing you want (a thumbnail, a button, a tab).
        - TV apps follow a focus highlight (outline, glow or enlarged tile). If a tap did not change the screen,
          switch to the D-pad: move focus with UP/DOWN/LEFT/RIGHT until the right item is highlighted, then OK.
        - FOCUS FIRST: before pressing OK, find the item that has the focus highlight RIGHT NOW and name it in
          "thought" (e.g. "focus is on 'support the project' card"). If it is not your target, move focus with the
          D-pad (or tap the target) and look again. Never press OK on an item just because your target is visible.
        - Links, ads, "support/donate" cards and anything that opens a browser are never what the user wants.
          If an "Open with" chooser, a browser or a web page appears, it was a mistake: press BACK once and
          move focus AWAY from that item before pressing OK again.
        - Each log line ends with what happened after it ("screen did NOT change", "now in app X"). Trust it:
          an action that changed nothing did nothing, so do something different.
        - Never repeat the same failing action more than twice; try another way.
        - Only say "done" when the screenshot proves it: video playing full-screen, player controls, or the requested screen.
          After opening a video or episode, if a details page shows Play/Resume/Watch, press it first.
        - "Latest"/"newest"/"new" video of a channel or creator: search the creator, open their CHANNEL, open the
          "Videos" tab (newest first) and play the FIRST tile. Check age labels like "2 hours ago" to confirm it's the newest.
          Don't just play a search result unless it's clearly the newest upload.
        - Picking from Continue Watching / My List: find the row on the app's home screen; scroll down or right if needed.
        - YouTube and TizenTube are the same YouTube TV interface. When the user says YouTube, use the app marked as YouTube in APPS.
        - Keep "say" under 15 words and in the user's language. Never invent what you can't see.

        APPS (label = package, [search] = supports the search action):
        ${apps.joinToString("\n        ") { "${it.label} = ${it.packageName}" + if (it.searchUrl != null) " [search]" else "" }}
    """.trimIndent()

    suspend fun run(task: String, onScreen: TvApp?, maxSteps: Int = 22): Result {
        val log = mutableListOf<String>()
        val chips = mutableListOf<String>()
        var opened: TvApp? = null
        var lastAction = ""
        var repeats = 0
        val system = systemPrompt()
        var prevSig: IntArray? = null
        var prevFront: String? = null

        for (step in 1..maxSteps) {
            onStep(if (step == 1) "Looking at the TV…" else "Reading the screen…")
            val shot = tv.screenshot()
            onShot(shot.jpeg)
            val front = runCatching { tv.foregroundPackage() }.getOrNull()
            // Tell the model what its last action actually did.
            val sig = signature(shot.jpeg)
            if (log.isNotEmpty() && prevSig != null) {
                val outcome = when {
                    front != null && prevFront != null && front != prevFront ->
                        if (isChooserOrBrowser(front)) "a popup/browser opened ($front), that was WRONG"
                        else "now in ${byPkg[front]?.label ?: front}"
                    sig != null && diff(prevSig, sig) < 0.5 -> "screen did NOT change (check where the focus is)"
                    else -> "screen changed"
                }
                log[log.lastIndex] = log.last() + " → " + outcome
            }
            prevSig = sig; prevFront = front
            val user = buildString {
                appendLine("TASK: $task")
                appendLine("App in front now: ${front?.let { p -> byPkg[p]?.label?.let { "$it ($p)" } ?: p } ?: onScreen?.label ?: "unknown"}")
                appendLine("Step $step of $maxSteps.")
                if (log.isEmpty()) appendLine("Nothing done yet.") else {
                    appendLine("Done so far:")
                    log.takeLast(12).forEach { appendLine("- $it") }
                }
                val app = front?.let { byPkg[it]?.label }
                if (rules.isNotEmpty()) appendLine("REMEMBER THE USER'S RULES" + (app?.let { " (you're in $it)" } ?: "") + ":\n" + rules)
                if (repeats >= 2) appendLine("WARNING: you repeated the same action and it isn't working. Do something different.")
                append("SCREENSHOT attached. Your JSON:")
            }
            val reply = ask(system, user, shot.jpeg)
            val action = reply.optJSONObject("action") ?: JSONObject().put("type", "wait")
            val type = action.optString("type").lowercase()
            val thought = reply.optString("thought").take(160)
            val say = reply.optString("say").ifBlank { null }

            val actSig = action.toString()
            repeats = if (actSig == lastAction) repeats + 1 else 0
            lastAction = actSig

            val nice = thought.substringBefore('.').trim().take(60).replaceFirstChar { it.uppercase() }
            if (nice.isNotBlank() && type !in setOf("done", "fail", "wait")) onStep(nice)
            fun px(v: Int, size: Int) = (v.coerceIn(0, 1000) / 1000.0 * size).toInt().coerceIn(1, size - 1)

            when (type) {
                "done" -> { onDone("Done"); return Result(say ?: "Done", chips, true, opened) }
                "fail" -> return Result(say ?: "I couldn't do that on the TV.", chips, false, opened)
                "open_app" -> {
                    val app = byPkg[action.optString("package")]
                        ?: apps.firstOrNull { it.label.equals(action.optString("package"), true) }
                    if (app == null) log += "open_app ${action.optString("package")} FAILED: not an installed package" else {
                        onStep("Opening ${app.label}")
                        tv.launch(app.packageName); tv.waitForApp(app.packageName)
                        opened = app; chips += "Opened ${app.label}"; log += "opened ${app.label}"; onDone("Opened ${app.label}")
                    }
                }
                "search" -> {
                    val app = byPkg[action.optString("package")] ?: opened ?: front?.let { byPkg[it] }
                    val query = action.optString("query").take(120)
                    if (app?.searchUrl == null || query.isBlank()) log += "search FAILED: ${app?.label ?: "that app"} has no direct search; use its search screen instead"
                    else {
                        onStep("Searching ${app.label} for \"$query\"")
                        tv.deepSearch(app, query, autoPlayFirst = false)
                        opened = app; chips += "${app.label} · $query"; log += "searched ${app.label} for \"$query\""; onDone("Searched \"$query\"")
                        delay(if (front == app.packageName) 2500 else 5500) // Cobalt is slow on a cold start
                    }
                }
                "tap" -> {
                    val x = px(action.optInt("x", 500), shot.width); val y = px(action.optInt("y", 500), shot.height)
                    onStep(thought.ifBlank { "Tapping" })
                    tv.tap(x, y); log += "tapped (${action.optInt("x")},${action.optInt("y")}) — $thought"; onDone(nice.ifBlank { "Tapped" })
                    delay(1300)
                }
                "key" -> {
                    val key = TvKey.entries.firstOrNull { it.name == action.optString("key").uppercase() }
                    if (key == null || key == TvKey.SLEEP) log += "key ${action.optString("key")} FAILED: unknown key" else {
                        val n = action.optInt("times", 1).coerceIn(1, 10)
                        onStep("${key.label}${if (n > 1) " ×$n" else ""}")
                        tv.key(key, n); log += "pressed ${key.name} x$n — $thought"; onDone("Pressed ${key.label}${if (n > 1) " ×$n" else ""}")
                        delay(if (key == TvKey.OK) 1300 else 600)
                    }
                }
                "swipe" -> {
                    val w = shot.width; val h = shot.height
                    when (action.optString("direction").lowercase()) {
                        "up" -> tv.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)
                        "left" -> tv.swipe(w * 3 / 10, h / 2, w * 7 / 10, h / 2)
                        "right" -> tv.swipe(w * 7 / 10, h / 2, w * 3 / 10, h / 2)
                        else -> tv.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)
                    }
                    onStep("Scrolling ${action.optString("direction")}")
                    log += "swiped ${action.optString("direction")}"; onDone("Scrolled ${action.optString("direction")}")
                    delay(900)
                }
                "text" -> {
                    val t = action.optString("text").take(100)
                    tv.typeText(t)
                    if (action.optBoolean("submit", true)) { delay(250); tv.shell("input keyevent 66") }
                    chips += "Typed \"$t\""; log += "typed \"$t\""; onDone("Typed \"$t\"")
                    delay(2000)
                }
                else -> { // wait
                    log += "waited"
                    delay(action.optLong("ms", 1500).coerceIn(300, 6000))
                }
            }
        }
        return Result("I ran out of steps. Here's where I got to.", chips, false, opened)
    }

    private fun isChooserOrBrowser(pkg: String) = pkg == "android" || listOf("intentresolver", "resolver", "browser", "chrome", "tvbro", "browsehere", "downloader")
        .any { pkg.contains(it, ignoreCase = true) }

    /** A tiny 64x36 grayscale fingerprint of the screen, to tell whether an action changed anything. */
    private fun signature(jpeg: ByteArray): IntArray? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        val b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o) ?: return null
        val s = android.graphics.Bitmap.createScaledBitmap(b, 64, 36, true)
        IntArray(64 * 36) { i -> val c = s.getPixel(i % 64, i / 64); ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3 }
    }.getOrNull()

    private fun diff(a: IntArray, b: IntArray): Double =
        if (a.size != b.size) 99.0 else a.indices.sumOf { kotlin.math.abs(a[it] - b[it]) }.toDouble() / a.size

    private suspend fun ask(system: String, user: String, jpeg: ByteArray): JSONObject {
        val img = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        fun body(effort: String?) = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 400)
            .apply { if (effort != null) put("reasoning_effort", effort) }
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", user))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", img))))))

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        var res: JSONObject? = null
        var lastErr: Exception? = null
        // Thinking slows every step down a lot; use the lowest level the model accepts.
        for (effort in listOf<String?>(workingEffort ?: "minimal", "low", null).distinct()) {
            try {
                res = postJson(url, apiKey, body(effort)); workingEffort = effort; break
            } catch (e: Exception) {
                lastErr = e
                if (!(e.message ?: "").contains("400")) break // only a bad-parameter error is worth retrying
            }
        }
        res ?: throw VisionApiException(lastErr?.message ?: "Vision model unreachable")
        val text = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return JSONObject().put("action", JSONObject().put("type", "wait"))
        return runCatching { JSONObject(text.substring(start, end + 1)) }
            .getOrElse { JSONObject().put("action", JSONObject().put("type", "wait")) }
    }

    companion object {
        @Volatile private var workingEffort: String? = null
    }
}
