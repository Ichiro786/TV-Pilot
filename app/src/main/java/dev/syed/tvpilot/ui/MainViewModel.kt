package dev.syed.tvpilot.ui

import dev.syed.tvpilot.data.SpeechEngine
import dev.syed.tvpilot.voice.GeminiTranscriber

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.syed.tvpilot.brain.DecisionBrain
import dev.syed.tvpilot.brain.TvAction
import dev.syed.tvpilot.brain.Plan
import dev.syed.tvpilot.brain.QuickCommands
import dev.syed.tvpilot.brain.VisionAgent
import dev.syed.tvpilot.brain.VisionApiException
import dev.syed.tvpilot.data.BrainMode
import dev.syed.tvpilot.data.Settings
import dev.syed.tvpilot.data.SettingsStore
import dev.syed.tvpilot.voice.SpeechRecorder
import dev.syed.tvpilot.voice.Transcriber
import dev.syed.tvpilot.tv.AppCatalog
import dev.syed.tvpilot.tv.Connection
import dev.syed.tvpilot.tv.TvApp
import dev.syed.tvpilot.tv.TvController
import dev.syed.tvpilot.tv.TvKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

data class ChatLine(
    val fromUser: Boolean,
    val text: String,
    val chips: List<String> = emptyList(),
    val isError: Boolean = false,
    val id: Long = System.nanoTime(),
)

