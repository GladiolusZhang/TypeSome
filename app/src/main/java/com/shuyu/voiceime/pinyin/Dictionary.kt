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
class Dictionary(private val openAsset: () -> java.io.InputStream, private val dataDir: File) {
    constructor(context: Context) : this({ context.assets.open("pinyin_dict.txt") }, context.filesDir)

    class Entry(val key: String, val syllables: Array<String>, val words: Array<String>, val freqs: IntArray)

    data class UserWord(val key: String, val word: String, var count: Int, val source: String = SRC_USER)

    private val entries = ArrayList<Entry>(130_000)
    /** Signature = first letter of each syllable, with fuzzy-merged letters (l->n). */
    private val bySig = HashMap<String, MutableList<Entry>>()
    /** Word -> frequency, used for association (联想) and char -> reading lookup. */
    private val sortedWords = ArrayList<String>()
    private val wordFreq = HashMap<String, Int>()
    private val charReading = HashMap<Char, String>()
    private val charReadingFreq = HashMap<Char, Int>()

    private val userWords = LinkedHashMap<String, UserWord>() // "key\tword" -> entry
    private val userBySig = HashMap<String, MutableList<UserWord>>()

    @Volatile var loaded = false
        private set

    private val userFile get() = File(dataDir, "user_dict.tsv")

    fun load() {
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
                }
                val e = Entry(key, key.split('\'').toTypedArray(), words, freqs)
                entries.add(e)
                bySig.getOrPut(sig(e.syllables)) { ArrayList(4) }.add(e)
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
        loadUser()
        loaded = true
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

    /** Words that start with [prefix] (for 联想), best first. */
    fun associations(prefix: String, limit: Int = 30): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val tail = prefix.takeLast(4)
        val res = ArrayList<Pair<String, Int>>()
        for (start in tail.indices) {
            val p = tail.substring(start)
            var idx = sortedWords.binarySearch(p).let { if (it < 0) -it - 1 else it + 1 }
            while (idx < sortedWords.size && sortedWords[idx].startsWith(p)) {
                val w = sortedWords[idx]
                res.add(w.substring(p.length) to ((wordFreq[w] ?: 0) * (p.length * p.length)))
                idx++
            }
            if (res.size > 400) break
        }
        for (u in userWords.values) if (u.word.length > prefix.length && u.word.startsWith(prefix))
            res.add(u.word.substring(prefix.length) to 10_000_000)
        return res.sortedByDescending { it.second }.map { it.first }.distinct().take(limit)
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
        userWords.clear(); userBySig.clear()
        keep.forEach { addUser(it) }
        saveUser()
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

    private fun loadUser() {
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

    fun flush() { if (dirty) saveUser() }

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

        private fun firstLetter(s: String): Char {
            val c = s[0]
            return if (c == 'l') 'n' else c
        }
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
