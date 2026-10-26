package com.shuyu.voiceime.voice

import kotlin.math.sqrt

/**
 * Energy-based speech detection on 16 kHz mono PCM16. Models like SenseVoice tend to "hear" 嗯 in
 * silence, so silence is cut off before recognition and a recording with no speech is not sent at all.
 */
object Vad {
    private const val FRAME = 320 // 20 ms

    /**
     * The recording trimmed to its speech (with some padding), or null if nobody spoke.
     * [level] 0 turns the filter off; 1..10 raise the bar (default 3 still picks up quiet speech).
     */
    fun trim(pcm: ByteArray, level: Int = 3): ByteArray? {
        if (level <= 0) return pcm
        val n = pcm.size / 2 / FRAME
        if (n == 0) return null
        val rms = FloatArray(n)
        for (f in 0 until n) {
            var sum = 0.0
            for (i in 0 until FRAME) {
                val o = (f * FRAME + i) * 2
                val v = ((pcm[o + 1].toInt() shl 8) or (pcm[o].toInt() and 0xff)).toShort().toDouble()
                sum += v * v
            }
            rms[f] = sqrt(sum / FRAME).toFloat()
        }
        val noise = rms.sorted()[n / 10]
        val threshold = maxOf(noise * (1.2f + 0.25f * level), 25f * level)
        val speech = rms.indices.filter { rms[it] > threshold }
        if (speech.size < 3 + level) return null // too little sound above the noise floor
        val pad = 20 // 400 ms, so soft word onsets and endings survive
        val from = (speech.first() - pad).coerceAtLeast(0)
        val to = (speech.last() + pad + 1).coerceAtMost(n)
        return pcm.copyOfRange(from * FRAME * 2, to * FRAME * 2)
    }

    private val FILLER = Regex("^[嗯啊呃额哦噢唔哼恩\\s，。、,.!?！？…~～]*$")

    /** Empty if the recognizer only produced filler sounds like 「嗯。」. */
    fun clean(text: String): String = if (FILLER.matches(text.trim())) "" else text.trim()
}
