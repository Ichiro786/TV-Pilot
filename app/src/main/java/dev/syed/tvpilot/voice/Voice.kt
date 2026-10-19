package dev.syed.tvpilot.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.syed.tvpilot.brain.http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Records 16 kHz mono speech and stops by itself after you go quiet,
 * or when [stop] is called (tapping the mic again).
 */
class SpeechRecorder {
    @Volatile private var stopRequested = false

    fun stop() { stopRequested = true }

    /** Returns a WAV file's bytes, or null if nothing was said. */
    @SuppressLint("MissingPermission") // the activity asks for RECORD_AUDIO before calling this
    suspend fun record(onLevel: (Float) -> Unit = {}): ByteArray? = withContext(Dispatchers.IO) {
        stopRequested = false
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(rate / 50) // 20 ms
        var heardSpeech = false
        var quietMs = 0
        var totalMs = 0
        var noise = 300.0 // running estimate of background level
        try {
            rec.startRecording()
            while (coroutineContext.isActive && !stopRequested) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) { bb.putShort(frame[i]); sum += frame[i].toDouble() * frame[i] }
                pcm.write(bb.array())
                val rms = sqrt(sum / n)
                onLevel((rms / 4000.0).coerceIn(0.0, 1.0).toFloat())
                totalMs += 20
                val speaking = rms > maxOf(700.0, noise * 2.5)
                if (!speaking && !heardSpeech) noise = noise * 0.95 + rms * 0.05
                if (speaking) { heardSpeech = true; quietMs = 0 } else quietMs += 20
                if (heardSpeech && quietMs >= 900) break       // finished talking
                if (!heardSpeech && totalMs >= 6000) break     // never started
                if (totalMs >= 15000) break                    // hard cap
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!heardSpeech && !stopRequested) null else wav(pcm.toByteArray(), rate)
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}

/** Sends audio to an OpenAI-style /v1/audio/transcriptions endpoint (our Parakeet server). */
object Transcriber {
    suspend fun transcribe(baseUrl: String, apiKey: String, wav: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "parakeet")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val url = baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/audio/transcriptions"
        val req = Request.Builder().url(url).post(body)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) error("Speech server said ${res.code}")
            JSONObject(text).optString("text").trim()
        }
    }
}

/** Transcribes with Gemini's audio understanding. Handles Hindi-English mixes and names well. */
object GeminiTranscriber {
    private const val BASE = "https://generativelanguage.googleapis.com"

    suspend fun transcribe(apiKey: String, model: String, wav: ByteArray, vocab: List<String> = emptyList()): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add a Gemini API key in Settings" }
        if (model.contains("transcribe")) transcribeModel(apiKey.trim(), model.trim(), wav, vocab)
        else generate(apiKey, model, wav)
    }

    /** gemini-*-transcribe lives on the Interactions API, not generateContent. */
    private fun transcribeModel(key: String, model: String, wav: ByteArray, vocab: List<String>): String {
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val inline = runCatching { interact(key, model, JSONObject().put("type", "audio").put("data", b64).put("mime_type", "audio/wav"), vocab) }
        inline.getOrNull()?.let { return it }
        // Some keys/regions only accept a file URI; upload and retry once.
        val uri = upload(key, wav)
        return interact(key, model, JSONObject().put("type", "audio").put("uri", uri).put("mime_type", "audio/wav"), vocab)
    }

    private fun interact(key: String, model: String, audio: JSONObject, vocab: List<String>): String {
        val tc = JSONObject().put("mode", "smart")
        val terms = vocab.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(100)
        if (terms.isNotEmpty()) tc.put("custom_vocabulary", org.json.JSONArray(terms))
        val body = JSONObject().put("model", model)
            .put("input", org.json.JSONArray().put(audio))
            .put("generation_config", JSONObject().put("transcription_config", tc))
        val req = Request.Builder().url("$BASE/v1beta/interactions")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) fail(res.code, text)
            val json = JSONObject(text)
            json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it.trim() }
            val out = StringBuilder()
            val steps = json.optJSONArray("steps") ?: org.json.JSONArray()
            for (i in 0 until steps.length()) {
                val st = steps.optJSONObject(i) ?: continue
                if (st.optString("type").let { it.isNotEmpty() && it != "model_output" }) continue
                val content = st.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val c = content.optJSONObject(j) ?: continue
                    if (c.optString("type", "text") == "text") out.append(c.optString("text"))
                }
            }
            if (out.isEmpty()) json.optJSONArray("outputs")?.let { o ->
                for (j in 0 until o.length()) o.optJSONObject(j)?.optString("text")?.let(out::append)
            }
            return out.toString().trim()
        }
    }

    /** Resumable upload to the Files API; returns the file URI. */
    private fun upload(key: String, wav: ByteArray): String {
        val start = Request.Builder().url("$BASE/upload/v1beta/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", wav.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/wav")
            .post(JSONObject().put("file", JSONObject().put("display_name", "tvpilot-voice")).toString()
                .toRequestBody("application/json".toMediaType())).build()
        val uploadUrl = http.newCall(start).execute().use { res ->
            if (!res.isSuccessful) fail(res.code, res.body.string())
            res.header("x-goog-upload-url") ?: error("Gemini upload gave no URL")
        }
        val put = Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0")
            .post(wav.toRequestBody("audio/wav".toMediaType())).build()
        return http.newCall(put).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) fail(res.code, text)
            JSONObject(text).getJSONObject("file").getString("uri")
        }
    }

    private fun fail(code: Int, body: String): Nothing {
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        error("Gemini said $code${msg?.let { ": " + it.take(140) } ?: ""}")
    }

    private fun generate(apiKey: String, model: String, wav: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val prompt = "Transcribe this short voice command for a TV remote exactly as spoken. " +
            "It may mix English and Hindi, and may name YouTubers, shows, movies or apps. " +
            "Write Hindi words in Latin script. Output only the transcript, nothing else. " +
            "If there is no speech, output nothing."
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64))))))
            .put("generationConfig", JSONObject().put("temperature", 0))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"
        val req = Request.Builder().url(url)
            .header("x-goog-api-key", apiKey.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) fail(res.code, text)
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return@use ""
            buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
                .trim().trim('"')
        }
    }
}
