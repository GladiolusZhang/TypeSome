package com.shuyu.voiceime

import android.content.Context
import android.content.SharedPreferences
import com.shuyu.voiceime.voice.Prompts

/** All user settings in one place. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, null)?.takeIf { it.isNotBlank() } ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    // ---- ASR
    /** "online" or "offline" */
    var asrEngine: String get() = str("asr_engine", "online"); set(v) = put("asr_engine", v)
    var asrBaseUrl: String get() = str("asr_base_url", "https://api.siliconflow.cn/v1"); set(v) = put("asr_base_url", v)
    var asrApiKey: String get() = sp.getString("asr_api_key", "") ?: ""; set(v) = put("asr_api_key", v)
    var asrModel: String get() = str("asr_model", "FunAudioLLM/SenseVoiceSmall"); set(v) = put("asr_model", v)
    var asrLanguage: String get() = sp.getString("asr_language", "") ?: ""; set(v) = put("asr_language", v)
    var offlineModelId: String get() = sp.getString("offline_model", "") ?: ""; set(v) = put("offline_model", v)
    var downloadMirror: String get() = sp.getString("download_mirror", "") ?: ""; set(v) = put("download_mirror", v)

    // ---- LLM
    var llmBaseUrl: String get() = str("llm_base_url", "https://api.siliconflow.cn/v1"); set(v) = put("llm_base_url", v)
    var llmApiKey: String get() = sp.getString("llm_api_key", "") ?: ""; set(v) = put("llm_api_key", v)
    var llmModel: String get() = str("llm_model", "Qwen/Qwen2.5-7B-Instruct"); set(v) = put("llm_model", v)
    // Voice prompts are layered (see voice/Prompts): shared core rules + one short block per mode.
    var corePrompt: String get() = str("core_prompt", Prompts.CORE); set(v) = put("core_prompt", v)
    var proofreadPrompt: String get() = str("proofread_prompt", Prompts.PROOFREAD); set(v) = put("proofread_prompt", v)
    var polishPrompt: String get() = str("polish_prompt", Prompts.POLISH); set(v) = put("polish_prompt", v)
    var translatePrompt: String get() = str("translate_mode_prompt", Prompts.TRANSLATE); set(v) = put("translate_mode_prompt", v)
    /** Adjust tone to the app being typed into (chat / email / notes…). */
    val appTone: Boolean get() = sp.getBoolean("app_tone", true)
    /** 「语音输入」 also goes through the model, but only to fix misrecognized words and apply spoken corrections. */
    val plainCorrect: Boolean get() = sp.getBoolean("plain_correct", true)
    /** Free text about the speaker (field, style), appended to every voice prompt. */
    var userProfile: String get() = sp.getString("user_profile", null) ?: Prompts.PROFILE; set(v) = put("user_profile", v)
    /** One entry per line: a term ("DeepSeek") or a fixed replacement ("D四 => DeepSeek"). */
    var vocabulary: String get() = sp.getString("vocabulary", "") ?: ""; set(v) = put("vocabulary", v)
    /** Ask models that can "think" (DeepSeek, Qwen3…) to answer directly: much faster for short texts. */
    val noThinking: Boolean get() = sp.getBoolean("no_thinking", true)
    /** Silence filter before recognition: 0 = off, higher = needs louder speech. */
    val vadLevel: Int get() = sp.getInt("vad_level", 3)
    var targetLanguage: String get() = str("target_language", "英语"); set(v) = put("target_language", v)

    /** Key used for LLM calls; falls back to the ASR key when both use the same provider. */
    val effectiveLlmKey: String get() = llmApiKey.ifBlank { asrApiKey }

    // 智能整理 / 翻译 straight from the recording, with a model that takes audio (Qwen-Omni)
    val audioLlm: Boolean get() = sp.getBoolean("audio_llm", true)
    var audioLlmBaseUrl: String get() = str("audio_llm_base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1"); set(v) = put("audio_llm_base_url", v)
    var audioLlmApiKey: String get() = sp.getString("audio_llm_api_key", "") ?: ""; set(v) = put("audio_llm_api_key", v)
    var audioLlmModel: String get() = str("audio_llm_model", "qwen3.8-omni-flash"); set(v) = put("audio_llm_model", v)
    /** The key for the audio model: its own, else the text model's or the ASR one's when they are the same provider. */
    val effectiveAudioLlmKey: String get() {
        if (audioLlmApiKey.isNotBlank()) return audioLlmApiKey
        val host = try { java.net.URI(audioLlmBaseUrl.trim()).host } catch (_: Exception) { null } ?: return ""
        return when {
            host in llmBaseUrl && llmApiKey.isNotBlank() -> llmApiKey
            host in asrBaseUrl -> asrApiKey
            else -> ""
        }
    }

    // ---- keyboard
    var chineseMode: Boolean get() = sp.getBoolean("chinese_mode", true); set(v) = sp.edit().putBoolean("chinese_mode", v).apply()
    val vibrate: Boolean get() = sp.getBoolean("vibrate", true)
    val fuzzyPinyin: Boolean get() = sp.getBoolean("fuzzy_pinyin", false)
    val autoCorrect: Boolean get() = sp.getBoolean("auto_correct", true)
    val associate: Boolean get() = sp.getBoolean("associate", true)
    val learnWords: Boolean get() = sp.getBoolean("learn_words", true)
    val contactsEnabled: Boolean get() = sp.getBoolean("contacts", false)
    val otpEnabled: Boolean get() = sp.getBoolean("otp", true)
    val calculator: Boolean get() = sp.getBoolean("calculator", true)
    val emojiWords: Boolean get() = sp.getBoolean("emoji_words", true)
    val hideOnOutsideTap: Boolean get() = sp.getBoolean("hide_outside", true)
    val keyboardHeightPercent: Int get() = sp.getInt("kb_height", 100)
    /** "seamless" (flat; white by day, gray at night) | "white" | "classic" | "auto" | "light" | "dark" */
    var skin: String get() = str("skin", "seamless"); set(v) = put("skin", v)

    companion object {
        val LANGUAGES = listOf("英语", "日语", "韩语", "法语", "德语", "西班牙语", "俄语", "葡萄牙语", "意大利语", "阿拉伯语", "泰语", "越南语", "繁体中文")
    }
}
