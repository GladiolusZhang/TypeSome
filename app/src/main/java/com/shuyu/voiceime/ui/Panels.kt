package com.shuyu.voiceime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.hypot

/** Things panels can ask the keyboard service to do. */
interface PanelHost {
    fun commit(text: String)
    fun backspace()
    fun closePanel()
    fun feedback()
    fun sendKey(keyCode: Int, shift: Boolean = false)
    fun editAction(action: String)
    fun selectMode(mode: String)
    val currentMode: String
    fun clipboardItems(): List<String>
    fun clearClipboard()
    fun showPanel(name: String)
}

// ------------------------------------------------------------------ helpers

private fun Context.tv(text: String, sizeSp: Float, color: Int = Theme.TEXT) = TextView(this).apply {
    this.text = text
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    gravity = Gravity.CENTER
}

/** Pressed-state background for plain TextView buttons. */
@SuppressLint("ClickableViewAccessibility")
private fun View.pressable(radiusDp: Float = 8f, outline: Boolean = false, cap: Boolean = outline) {
    // On the key-cap skins, outlined buttons become filled caps like the keys.
    val asCap = cap && !Theme.flat
    val inset = if (asCap && !outline) context.dp(3f).toInt() else 0
    fun bg(fill: Int) = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
        cornerRadius = context.dp(if (asCap) 7f else radiusDp)
        setColor(fill)
        if (outline && !asCap) setStroke(context.dp(1f).toInt(), Theme.OUTLINE)
    }, inset, inset + context.dp(1f).toInt(), inset, inset + context.dp(1f).toInt())
    val normal = bg(if (asCap) Theme.KEY else 0)
    val down = bg(if (asCap) Theme.KEY_DOWN else Theme.PRESSED)
    background = normal
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.background = down
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.background = normal
        }
        false
    }
}

/** Header row "‹ title" used by the edit / mode / clipboard panels. */
private fun header(ctx: Context, title: String, host: PanelHost, barHeight: Int, extra: View? = null): View {
    val row = LinearLayout(ctx)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setBackgroundColor(Theme.BAR)
    row.addView(IconView(ctx, Icon.CHEVRON_LEFT, Theme.TEXT, 22f).apply { setOnClickListener { host.closePanel() } },
        LinearLayout.LayoutParams(ctx.dp(52f).toInt(), barHeight))
    row.addView(ctx.tv(title, 19f).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -1, 1f))
    if (extra != null) row.addView(extra, LinearLayout.LayoutParams(-2, -1))
    return row
}

/** Simple grid of text cells inside a vertical ScrollView. */
private class Grid(ctx: Context, val columns: Int, val cellHeight: Int, val caps: Boolean = false) : ScrollView(ctx) {
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    init { isVerticalScrollBarEnabled = false; addView(box) }

    fun set(items: List<String>, sizeSp: Float, onClick: (String) -> Unit, sections: List<Pair<String, Int>> = emptyList()) {
        box.removeAllViews()
        val starts = sections.associate { it.second to it.first }
        var row: LinearLayout? = null
        var inRow = 0
        for ((i, s) in items.withIndex()) {
            starts[i]?.let { title ->
                if (row != null) { while (inRow < columns) { row!!.addView(View(context), LinearLayout.LayoutParams(0, cellHeight, 1f)); inRow++ } }
                box.addView(context.tv(title, 13f, Theme.HINT).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(context.dp(14f).toInt(), 0, 0, 0) },
                    LinearLayout.LayoutParams(-1, context.dp(30f).toInt()))
                row = null
            }
            if (row == null || inRow == columns) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row, LinearLayout.LayoutParams(-1, cellHeight)); inRow = 0
            }
            val cell = context.tv(s, sizeSp)
            cell.maxLines = 1
            cell.pressable(cap = caps)
            cell.setOnClickListener { onClick(s) }
            row!!.addView(cell, LinearLayout.LayoutParams(0, cellHeight, 1f))
            inRow++
        }
        if (row != null) while (inRow < columns) { row!!.addView(View(context), LinearLayout.LayoutParams(0, cellHeight, 1f)); inRow++ }
        scrollTo(0, 0)
    }

    fun scrollToSection(index: Int) {
        var seen = -1
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            if (v is TextView) { seen++; if (seen == index) { smoothScrollTo(0, v.top); return } }
        }
    }
}

