package dev.syed.tvpilot.brain

import dev.syed.tvpilot.tv.TvApp
import dev.syed.tvpilot.tv.TvKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface TvAction {
    data class OpenApp(val app: TvApp) : TvAction
    data class Search(val app: TvApp?, val query: String) : TvAction
    data class Press(val key: TvKey, val times: Int = 1) : TvAction
    data class Type(val text: String) : TvAction
    /** Find an item already on the TV screen (a Continue Watching tile, a row) by its title and select it. */
    data class PickOnScreen(val title: String, val afterOpen: Boolean = false) : TvAction
}

data class Plan(val actions: List<TvAction>, val say: String, val confidence: Double? = null)

interface Brain {
    suspend fun plan(command: String, apps: List<TvApp>, onScreen: TvApp?): Plan
}

internal val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private val JSON_TYPE = "application/json".toMediaType()

internal suspend fun postJson(url: String, apiKey: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody(JSON_TYPE))
        .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
        .build()
    http.newCall(req).execute().use { res ->
        val text = res.body.string()
        if (!res.isSuccessful) error("Server said ${res.code}: ${text.take(200)}")
        JSONObject(text)
    }
}

/* ---------- Liquid AI d1-3B (or any /v1/systemone decision model) ---------- */

/**
 * A decision model never writes text, so it only answers typed questions:
 * what kind of command this is, which app, which key. The words to search for
 * come from the command itself, with the app name and filler words taken out.
 */
class DecisionBrain(private val baseUrl: String, private val apiKey: String) : Brain {

