package com.shuyu.voiceime.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream

/**
 * 16 kHz mono PCM → AAC-LC in ADTS frames (a plain .aac file), about a tenth of the WAV size,
 * so a recording uploads in a fraction of the time. Returns null if the device has no encoder.
 */
object Aac {
    private const val RATE = 16000
    private const val BITRATE = 32000

    fun encode(pcm: ByteArray): ByteArray? = try {
        // at 32 kbit/s the result is about pcm/8; far less means the encoder dropped the audio
        run(pcm).takeIf { it.size > pcm.size / 40 }
    } catch (_: Exception) { null }

    private fun run(pcm: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ByteArrayOutputStream(pcm.size / 8)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var inputDone = false
            var presentationUs = 0L
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = minOf(buf.remaining(), pcm.size - pos) and 1.inv()
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, pos, n)
                            codec.queueInputBuffer(i, 0, n, presentationUs, 0)
                            presentationUs += n / 2 * 1_000_000L / RATE
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        out.write(adts(info.size))
                        val frame = ByteArray(info.size); buf.get(frame); out.write(frame)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        return out.toByteArray()
    }

    /** 7-byte ADTS header: AAC-LC, 16 kHz (index 8), mono. */
    private fun adts(payload: Int): ByteArray {
        val len = payload + 7
        val profile = 2 - 1 // LC
        val freqIdx = 8
        val chan = 1
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((profile shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
