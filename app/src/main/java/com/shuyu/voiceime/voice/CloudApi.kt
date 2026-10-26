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