    override suspend fun plan(command: String, apps: List<TvApp>, onScreen: TvApp?): Plan {
        // Plain remote commands ("pause", "volume up 3") never need the model.
        QuickCommands.match(command)?.let { return it }

        val appIds = apps.mapIndexed { i, app -> "app$i" to app }.toMap()
        val allQuestions = JSONObject()
            .put("intent", choice("What does the person want the TV to do?", linkedMapOf(
                "watch" to "Watch, play or find specific content: a show, movie, anime, video, song, channel or topic",
                "pick_on_screen" to "Choose something already on a list or row, such as continue watching, my list, recently watched, or the item on screen",
                "open_app" to "Open an app, without naming anything to watch",
                "playback" to "Control what is playing: pause, play, resume, stop, skip, next, previous, rewind, fast forward",
                "navigate" to "Move around the screen: up, down, left, right, select, back, home",
                "volume" to "Change the sound: louder, quieter, mute, unmute",
                "power" to "Turn the TV off or on, or put it to sleep",
                "other" to "Anything else",
            )))
            .put("app", choice("Which app does the command name or clearly imply?", LinkedHashMap<String, String>().apply {
                appIds.forEach { (id, app) -> put(id, "${app.label} (${app.aliases.joinToString()})") }
                put("none", "No app is named and none is clearly implied")
            }))
            .put("playback", choice("Which playback control is meant?", linkedMapOf(
                "play_pause" to "Pause, play, resume or stop",
                "next" to "Next video, song or episode, or skip",
                "previous" to "Previous video, song or episode",
                "fast_forward" to "Skip ahead within what is playing",
                "rewind" to "Go back a bit within what is playing",
            )))
            .put("navigate", choice("Which way or button?", linkedMapOf(
                "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right",
                "ok" to "Select, open, click or enter",
                "back" to "Go back",
                "home" to "Go to the home screen",
            )))
            .put("volume", choice("Which volume change?", linkedMapOf(
                "up" to "Louder", "down" to "Quieter", "mute" to "Mute or unmute",
            )))
            .put("power", choice("Off or on?", linkedMapOf(
                "off" to "Turn off or sleep", "on" to "Turn on or wake up",
            )))

        val state = JSONObject()
            .put("command", command)
            .put("app_on_screen", onScreen?.label ?: JSONObject.NULL)

        val url = baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/systemone"
        // Ask only what every command needs; each extra question costs the server time.
        suspend fun ask(vararg names: String): JSONObject {
            val qs = JSONObject().apply { names.forEach { put(it, allQuestions.getJSONObject(it)) } }
            return postJson(url, apiKey, JSONObject().put("state", state).put("questions", qs)).getJSONObject("answers")
        }
        val answers = ask("intent", "app")
        val kindNow = answers.getJSONObject("intent").getString("choice")
        if (kindNow in setOf("playback", "navigate", "volume", "power")) {
            val more = ask(kindNow)
            answers.put(kindNow, more.getJSONObject(kindNow))
        }

        val intent = answers.getJSONObject("intent")
        val kind = intent.getString("choice")
        // "confidence" is a margin score; the winner's own probability reads better as "how sure".
        val conf = intent.optJSONObject("probabilities")?.optDouble(kind) ?: intent.optDouble("confidence", 1.0)
        val appId = answers.getJSONObject("app").getString("choice")
        // Trust a named app; otherwise fall back to an alias match in the text.
        val app = appIds[appId] ?: CommandText.findApp(command, apps)
        val times = CommandText.times(command)

        if (conf < 0.3) return Plan(emptyList(), "I'm not sure what you meant. Try \"play lofi on YouTube\".", conf)

        // "from my continue watching / my list" always means picking off the screen.
        val effective = if (CommandText.mentionsList(command) && kind in setOf("watch", "open_app", "other")) "pick_on_screen" else kind

        return when (effective) {
            "pick_on_screen" -> {
                val title = CommandText.query(command, apps)
                val actions = mutableListOf<TvAction>()
                val needsOpen = app != null && app != onScreen
                if (needsOpen) actions += TvAction.OpenApp(app!!)
                if (title.isNotBlank()) actions += TvAction.PickOnScreen(title, afterOpen = needsOpen)
                when {
                    title.isBlank() && app != null -> Plan(actions, "Opening ${app.label}", conf)
                    title.isBlank() -> Plan(emptyList(), "Which title should I pick?", conf)
                    else -> Plan(actions, "Looking for \"$title\"" + (app?.let { " in ${it.label}" } ?: " on screen"), conf)
                }
            }
            "watch" -> {
                val query = CommandText.query(command, apps)
                when {
                    query.isNotBlank() && app == null && onScreen == null -> Plan(emptyList(), "Which app should I play \"$query\" in?", conf)
                    query.isNotBlank() -> Plan(listOf(TvAction.Search(app ?: onScreen, query)),
                        "Finding \"$query\" in ${(app ?: onScreen)!!.label}", conf)
                    app != null -> Plan(listOf(TvAction.OpenApp(app)), "Opening ${app.label}", conf)
                    else -> Plan(emptyList(), "What should I play?", conf)
                }
            }
            "open_app" -> if (app != null) Plan(listOf(TvAction.OpenApp(app)), "Opening ${app.label}", conf)
                else Plan(emptyList(), "I couldn't find that app on your TV.", conf)
            "playback" -> {
                val key = when (answers.getJSONObject("playback").getString("choice")) {
                    "next" -> TvKey.NEXT
                    "previous" -> TvKey.PREVIOUS
                    "fast_forward" -> TvKey.FAST_FORWARD
                    "rewind" -> TvKey.REWIND
                    else -> TvKey.PLAY_PAUSE
                }
                Plan(listOf(TvAction.Press(key, times ?: 1)), key.label, conf)
            }
            "navigate" -> {
                val key = when (answers.getJSONObject("navigate").getString("choice")) {
                    "up" -> TvKey.UP; "down" -> TvKey.DOWN; "left" -> TvKey.LEFT; "right" -> TvKey.RIGHT
                    "back" -> TvKey.BACK; "home" -> TvKey.HOME
                    else -> TvKey.OK
                }
                Plan(listOf(TvAction.Press(key, times ?: 1)), key.label, conf)
            }
            "volume" -> {
                val key = when (answers.getJSONObject("volume").getString("choice")) {
                    "down" -> TvKey.VOLUME_DOWN; "mute" -> TvKey.MUTE; else -> TvKey.VOLUME_UP
                }
                Plan(listOf(TvAction.Press(key, if (key == TvKey.MUTE) 1 else times ?: 3)), key.label, conf)
            }
            "power" -> {
                val key = if (answers.getJSONObject("power").getString("choice") == "on") TvKey.WAKE else TvKey.SLEEP
                Plan(listOf(TvAction.Press(key)), key.label, conf)
            }
            else -> Plan(emptyList(), "That's not something I can do on the TV yet.", conf)
        }
    }