// ------------------------------------------------------------------ symbols

class SymbolPanel(ctx: Context, private val host: PanelHost, recent: List<String>, private val onUse: (String) -> Unit) : LinearLayout(ctx) {
    private val cats = linkedMapOf(
        "最近" to recent,
        "中文" to "，。？！、；：“”‘’（）《》〈〉【】「」『』…—～·￥".map { it.toString() } + listOf("……", "——", "《》", "“”", "（）", "【】"),
        "英文" to ",.?!;:'\"()[]{}<>@#$%^&*_-+=/\\|`~".map { it.toString() } + listOf("...", "()", "[]", "{}", "<>", "\"\""),
        "数学" to "+-×÷=≠≈±<>≤≥∞√∑∏∫°%‰π∈∉∩∪⊂⊃∠⊥∥∵∴∝∀∃∂∇".map { it.toString() },
        "序号" to "①②③④⑤⑥⑦⑧⑨⑩⑴⑵⑶⑷⑸⑹⑺⑻⑼⑽ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ㈠㈡㈢㈣㈤㈥㈦㈧㈨㈩".map { it.toString() },
        "单位" to "℃℉¥$€£¢₩㎡㎥㎏㎎㎞㎝㎜㏄㎖㎗㎘".map { it.toString() } + listOf("km/h", "m²", "m³"),
        "箭头" to "←↑→↓↖↗↘↙↔↕⇐⇒⇔⇑⇓➔➜➤↩↪⤴⤵".map { it.toString() },
        "特殊" to "★☆○●◎◇◆□■△▲▽▼※✓✔✗✘♀♂♪♫☀☁☂☃☺☹♠♥♣♦❤✿❀✪✈☎✉".map { it.toString() },
    )
    private val grid = Grid(ctx, 4, ctx.dp(62f).toInt(), caps = true)
    private val catViews = ArrayList<TextView>()
    private var locked = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        val catCol = LinearLayout(ctx).apply { orientation = VERTICAL }
        val catScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(catCol) }
        for (name in cats.keys) {
            val v = ctx.tv(name, 17f)
            v.setOnClickListener { select(name) }
            catViews.add(v)
            catCol.addView(v, LayoutParams(-1, ctx.dp(56f).toInt()))
        }
        top.addView(catScroll, LayoutParams(ctx.dp(70f).toInt(), -1))
        top.addView(View(ctx).apply { setBackgroundColor(Theme.SEP) }, LayoutParams(ctx.dp(1f).toInt(), -1))
        top.addView(grid, LayoutParams(0, -1, 1f))
        addView(top, LayoutParams(-1, 0, 1f))
        addView(View(ctx).apply { setBackgroundColor(Theme.SEP) }, LayoutParams(-1, ctx.dp(1f).toInt()))

        val bottom = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        val lock = IconView(ctx, Icon.UNLOCK, Theme.TEXT, 26f)
        lock.setOnClickListener { locked = !locked; lock.icon = if (locked) Icon.LOCK else Icon.UNLOCK; lock.invalidate() }
        bottom.addView(lock, LayoutParams(0, -1, 1f))
        bottom.addView(IconView(ctx, Icon.UP, Theme.HINT, 22f).apply { setOnClickListener { grid.smoothScrollBy(0, -grid.height) } }, LayoutParams(0, -1, 1f))
        bottom.addView(IconView(ctx, Icon.DOWN, Theme.HINT, 22f).apply { setOnClickListener { grid.smoothScrollBy(0, grid.height) } }, LayoutParams(0, -1, 1f))
        bottom.addView(IconView(ctx, Icon.BACKSPACE, Theme.TEXT, 26f).apply { setOnClickListener { host.backspace() } }, LayoutParams(0, -1, 1f))
        bottom.addView(ctx.tv("返回", 18f).apply { pressable(); setOnClickListener { host.closePanel() } }, LayoutParams(0, -1, 1f))
        addView(bottom, LayoutParams(-1, ctx.dp(56f).toInt()))
        select(if (recent.isEmpty()) "中文" else "最近")
    }

    private fun select(name: String) {
        for (v in catViews) {
            val sel = v.text == name
            v.setBackgroundColor(if (sel) Theme.CARD else 0)
        }
        grid.set(cats[name] ?: emptyList(), 20f, { s ->
            host.commit(s)
            onUse(s)
            if (!locked) host.closePanel()
        })
    }
}

