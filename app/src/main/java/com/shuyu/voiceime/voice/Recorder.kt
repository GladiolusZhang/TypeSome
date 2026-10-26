package com.shuyu.voiceime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Records 16 kHz mono PCM16 into memory and reports a 0..1 loudness level. */
class Recorder(private val onLevel: (Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val buffer = ByteArrayOutputStream()

    val sampleRate = 16000
    private val maxBytes = sampleRate * 2 * 300 // 5 minutes

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        buffer.reset()
        record = r
        running = true
        r.startRecording()
        thread = Thread {
            val chunk = ByteArray(3200)
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                synchronized(buffer) { if (buffer.size() < maxBytes) buffer.write(chunk, 0, n) }
                onLevel(level(chunk, n))
            }
        }.apply { name = "recorder"; start() }
        return true
    }

    /** Stops and returns the PCM16 little-endian data. */
    fun stop(): ByteArray {
        running = false
        thread?.join(500)
        thread = null
        record?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        record = null
        return synchronized(buffer) { buffer.toByteArray() }
    }

    private fun level(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
        return (rms / 6000.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        fun toWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + pcm.size); header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(1)
            header.putInt(sampleRate); header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(pcm.size)
            return header.array() + pcm
        }

        fun toFloat(pcm: ByteArray): FloatArray {
            val out = FloatArray(pcm.size / 2)
            for (i in out.indices) {
                val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
                out[i] = s.toShort() / 32768f
            }
            return out
        }
    }
}