    private fun choice(instructions: String, criteria: Map<String, String>) = JSONObject()
        .put("type", "choice")
        .put("instructions", instructions)
        .put("criteria", JSONObject(criteria))
}

/* ---------- Any OpenAI-compatible chat model (Gemini, Groq, Ollama, llama.cpp) ---------- */

class ChatBrain(private val baseUrl: String, private val apiKey: String, private val model: String) : Brain {

    override suspend fun plan(command: String, apps: List<TvApp>, onScreen: TvApp?): Plan {
        val system = """
            You control a Google TV. Reply with ONLY a JSON object: {"actions": [...], "say": "short reply"}
            Allowed actions:
              {"type":"open_app","package":"<package from APPS>"}
              {"type":"search","package":"<package from APPS or null>","query":"what to watch"}
              {"type":"key","key":"<one of KEYS>","times":1}
              {"type":"text","text":"..."}
              {"type":"pick","title":"exact title"}   picks an item already on screen, e.g. from Continue Watching
            For "from my continue watching / my list", open the app first, then "pick". Use "search" whenever the person names something to watch or play. Use as few actions as possible.
            APPS: ${apps.joinToString { "${it.label}=${it.packageName}" }}
            KEYS: ${TvKey.entries.joinToString { it.name }}
            On screen now: ${onScreen?.label ?: "unknown"}
        """.trimIndent()

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", command)))

        val res = postJson(baseUrl.trimEnd('/') + "/chat/completions", apiKey, body)
        var text = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        text = text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}') + 1)
        val json = JSONObject(text)

        val byPkg = apps.associateBy { it.packageName }
        val actions = mutableListOf<TvAction>()
        val arr = json.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), 6)) {
            val a = arr.getJSONObject(i)
            when (a.optString("type")) {
                "open_app" -> byPkg[a.optString("package")]?.let { actions += TvAction.OpenApp(it) }
                "search" -> a.optString("query").takeIf { it.isNotBlank() }?.let {
                    actions += TvAction.Search(byPkg[a.optString("package")], it.take(120))
                }
                "key" -> TvKey.entries.firstOrNull { it.name == a.optString("key").uppercase() }?.let {
                    actions += TvAction.Press(it, a.optInt("times", 1))
                }
                "pick" -> a.optString("title").takeIf { it.isNotBlank() }?.let {
                    actions += TvAction.PickOnScreen(it.take(80), afterOpen = actions.any { x -> x is TvAction.OpenApp })
                }
                "text" -> a.optString("text").takeIf { it.isNotBlank() }?.let { actions += TvAction.Type(it) }
            }
        }
        return Plan(actions, json.optString("say", "Done"))
    }
}

/* ---------- Pulling the useful words out of a command ---------- */

object CommandText {
    private val listPhrases = listOf(
        "from my continue watching list", "from my continue watching", "from continue watching", "in continue watching",
        "on continue watching", "continue watching list", "continue watching", "from my watchlist", "from my watch list",
        "from my list", "in my list", "my list", "watchlist", "recently watched", "where i left off", "resume",
    )

    fun mentionsList(command: String): Boolean {
        val c = command.lowercase()
        return listPhrases.any { c.contains(it) }
    }

    private val filler = listOf(
        "hey tv", "ok tv", "hey", "please", "can you", "could you", "would you", "i want to", "i wanna",
        "i'd like to", "let's", "lets", "for me", "on the tv", "on tv",
        "play", "watch", "put on", "search for", "search", "find", "show me", "look up",
        "stream", "start", "open", "launch", "some", "the app", "app", "application", "now",
    )

