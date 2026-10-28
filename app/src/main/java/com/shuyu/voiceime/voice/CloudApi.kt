package com.shuyu.voiceime.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OpenAI-compatible endpoints: /audio/transcriptions and /chat/completions. */
object CloudApi {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** The prompts expect the dictated text inside <transcription> tags. */
    fun wrap(text: String) = "<transcription>\n$text\n</transcription>"

    private fun url(base: String, path: String) = base.trimEnd('/') + path

    fun transcribe(baseUrl: String, apiKey: String, model: String, wav: ByteArray, language: String, hint: String = ""): String {
        if (apiKey.isBlank()) throw IOException("未填写语音识别 API Key")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            // Whisper models take a text prompt that biases spelling of names and terms
            .apply { if (hint.isNotBlank() && "whisper" in model.lowercase()) addFormDataPart("prompt", hint) }
            .build()
        val req = Request.Builder().url(url(baseUrl, "/audio/transcriptions"))
            .header("Authorization", "Bearer $apiKey").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("语音识别失败 ${resp.code}: ${text.take(200)}")
            return try { JSONObject(text).optString("text") } catch (_: Exception) { text }.trim()
        }
    }

    /**
     * Sends the recording itself, with the system prompt, to a chat model that understands audio
     * (Qwen-Omni on DashScope: an `input_audio` part with a `data:;base64,` URL, streamed replies).
     */
    /** How long a request to the audio model took: until the reply started, its first text, and in all. */
    class Timing(val headersMs: Long, val firstTextMs: Long, val totalMs: Long, val requests: Int)

    fun chatAudio(baseUrl: String, apiKey: String, model: String, system: String, audioBytes: ByteArray, format: String,
                  instruction: String, noThinking: Boolean = true, cancelled: () -> Boolean = { false },
                  onTiming: (Timing) -> Unit = {}, onPartial: (String) -> Unit = {}): String {
        if (apiKey.isBlank()) throw IOException("未填写多模态大模型 API Key")
        val audio = "data:;base64," + android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val json = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("modalities", JSONArray().put("text"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "input_audio")
                        .put("input_audio", JSONObject().put("data", audio).put("format", format)))
                    .put(JSONObject().put("type", "text").put("text", instruction)))))
        if (noThinking && "dashscope" in baseUrl.lowercase()) {
            // Qwen3.8-Omni thinks by default (reasoning_effort xhigh), which makes a short reply take
            // many seconds; older Omni models use enable_thinking instead
            if (Regex("qwen3\\.[6-9]|qwen[4-9]").containsMatchIn(model.lowercase())) json.put("reasoning_effort", "none")
            else json.put("enable_thinking", false)
        }
        val body = json.toString()
        val t0 = System.currentTimeMillis()
        // The first text of a reply usually comes within about a second, but one request in ten or
        // twenty waits several seconds. When the first is slow, the same request goes out once more
        // and whichever starts answering first is used; the other is cancelled.
        val result = java.util.concurrent.CompletableFuture<String>()
        val winner = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
        val calls = java.util.Collections.synchronizedList(ArrayList<okhttp3.Call>())
        val failures = java.util.concurrent.atomic.AtomicInteger()
        fun attempt() {
            val call = http.newCall(Request.Builder().url(url(baseUrl, "/chat/completions"))
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType())).build())
            calls.add(call)
            Thread {
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("多模态大模型调用失败 ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
                        val headers = System.currentTimeMillis() - t0
                        var first = 0L
                        val out = StringBuilder()
                        val source = resp.body?.source() ?: throw IOException("多模态大模型没有返回内容")
                        while (!cancelled()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.substring(5).trim()
                            if (data == "[DONE]") break
                            val choices = try { JSONObject(data).optJSONArray("choices") } catch (_: Exception) { null } ?: continue
                            if (choices.length() == 0) continue
                            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                            if (delta.isNull("content")) continue
                            val piece = delta.optString("content")
                            if (piece.isEmpty()) continue
                            if (first == 0L) {
                                first = System.currentTimeMillis() - t0
                                if (!winner.compareAndSet(null, call) && winner.get() !== call) { call.cancel(); return@Thread }
                                synchronized(calls) { calls.filter { it !== call }.forEach { it.cancel() } }
                            }
                            out.append(piece)
                            if ("<think>" !in out) onPartial(out.toString())
                        }
                        if (cancelled()) throw IOException("已取消")
                        onTiming(Timing(headers, first, System.currentTimeMillis() - t0, calls.size))
                        result.complete(out.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim())
                    }
                } catch (e: Exception) {
                    // a lost race or the other request's failure is not the answer while one is still going
                    if (winner.get() === call || failures.incrementAndGet() >= calls.size && winner.get() == null) result.completeExceptionally(e)
                }
            }.apply { isDaemon = true }.start()
        }
        attempt()
        try {
            while (true) {
                try { return result.get(HEDGE_MS, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) {}
                if (cancelled()) throw IOException("已取消")
                if (winner.get() == null && calls.size < 2 && failures.get() == 0) attempt()
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: e.toString())
        } finally {
            synchronized(calls) { calls.forEach { if (it !== winner.get() || cancelled()) it.cancel() } }
        }
    }

    private const val HEDGE_MS = 2500L

    /** Opens the connection (DNS, TLS) while the user is still talking, so the real request starts at once. */
    fun warmUp(baseUrl: String, apiKey: String) {
        try {
            val req = Request.Builder().url(url(baseUrl, "/models")).header("Authorization", "Bearer $apiKey").head().build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    fun chat(baseUrl: String, apiKey: String, model: String, system: String, user: String, noThinking: Boolean = true): String {
        if (apiKey.isBlank()) throw IOException("未填写大模型 API Key")
        val json = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (noThinking) {
            // Each provider has its own switch; only send it where it is understood.
            val host = baseUrl.lowercase()
            when {
                "deepseek" in host -> json.put("thinking", JSONObject().put("type", "disabled"))
                "siliconflow" in host || "dashscope" in host -> json.put("enable_thinking", false)
            }
        }
        val req = Request.Builder().url(url(baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("大模型调用失败 ${resp.code}: ${text.take(200)}")
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
            return content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
    }
}
