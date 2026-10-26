package com.shuyu.voiceime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The strip above the keys. Shows one of:
 *  - the toolbar (logo / input modes / text editing / emoji / hide)
 *  - pinyin composing text + candidates
 *  - smart suggestions (calculator result, phone numbers, verification code, associations)
 *  - a status line during voice processing
 */
@SuppressLint("ViewConstructor")
class TopBar(ctx: Context, private val l: Listener) : FrameLayout(ctx) {

    interface Listener {
        fun onToolbar(action: String)
        fun onCandidate(index: Int)
        fun onExpandCandidates()
        fun onSuggestion(index: Int)
        fun onCloseSuggestions()
        fun onComposingTap(displayOffset: Int)
        fun onComposingCursor(delta: Int)
        fun onCancelStatus()
    }

    class Suggestion(val text: String, val tag: String? = null, val highlight: Boolean = false)

    private val toolbar = LinearLayout(ctx)
    private val candBox = LinearLayout(ctx)
    private val composing = TextView(ctx)
    private val composingRow = LinearLayout(ctx)
    private val scroll = HorizontalScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val expand = IconView(ctx, Icon.CHEVRON_DOWN, Theme.TEXT, 22f)
    private val status = TextView(ctx)
    private val statusRow = LinearLayout(ctx)