    fun findApp(command: String, apps: List<TvApp>): TvApp? {
        val c = command.lowercase()
        return apps.flatMap { app -> app.aliases.map { it to app } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) -> Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(c) }
            ?.second
    }

    fun query(command: String, apps: List<TvApp>): String {
        var c = " " + command.lowercase() + " "
        apps.flatMap { it.aliases }.sortedByDescending { it.length }.forEach { alias ->
            c = c.replace(Regex("\\s(on|in|from|using|with)\\s+${Regex.escape(alias)}\\b"), " ")
            c = c.replace(Regex("\\b${Regex.escape(alias)}\\b"), " ")
        }
        (listPhrases + filler).sortedByDescending { it.length }.forEach { f ->
            c = c.replace(Regex("\\b${Regex.escape(f)}\\b"), " ")
        }
        c = c.replace(Regex("[?!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        // Drop joining words left at the edges ("open nuvio and play the X" -> "X").
        repeat(3) { c = c.replace(Regex("^(and|then|the|from|in|on|to)\\s+"), "").replace(Regex("\\s+(and|then|from|in|on)$"), "").trim() }
        return c
    }

    private val words = mapOf("once" to 1, "twice" to 2, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "a lot" to 6, "a bit" to 2, "little" to 2)

    fun times(command: String): Int? {
        val c = command.lowercase()
        Regex("(\\d{1,2})").find(c)?.let { return it.value.toInt() }
        return words.entries.firstOrNull { c.contains(it.key) }?.value
    }
}


/** Instant answers for plain remote commands, so they don't wait on the model. */
object QuickCommands {
    private fun p(key: TvKey, times: Int = 1) = Plan(listOf(TvAction.Press(key, times)), key.label)

    fun match(command: String): Plan? {
        val c = command.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("please ").removeSuffix(" please").removePrefix("hey tv ").trim()
        val n = CommandText.times(c)
        return when {
            c.matches(Regex("(volume up|louder|increase (the )?volume|turn (it )?up)( \\w+)*")) -> p(TvKey.VOLUME_UP, n ?: 3)
            c.matches(Regex("(volume down|quieter|lower (the )?volume|decrease (the )?volume|turn (it )?down)( \\w+)*")) -> p(TvKey.VOLUME_DOWN, n ?: 3)
            c in setOf("mute", "unmute", "mute it", "mute the tv", "silence") -> p(TvKey.MUTE)
            c in setOf("pause", "play", "resume", "stop", "pause it", "play it", "resume it", "pause the video", "play pause") -> p(TvKey.PLAY_PAUSE)
            c in setOf("next", "next episode", "skip", "next video", "next song") -> p(TvKey.NEXT)
            c in setOf("previous", "previous episode", "previous video", "go back a video") -> p(TvKey.PREVIOUS)
            c.matches(Regex("(fast forward|forward|skip ahead)( \\w+)*")) -> p(TvKey.FAST_FORWARD, n ?: 1)
            c.matches(Regex("(rewind|go back a bit)( \\w+)*")) -> p(TvKey.REWIND, n ?: 1)
            c in setOf("back", "go back") -> p(TvKey.BACK)
            c in setOf("home", "go home", "home screen") -> p(TvKey.HOME)
            c in setOf("ok", "select", "enter", "click") -> p(TvKey.OK)
            c.matches(Regex("(go |move )?(up|down|left|right)( \\w+)*")) -> {
                val k = when (Regex("up|down|left|right").find(c)!!.value) { "up" -> TvKey.UP; "down" -> TvKey.DOWN; "left" -> TvKey.LEFT; else -> TvKey.RIGHT }
                p(k, n ?: 1)
            }
            c in setOf("turn off", "turn off the tv", "tv off", "power off", "sleep", "switch off") -> p(TvKey.SLEEP)
            c in setOf("turn on", "turn on the tv", "tv on", "wake up", "power on") -> p(TvKey.WAKE)
            else -> null
        }
    }
}