// ------------------------------------------------------------------ emoji

class EmojiPanel(ctx: Context, private val host: PanelHost, private val recent: List<String>, private val onUse: (String) -> Unit) : LinearLayout(ctx) {

    private val categories: List<Pair<String, List<String>>> by lazy { buildEmoji() }
    private val grid = Grid(ctx, 8, ctx.dp(50f).toInt())
    private val tabs = ArrayList<TextView>()
    private val bottom = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Theme.BAR) }
        head.addView(IconView(ctx, Icon.CHEVRON_LEFT, Theme.TEXT, 22f).apply { setOnClickListener { host.closePanel() } },
            LayoutParams(ctx.dp(52f).toInt(), -1))
        for (name in listOf("Emoji", "颜文字", "符号")) {
            val t = ctx.tv(name, 19f)
            t.setOnClickListener { selectTab(name) }
            tabs.add(t)
            head.addView(t, LayoutParams(0, -1, 1f))
        }
        addView(head, LayoutParams(-1, ctx.dp(48f).toInt()))
        addView(grid, LayoutParams(-1, 0, 1f))
        bottom.orientation = HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        addView(bottom, LayoutParams(-1, ctx.dp(50f).toInt()))
        selectTab("Emoji")
    }

    private fun selectTab(name: String) {
        if (name == "符号") { host.showPanel("symbols"); return }
        for (t in tabs) t.setTextColor(if (t.text == name) Theme.ACCENT else Theme.TEXT)
        bottom.removeAllViews()
        if (name == "Emoji") {
            val all = ArrayList<String>()
            val sections = ArrayList<Pair<String, Int>>()
            val cats = (if (recent.isNotEmpty()) listOf(RECENT_TITLE to recent) else emptyList()) + categories
            for ((title, items) in cats) { sections.add(title to all.size); all.addAll(items) }
            grid.set(all, 26f, { s -> host.commit(s); onUse(s) }, sections)
            // bottom category bar with grey emoji icons
            val gray = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            cats.forEachIndexed { i, (title, items) ->
                val icon = if (title == RECENT_TITLE) IconView(context, Icon.CLOCK, Theme.TEXT, 24f)
                else context.tv(items.firstOrNull() ?: "?", 22f).apply { setLayerType(LAYER_TYPE_HARDWARE, gray); alpha = 0.75f }
                icon.setOnClickListener { grid.scrollToSection(i) }
                bottom.addView(icon, LayoutParams(0, -1, 1f))
            }
        } else {
            grid.set(KAOMOJI, 15f, { s -> host.commit(s); onUse(s) })
        }
        bottom.addView(IconView(context, Icon.BACKSPACE, Theme.TEXT, 24f).apply { setOnClickListener { host.backspace() } }, LayoutParams(0, -1, 1f))
    }

    /** Full Unicode emoji set from assets/emoji.txt, minus whatever this phone's font can't draw. */
    private fun buildEmoji(): List<Pair<String, List<String>>> {
        cached?.let { return it }
        val p = Paint()
        val out = ArrayList<Pair<String, List<String>>>()
        try {
            context.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                var title: String? = null
                for (line in lines) {
                    if (line.startsWith("# ")) { title = line.substring(2).trim(); continue }
                    val t = title ?: continue
                    val items = line.split(' ').filter { it.isNotEmpty() && p.hasGlyph(it) }
                    if (items.isNotEmpty()) out.add(t to items)
                }
            }
        } catch (_: Exception) {}
        cached = out
        return out
    }

    companion object {
        private var cached: List<Pair<String, List<String>>>? = null
        const val RECENT_TITLE = "最近使用"
        val KAOMOJI = listOf("(＾▽＾)", "(๑•̀ㅂ•́)و✧", "(｡･ω･｡)", "(*^▽^*)", "o(╥﹏╥)o", "(╯°□°）╯︵ ┻━┻", "¯\\_(ツ)_/¯",
            "(≧∇≦)ﾉ", "(´･ω･`)", "ヾ(≧▽≦*)o", "(ಥ_ಥ)", "(⊙_⊙)?", "(￣▽￣)~*", "(｀・ω・´)", "(*/ω＼*)", "(～￣▽￣)～",
            "(=^･ω･^=)", "(T_T)", "(^_−)☆", "(｡♥‿♥｡)", "(￣ー￣)", "(•_•)", "(°ロ°)", "Σ(っ°Д°;)っ", "(っ´ω`c)", "ᕦ(ò_óˇ)ᕤ",
            "(^o^)/", "(；一_一)", "(*≧ω≦)", "(￣^￣)")
    }
}

