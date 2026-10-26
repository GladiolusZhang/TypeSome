package com.shuyu.voiceime.pinyin

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * English word completion and next-word suggestions.
 * assets/en_words.txt ("word count", best first) and en_bigram.txt ("word<TAB>total<TAB>next,count ...")
 * are generated at build time; words the user types are counted in files/user_en.tsv.
 */
class EnglishWords(
    private val openWords: () -> InputStream?,
    private val openPairs: () -> InputStream?,
    private val dataDir: File,
) {
    constructor(context: Context) : this(asset(context, "en_words.txt"), asset(context, "en_bigram.txt"), context.filesDir)

    /** Lowercase words without apostrophes, sorted, so that "dont" finds "don't". */
    private var keys: Array<String> = emptyArray()
    private var words: Array<String> = emptyArray()
    private var freqs: IntArray = IntArray(0)
    private val byKey = HashMap<String, Int>()
    private val next = HashMap<String, Array<String>>()
    private val user = HashMap<String, Int>()
    private val userFile get() = File(dataDir, "user_en.tsv")
    private var dirty = 0

    @Volatile var loaded = false
        private set

    fun load() {
        val list = ArrayList<Pair<String, Int>>(40_000)
        openWords()?.let { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    val sp = line.indexOf(' ')
                    if (sp <= 0) continue
                    list.add(line.substring(0, sp) to (line.substring(sp + 1).toIntOrNull() ?: 1))
                }
            }
        }
        list.sortBy { key(it.first) }
        keys = Array(list.size) { key(list[it].first) }
        words = Array(list.size) { list[it].first }
        freqs = IntArray(list.size) { list[it].second }
        for (i in keys.indices) { val old = byKey[keys[i]]; if (old == null || freqs[old] < freqs[i]) byKey[keys[i]] = i }
        openPairs()?.let { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t'); val t2 = line.indexOf('\t', t1 + 1)
                    if (t1 <= 0 || t2 < 0) continue
                    next[line.substring(0, t1)] = line.substring(t2 + 1).split(' ').map { it.substringBeforeLast(',') }.toTypedArray()
                }
            }
        }
        if (userFile.exists()) userFile.forEachLine { l ->
            val p = l.split('\t')
            if (p.size == 2) user[p[0]] = p[1].toIntOrNull() ?: 1
        }
        loaded = true
    }

    private fun score(i: Int): Double = freqs[i] + (user[words[i]] ?: 0) * USER_WEIGHT

    /** Words that start with [typed], best first, then likely corrections of it (teh → the). */
    fun suggest(typed: String, limit: Int = 12): List<String> {
        if (!loaded || typed.isEmpty()) return emptyList()
        val p = key(typed)
        val found = HashMap<String, Double>()
        var i = keys.binarySearch(p).let { if (it < 0) -it - 1 else it }
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(p) && scanned++ < 20_000) {
            // what was typed exactly is most likely meant: lift it over longer words a little
            found[words[i]] = score(i) * (if (keys[i] == p) 3.0 else 1.0)
            i++
        }
        // the user's own words (names, slang) once they have been typed twice
        for ((w, c) in user) if (c >= 2 && key(w).startsWith(p) && w !in found) found[w] = c * USER_WEIGHT
        val completions = found.entries.sortedByDescending { it.value }.map { it.key }
        if (p.length < 3) return completions.take(limit)
        // a few likely typo fixes; they lead only when far more common than any completion (teh → the)
        val exact = byKey[p]
        val fixes = edits(p).mapNotNull { byKey[it] }.filter { freqs[it] >= 1000 && words[it] !in found }
            .filter { exact == null || freqs[it] > freqs[exact] * 20 }
            .sortedByDescending { score(it) }.take(3)
        val best = found.values.maxOrNull() ?: 0.0
        val (lead, tail) = fixes.partition { score(it) > best * 50 }
        return (lead.map { words[it] } + completions + tail.map { words[it] }).distinct().take(limit)
    }

    /** Words that often follow [word] ("thank" → you). */
    fun nextWords(word: String, limit: Int = 12): List<String> {
        if (!loaded) return emptyList()
        return next[word.lowercase()]?.take(limit) ?: emptyList()
    }

    fun isWord(w: String) = byKey.containsKey(key(w))

    /** Counts a finished word: known words rank higher, new ones become suggestions. */
    fun learn(word: String) {
        if (word.length < 2 || word.length > 30 || !word.all { it.isLetter() || it == '\'' }) return
        val w = byKey[key(word)]?.let { words[it] } ?: word.lowercase().takeIf { word.drop(1).none { it.isUpperCase() } } ?: word
        user[w] = (user[w] ?: 0) + 1
        if (++dirty >= 10) flush()
    }

    @Synchronized fun flush() {
        if (dirty == 0) return
        dirty = 0
        val rows = user.entries.sortedByDescending { it.value }.take(MAX_USER)
        val tmp = File(userFile.path + ".tmp")
        tmp.bufferedWriter().use { o -> for ((w, c) in rows) o.write("$w\t$c\n") }
        tmp.renameTo(userFile)
    }

    fun clearUser() { user.clear(); dirty = 1; flush() }

    private fun edits(w: String): Set<String> {
        val out = HashSet<String>()
        for (i in w.indices) out.add(w.removeRange(i, i + 1))
        for (i in 0 until w.length - 1) out.add(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        for (i in w.indices) for (c in 'a'..'z') if (c != w[i]) out.add(w.substring(0, i) + c + w.substring(i + 1))
        for (i in 0..w.length) for (c in 'a'..'z') out.add(w.substring(0, i) + c + w.substring(i))
        return out
    }

    companion object {
        private const val USER_WEIGHT = 50_000.0
        private const val MAX_USER = 3000
        fun key(w: String) = w.lowercase().replace("'", "")
        private fun asset(ctx: Context, name: String): () -> InputStream? =
            { try { ctx.assets.open(name) } catch (_: java.io.IOException) { null } }

        /** Gives a suggestion the case of what was typed: "Hel" → "Hello", "HEL" → "HELLO", and always "I". */
        fun matchCase(word: String, typed: String): String = when {
            word == "i" || word.startsWith("i'") -> "I" + word.substring(1)
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
