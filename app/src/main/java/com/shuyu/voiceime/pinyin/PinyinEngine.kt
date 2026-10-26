package com.shuyu.voiceime.pinyin

/**
 * Composing state for full-key pinyin.
 * raw     = letters typed so far that are not yet converted (may contain apostrophes)
 * cursor  = insertion point inside raw (lets the user fix a typo in the middle)
 * picked  = words already chosen for the leading syllables (partial selection)
 */
class PinyinEngine(private val dict: Dictionary) {

    /** [key] is set when the candidate reads the letters differently from [tokens] (fan'gan vs fang'an). */
    data class Candidate(val text: String, val tokenCount: Int, val label: String? = null, val key: String? = null)

    private data class Pick(val word: String, val raw: String, val key: String)

    var raw = StringBuilder(); private set
    var cursor = 0; private set
    private val picked = ArrayList<Pick>()
    /** Text before the cursor when composing started: its last word steers the ranking. */
    var context: String = ""
    var fuzzy = false
    var autoCorrect = true
    var learning = true
    /** 9-key mode: raw holds digits 2-9 (and 1 as separator). */
    var t9 = false
        set(v) { field = v; reset() }
    /** 9-key: spelling the user picked for the first syllable (left column), or null. */
    var t9First: String? = null
        private set

    fun pickT9First(spelling: String?) { t9First = spelling; refresh() }

    private fun lookup(sub: List<Syllables.Token>, start: Int): List<Dictionary.Hit> =
        if (t9) dict.lookupT9(sub, if (start == 0) t9First else null) else dict.lookup(sub, fuzzy)

    /** 9-key: possible spellings of the first syllable, for the left column. */
    fun t9FirstOptions(): List<String> = tokens.firstOrNull()?.let { Syllables.t9Spellings(it.text) } ?: emptyList()

    var tokens: List<Syllables.Token> = emptyList(); private set
    /** The letters [tokens] were cut from, after auto-correction (apostrophes kept). */
    private var letters = ""
    var candidates: List<Candidate> = emptyList(); private set

    val isComposing get() = raw.isNotEmpty() || picked.isNotEmpty()

    fun reset() {
        raw.clear(); cursor = 0; picked.clear(); tokens = emptyList(); candidates = emptyList(); t9First = null
    }

    /** The word right before what is being typed: the last picked word, else the end of [context]. */
    private fun prevWord(): String? =
        if (picked.isNotEmpty()) dict.lastWord(picked.joinToString("") { it.word }) else dict.lastWord(context)

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
        if (t9) { val t = t9Display(); return t to t.length }
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

