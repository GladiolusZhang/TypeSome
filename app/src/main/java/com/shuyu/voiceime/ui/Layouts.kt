package com.shuyu.voiceime.ui

/** What a key does. Text keys output [Key.text]; the rest are actions handled by the service. */
enum class KeyType { TEXT, SHIFT, BACKSPACE, ENTER, SPACE, NUM_PAD, LANG, SYMBOLS, BACK }

class Key(
    val type: KeyType,
    val label: String = "",
    val text: String = label,
    /** Secondary character shown small above the label; produced by swipe-up or long-press. */
    val hint: String? = null,
    val weight: Float = 1f,
    val icon: Icon? = null,
    /** Smaller, dimmer label (e.g. 符号 / 返回 on the number pad). */
    val small: Boolean = false,
    val hintIcon: Icon? = null,
)

class KbLayout(val name: String, val rows: List<List<Key>>, val leftColumn: List<Key>? = null)

object Layouts {
    private fun t(c: String, hint: String?) = Key(KeyType.TEXT, c, c, hint)

    fun qwerty(chinese: Boolean): KbLayout {
        val r1 = "qwertyuiop".mapIndexed { i, c -> t(c.toString(), ((i + 1) % 10).toString()) }
        val h2 = listOf("~", "@", "#", "$", "%", "&", "*", "(", ")")
        val r2 = "asdfghjkl".mapIndexed { i, c -> t(c.toString(), h2[i]) }
        val h3 = listOf("'", "/", "-", "_", ":", ";", "?")
        val r3 = listOf(Key(KeyType.SHIFT, icon = Icon.SHIFT, weight = 1.45f)) +
            "zxcvbnm".mapIndexed { i, c -> t(c.toString(), h3[i]) } +
            Key(KeyType.BACKSPACE, icon = Icon.BACKSPACE, weight = 1.45f)
        val r4 = listOf(
            Key(KeyType.NUM_PAD, "123", weight = 1.15f),
            Key(KeyType.LANG, if (chinese) "中" else "En", hint = if (chinese) "En" else "中", weight = 1.15f),
            if (chinese) Key(KeyType.TEXT, "，", "，", hint = "！", weight = 1.05f) else Key(KeyType.TEXT, ",", ",", hint = "!", weight = 1.05f),
            Key(KeyType.SPACE, icon = Icon.SPACE_MIC, weight = 3.4f),
            if (chinese) Key(KeyType.TEXT, "。", "。", hint = "？", weight = 1.05f) else Key(KeyType.TEXT, ".", ".", hint = "?", weight = 1.05f),
            Key(KeyType.SYMBOLS, "符", hintIcon = Icon.SMILE, weight = 1.15f),
            Key(KeyType.ENTER, icon = Icon.ENTER, weight = 1.6f),
        )
        return KbLayout(if (chinese) "pinyin" else "english", listOf(r1, r2, r3, r4))
    }

    /** 9-key number pad with a narrow symbol column on the left. */
    fun numbers(): KbLayout {
        fun n(s: String) = Key(KeyType.TEXT, s)
        val rows = listOf(
            listOf(n("1"), n("2"), n("3"), Key(KeyType.BACKSPACE, icon = Icon.BACKSPACE)),
            listOf(n("4"), n("5"), n("6"), Key(KeyType.ENTER, icon = Icon.ENTER)),
            listOf(n("7"), n("8"), n("9"), Key(KeyType.SPACE, icon = Icon.SPACE_MIC)),
            listOf(n("."), n("0"), Key(KeyType.SYMBOLS, "符号", small = true), Key(KeyType.BACK, "返回", small = true)),
        )
        val left = listOf("@", ":", "+", "-", "*", "/", "=", "%", "(", ")", ",").map { Key(KeyType.TEXT, it) }
        return KbLayout("numbers", rows, left)
    }
}