// ------------------------------------------------------------------ text editing

class EditPanel(ctx: Context, private val host: PanelHost, barHeight: Int) : LinearLayout(ctx) {
    private var selecting = false
    private val pad = DPad(ctx)
    /** 全选 turns into 剪切 while text is selected. */
    private var selectAllButton: TextView? = null
    private var hasSelection = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        addView(header(ctx, "文字编辑", host, barHeight), LayoutParams(-1, barHeight))
        val body = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(ctx.dp(12f).toInt(), ctx.dp(10f).toInt(), ctx.dp(12f).toInt(), ctx.dp(10f).toInt()) }
        val left = FrameLayout(ctx)
        left.addView(pad, FrameLayout.LayoutParams(-1, -1))
        body.addView(left, LayoutParams(0, -1, 1f))
        val right = LinearLayout(ctx).apply { orientation = VERTICAL }
        val buttons = listOf(listOf("全选" to "selectAll", "删除" to "delete"), listOf("复制" to "copy", "换行" to "newline"), listOf("粘贴" to "paste", "剪贴板" to "clipboard"))
        for (row in buttons) {
            val r = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            for ((label, action) in row) {
                val b = ctx.tv(label, 18f, if (Theme.flat) Theme.HINT else Theme.TEXT)
                b.pressable(4f, outline = true)
                if (action == "selectAll") {
                    selectAllButton = b
                    b.setOnClickListener {
                        host.feedback()
                        if (hasSelection) { host.editAction("cut"); selectionChanged(false) }
                        else { host.editAction("selectAll"); selectionChanged(true) }
                    }
                } else b.setOnClickListener { host.feedback(); host.editAction(action) }
                r.addView(b, LayoutParams(0, -1, 1f).apply { setMargins(ctx.dp(4f).toInt(), ctx.dp(4f).toInt(), ctx.dp(4f).toInt(), ctx.dp(4f).toInt()) })
            }
            right.addView(r, LayoutParams(-1, 0, 1f))
        }
        body.addView(right, LayoutParams(0, -1, 1f))
        addView(body, LayoutParams(-1, 0, 1f))
    }

    fun selectionChanged(selected: Boolean) {
        if (selected == hasSelection) return
        hasSelection = selected
        selectAllButton?.text = if (selected) "剪切" else "全选"
    }

    /** Round direction pad with a 选择 toggle in the middle and home/end buttons below. */
    @SuppressLint("ViewConstructor")
    private inner class DPad(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val main = Handler(Looper.getMainLooper())
        private var activeDir = -1
        private var repeat: Runnable? = null
        private val homeRect = RectF(); private val endRect = RectF()

        override fun onDraw(c: Canvas) {
            val btn = dp(44f)
            val rOuter = minOf(width / 2f, (height - btn - dp(8f)) / 2f) - dp(4f)
            val cx = width / 2f; val cy = rOuter + dp(4f)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Theme.OUTLINE
            c.drawCircle(cx, cy, rOuter, p)
            c.drawCircle(cx, cy, rOuter * 0.4f, p)
            if (selecting) { p.style = Paint.Style.FILL; p.color = Theme.ACCENT; c.drawCircle(cx, cy, rOuter * 0.4f, p) }
            p.style = Paint.Style.FILL
            p.color = if (selecting) android.graphics.Color.WHITE else Theme.HINT
            p.textSize = dp(16f); p.textAlign = Paint.Align.CENTER
            c.drawText("选择", cx, cy + dp(6f), p)
            val d = rOuter * 0.72f
            Icons.draw(c, Icon.CHEVRON_UP, cx, cy - d, dp(18f), Theme.TEXT)
            Icons.draw(c, Icon.CHEVRON_DOWN, cx, cy + d, dp(18f), Theme.TEXT)
            Icons.draw(c, Icon.LEFT, cx - d, cy, dp(18f), Theme.TEXT)
            Icons.draw(c, Icon.RIGHT, cx + d, cy, dp(18f), Theme.TEXT)
            val by = height - btn / 2f - dp(2f)
            homeRect.set(dp(4f), by - btn / 2, dp(4f) + btn, by + btn / 2)
            endRect.set(width - dp(4f) - btn, by - btn / 2, width - dp(4f), by + btn / 2)
            p.style = Paint.Style.STROKE; p.color = Theme.OUTLINE
            c.drawCircle(homeRect.centerX(), homeRect.centerY(), btn / 2, p)
            c.drawCircle(endRect.centerX(), endRect.centerY(), btn / 2, p)
            Icons.draw(c, Icon.HOME, homeRect.centerX(), homeRect.centerY(), dp(20f), Theme.TEXT)
            Icons.draw(c, Icon.END, endRect.centerX(), endRect.centerY(), dp(20f), Theme.TEXT)
            tag = floatArrayOf(cx, cy, rOuter)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val g = tag as? FloatArray ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    host.feedback()
                    if (homeRect.contains(e.x, e.y)) { host.sendKey(android.view.KeyEvent.KEYCODE_MOVE_HOME, selecting); return true }
                    if (endRect.contains(e.x, e.y)) { host.sendKey(android.view.KeyEvent.KEYCODE_MOVE_END, selecting); return true }
                    val dx = e.x - g[0]; val dy = e.y - g[1]
                    val dist = hypot(dx, dy)
                    if (dist > g[2]) return true
                    if (dist < g[2] * 0.4f) { selecting = !selecting; invalidate(); return true }
                    val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    activeDir = when {
                        ang >= -45 && ang < 45 -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        ang >= 45 && ang < 135 -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        ang >= -135 && ang < -45 -> android.view.KeyEvent.KEYCODE_DPAD_UP
                        else -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    }
                    host.sendKey(activeDir, selecting)
                    repeat = object : Runnable { override fun run() { host.sendKey(activeDir, selecting); main.postDelayed(this, 70) } }
                    main.postDelayed(repeat!!, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { repeat?.let { main.removeCallbacks(it) }; repeat = null; activeDir = -1 }
            }
            return true
        }
    }
}