data class UiState(
    val connection: Connection = Connection.Disconnected,
    val apps: List<TvApp> = emptyList(),
    val onScreen: TvApp? = null,
    val lines: List<ChatLine> = emptyList(),
    val thinking: Boolean = false,
    /** Mic is recording (Parakeet mode). */
    val listening: Boolean = false,
    val micLevel: Float = 0f,
    /** What the vision agent is doing right now, shown under the spinner. */
    val status: String? = null,
    /** Finished agent steps for the live card. */
    val steps: List<String> = emptyList(),
    /** Newest TV screenshot the agent looked at. */
    val shot: android.graphics.Bitmap? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val tv = TvController(app)

    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var tts: TextToSpeech? = null
    private val recorder = SpeechRecorder()

    /** Tap once to talk; it stops when you go quiet. Tap again to stop early. */
    fun toggleListening() {
        if (_ui.value.listening) { recorder.stop(); return }
        viewModelScope.launch {
            val s = settings.value
            val gemKey = s.geminiSpeechKey.ifBlank { if (s.chatUrl.contains("googleapis")) s.chatKey else "" }
            if (s.speechEngine == SpeechEngine.GEMINI && gemKey.isBlank()) { say("Add a Gemini API key in Settings → Voice", error = true); return@launch }
            if (s.speechEngine == SpeechEngine.PARAKEET && s.asrUrl.isBlank()) { say("Add the speech server in Settings", error = true); return@launch }
            tts?.stop()
            _ui.update { it.copy(listening = true) }
            val wav = try {
                recorder.record { lvl -> _ui.update { it.copy(micLevel = lvl) } }
            } catch (e: Exception) {
                say("Mic problem: ${e.message}", error = true); null
            } finally {
                _ui.update { it.copy(listening = false, micLevel = 0f) }
            }
            if (wav == null) return@launch
            _ui.update { it.copy(thinking = true, status = "Transcribing…", steps = emptyList(), shot = null) }
            val text = runCatching {
                if (s.speechEngine == SpeechEngine.GEMINI) GeminiTranscriber.transcribe(gemKey, s.geminiSpeechModel, wav, _ui.value.apps.map { it.label } + listOf("YouTube", "Netflix", "Prime Video", "JioHotstar", "Crunchyroll", "TizenTube"))
                else Transcriber.transcribe(s.asrUrl, s.asrKey, wav)
            }
                .onFailure { say("Couldn't transcribe: ${it.message}", error = true) }
                .getOrNull()
            _ui.update { it.copy(thinking = false) }
            if (text.isNullOrBlank()) { if (text != null) say("I didn't catch that.", error = true); return@launch }
            send(text)
        }
    }

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        tv.fastKeys = { k, n -> settings.value.btRemote && dev.syed.tvpilot.tv.BtRemote.send(k, n) }
        dev.syed.tvpilot.tv.BtRemote.onHostConnected = { mac ->
            viewModelScope.launch { val cur = store.settings.first(); if (cur.btAddress != mac) store.save(cur.copy(btAddress = mac)) }
        }
        viewModelScope.launch {
            val s = store.settings.first()
            if (s.tvHost.isNotBlank()) connect()
            if (s.btRemote) dev.syed.tvpilot.tv.BtRemote.start(getApplication(), s.btAddress)
        }
    }

    /** Turns the Bluetooth remote on (after permission) without pairing. */
    fun btStart() = viewModelScope.launch {
        val s = store.settings.first()
        dev.syed.tvpilot.tv.BtRemote.start(getApplication(), s.btAddress)
    }

    /** One tap: read the TV's Bluetooth address over ADB, pair, accept the TV's dialog, connect. */
    fun btPair(current: Settings) = viewModelScope.launch {
        val bt = dev.syed.tvpilot.tv.BtRemote
        store.save(current.copy(btRemote = true))
        if (_ui.value.connection !is Connection.Connected) connect(current).join()
        val mac = if (_ui.value.connection is Connection.Connected) runCatching { tv.bluetoothAddress() }.getOrNull() else null
        if (mac == null) {
            if (current.btAddress.isNotBlank()) { bt.pair(getApplication(), current.btAddress); return@launch }
            bt.start(getApplication(), null)
            say("Couldn't read the TV's Bluetooth address. Use \"Pair from the TV\" in Settings.", error = true)
            return@launch
        }
        store.save(store.settings.first().copy(btRemote = true, btAddress = mac))
        bt.pair(getApplication(), mac)
        // The TV asks "Pair with this phone?". Press its Pair button over ADB.
        repeat(8) {
            delay(1500)
            if (bt.isBonded(mac)) return@launch
            runCatching { tv.tapLabel("pair", "connect", "ok", "accept", "yes") }
        }
    }

    fun btOff() { dev.syed.tvpilot.tv.BtRemote.stop() }

    fun saveSettings(s: Settings) = viewModelScope.launch {
        val old = settings.value
        store.save(s)
        if (old.btRemote && !s.btRemote) dev.syed.tvpilot.tv.BtRemote.stop()
        if (s.btRemote && !old.btRemote) dev.syed.tvpilot.tv.BtRemote.start(getApplication(), s.btAddress)
        if (s.tvHost != old.tvHost || s.tvPort != old.tvPort || _ui.value.connection !is Connection.Connected) connect(s)
    }

    fun connect(s: Settings? = null) = viewModelScope.launch {
        val cfg = s ?: store.settings.first()
        if (cfg.tvHost.isBlank()) {
            _ui.update { it.copy(connection = Connection.Failed("Add your TV's IP address in Settings")) }
            return@launch
        }
        _ui.update { it.copy(connection = Connection.Connecting) }
        tv.connect(cfg.tvHost, cfg.tvPort)
            .onSuccess {
                val apps = runCatching { AppCatalog.forPackages(tv.installedPackages()) }.getOrDefault(emptyList())
                _ui.update { it.copy(connection = Connection.Connected(cfg.tvHost), apps = apps) }
                refreshOnScreen()
            }
            .onFailure { e ->
                _ui.update { it.copy(connection = Connection.Failed(e.message ?: "Couldn't reach the TV")) }
            }
    }

    private suspend fun refreshOnScreen() {
        val pkg = runCatching { tv.foregroundPackage() }.getOrNull()
        _ui.update { st -> st.copy(onScreen = st.apps.firstOrNull { it.packageName == pkg }) }
    }

    fun press(key: TvKey) = viewModelScope.launch {
        runCatching { tv.key(key) }.onFailure { say("Lost the TV: ${it.message}", error = true) }
    }

    fun open(app: TvApp) = viewModelScope.launch {
        runCatching { tv.launch(app.packageName) }
            .onSuccess { _ui.update { it.copy(onScreen = app) } }
            .onFailure { say("Couldn't open ${app.label}: ${it.message}", error = true) }
    }

    private var job: kotlinx.coroutines.Job? = null

    fun stop() {
        job?.cancel()
        _ui.update { it.copy(thinking = false, status = null) }
    }

    fun send(command: String) {
        val text = command.trim()
        if (text.isEmpty()) return
        job?.cancel()
        _ui.update { it.copy(lines = it.lines + ChatLine(true, text), thinking = true, status = null, steps = emptyList(), shot = null) }
        job = viewModelScope.launch {
            val s = settings.value
            try {
                if (_ui.value.connection !is Connection.Connected) {
                    connect(s).join()
                    if (_ui.value.connection !is Connection.Connected) error("Not connected to the TV")
                }
                // Plain remote commands never wait on a model.
                QuickCommands.match(text)?.let { executePlan(it, s); return@launch }

                if (s.brainMode == BrainMode.CHAT && s.chatKey.isNotBlank() && s.chatUrl.isNotBlank()) {
                    val agent = VisionAgent(
                        s.chatUrl, s.chatKey, s.chatModel, tv, _ui.value.apps,
                        onStep = { st -> _ui.update { it.copy(status = st) } },
                        onDone = { d -> _ui.update { it.copy(steps = (it.steps + d).takeLast(8)) } },
                        onShot = { jpg ->
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
                            _ui.update { it.copy(shot = bmp) }
                        },
                        instructions = s.customInstructions,
                    )
                    try {
                        val r = agent.run(text, _ui.value.onScreen)
                        r.opened?.let { app -> _ui.update { it.copy(onScreen = app) } }
                        say(r.say, r.chips, error = !r.success)
                        refreshOnScreen()
                        return@launch
                    } catch (e: VisionApiException) {
                        if (s.serverUrl.isBlank()) throw e
                        _ui.update { it.copy(status = "Vision model failed, asking d1…") }
                        val plan = DecisionBrain(s.serverUrl, s.apiKey).plan(text, _ui.value.apps, _ui.value.onScreen)
                        executePlan(plan, s, extraChips = listOf("d1 backup (${shortReason(e.message)})"))
                        return@launch
                    }
                }
                if (s.serverUrl.isBlank()) error("Add your d1 server in Settings")
                val plan = DecisionBrain(s.serverUrl, s.apiKey).plan(text, _ui.value.apps, _ui.value.onScreen)
                executePlan(plan, s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("Stopped.", error = false)
                throw e
            } catch (e: Exception) {
                say(e.message ?: "Something went wrong", error = true)
            } finally {
                _ui.update { it.copy(thinking = false, status = null) }
            }
        }
    }

    private fun shortReason(msg: String?): String = when {
        msg == null -> "no reply"
        msg.contains("429") -> "quota hit"
        msg.contains("401") || msg.contains("403") -> "bad key"
        msg.contains("404") -> "model not found"
        else -> msg.take(40)
    }

    private suspend fun executePlan(plan: Plan, s: Settings, extraChips: List<String> = emptyList()) {
        val chips = extraChips.toMutableList()
        for (a in plan.actions) {
            when (a) {
                is TvAction.OpenApp -> {
                    if (_ui.value.onScreen != a.app) { tv.launch(a.app.packageName); _ui.update { it.copy(onScreen = a.app) } }
                    chips += "Opened ${a.app.label}"
                }
                is TvAction.Search -> chips += findAndPlay(a.app ?: _ui.value.onScreen, a.query, s.autoPlayFirst)
                is TvAction.Press -> { tv.key(a.key, a.times); chips += a.key.label + if (a.times > 1) " ×${a.times}" else "" }
                is TvAction.Type -> { tv.typeText(a.text); chips += "Typed \"${a.text}\"" }
                is TvAction.PickOnScreen -> {
                    if (a.afterOpen) _ui.value.onScreen?.let { tv.waitForApp(it.packageName) }
                    val pick = tv.pickOnScreen(a.title)
                    if (pick.tapped == null) error(notFound(a.title, pick))
                    chips += "Picked \"${pick.tapped}\""
                    if (tv.tapLabel("resume", "continue watching", "continue", "play", "watch now") != null) chips += "Pressed play"
                }
            }
        }
        plan.confidence?.let { if (plan.actions.isNotEmpty()) chips += "${(it * 100).toInt()}% sure" }
        say(plan.say, chips)
        refreshOnScreen()
    }

    /**
     * Gets a title playing inside an app: deep link when the app has one; otherwise open it,
     * look for the title on its home rows (Continue Watching), then fall back to the app's own search.
     * Never uses Google's global search, which pops up the Assistant.
     */
    private suspend fun findAndPlay(app: TvApp?, query: String, autoPlay: Boolean): List<String> {
        requireNotNull(app) { "Which app should I play \"$query\" in?" }
        if (app.searchUrl != null) {
            tv.deepSearch(app, query, autoPlay)
            return listOf("${app.label} · $query")
        }
        val chips = mutableListOf<String>()
        if (_ui.value.onScreen != app || tv.foregroundPackage() != app.packageName) {
            tv.launch(app.packageName)
            _ui.update { it.copy(onScreen = app) }
            chips += "Opened ${app.label}"
            tv.waitForApp(app.packageName)
        }
        var pick = tv.pickOnScreen(query, attempts = 2)
        if (pick.tapped == null) {
            val boxY = tv.searchInsideApp(query)
            if (boxY != null) {
                chips += "Searched \"$query\""
                pick = tv.pickOnScreen(query, attempts = 3, avoidY = boxY)
            }
        }
        if (pick.tapped == null) error(notFound(query, pick))
        chips += "Picked \"${pick.tapped}\""
        delay(1800)
        if (tv.tapLabel("resume", "continue watching", "continue", "play", "watch now") != null) chips += "Pressed play"
        return chips
    }

    private fun notFound(title: String, pick: TvController.Pick): String {
        val sample = pick.seen.map { it.take(28) }.distinct().take(8)
        return if (sample.isEmpty()) "Couldn't read anything on the TV screen, so I couldn't find \"$title\"."
        else "Couldn't spot \"$title\". I could read (${pick.how}): " + sample.joinToString(", ")
    }

    fun clear() = _ui.update { it.copy(lines = emptyList()) }

    private fun say(text: String, chips: List<String> = emptyList(), error: Boolean = false) {
        _ui.update { it.copy(lines = it.lines + ChatLine(false, text, chips, error)) }
        if (settings.value.speakReplies && !error) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    override fun onCleared() {
        dev.syed.tvpilot.tv.BtRemote.stop()
        tts?.shutdown()
        tv.disconnect()
    }
}
