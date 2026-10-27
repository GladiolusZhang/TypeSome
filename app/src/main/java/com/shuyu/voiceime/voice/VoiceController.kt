package com.shuyu.voiceime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shuyu.voiceime.Prefs
import java.util.concurrent.Executors

/** Long-press-space voice input: record -> ASR -> optional LLM post-processing. */
class VoiceController(private val ctx: Context, private val ui: Ui) {

    enum class Mode { PLAIN, SUMMARY, TRANSLATE }

    interface Ui {
        fun onLevel(level: Float)
        fun onStatus(text: String?)          // null hides the status line
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recorder: Recorder? = null
    @Volatile private var job = 0

    val isRecording get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        val r = Recorder { lvl -> main.post { ui.onLevel(lvl) } }
        if (!r.start()) {
            ui.onError("无法打开麦克风，请在设置页授予麦克风权限")
            return false
        }
        recorder = r
        // Warm up the offline model while the user is still talking.
        if (prefs.asrEngine == "offline" && prefs.offlineModelId.isNotEmpty()) {
            worker.execute { try { OfflineAsr.ensure(ctx, prefs.offlineModelId) } catch (_: Exception) {} }
        }
        return true
    }

    fun cancel() {
        recorder?.stop(); recorder = null
        job++
        ui.onStatus(null)
    }

    fun finish(mode: Mode, app: String? = null) {
        val r = recorder ?: return
        recorder = null
        val raw = r.stop()
        if (raw.size < 16000 * 2 / 3) { // < ~0.3 s
            ui.onStatus(null)
            ui.onError("说话时间太短")
            return
        }
        val pcm = Vad.trim(raw, prefs.vadLevel)
        if (pcm == null) {
            ui.onStatus(null)
            ui.onError("没有听到声音")
            return
        }
        val id = ++job
        val direct = mode != Mode.PLAIN && useAudioLlm(pcm)
        ui.onStatus(if (direct) null else "识别中…")
        worker.execute {
            if (direct) {
                try {
                    val out = listen(mode, pcm, app, id)
                    if (id != job) return@execute
                    if (out.isNotBlank()) { post(id) { ui.onStatus(null); ui.onResult(out) }; return@execute }
                } catch (e: Exception) {
                    if (id != job) return@execute
                    // the text pipeline still works: say why, then fall back to it
                    post(id) { ui.onStatus("多模态模型不可用，改用识别后整理…") }
                    android.util.Log.w("VoiceController", "audio LLM failed", e)
                }
            }
            try {
                val vocab = Vocabulary(prefs.vocabulary)
                val text = vocab.apply(Vad.clean(recognize(pcm, vocab)))
                if (id != job) return@execute
                if (text.isBlank()) { post(id) { ui.onStatus(null); ui.onError("没有识别到内容") }; return@execute }
                // core rules + profile + vocabulary stay identical across requests (cache-friendly prefix);
                // only the short mode block and the app tone differ
                val tone = if (prefs.appTone) Prompts.appTone(app) else null
                fun system(mode: String) = Prompts.build(prefs.corePrompt, prefs.userProfile, vocab, mode, tone)
                fun llm(sys: String) = CloudApi.chat(prefs.llmBaseUrl, prefs.effectiveLlmKey, prefs.llmModel, sys, CloudApi.wrap(text), prefs.noThinking)
                val out = when (mode) {
                    Mode.PLAIN -> if (!prefs.plainCorrect || prefs.effectiveLlmKey.isBlank() || !Prompts.needsProofread(text)) text else {
                        post(id) { ui.onStatus("纠错中…") }
                        // only a proofreading pass: if the model is unreachable, the raw text is still fine
                        try { vocab.apply(llm(system(prefs.proofreadPrompt))).ifBlank { text } } catch (_: Exception) { text }
                    }
                    Mode.SUMMARY -> {
                        post(id) { ui.onStatus("AI 整理中…") }
                        vocab.apply(llm(system(prefs.polishPrompt)))
                    }
                    Mode.TRANSLATE -> {
                        post(id) { ui.onStatus("翻译成${prefs.targetLanguage}…") }
                        llm(system(prefs.translatePrompt.replace("{lang}", prefs.targetLanguage)))
                    }
                }
                post(id) { ui.onStatus(null); ui.onResult(out) }
            } catch (e: Exception) {
                post(id) { ui.onStatus(null); ui.onError(e.message ?: e.toString()) }
            }
        }
    }

    /** Whether this recording goes straight to the audio model (small enough to send inline). */
    private fun useAudioLlm(pcm: ByteArray): Boolean =
        prefs.audioLlm && prefs.effectiveAudioLlmKey.isNotBlank() && pcm.size < MAX_AUDIO_BYTES

    /** 智能整理 / 翻译 in one step: the model hears the recording, no speech recognition first. */
    private fun listen(mode: Mode, pcm: ByteArray, app: String?, id: Int): String {
        val vocab = Vocabulary(prefs.vocabulary)
        val tone = if (prefs.appTone) Prompts.appTone(app) else null
        val modeBlock = if (mode == Mode.TRANSLATE) prefs.translatePrompt.replace("{lang}", prefs.targetLanguage) else prefs.polishPrompt
        post(id) { ui.onStatus(if (mode == Mode.TRANSLATE) "翻译成${prefs.targetLanguage}…" else "AI 整理中…") }
        val system = Prompts.build(prefs.corePrompt, prefs.userProfile, vocab, modeBlock, tone)
        val out = CloudApi.chatAudio(prefs.audioLlmBaseUrl, prefs.effectiveAudioLlmKey, prefs.audioLlmModel, system,
            Recorder.toWav(pcm), Prompts.AUDIO_INSTRUCTION, prefs.noThinking)
        return if (mode == Mode.TRANSLATE) out else vocab.apply(out)
    }

    private fun recognize(pcm: ByteArray, vocab: Vocabulary): String {
        val offline = prefs.asrEngine == "offline" && prefs.offlineModelId.isNotEmpty()
        return if (offline) OfflineAsr.transcribe(ctx, prefs.offlineModelId, pcm)
        else CloudApi.transcribe(prefs.asrBaseUrl, prefs.asrApiKey, prefs.asrModel, Recorder.toWav(pcm), prefs.asrLanguage, vocab.asHint())
    }

    private companion object {
        /** Inline audio must stay under 10 MB of base64: about 3.5 minutes of 16 kHz mono. */
        const val MAX_AUDIO_BYTES = 7_000_000
    }

    private fun post(id: Int, block: () -> Unit) = main.post { if (id == job) block() }

    fun release() {
        cancel()
        worker.shutdown()
    }
}