    /** 9-key: spell the digits using the best candidate's pinyin where possible. */
    private fun t9Display(): String {
        val sb = StringBuilder()
        picked.forEach { sb.append(it.word) }
        val top = candidates.firstOrNull()
        val spelled = top?.let { if (it.tokenCount > 0) dict.pinyinOf(it.text)?.split('\'') else null }
            ?.takeIf { it.size == top.tokenCount } ?: emptyList()
        for ((i, t) in tokens.withIndex()) {
            if (i > 0) sb.append('\'')
            val full = spelled.getOrNull(i)
            val sp = when {
                i == 0 && t9First != null -> t9First!!
                full != null -> if (t.complete) full else full.take(t.text.length)
                else -> Syllables.t9Spellings(t.text).firstOrNull() ?: t.text
            }
            sb.append(sp)
        }
        return sb.toString()
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
            val key = if (t9) dict.pinyinOf(full) ?: ""
                else if (c.key != null) (picked.map { it.key } + c.key).filter { it.isNotEmpty() }.joinToString("'")
                else (picked.map { it.key } + listOf(keyOf(tokens.take(maxOf(c.tokenCount, tokens.size))))).filter { it.isNotEmpty() }.joinToString("'")
            learnSelection(full, key, c)
            reset()
            return full
        }
        val used = tokens.take(c.tokenCount)
        val usedRaw = used.joinToString("") { it.raw }
        picked.add(Pick(c.text, usedRaw, keyOf(used)))
        raw.delete(0, usedRaw.length)
        cursor = (cursor - usedRaw.length).coerceAtLeast(0)
        while (raw.startsWith("'") || (t9 && raw.startsWith("1"))) { raw.deleteCharAt(0); cursor = (cursor - 1).coerceAtLeast(0) }
        t9First = null
        refresh()
        return null
    }

    /** Commits the raw letters as-is (Enter key). */
    fun commitRaw(): String {
        val s = if (t9) t9Display().filter { it != '\'' }
            else picked.joinToString("") { it.word } + raw.toString().replace("'", "")
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
        if (t9) {
            tokens = Syllables.segmentT9(raw.toString())
            candidates = if (tokens.isEmpty()) listOf(Candidate(raw.toString(), 0)) else buildCandidates()
            return
        }
        var text = raw.toString().lowercase()
        if (autoCorrect) text = Syllables.autoCorrect(text)
        letters = text
        tokens = Syllables.segment(text)
        if (tokens.isEmpty()) { candidates = listOf(Candidate(raw.toString(), 0)); return }
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
        val prev = prevWord()
        fun rank(score: Long, w: String) = dict.logUni(score) + dict.contextBonus(prev, w, score)

        val lattice = Lattice()
        val levels = ArrayList<List<Edge>>()
        for (k in n downTo 1) {
            val hits = lattice.tokenEdges(0, k).toMutableList()
            // words that read the whole input with other syllable boundaries (xian → 西安, fangan → 反感)
            if (k == n) lattice.edgesFrom(0).filterTo(hits) { it.to == lattice.end && it.key != null }
            levels.add(hits.distinctBy { it.word }.sortedByDescending { rank(it.score, it.word) })
        }
        val fullHits = levels[0]
        if (n >= 2) {
            // a guessed sentence goes first when no single word covers the input, or when it is
            // clearly more likely than the best such word (wo'xiang'qu'shang'hai → 我想去上海)
            val sentence = lattice.bestPath(prev)
            if (sentence != null) {
                val best = fullHits.firstOrNull()
                if (best == null || sentence.text != best.word && n >= 3 && sentence.score > rank(best.score, best.word) + SENTENCE_MARGIN) {
                    out.add(Candidate(sentence.text, n, key = sentence.key)); seen.add(sentence.text)
                }
            }
        }
        for ((li, hits) in levels.withIndex()) {
            val k = n - li
            val limit = if (k == 1) 200 else 60
            var added = 0
            for (h in hits) {
                if (added >= limit) break
                if (seen.add(h.word)) { out.add(Candidate(h.word, k, key = h.key)); added++ }
            }
        }
        if (out.isEmpty()) out.add(Candidate(raw.toString().replace("'", ""), n))
        return out
    }

    /** A word over letters [from, to); [key] is set only for readings that differ from [tokens]. */
    private class Edge(val from: Int, val to: Int, val word: String, val score: Long, val key: String?)

    private class Path(val text: String, val score: Double, val key: String?)

    /**
     * All words that can be read from the input: those over the default syllable split ([tokens],
     * which also covers abbreviations like zg), plus, in full-key mode, those over any other split
     * into complete syllables (heng'an'dong vs hen'gan'dong).
     */
    private inner class Lattice {
        private val n = tokens.size
        val end: Int
        private val bounds = IntArray(n + 1)
        private val byStart = HashMap<Int, MutableList<Edge>>()
        private val tokenCache = HashMap<Long, List<Edge>>()

        init {
            var pos = 0
            for ((i, t) in tokens.withIndex()) {
                while (pos < letters.length && letters[pos] == '\'') pos++
                bounds[i] = pos
                pos += t.text.length
            }
            end = if (t9) n else letters.length
            if (t9) for (i in 0..n) bounds[i] = i else bounds[n] = end
            for (a in 0 until n) for (b in a + 1..minOf(n, a + 6)) add(tokenEdges(a, b))
            if (!t9) alternatives()
        }

        /** Words over tokens [a, b), best first. */
        fun tokenEdges(a: Int, b: Int): List<Edge> = tokenCache.getOrPut(a * 1000L + b) {
            lookup(tokens.subList(a, b), a).sortedByDescending { it.score }.distinctBy { it.word }
                .map { Edge(bounds[a], bounds[b], it.word, it.score, null) }
        }

        fun edgesFrom(pos: Int): List<Edge> = byStart[pos] ?: emptyList()

        private fun add(edges: List<Edge>) {
            for (e in edges.take(SPAN_WORDS)) byStart.getOrPut(e.from) { ArrayList() }.add(e)
        }

        private fun alternatives() {
            val tokenSplit = HashSet<String>()
            for (a in 0 until n) for (b in a + 1..minOf(n, a + 6)) tokenSplit.add("${bounds[a]}:" + tokens.subList(a, b).joinToString("'") { it.text })
            val reachable = java.util.TreeSet<Int>()
            reachable.add(skipSep(0))
            for (i in 0 until n) reachable.add(bounds[i])
            val done = HashSet<Int>()
            while (true) {
                val start = reachable.firstOrNull { it !in done && it < end } ?: break
                done.add(start)
                walk(start, start, ArrayList(), tokenSplit, reachable)
            }
        }

        private fun skipSep(p: Int): Int { var q = p; while (q < letters.length && letters[q] == '\'') q++; return q }

        /** Extends a run of syllables from [pos], adding the words each run spells. */
        private fun walk(start: Int, pos: Int, syls: ArrayList<String>, tokenSplit: Set<String>, reachable: MutableSet<Int>) {
            if (syls.size >= 6) return
            for (len in 1..minOf(6, end - pos)) {
                val piece = letters.substring(pos, pos + len)
                if ('\'' in piece) break
                val next = pos + len
                val full = piece in Syllables.FULL
                val last = next == end && !full && piece in Syllables.PREFIX && syls.isNotEmpty()
                if (!full && !last) continue
                syls.add(piece)
                val key = syls.joinToString("'")
                if ("$start:$key" !in tokenSplit) {
                    val toks = syls.mapIndexed { i, s -> Syllables.Token(s, full || i < syls.size - 1, s) }
                    val hits = dict.lookup(toks, fuzzy).sortedByDescending { it.score }.distinctBy { it.word }
                    // only multi-syllable words: single characters are already offered by the default split
                    if (syls.size > 1) add(hits.map { Edge(start, next, it.word, it.score, if (full) key else "") })
                }
                if (full && next < end) {
                    val q = skipSep(next)
                    reachable.add(q)
                    walk(start, q, syls, tokenSplit, reachable)
                }
                syls.removeAt(syls.size - 1)
            }
        }

        /** Most likely sentence over the whole input, with its log-probability. */
        fun bestPath(prev: String?): Path? {
            val beams = HashMap<Int, List<State>>()
            beams[bounds[0]] = listOf(State(0.0, prev, null, null))
            val starts = java.util.TreeSet<Int>(byStart.keys)
            starts.add(bounds[0])
            val pending = java.util.TreeMap<Int, ArrayList<State>>()
            for (pos in (starts + end).toSortedSet()) {
                val here = pending.remove(pos)?.let { prune(it) } ?: beams[pos] ?: continue
                beams[pos] = here
                if (pos == end) break
                var any = false
                for (e in edgesFrom(pos)) {
                    any = true
                    val list = pending.getOrPut(e.to) { ArrayList() }
                    for (st in here) list.add(State(st.score + dict.logP(st.word, e.word, e.score), e.word, st, e))
                }
                if (!any) {
                    // no word starts here: pass the letters through so the rest can still be read
                    val tok = (0 until n).firstOrNull { bounds[it] == pos }
                    if (tok != null) {
                        val e = Edge(pos, bounds[tok + 1], tokens[tok].text, 0, "")
                        val list = pending.getOrPut(e.to) { ArrayList() }
                        for (st in here) list.add(State(st.score - 40.0, e.word, st, e))
                    }
                }
            }
            val best = beams[end]?.firstOrNull() ?: return null
            val parts = ArrayList<Edge>()
            var st: State? = best
            while (st?.edge != null) { parts.add(st.edge!!); st = st.from }
            parts.reverse()
            val keys = parts.map { it.key ?: keyOfSpan(it) }
            val key = if (keys.any { it.isNullOrEmpty() }) "" else keys.joinToString("'")
            return Path(parts.joinToString("") { it.word }, best.score, key)
        }

        private fun keyOfSpan(e: Edge): String? {
            val a = bounds.indexOf(e.from); val b = bounds.indexOf(e.to)
            if (a < 0 || b < 0 || t9) return null
            return keyOf(tokens.subList(a, b)).ifEmpty { null }
        }

        private fun prune(c: List<State>): List<State> {
            val kept = ArrayList<State>(BEAM)
            val words = HashSet<String?>()
            for (x in c.sortedByDescending { it.score }) { if (words.add(x.word)) kept.add(x); if (kept.size >= BEAM) break }
            return kept
        }
    }

    private class State(val score: Double, val word: String?, val from: State?, val edge: Edge?)

    private companion object {
        const val BEAM = 8
        const val SPAN_WORDS = 6
        const val SENTENCE_MARGIN = 3.0
    }
}
