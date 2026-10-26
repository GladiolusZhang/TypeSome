package com.shuyu.voiceime.input

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/**
 * Chinese handwriting via Google ML Kit Digital Ink Recognition.
 * The ~20 MB model is downloaded on first use (needs access to Google servers), then works offline.
 */
object Handwriting {
    private var recognizer: DigitalInkRecognizer? = null
    private var model: DigitalInkRecognitionModel? = null
    @Volatile var ready = false; private set
    @Volatile private var downloading = false

    private fun model(): DigitalInkRecognitionModel {
        model?.let { return it }
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani-CN")
            ?: throw IllegalStateException("不支持中文手写模型")
        return DigitalInkRecognitionModel.builder(id).build().also { model = it }
    }

    /** Makes sure the model is present. [done] gets null on success or an error message. */
    fun prepare(done: (String?) -> Unit) {
        if (ready) { done(null); return }
        if (downloading) { done("手写模型下载中，请稍候…"); return }
        val m = try { model() } catch (e: Exception) { done(e.message); return }
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(m).addOnSuccessListener { has ->
            if (has == true) {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
                ready = true
                done(null)
            } else {
                downloading = true
                done("首次使用正在下载手写模型（约 20MB，需能访问 Google）…")
                manager.download(m, DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        downloading = false
                        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
                        ready = true
                        done(null)
                    }
                    .addOnFailureListener { e -> downloading = false; done("手写模型下载失败：${e.message}") }
            }
        }.addOnFailureListener { e -> done("手写模型检查失败：${e.message}") }
    }

    /** strokes: list of strokes, each a list of (x, y, t). */
    fun recognize(strokes: List<List<FloatArray>>, result: (List<String>) -> Unit, error: (String) -> Unit) {
        val r = recognizer ?: run { error("手写模型还没准备好"); return }
        val ink = Ink.builder()
        for (s in strokes) {
            val sb = Ink.Stroke.builder()
            for (pt in s) sb.addPoint(Ink.Point.create(pt[0], pt[1], pt[2].toLong()))
            ink.addStroke(sb.build())
        }
        r.recognize(ink.build())
            .addOnSuccessListener { res -> result(res.candidates.map { it.text }) }
            .addOnFailureListener { e -> error(e.message ?: "识别失败") }
    }
}
