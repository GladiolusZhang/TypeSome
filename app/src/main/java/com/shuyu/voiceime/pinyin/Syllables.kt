package com.shuyu.voiceime.pinyin

/** Valid Mandarin pinyin syllables (toneless, ü written as v) and input segmentation. */
object Syllables {
    private val INITIALS = listOf("b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
        "zh", "ch", "sh", "r", "z", "c", "s", "y", "w")

    private const val RAW = "a ai an ang ao e ei en eng er o ou " +
        "ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
        "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
        "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
        "fa fan fang fei fen feng fo fou fu " +
        "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
        "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
        "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve " +
        "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve " +
        "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
        "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
        "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
        "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
        "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
        "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
        "zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo " +
        "cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo " +
        "sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo " +
        "ra ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo " +
        "za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo " +
        "ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo " +
        "sa sai san sang sao se sen seng si song sou su suan sui sun suo " +
        "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
        "wa wai wan wang wei wen weng wo wu"

    val FULL: Set<String> = RAW.split(' ').filter { it.isNotEmpty() }.toHashSet()
    /** Every proper or improper prefix of a valid syllable. */
    val PREFIX: Set<String> = HashSet<String>().apply {
        for (s in FULL) for (i in 1..s.length) add(s.substring(0, i))
    }

    fun isInitialOnly(s: String) = s in INITIALS

    /** Common typos fixed before segmentation. */
    private val CORRECTIONS = listOf("ign" to "ing", "img" to "ing", "aun" to "uan", "uei" to "ui",
        "iou" to "iu", "uen" to "un", "eng" to "eng", "mg" to "ng", "hc" to "ch", "hs" to "sh", "hz" to "zh")

    fun autoCorrect(input: String): String {
        var s = input
        for ((a, b) in CORRECTIONS) if (a != b) s = s.replace(a, b)
        return s
    }

    data class Token(val text: String, val complete: Boolean, val raw: String)

    /**
     * Splits raw letters (apostrophes allowed as explicit separators) into syllable tokens.
     * Prefers segmentations with fewer partial tokens, then fewer tokens, then longer leading syllables.
     */
    fun segment(input: String): List<Token> {
        val out = ArrayList<Token>()
        val chunks = input.split('\'')
        for ((ci, chunk) in chunks.withIndex()) {
            if (chunk.isEmpty()) continue
            val seg = segmentChunk(chunk)
            for ((i, t) in seg.withIndex()) {
                val raw = if (i == 0 && ci > 0) "'" + t.raw else t.raw
                out.add(t.copy(raw = raw))
            }
        }
        if (input.endsWith("'") && out.isNotEmpty()) {
            val last = out.removeAt(out.size - 1)
            out.add(last.copy(raw = last.raw + "'"))
        }
        return out
    }

    private class Best(val tokens: List<Token>, val partial: Int)

    private fun segmentChunk(s: String): List<Token> {
        val n = s.length
        val memo = arrayOfNulls<Best>(n + 1)
        memo[n] = Best(emptyList(), 0)
        for (i in n - 1 downTo 0) {
            var best: Best? = null
            for (len in minOf(6, n - i) downTo 1) {
                val piece = s.substring(i, i + len)
                val complete = piece in FULL
                val partial = !complete && piece in PREFIX
                if (!complete && !partial && len > 1) continue
                val rest = memo[i + len] ?: continue
                val p = rest.partial + if (complete) 0 else 1
                val cand = Best(listOf(Token(piece, complete, piece)) + rest.tokens, p)
                if (best == null || better(cand, best)) best = cand
            }
            memo[i] = best
        }
        return memo[0]?.tokens ?: listOf(Token(s, false, s))
    }

    private fun better(a: Best, b: Best): Boolean {
        if (a.partial != b.partial) return a.partial < b.partial
        if (a.tokens.size != b.tokens.size) return a.tokens.size < b.tokens.size
        return a.tokens.first().text.length > b.tokens.first().text.length
    }
}
