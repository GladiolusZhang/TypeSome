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
    fun chatAudio(baseUrl: String, apiKey: String, model: String, system: String, wav: ByteArray, instruction: String, noThinking: Boolean = true): String {
        if (apiKey.isBlank()) throw IOException("未填写多模态大模型 API Key")
        val audio = "data:;base64," + android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val json = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("modalities", JSONArray().put("text"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "input_audio")
                        .put("input_audio", JSONObject().put("data", audio).put("format", "wav")))
                    .put(JSONObject().put("type", "text").put("text", instruction)))))
        if (noThinking && "dashscope" in baseUrl.lowercase()) json.put("enable_thinking", false)
        val req = Request.Builder().url(url(baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("多模态大模型调用失败 ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
            val out = StringBuilder()
            val source = resp.body?.source() ?: throw IOException("多模态大模型没有返回内容")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val choices = try { JSONObject(data).optJSONArray("choices") } catch (_: Exception) { null } ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                if (!delta.isNull("content")) out.append(delta.optString("content"))
            }
            return out.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
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