    private var showingSuggestions = false
    private val line = Paint().apply { color = Theme.BAR_LINE }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (Theme.flat) canvas.drawRect(0f, height - dp(0.8f), width.toFloat(), height.toFloat(), line)
    }

    init {
        setBackgroundColor(Theme.BAR)
        setWillNotDraw(false)
        // ---- toolbar
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        fun tb(icon: Icon, action: String, size: Float = 28f) = IconView(ctx, icon, Theme.TEXT, size).apply {
            setOnClickListener { l.onToolbar(action) }
        }
        fun divider() = View(ctx).apply { setBackgroundColor(if (Theme.flat) Theme.OUTLINE else 0) }
        // Slots measured from the reference: 58dp logo | four evenly spaced tools | 58dp hide
        toolbar.addView(tb(Icon.LOGO, "settings", 27f), LinearLayout.LayoutParams(dpi(57f), -1))
        toolbar.addView(divider(), LinearLayout.LayoutParams(dpi(1f), dpi(21f)))
        toolbar.addView(tb(Icon.KEYBOARD, "modes", 26f), LinearLayout.LayoutParams(0, -1, 1f))
        toolbar.addView(tb(Icon.CURSOR, "edit", 26f), LinearLayout.LayoutParams(0, -1, 1f))
        toolbar.addView(tb(Icon.MIC, "voice", 26f), LinearLayout.LayoutParams(0, -1, 1f))
        toolbar.addView(tb(Icon.SMILE, "emoji", 27f), LinearLayout.LayoutParams(0, -1, 1f))
        toolbar.addView(divider(), LinearLayout.LayoutParams(dpi(1f), dpi(21f)))
        toolbar.addView(tb(Icon.HIDE, "hide", 26f), LinearLayout.LayoutParams(dpi(57f), -1))
        addView(toolbar, LayoutParams(-1, -1))

        // ---- candidates
        candBox.orientation = LinearLayout.VERTICAL
        composingRow.orientation = LinearLayout.HORIZONTAL
        composingRow.gravity = Gravity.CENTER_VERTICAL
        composing.setTextColor(Theme.TEXT)
        composing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        composing.setPadding(dpi(12f), 0, dpi(8f), 0)
        composing.isSingleLine = true
        composing.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val tv = v as TextView
                val lay = tv.layout
                if (lay != null) l.onComposingTap(lay.getOffsetForHorizontal(0, e.x - tv.totalPaddingLeft))
            }
            true
        }
        composingRow.addView(composing, LinearLayout.LayoutParams(0, -1, 1f))
        composingRow.addView(IconView(ctx, Icon.LEFT, Theme.HINT, 14f).apply { setOnClickListener { l.onComposingCursor(-1) } },
            LinearLayout.LayoutParams(dpi(30f), -1))
        composingRow.addView(IconView(ctx, Icon.RIGHT, Theme.HINT, 14f).apply { setOnClickListener { l.onComposingCursor(1) } },
            LinearLayout.LayoutParams(dpi(30f), -1))
        candBox.addView(composingRow, LinearLayout.LayoutParams(-1, dpi(18f)))

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        scroll.isHorizontalScrollBarEnabled = false
        list.orientation = LinearLayout.HORIZONTAL
        list.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(list, LayoutParams(-2, -1))
        row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        expand.setOnClickListener { if (showingSuggestions) l.onCloseSuggestions() else l.onExpandCandidates() }
        row.addView(expand, LinearLayout.LayoutParams(dpi(46f), -1))
        candBox.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(candBox, LayoutParams(-1, -1))

        // ---- status
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        status.setTextColor(Theme.TEXT)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        status.setPadding(dpi(16f), 0, 0, 0)
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(IconView(ctx, Icon.CLOSE, Theme.TEXT, 22f).apply { setOnClickListener { l.onCancelStatus() } },
            LinearLayout.LayoutParams(dpi(46f), -1))
        addView(statusRow, LayoutParams(-1, -1))

        showToolbar()
    }

    private fun only(v: View) {
        toolbar.visibility = if (v === toolbar) VISIBLE else GONE
        candBox.visibility = if (v === candBox) VISIBLE else GONE
        statusRow.visibility = if (v === statusRow) VISIBLE else GONE
    }

    private var statusText: String? = null

    fun showToolbar() { if (statusText == null) only(toolbar) }

    fun setStatus(text: String?) {
        statusText = text
        if (text != null) { status.text = text; only(statusRow) } else only(toolbar)
    }

    val isStatusShowing get() = statusText != null

    fun showCandidates(composingText: String, caret: Int, cands: List<String>) {
        showingSuggestions = false
        expand.icon = Icon.CHEVRON_DOWN; expand.invalidate()
        composingRow.visibility = VISIBLE
        val sb = SpannableStringBuilder(composingText)
        if (caret in 0 until composingText.length) {
            sb.insert(caret, "|")
            sb.setSpan(ForegroundColorSpan(Theme.ACCENT), caret, caret + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        composing.text = sb
        fill(cands.map { Suggestion(it) }, highlightFirst = true) { l.onCandidate(it) }
        only(candBox)
    }

    /** Suggestions without composing text (calculator / phone / code / association). */
    fun showSuggestions(items: List<Suggestion>) {
        showingSuggestions = true
        expand.icon = Icon.CLOSE; expand.invalidate()
        composingRow.visibility = GONE
        fill(items, highlightFirst = false) { l.onSuggestion(it) }
        only(candBox)
    }

    private fun fill(items: List<Suggestion>, highlightFirst: Boolean, click: (Int) -> Unit) {
        list.removeAllViews()
        for ((i, s) in items.withIndex()) {
            val tv = CandidateText(context, s.text, s.tag, s.highlight || (highlightFirst && i == 0))
            tv.setOnClickListener { click(i) }
            list.addView(tv, LinearLayout.LayoutParams(-2, -1))
        }
        scroll.scrollTo(0, 0)
    }

    /** One candidate: big text with an optional small grey tag in front (e.g. 验证码). */
    private class CandidateText(ctx: Context, val text: String, val tag: String?, val highlight: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var down = false
        private val main = ctx.dp(20f)
        private val small = ctx.dp(11f)
        override fun onMeasure(w: Int, h: Int) {
            p.textSize = main
            var width = p.measureText(text) + ctx(28f)
            if (tag != null) { p.textSize = small; width += p.measureText(tag) + ctx(6f) }
            setMeasuredDimension(width.toInt().coerceAtLeast(ctx(44f).toInt()), MeasureSpec.getSize(h))
        }
        private fun ctx(v: Float) = context.dp(v)
        override fun onDraw(c: Canvas) {
            if (down) { p.color = Theme.PRESSED; c.drawRoundRect(ctx(2f), ctx(3f), width - ctx(2f), height - ctx(3f), ctx(6f), ctx(6f), p) }
            var x = ctx(14f)
            val base = height / 2f + main * 0.36f
            if (tag != null) {
                p.textSize = small; p.color = Theme.HINT
                c.drawText(tag, x, base - ctx(1f), p)
                x += p.measureText(tag) + ctx(6f)
            }
            p.textSize = main; p.color = if (highlight) Theme.ACCENT else Theme.TEXT
            c.drawText(text, x, base, p)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
                MotionEvent.ACTION_UP -> { down = false; invalidate(); performClick() }
                MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
            }
            return true
        }
    }
}
