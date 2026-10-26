package com.shuyu.voiceime.pinyin

/**
 * Composing state for full-key pinyin.
 * raw     = letters typed so far that are not yet converted (may contain apostrophes)
 * cursor  = insertion point inside raw (lets the user fix a typo in the middle)
 * picked  = words already chosen for the leading syllables (partial selection)
 */
class PinyinEngine(private val dict: Dictionary) {

    data class Candidate(val text: String, val tokenCount: Int, val label: String? = null)

    private data class Pick(val word: String, val raw: String, val key: String)

    var raw = StringBuilder(); private set
    var cursor = 0; private set
    private val picked = ArrayList<Pick>()
    var fuzzy = false
    var autoCorrect = true
    var learning = true

    var tokens: List<Syllables.Token> = emptyList(); private set
    var candidates: List<Candidate> = emptyList(); private set

    val isComposing get() = raw.isNotEmpty() || picked.isNotEmpty()

    fun reset() {
        raw.clear(); cursor = 0; picked.clear(); tokens = emptyList(); candidates = emptyList()
    }

    fun input(c: Char) {
        raw.insert(cursor, c); cursor++
        refresh()
    }

    /** @return false when nothing was composing (the caller should delete in the editor). */
    fun backspace(): Boolean {
        if (cursor > 0) {
            raw.deleteCharAt(cursor - 1); cursor--
        } else if (picked.isNotEmpty()) {
            val p = picked.removeAt(picked.size - 1)
            raw.insert(0, p.raw); cursor = p.raw.length
        } else return false
        refresh()
        return true
    }

