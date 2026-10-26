package com.shuyu.voiceime.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.IOException

/** On-device recognition with sherpa-onnx. The recognizer is created once and reused. */
object OfflineAsr {
    private var recognizer: OfflineRecognizer? = null
    private var loadedId: String? = null

    @Synchronized
    fun ensure(ctx: Context, modelId: String) {
        if (loadedId == modelId && recognizer != null) return
        val m = ModelManager.byId(modelId) ?: throw IOException("未选择离线模型")
        if (!ModelManager.isInstalled(ctx, m)) throw IOException("离线模型「${m.title}」尚未下载")
        val f = ModelManager.files(ctx, m)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val modelConfig = when (m.kind) {
            ModelManager.Kind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = f["model"]!!, language = "auto", useInverseTextNormalization = true),
                tokens = f["tokens"]!!, numThreads = threads, provider = "cpu", debug = false)
            ModelManager.Kind.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = f["encoder"]!!, decoder = f["decoder"]!!, language = "zh", task = "transcribe"),
                tokens = f["tokens"]!!, numThreads = threads, provider = "cpu", debug = false, modelType = "whisper")
        }
        val config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = modelConfig)
        recognizer?.release()
        recognizer = OfflineRecognizer(null, config)
        loadedId = modelId
    }

    @Synchronized
    fun transcribe(ctx: Context, modelId: String, pcm: ByteArray): String {
        ensure(ctx, modelId)
        val r = recognizer!!
        val stream = r.createStream()
        try {
            stream.acceptWaveform(Recorder.toFloat(pcm), 16000)
            r.decode(stream)
            return r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release(); recognizer = null; loadedId = null
    }
}