// ------------------------------------------------------------------ clipboard

class ClipboardPanel(ctx: Context, private val host: PanelHost, barHeight: Int) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        val clear = ctx.tv("清空", 16f, Theme.HINT).apply {
            setPadding(ctx.dp(18f).toInt(), 0, ctx.dp(18f).toInt(), 0)
            setOnClickListener { host.clearClipboard(); host.showPanel("clipboard") }
        }
        addView(header(ctx, "剪贴板", host, barHeight, clear), LayoutParams(-1, barHeight))
        val items = host.clipboardItems()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dp(10f).toInt(), ctx.dp(6f).toInt(), ctx.dp(10f).toInt(), ctx.dp(6f).toInt()) }
        if (items.isEmpty()) col.addView(ctx.tv("复制过的文字会出现在这里", 15f, Theme.HINT), LayoutParams(-1, ctx.dp(120f).toInt()))
        for (s in items) {
            val t = ctx.tv(s, 15f)
            t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            t.maxLines = 2
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            t.setPadding(ctx.dp(12f).toInt(), ctx.dp(10f).toInt(), ctx.dp(12f).toInt(), ctx.dp(10f).toInt())
            t.background = GradientDrawable().apply { cornerRadius = ctx.dp(8f); setColor(Theme.CARD) }
            t.setOnClickListener { host.commit(s); host.closePanel() }
            col.addView(t, LayoutParams(-1, -2).apply { setMargins(0, ctx.dp(4f).toInt(), 0, ctx.dp(4f).toInt()) })
        }
        scroll.addView(col)
        addView(scroll, LayoutParams(-1, 0, 1f))
    }
}

