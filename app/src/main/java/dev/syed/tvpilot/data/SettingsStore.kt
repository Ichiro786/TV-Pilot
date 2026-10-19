package dev.syed.tvpilot.data

import android.content.Context
import dev.syed.tvpilot.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class BrainMode { DECISION, CHAT }
/** GOOGLE = the phone's own recogniser; GEMINI = audio sent to the Gemini API. */
enum class SpeechEngine { PARAKEET, GOOGLE, GEMINI }

data class Settings(
    val tvHost: String = "",
    val tvPort: Int = 5555,
    val brainMode: BrainMode = BrainMode.DECISION,
    /** d1 decision server (llama-server). Always kept: it is the backup when the vision model fails. */
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val apiKey: String = BuildConfig.DEFAULT_API_KEY,
    /** Vision chat model: any OpenAI-compatible base URL ending in /v1 (Gemini by default). */
    val chatUrl: String = GEMINI_URL,
    val chatKey: String = "",
    val chatModel: String = "gemini-3.5-flash-lite",
    val speakReplies: Boolean = true,
    val autoPlayFirst: Boolean = false,
    val speechEngine: SpeechEngine = SpeechEngine.PARAKEET,
    /** Parakeet server and its own key (defaults to the server key baked into the app). */
    val asrUrl: String = BuildConfig.DEFAULT_ASR_URL,
    val asrKey: String = BuildConfig.DEFAULT_API_KEY,
    /** Gemini key for transcription; blank falls back to the agent key. */
    val geminiSpeechKey: String = "",
    val geminiSpeechModel: String = "gemini-3.5-transcribe",
    /** Pure black backgrounds for OLED screens. */
    val amoled: Boolean = true,
    /** The centered-mic home. Off brings back the classic home with apps and remote. */
    val focusHome: Boolean = true,
    val showApps: Boolean = true,
    val showRemote: Boolean = true,
    /** Extra rules the vision agent always follows (per-app habits like Nuvio providers). */
    val customInstructions: String = "",
    /** Send remote keys over Bluetooth HID instead of ADB. */
    val btRemote: Boolean = false,
    val btAddress: String = "",
)

const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"

private fun looksLikeChatApi(url: String?) = url != null && (url.contains("generativelanguage") || url.contains("openai") || url.trimEnd('/').endsWith("/v1"))

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val host = stringPreferencesKey("tv_host")
        val port = intPreferencesKey("tv_port")
        val mode = stringPreferencesKey("brain_mode")
        val url = stringPreferencesKey("server_url")
        val key = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("chat_model")
        val speak = booleanPreferencesKey("speak")
        val autoplay = booleanPreferencesKey("autoplay")
        val engine = stringPreferencesKey("speech_engine")
        val asr = stringPreferencesKey("asr_url")
        val asrKey = stringPreferencesKey("asr_key")
        val chatUrl = stringPreferencesKey("chat_url")
        val chatKey = stringPreferencesKey("chat_key")
        val amoled = booleanPreferencesKey("amoled")
        val gemKey = stringPreferencesKey("gemini_speech_key")
        val gemModel = stringPreferencesKey("gemini_speech_model")
        val focus = booleanPreferencesKey("focus_home")
        val showApps = booleanPreferencesKey("show_apps")
        val showRemote = booleanPreferencesKey("show_remote")
        val instructions = stringPreferencesKey("custom_instructions")
        val bt = booleanPreferencesKey("bt_remote")
        val btAddr = stringPreferencesKey("bt_address")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            tvHost = p[K.host] ?: "",
            tvPort = p[K.port] ?: 5555,
            brainMode = runCatching { BrainMode.valueOf(p[K.mode] ?: "DECISION") }.getOrDefault(BrainMode.DECISION),
            // Before 0.4.0 one URL/key pair served both brains; split it up without losing what was typed.
            serverUrl = p[K.url]?.takeUnless { looksLikeChatApi(it) || it.isBlank() } ?: BuildConfig.DEFAULT_SERVER_URL,
            apiKey = p[K.key]?.takeUnless { looksLikeChatApi(p[K.url]) || it.isBlank() } ?: BuildConfig.DEFAULT_API_KEY,
            chatUrl = p[K.chatUrl] ?: p[K.url]?.takeIf { looksLikeChatApi(it) } ?: GEMINI_URL,
            chatKey = p[K.chatKey] ?: p[K.key]?.takeIf { looksLikeChatApi(p[K.url]) } ?: "",
            chatModel = (p[K.model] ?: "gemini-3.5-flash-lite").ifBlank { "gemini-3.5-flash-lite" },
            speakReplies = p[K.speak] ?: true,
            autoPlayFirst = p[K.autoplay] ?: false,
            speechEngine = runCatching { SpeechEngine.valueOf(p[K.engine] ?: "PARAKEET") }.getOrDefault(SpeechEngine.PARAKEET),
            asrUrl = p[K.asr]?.ifBlank { null } ?: BuildConfig.DEFAULT_ASR_URL,
            asrKey = p[K.asrKey]?.ifBlank { null } ?: BuildConfig.DEFAULT_API_KEY,
            amoled = p[K.amoled] ?: true,
            geminiSpeechKey = p[K.gemKey] ?: "",
            geminiSpeechModel = (p[K.gemModel] ?: "gemini-3.5-transcribe").ifBlank { "gemini-3.5-transcribe" }.let { if (it == "gemini-3.5-flash-lite") "gemini-3.5-transcribe" else it },
            focusHome = p[K.focus] ?: true,
            showApps = p[K.showApps] ?: true,
            showRemote = p[K.showRemote] ?: true,
            customInstructions = p[K.instructions] ?: "",
            btRemote = p[K.bt] ?: false,
            btAddress = p[K.btAddr] ?: "",
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[K.host] = s.tvHost.trim()
            p[K.port] = s.tvPort
            p[K.mode] = s.brainMode.name
            p[K.url] = s.serverUrl.trim().trimEnd('/')
            p[K.key] = s.apiKey.trim()
            p[K.model] = s.chatModel.trim()
            p[K.speak] = s.speakReplies
            p[K.autoplay] = s.autoPlayFirst
            p[K.engine] = s.speechEngine.name
            p[K.asr] = s.asrUrl.trim().trimEnd('/')
            p[K.asrKey] = s.asrKey.trim()
            p[K.chatUrl] = s.chatUrl.trim().trimEnd('/')
            p[K.chatKey] = s.chatKey.trim()
            p[K.amoled] = s.amoled
            p[K.gemKey] = s.geminiSpeechKey.trim()
            p[K.gemModel] = s.geminiSpeechModel.trim()
            p[K.focus] = s.focusHome
            p[K.showApps] = s.showApps
            p[K.showRemote] = s.showRemote
            p[K.instructions] = s.customInstructions.trim()
            p[K.bt] = s.btRemote
            p[K.btAddr] = s.btAddress.trim().uppercase()
        }
    }
}
