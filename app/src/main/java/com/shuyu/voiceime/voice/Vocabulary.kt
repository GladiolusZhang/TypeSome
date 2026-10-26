package com.shuyu.voiceime.voice

/**
 * The user's own words for voice input, one per line:
 *   DeepSeek              a term the recognizer tends to mishear (given to the model as a hint)
 *   D四 => DeepSeek       a fixed replacement, applied to every result
 */
class Vocabulary(text: String) {
    val replacements: List<Pair<String, String>>
    val terms: List<String>

    init {
        val reps = ArrayList<Pair<String, String>>()
        val ts = LinkedHashSet<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = ARROW.find(line)
            if (sep != null) {
                val from = line.substring(0, sep.range.first).trim()
                val to = line.substring(sep.range.last + 1).trim()
                if (from.isNotEmpty() && to.isNotEmpty()) { reps.add(from to to); ts.add(to) }
            } else ts.add(line)
        }
        replacements = reps.sortedByDescending { it.first.length }
        terms = ts.toList()
    }

    fun apply(text: String): String = replacements.fold(text) { t, (from, to) -> t.replace(from, to) }

    /** Comma-separated terms for Whisper-style `prompt` biasing. */
    fun asHint(): String = terms.joinToString(", ")

    private companion object { val ARROW = Regex("\\s*(=>|->|→)\\s*") }
}
