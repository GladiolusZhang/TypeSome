package com.shuyu.voiceime.voice

import android.content.Context
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/** Offline models published by sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models). */
object ModelManager {

    enum class Kind { SENSE_VOICE, WHISPER }

    data class Model(val id: String, val title: String, val desc: String, val archive: String, val kind: Kind)

    val MODELS = listOf(
        Model("sensevoice", "SenseVoice（推荐）", "阿里开源，中/英/粤/日/韩，中文准确、速度快，下载约 160MB",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09", Kind.SENSE_VOICE),
        Model("whisper-base", "Whisper base", "OpenAI 多语言模型，体积较小，中文效果一般",
            "sherpa-onnx-whisper-base", Kind.WHISPER),
        Model("whisper-small", "Whisper small", "OpenAI 多语言模型，比 base 准，但更慢、更大",
            "sherpa-onnx-whisper-small", Kind.WHISPER),
    )

    fun byId(id: String) = MODELS.firstOrNull { it.id == id }

    fun dir(ctx: Context, m: Model) = File(ctx.filesDir, "models/${m.id}")

    fun isInstalled(ctx: Context, m: Model) = File(dir(ctx, m), ".done").exists()

    fun delete(ctx: Context, m: Model) { dir(ctx, m).deleteRecursively() }

    /** Downloads and unpacks a model. [progress] receives (downloadedBytes, totalBytes or -1). */
    fun download(ctx: Context, m: Model, mirror: String, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val url = mirror.trim() + "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${m.archive}.tar.bz2"
        val target = dir(ctx, m)
        target.deleteRecursively()
        target.mkdirs()
        val tmp = File(ctx.cacheDir, "${m.id}.tar.bz2")
        val req = Request.Builder().url(url).build()
        CloudApi.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("下载失败：空响应")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw IOException("已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) { progress(done, total); lastReport = done }
                    }
                    progress(done, total)
                }
            }
        }
        extract(tmp, target, m)
        tmp.delete()
        File(target, ".done").writeText("ok")
    }

    private fun extract(archive: File, target: File, m: Model) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (!keep(name, m)) continue
                File(target, name).outputStream().use { tar.copyTo(it) }
            }
        }
    }

    private fun keep(name: String, m: Model): Boolean = when (m.kind) {
        Kind.SENSE_VOICE -> name == "tokens.txt" || name.endsWith(".int8.onnx") || name == "model.onnx"
        Kind.WHISPER -> name.endsWith("tokens.txt") || name.endsWith(".int8.onnx")
    }

    /** Files needed by the recognizer: model/encoder/decoder/tokens. */
    fun files(ctx: Context, m: Model): Map<String, String> {
        val d = dir(ctx, m)
        val all = d.listFiles()?.map { it.name } ?: emptyList()
        fun find(pred: (String) -> Boolean) = all.firstOrNull(pred)?.let { File(d, it).absolutePath } ?: ""
        return when (m.kind) {
            Kind.SENSE_VOICE -> mapOf(
                "model" to find { it.endsWith(".int8.onnx") }.ifEmpty { find { it == "model.onnx" } },
                "tokens" to find { it == "tokens.txt" })
            Kind.WHISPER -> mapOf(
                "encoder" to find { it.contains("encoder") && it.endsWith(".int8.onnx") },
                "decoder" to find { it.contains("decoder") && it.endsWith(".int8.onnx") },
                "tokens" to find { it.endsWith("tokens.txt") })
        }
    }
}