// ------------------------------------------------------------------ input mode picker

class ModePanel(ctx: Context, private val host: PanelHost, barHeight: Int) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        addView(header(ctx, "输入方式", host, barHeight), LayoutParams(-1, barHeight))
        val modes = listOf(
            Triple("pinyin9", "拼音九键", "拼"), Triple("pinyin", "拼音全键", "拼"), Triple("handwriting", "手写", "写"), Triple("stroke", "笔画", "笔"),
            Triple("wubi", "五笔", "五"), Triple("english9", "英文九键", "EN"), Triple("english", "英文全键", "EN"), Triple("languages", "更多语言", null),
        )
        val body = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(0, ctx.dp(20f).toInt(), 0, ctx.dp(20f).toInt()) }
        for (rowItems in modes.chunked(4)) {
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            for ((id, label, badge) in rowItems) {
                val sel = host.currentMode == id
                val color = if (sel) Theme.ACCENT else Theme.TEXT
                val cell = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
                cell.addView(IconView(ctx, if (badge == null) Icon.GLOBE else Icon.KEYBOARD, color, 46f, badge).apply { isClickable = false; isEnabled = false },
                    LayoutParams(ctx.dp(64f).toInt(), ctx.dp(54f).toInt()))
                cell.addView(ctx.tv(label, 15f, color), LayoutParams(-2, -2))
                cell.pressable()
                cell.setOnClickListener { host.selectMode(id) }
                row.addView(cell, LayoutParams(0, -1, 1f))
            }
            body.addView(row, LayoutParams(-1, 0, 1f))
        }
        addView(body, LayoutParams(-1, 0, 1f))
    }
}

// ------------------------------------------------------------------ expanded candidates

class CandidateGridPanel(ctx: Context, private val host: PanelHost, cands: List<String>, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
        setBackgroundColor(Theme.BG)
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        var row: LinearLayout? = null
        var used = 0f
        val maxUnits = 4f
        for ((i, c) in cands.withIndex()) {
            val units = when { c.length <= 2 -> 1f; c.length <= 4 -> 2f; else -> 4f }
            if (row == null || used + units > maxUnits) {
                if (row != null && used < maxUnits) row.addView(View(ctx), LayoutParams(0, -1, maxUnits - used))
                row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
                col.addView(row, LayoutParams(-1, ctx.dp(54f).toInt()))
                used = 0f
            }
            val t = ctx.tv(c, 20f, if (i == 0) Theme.ACCENT else Theme.TEXT)
            t.maxLines = 1
            t.pressable(4f)
            t.setOnClickListener { onPick(i) }
            row.addView(t, LayoutParams(0, -1, units))
            used += units
        }
        if (row != null && used < maxUnits) row.addView(View(ctx), LayoutParams(0, -1, maxUnits - used))
        scroll.addView(col)
        addView(scroll, LayoutParams(0, -1, 1f))
        val side = LinearLayout(ctx).apply { orientation = VERTICAL; setBackgroundColor(Theme.BAR) }
        side.addView(IconView(ctx, Icon.CHEVRON_UP, Theme.TEXT, 22f).apply { setOnClickListener { host.closePanel() } }, LayoutParams(-1, 0, 1f))
        side.addView(IconView(ctx, Icon.BACKSPACE, Theme.TEXT, 24f).apply { setOnClickListener { host.backspace(); host.closePanel() } }, LayoutParams(-1, 0, 1f))
        side.addView(ctx.tv("重输", 16f).apply { pressable(); setOnClickListener { host.editAction("resetComposing"); host.closePanel() } }, LayoutParams(-1, 0, 1f))
        addView(side, LayoutParams(ctx.dp(64f).toInt(), -1))
    }
}
