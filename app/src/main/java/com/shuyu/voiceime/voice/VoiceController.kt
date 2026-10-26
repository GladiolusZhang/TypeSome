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

    fun finish(mode: Mode) {
        val r = recorder ?: return
        recorder = null
        val raw = r.stop()
        if (raw.size < 16000 * 2 / 3) { // < ~0.3 s
            ui.onStatus(null)
            ui.onError("说话时间太短")
            return
        }
        val pcm = Vad.trim(raw)
        if (pcm == null) {
            ui.onStatus(null)
            ui.onError("没有听到声音")
            return
        }
        val id = ++job
        ui.onStatus("识别中…")
        worker.execute {
            try {
                val text = Vad.clean(recognize(pcm))
                if (id != job) return@execute
                if (text.isBlank()) { post(id) { ui.onStatus(null); ui.onError("没有识别到内容") }; return@execute }
                val out = when (mode) {
                    Mode.PLAIN -> text
                    Mode.SUMMARY -> {
                        post(id) { ui.onStatus("AI 整理中…") }
                        CloudApi.chat(prefs.llmBaseUrl, prefs.effectiveLlmKey, prefs.llmModel, prefs.summaryPrompt, text, prefs.noThinking)
                    }
                    Mode.TRANSLATE -> {
                        post(id) { ui.onStatus("翻译成${prefs.targetLanguage}…") }
                        val sys = prefs.translatePrompt.replace("{lang}", prefs.targetLanguage)
                        CloudApi.chat(prefs.llmBaseUrl, prefs.effectiveLlmKey, prefs.llmModel, sys, text, prefs.noThinking)
                    }
                }
                post(id) { ui.onStatus(null); ui.onResult(out) }
            } catch (e: Exception) {
                post(id) { ui.onStatus(null); ui.onError(e.message ?: e.toString()) }
            }
        }
    }

    private fun recognize(pcm: ByteArray): String {
        val offline = prefs.asrEngine == "offline" && prefs.offlineModelId.isNotEmpty()
        return if (offline) OfflineAsr.transcribe(ctx, prefs.offlineModelId, pcm)
        else CloudApi.transcribe(prefs.asrBaseUrl, prefs.asrApiKey, prefs.asrModel, Recorder.toWav(pcm), prefs.asrLanguage)
    }

    private fun post(id: Int, block: () -> Unit) = main.post { if (id == job) block() }

    fun release() {
        cancel()
        worker.shutdown()
    }
}