    fun moveCursor(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, raw.length)
    }

    fun setCursor(pos: Int) {
        cursor = pos.coerceIn(0, raw.length)
    }

    /** Text shown in the composing strip: picked words + segmented pinyin, with a caret marker. */
    fun displayText(): Pair<String, Int> {
        val sb = StringBuilder()
        picked.forEach { sb.append(it.word) }
        var caret = -1
        var rawPos = 0
        for ((i, t) in tokens.withIndex()) {
            val r = t.raw
            val explicitSep = r.startsWith("'")
            if (i > 0 && !explicitSep) sb.append('\'')
            for (ch in r) {
                if (rawPos == cursor && caret < 0) caret = sb.length
                sb.append(ch); rawPos++
            }
        }
        if (caret < 0) caret = sb.length
        return sb.toString() to caret
    }

    /** Maps a position in [displayText] back to a raw cursor index. */
    fun displayToRaw(displayPos: Int): Int {
        var pos = picked.sumOf { it.word.length }
        if (displayPos <= pos) return 0
        var rawPos = 0
        for ((i, t) in tokens.withIndex()) {
            if (i > 0 && !t.raw.startsWith("'")) pos++
            for (ch in t.raw) {
                if (pos >= displayPos) return rawPos
                pos++; rawPos++
            }
        }
        return raw.length
    }

    /**
     * Selects a candidate. Returns the text to commit when the whole input is consumed,
     * or null when composing continues with the remaining syllables.
     */
    fun select(index: Int): String? {
        val c = candidates.getOrNull(index) ?: return null
        if (c.tokenCount <= 0 || c.tokenCount >= tokens.size) {
            val full = picked.joinToString("") { it.word } + c.text
            val key = (picked.map { it.key } + listOf(keyOf(tokens.take(maxOf(c.tokenCount, tokens.size))))).filter { it.isNotEmpty() }.joinToString("'")
            learnSelection(full, key, c)
            reset()
            return full
        }
        val used = tokens.take(c.tokenCount)
        val usedRaw = used.joinToString("") { it.raw }
        picked.add(Pick(c.text, usedRaw, keyOf(used)))
        raw.delete(0, usedRaw.length)
        cursor = (cursor - usedRaw.length).coerceAtLeast(0)
        if (raw.startsWith("'")) { raw.deleteCharAt(0); cursor = (cursor - 1).coerceAtLeast(0) }
        refresh()
        return null
    }

    /** Commits the raw letters as-is (Enter key). */
    fun commitRaw(): String {
        val s = picked.joinToString("") { it.word } + raw.toString().replace("'", "")
        reset()
        return s
    }

    private fun learnSelection(full: String, key: String, c: Candidate) {
        if (c.label != null || !learning) return
        if (full.any { it.code < 0x3400 }) return
        if (key.isNotEmpty() && key.count { it == '\'' } + 1 == full.length) dict.learn(full, key)
    }

    private fun keyOf(tokens: List<Syllables.Token>): String {
        // Only fully typed syllables are learned; abbreviations would not be findable later.
        if (tokens.any { !it.complete }) return ""
        return tokens.joinToString("'") { t -> t.text }
    }

    fun refresh() {
        if (raw.isEmpty()) {
            tokens = emptyList()
            candidates = if (picked.isNotEmpty()) listOf(Candidate(picked.joinToString("") { it.word }, 0)) else emptyList()
            return
        }
        var text = raw.toString().lowercase()
        if (autoCorrect) text = Syllables.autoCorrect(text)
        tokens = Syllables.segment(text)
        if (autoCorrect && text != raw.toString().lowercase()) {
            // keep raw spans aligned with what the user actually typed
            tokens = realign(tokens, raw.toString())
        }
        candidates = buildCandidates()
    }

    private fun realign(toks: List<Syllables.Token>, original: String): List<Syllables.Token> {
        var pos = 0
        val out = ArrayList<Syllables.Token>()
        for ((i, t) in toks.withIndex()) {
            val len = if (i == toks.size - 1) original.length - pos else minOf(t.raw.length, original.length - pos)
            out.add(t.copy(raw = original.substring(pos, pos + len)))
            pos += len
        }
        return out
    }

    private fun buildCandidates(): List<Candidate> {
        if (!dict.loaded) return listOf(Candidate(raw.toString(), tokens.size))
        val n = tokens.size
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()

        val levels = ArrayList<List<Dictionary.Hit>>()
        for (k in n downTo 1) {
            val hits = dict.lookup(tokens.subList(0, k), fuzzy).sortedByDescending { it.score }
            levels.add(hits)
        }
        val fullHits = levels[0]
        if (n >= 2 && fullHits.isEmpty()) {
            composeSentence()?.let { out.add(Candidate(it, n)); seen.add(it) }
        }
        for ((li, hits) in levels.withIndex()) {
            val k = n - li
            val limit = if (k == 1) 200 else 60
            var added = 0
            for (h in hits) {
                if (added >= limit) break
                if (seen.add(h.word)) { out.add(Candidate(h.word, k)); added++ }
            }
        }
        if (out.isEmpty()) out.add(Candidate(raw.toString().replace("'", ""), n))
        return out
    }

    /** Best whole-input conversion: Viterbi over word spans, scoring each word by log frequency. */
    private fun composeSentence(): String? {
        val n = tokens.size
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1)
        val word = arrayOfNulls<String>(n + 1)
        best[0] = 0.0
        val logTotal = Math.log(5e7)
        for (i in 1..n) {
            for (k in 1..minOf(i, 6)) {
                if (best[i - k] == Double.NEGATIVE_INFINITY) continue
                val hit = dict.lookup(tokens.subList(i - k, i), fuzzy).maxByOrNull { it.score }
                val (w, score) = if (hit != null) hit.word to (Math.log(hit.score + 1.0) - logTotal)
                    else if (k == 1) tokens[i - 1].text to -40.0 else continue
                val v = best[i - k] + score
                if (v > best[i]) { best[i] = v; back[i] = k; word[i] = w }
            }
        }
        if (best[n] == Double.NEGATIVE_INFINITY) return null
        val parts = ArrayList<String>()
        var i = n
        while (i > 0) { parts.add(word[i]!!); i -= back[i] }
        return parts.asReversed().joinToString("")
    }
}
