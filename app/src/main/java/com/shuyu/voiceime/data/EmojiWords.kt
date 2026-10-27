package com.shuyu.voiceime.data

import android.content.Context

/** Words → emoji (敬礼 → 🫡, salute → 🫡), from assets/emoji_words.txt (tools/gen_emoji_words.py). */
object EmojiWords {
    @Volatile private var map: Map<String, List<String>> = emptyMap()

    fun load(ctx: Context) {
        if (map.isNotEmpty()) return
        val m = HashMap<String, List<String>>(12_000)
        try {
            ctx.assets.open("emoji_words.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.indexOf('\t')
                    if (t > 0) m[line.substring(0, t)] = split(line.substring(t + 1))
                }
            }
        } catch (_: java.io.IOException) { return }
        map = m
    }

    /** Emoji for a word, best first (empty when there are none). */
    fun forWord(word: String): List<String> = map[word] ?: map[word.lowercase()] ?: emptyList()

    /** Splits a run of emoji into single ones (code points joined by ZWJ / variation selectors stay together). */
    private fun split(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            var j = s.offsetByCodePoints(i, 1)
            while (j < s.length) {
                val cp = s.codePointAt(j)
                when {
                    cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 -> j = s.offsetByCodePoints(j, 1)
                    cp == 0x200D && j + 1 < s.length -> j = s.offsetByCodePoints(j, 2)
                    else -> break
                }
            }
            out.add(s.substring(i, j)); i = j
        }
        return out
    }
}
