package com.shuyu.voiceime.pinyin

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Word store keyed by toneless pinyin ("zhong'guo").
 * System words come from assets/pinyin_dict.txt (generated at build time by tools/gen_dict.py);
 * user words (learned, imported, contacts) are kept in files/user_dict.tsv.
 */
class Dictionary(
    private val openAsset: () -> java.io.InputStream,
    private val dataDir: File,
    /** Optional generated data by file name: zh_bigram.txt, zh_spoken.txt (see tools/gen_ngram.py). */
    private val openData: (String) -> java.io.InputStream? = { null },
) {
    constructor(context: Context) : this({ context.assets.open("pinyin_dict.txt") }, context.filesDir,
        { name -> try { context.assets.open(name) } catch (_: java.io.IOException) { null } })

    /** Words seen after [prev] in the corpora (assets/zh_bigram.txt, from tools/gen_ngram.py). */
    class Next(val total: Int, val words: Array<String>, val counts: IntArray) {
        fun count(w: String): Int { for (i in words.indices) if (words[i] == w) return counts[i]; return 0 }
    }

    class Entry(val key: String, val syllables: Array<String>, val words: Array<String>, val freqs: IntArray)

    data class UserWord(val key: String, val word: String, var count: Int, val source: String = SRC_USER)

    private val entries = ArrayList<Entry>(130_000)
    /** Signature = first letter of each syllable, with fuzzy-merged letters (l->n). */
    private val bySig = HashMap<String, MutableList<Entry>>()
    /** Same entries keyed by the 9-key digit of each syllable's first letter. */
    private val byDigitSig = HashMap<String, MutableList<Entry>>()
    /** Word -> frequency, used for association (联想) and char -> reading lookup. */
    private val sortedWords = ArrayList<String>()
    private val wordFreq = HashMap<String, Int>()
    private val charReading = HashMap<Char, String>()
    private val charReadingFreq = HashMap<Char, Int>()

    private val next = HashMap<String, Next>(60_000)
    /** Sum of all word frequencies: turns a frequency into a probability. */
    private var totalFreq = 1e9

    private val userWords = LinkedHashMap<String, UserWord>() // "key\tword" -> entry
    /** Learned word pairs: prev -> (next -> times chosen). */
    private val userNext = HashMap<String, HashMap<String, Int>>()
    private val userBySig = HashMap<String, MutableList<UserWord>>()

    @Volatile var loaded = false
        private set

    private val userFile get() = File(dataDir, "user_dict.tsv")
    private val userNextFile get() = File(dataDir, "user_next.tsv")

    fun load() {
        // dictionary weights come mostly from written text; these factors shift them toward speech
        val spoken = HashMap<String, Float>()
        openData("zh_spoken.txt")?.let { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    val t = line.indexOf('\t')
                    if (t > 0) spoken[line.substring(0, t)] = line.substring(t + 1).toFloatOrNull() ?: continue
                }
            }
        }
        val reader = BufferedReader(InputStreamReader(openAsset(), Charsets.UTF_8), 1 shl 16)
        reader.useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                val parts = line.substring(tab + 1).split(' ')
                val words = Array(parts.size) { "" }
                val freqs = IntArray(parts.size)
                for ((i, p) in parts.withIndex()) {
                    val c = p.lastIndexOf(',')
                    words[i] = p.substring(0, c)
                    freqs[i] = p.substring(c + 1).toIntOrNull() ?: 1
                    spoken[words[i]]?.let { f -> freqs[i] = (freqs[i] * f).toInt() }
                }
                val e = Entry(key, key.split('\'').toTypedArray(), words, freqs)
                entries.add(e)
                bySig.getOrPut(sig(e.syllables)) { ArrayList(4) }.add(e)
                byDigitSig.getOrPut(digitSig(e.syllables)) { ArrayList(4) }.add(e)
                for (i in words.indices) {
                    val w = words[i]
                    val old = wordFreq[w]
                    if (old == null || old < freqs[i]) wordFreq[w] = freqs[i]
                    if (w.length == 1) {
                        val ch = w[0]
                        if ((charReadingFreq[ch] ?: -1) < freqs[i]) {
                            charReading[ch] = key
                            charReadingFreq[ch] = freqs[i]
                        }
                    }
                }
            }
        }
        sortedWords.addAll(wordFreq.keys.filter { it.length > 1 })
        sortedWords.sort()
        totalFreq = wordFreq.values.sumOf { it.toDouble() } + wordFreq.size
        loadBigrams()
        loadUser()
        loaded = true
    }

    private fun loadBigrams() {
        val input = openData("zh_bigram.txt") ?: return
        // share the word strings with the dictionary instead of keeping half a million copies
        val canon = HashMap<String, String>(wordFreq.size * 2)
        for (w in wordFreq.keys) canon[w] = w
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).useLines { lines ->
            for (line in lines) {
                val t1 = line.indexOf('\t')
                val t2 = line.indexOf('\t', t1 + 1)
                if (t1 <= 0 || t2 < 0) continue
                val prev = line.substring(0, t1)
                val parts = line.substring(t2 + 1).split(' ')
                val words = Array(parts.size) { "" }
                val counts = IntArray(parts.size)
                for ((i, p) in parts.withIndex()) {
                    val c = p.lastIndexOf(',')
                    val w = p.substring(0, c)
                    words[i] = canon[w] ?: w
                    counts[i] = p.substring(c + 1).toIntOrNull() ?: 1
                }
                next[canon[prev] ?: prev] = Next(line.substring(t1 + 1, t2).toIntOrNull() ?: counts.sum(), words, counts)
            }
        }
    }

    // ---------------------------------------------------------------- context (word pairs)

    /** The word the text ends with, as far as word-pair data knows it; null at a sentence boundary. */
    fun lastWord(text: CharSequence?): String? {
        if (text.isNullOrEmpty()) return null
        for (len in minOf(4, text.length) downTo 1) {
            val s = text.substring(text.length - len)
            if (s[0].code < 0x3400) continue
            if (next.containsKey(s) || userNext.containsKey(s)) return s
        }
        return null
    }

    /** P(w | prev) from the corpora, or from the user's own choices when those say more. */
    fun nextProb(prev: String, w: String): Double {
        var p = 0.0
        next[prev]?.let { n -> val c = n.count(w); if (c > 0) p = c.toDouble() / n.total }
        userNext[prev]?.get(w)?.let { p = maxOf(p, minOf(0.6, 0.15 * it)) }
        return p
    }

    /** Unigram log-probability of a hit (user words carry boosted scores). */
    fun logUni(score: Long): Double = Math.log((score + 1.0) / totalFreq)

    /** Interpolated bigram log-probability, used to chain words when guessing a whole sentence. */
    fun logP(prev: String?, w: String, score: Long): Double {
        val pu = (score + 1.0) / totalFreq
        if (prev == null) return Math.log(pu)
        return Math.log(LAMBDA * nextProb(prev, w) + (1 - LAMBDA) * pu)
    }

    /**
     * How much more likely [w] is right after [prev] than on its own, as a bounded log bonus:
     * enough to lift 是 after 我, not enough for one odd corpus pair to bury a common word.
     */
    fun contextBonus(prev: String?, w: String, score: Long): Double {
        if (prev == null) return 0.0
        val pb = nextProb(prev, w)
        if (pb <= 0.0) return 0.0
        val pu = (score + 1.0) / totalFreq
        return minOf(Math.log(1 + LAMBDA * pb / ((1 - LAMBDA) * pu)), MAX_BONUS)
    }

    /** Remembers that the user put [w] right after the text [before]. */
    fun learnNext(before: CharSequence?, w: String) {
        // whole guessed sentences are not "next words"
        if (w.isEmpty() || w[0].code < 0x3400 || (w.length > 4 && !wordFreq.containsKey(w))) return
        val prev = lastKnownWord(before) ?: return
        val m = userNext.getOrPut(prev) { HashMap() }
        m[w] = (m[w] ?: 0) + 1
        nextDirty = true
        if (++nextChanges % 10 == 0) saveUserNext()
    }

    /** Like [lastWord], but any dictionary word counts (for learning pairs the corpora never saw). */
    private fun lastKnownWord(text: CharSequence?): String? {
        if (text.isNullOrEmpty()) return null
        for (len in minOf(4, text.length) downTo 1) {
            val s = text.substring(text.length - len)
            if (s[0].code < 0x3400) continue
            if (next.containsKey(s) || userNext.containsKey(s) || wordFreq.containsKey(s)) return s
        }
        return null
    }

    // ---------------------------------------------------------------- lookup

    class Hit(val word: String, val score: Long, val tokenCount: Int, val fromUser: Boolean)

    /** Words whose syllables match the given tokens exactly in count. */
    fun lookup(tokens: List<Syllables.Token>, fuzzy: Boolean): List<Hit> {
        if (tokens.isEmpty()) return emptyList()
        val s = sigOf(tokens)
        val out = ArrayList<Hit>()
        bySig[s]?.forEach { e ->
            if (e.syllables.size == tokens.size && matches(e.syllables, tokens, fuzzy)) {
                for (i in e.words.indices) out.add(Hit(e.words[i], e.freqs[i].toLong(), tokens.size, false))
            }
        }
        userBySig[s]?.forEach { u ->
            val syl = u.key.split('\'')
            if (syl.size == tokens.size && matches(syl.toTypedArray(), tokens, fuzzy)) {
                val boost = if (u.source == SRC_CONTACT) 2_000_000L else 300_000L
                out.add(Hit(u.word, boost + u.count * 200_000L, tokens.size, true))
            }
        }
        return out
    }

    /** 9-key lookup: tokens hold digits. [first] optionally pins the first syllable's spelling. */
    fun lookupT9(tokens: List<Syllables.Token>, first: String?): List<Hit> {
        if (tokens.isEmpty()) return emptyList()
        val s = String(CharArray(tokens.size) { tokens[it].text[0] })
        val out = ArrayList<Hit>()
        fun ok(syl: Array<String>): Boolean {
            if (syl.size != tokens.size) return false
            if (first != null && syl[0] != first && !(first !in Syllables.FULL && syl[0].startsWith(first))) return false
            for (i in tokens.indices) {
                val d = Syllables.digits(syl[i])
                if (tokens[i].complete) { if (d != tokens[i].text) return false } else if (!d.startsWith(tokens[i].text)) return false
            }
            return true
        }
        byDigitSig[s]?.forEach { e -> if (ok(e.syllables)) for (i in e.words.indices) out.add(Hit(e.words[i], e.freqs[i].toLong(), tokens.size, false)) }
        for (u in userWords.values) {
            if (u.key.isEmpty()) continue
            val syl = u.key.split('\'').toTypedArray()
            if (ok(syl)) out.add(Hit(u.word, (if (u.source == SRC_CONTACT) 2_000_000L else 300_000L) + u.count * 200_000L, tokens.size, true))
        }
        return out
    }

    private fun matches(syl: Array<String>, tokens: List<Syllables.Token>, fuzzy: Boolean): Boolean {
        for (i in tokens.indices) {
            val t = tokens[i]
            val s = syl[i]
            if (t.complete) {
                if (s != t.text && !(fuzzy && fz(s) == fz(t.text))) return false
            } else {
                if (!s.startsWith(t.text) && !(fuzzy && fz(s).startsWith(fz(t.text)))) return false
            }
        }
        return true
    }

    /**
     * 联想 for the text before the cursor, best first: words that usually follow its last word,
     * mixed with completions of the words it ends with (中 → 国, 人工 → 智能).
     */
    fun associations(context: String, limit: Int = 30): List<String> {
        if (context.isEmpty()) return emptyList()
        val prev = lastWord(context)
        val following = ArrayList<Pair<String, Double>>()
        if (prev != null) {
            next[prev]?.let { n -> for (i in n.words.indices) following.add(n.words[i] to n.counts[i].toDouble() / n.total) }
            userNext[prev]?.forEach { (w, c) -> following.add(w to 0.2 + 0.15 * c) }
        }
        following.sortByDescending { it.second }
        // completions must cover the whole last word: 明天 may become 明天见, never 天+津
        val completions = completions(context, prev?.length ?: 1)
        // after a word that is often followed by others, those come first;
        // a lone, rarely standalone character is more likely the start of a longer word
        val wordFirst = prev != null && (prev.length > 1 || (next[prev]?.total ?: 0) >= 500)
        val (first, second) = if (wordFirst) following.map { it.first } to completions else completions to following.map { it.first }
        val ratio = if (wordFirst) 3 else 2
        val out = LinkedHashSet<String>()
        var i = 0; var j = 0
        while (out.size < limit && (i < first.size || j < second.size)) {
            repeat(ratio) { if (i < first.size) out.add(first[i++]) }
            if (j < second.size) out.add(second[j++])
        }
        return out.take(limit)
    }

    /** Rest of the words that start with the end of [prefix] (longer overlaps rank higher). */
    private fun completions(prefix: String, minLen: Int): List<String> {
        val tail = prefix.takeLast(4)
        val res = ArrayList<Pair<String, Int>>()
        for (start in 0..tail.length - minLen) {
            val p = tail.substring(start)
            if (p[0].code < 0x3400) continue
            var idx = sortedWords.binarySearch(p).let { if (it < 0) -it - 1 else it + 1 }
            while (idx < sortedWords.size && sortedWords[idx].startsWith(p)) {
                val w = sortedWords[idx]
                res.add(w.substring(p.length) to ((wordFreq[w] ?: 0) * (p.length * p.length)))
                idx++
            }
            if (res.size > 400) break
        }
        for (u in userWords.values) if (u.word.length > tail.length && u.word.startsWith(tail))
            res.add(u.word.substring(tail.length) to 10_000_000)
        return res.sortedByDescending { it.second }.map { it.first }.distinct().take(40)
    }

    /** Best-guess pinyin key for an arbitrary Chinese string, or null if a char is unknown. */
    fun pinyinOf(text: String): String? {
        val parts = ArrayList<String>()
        for (ch in text) {
            parts.add(charReading[ch] ?: return null)
        }
        return if (parts.isEmpty()) null else parts.joinToString("'")
    }

    // ---------------------------------------------------------------- user dictionary

    fun learn(word: String, key: String) {
        if (word.isEmpty()) return
        val k = "$key\t$word"
        val u = userWords[k]
        if (u != null) u.count++ else addUser(UserWord(key, word, 1))
        scheduleSave()
    }

    fun userEntries(): List<UserWord> = userWords.values.toList()

    fun removeUser(u: UserWord) {
        userWords.remove("${u.key}\t${u.word}")
        userBySig[sig(u.key.split('\'').toTypedArray())]?.remove(u)
        saveUser()
    }

    fun clearUser(keepContacts: Boolean = true) {
        val keep = if (keepContacts) userWords.values.filter { it.source == SRC_CONTACT } else emptyList()
        userWords.clear(); userBySig.clear(); userNext.clear()
        keep.forEach { addUser(it) }
        saveUser(); saveUserNext()
    }

    fun addManual(word: String, pinyin: String?): Boolean {
        val key = pinyin?.trim()?.lowercase()?.replace(' ', '\'')?.takeIf { it.isNotEmpty() } ?: pinyinOf(word) ?: return false
        val k = "$key\t$word"
        if (userWords[k] == null) addUser(UserWord(key, word, 3)) else userWords[k]!!.count += 3
        saveUser()
        return true
    }

    fun setContacts(names: Collection<String>) {
        userWords.values.filter { it.source == SRC_CONTACT }.toList().forEach {
            userWords.remove("${it.key}\t${it.word}")
            userBySig[sig(it.key.split('\'').toTypedArray())]?.remove(it)
        }
        for (n in names) {
            val key = pinyinOf(n) ?: continue
            addUser(UserWord(key, n, 0, SRC_CONTACT))
        }
    }

    /** Export format: one "word<TAB>pinyin<TAB>count" per line (contacts are not exported). */
    fun exportText(): String = buildString {
        append("# VoiceIME user dictionary v1\n")
        for (u in userWords.values) if (u.source != SRC_CONTACT) append(u.word).append('\t').append(u.key).append('\t').append(u.count).append('\n')
    }

    fun importText(text: String): Int {
        var n = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('\t')
            val word = p[0].trim()
            if (word.isEmpty()) continue
            val key = p.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: pinyinOf(word) ?: continue
            val count = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
            val k = "$key\t$word"
            val u = userWords[k]
            if (u != null) u.count = maxOf(u.count, count) else addUser(UserWord(key, word, count))
            n++
        }
        saveUser()
        return n
    }

    private fun addUser(u: UserWord) {
        userWords["${u.key}\t${u.word}"] = u
        userBySig.getOrPut(sig(u.key.split('\'').toTypedArray())) { ArrayList() }.add(u)
    }

    private var nextDirty = false
    private var nextChanges = 0

    private fun loadUserNext() {
        val f = userNextFile
        if (!f.exists()) return
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3) userNext.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toIntOrNull() ?: 1
        }
    }

    @Synchronized private fun saveUserNext() {
        nextDirty = false
        // keep the file small: the most used pairs win
        val rows = userNext.flatMap { (a, m) -> m.map { (b, c) -> Triple(a, b, c) } }.sortedByDescending { it.third }.take(MAX_USER_PAIRS)
        val tmp = File(userNextFile.path + ".tmp")
        tmp.bufferedWriter().use { w -> for ((a, b, c) in rows) w.write("$a\t$b\t$c\n") }
        tmp.renameTo(userNextFile)
    }

    private fun loadUser() {
        loadUserNext()
        val f = userFile
        if (!f.exists()) return
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3) addUser(UserWord(p[1], p[0], p[2].toIntOrNull() ?: 1))
        }
    }

    @Volatile private var dirty = false
    private fun scheduleSave() {
        dirty = true
        if (userWords.size % 5 == 0) saveUser()
    }

    fun flush() { if (dirty) saveUser(); if (nextDirty) saveUserNext() }

    @Synchronized private fun saveUser() {
        dirty = false
        val tmp = File(userFile.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (u in userWords.values) if (u.source != SRC_CONTACT) w.write("${u.word}\t${u.key}\t${u.count}\n")
        }
        tmp.renameTo(userFile)
    }

    companion object {
        const val SRC_USER = "user"
        const val SRC_CONTACT = "contact"
        private const val LAMBDA = 0.4
        private const val MAX_BONUS = 2.5
        private const val MAX_USER_PAIRS = 5000

        private fun firstLetter(s: String): Char {
            val c = s[0]
            return if (c == 'l') 'n' else c
        }
        fun digitSig(syl: Array<String>): String = String(CharArray(syl.size) { Syllables.digitOf(syl[it][0]) })
        fun sig(syl: Array<String>): String = String(CharArray(syl.size) { firstLetter(syl[it]) })
        fun sigOf(tokens: List<Syllables.Token>): String = String(CharArray(tokens.size) { firstLetter(tokens[it].text) })

        /** Fuzzy normalization: zh/ch/sh -> z/c/s, l -> n, ang/eng/ing -> an/en/in. */
        fun fz(s: String): String {
            var r = s
            if (r.startsWith("zh") || r.startsWith("ch") || r.startsWith("sh")) r = r[0] + r.substring(2)
            if (r.startsWith("l")) r = "n" + r.substring(1)
            if (r.endsWith("ang") || r.endsWith("eng") || r.endsWith("ing")) r = r.dropLast(1)
            return r
        }
    }
}
