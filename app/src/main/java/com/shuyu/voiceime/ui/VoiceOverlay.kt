package com.shuyu.voiceime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Covers the key area while recording: three mode cards at the top, a level meter and a cancel pill.
 * Two ways to drive it:
 *  - hold mode: the finger stays on the space bar; the keyboard forwards screen coordinates via [track]
 *  - tap mode: started from the toolbar mic; the overlay takes taps itself
 */
@SuppressLint("ViewConstructor")
class VoiceOverlay(ctx: Context, private val onFeedback: () -> Unit, private val onFinish: (Int) -> Unit) : View(ctx) {

    var labels = arrayOf("语音输入" to "直接转文字", "智能整理" to "去口水词 · 更有条理", "翻译" to "说中文 → 英语")
    var selection = 0; private set
    private var tapMode = false
    private val levels = FloatArray(28)
    private var smooth = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cards = Array(3) { RectF() }
    private val cancel = RectF()
    private val done = RectF()
    private val loc = IntArray(2)

    fun show(tap: Boolean) {
        tapMode = tap
        selection = 0
        levels.fill(0f)
        visibility = VISIBLE
        invalidate()
    }

    fun hide() { visibility = GONE }

    val isShowing get() = visibility == VISIBLE

    fun setLevel(l: Float) {
        smooth = smooth * 0.5f + l * 0.5f
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = smooth
        if (isShowing) invalidate()
    }

    /** Hold mode: finger position in screen coordinates. */
    fun track(rawX: Float, rawY: Float) {
        getLocationOnScreen(loc)
        select(rawX - loc[0], rawY - loc[1])
    }

    private fun hit(x: Float, y: Float): Int {
        if (cancel.contains(x, y) || (x < cancel.right + dp(12f) && y > cancel.top - dp(12f))) return -1
        for (i in 0..2) if (x >= cards[i].left - dp(5f) && x <= cards[i].right + dp(5f) && y < cards[i].bottom + dp(24f)) return i
        return -2
    }

    private fun select(x: Float, y: Float) {
        val old = selection
        val h = hit(x, y)
        selection = if (h == -2) 0 else h
        if (old != selection) { onFeedback(); invalidate() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!tapMode) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val h = hit(e.x, e.y)
                if (h != -2 && h != selection) { selection = h; onFeedback(); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                when {
                    h != -2 -> onFinish(h)
                    done.contains(e.x, e.y) -> onFinish(selection)
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.argb(240, 0xA8, 0xA8, 0xA8))
        val pad = dp(10f)
        val cw = (width - pad * 4) / 3f
        val top = dp(10f)
        val ch = minOf(height * 0.34f, dp(80f))
        for (i in 0..2) {
            val r = cards[i]
            r.set(pad + i * (cw + pad), top, pad + i * (cw + pad) + cw, top + ch)
            val sel = selection == i
            p.color = if (sel) Theme.ACCENT else Color.argb(150, 255, 255, 255)
            c.drawRoundRect(r, dp(12f), dp(12f), p)
            p.textAlign = Paint.Align.CENTER
            p.color = if (sel) Color.WHITE else Theme.TEXT
            p.textSize = dp(17f); p.isFakeBoldText = true
            c.drawText(labels[i].first, r.centerX(), r.top + ch * 0.45f, p)
            p.isFakeBoldText = false
            p.textSize = dp(11.5f)
            p.color = if (sel) Color.argb(220, 255, 255, 255) else Theme.HINT
            c.drawText(labels[i].second, r.centerX(), r.top + ch * 0.76f, p)
        }
        val midY = top + ch + (height - top - ch) * 0.38f
        val barW = dp(4f); val gap = dp(3.5f)
        var x = width / 2f - levels.size * (barW + gap) / 2f
        p.color = if (selection == -1) Theme.HINT else Theme.ACCENT
        for (l in levels) {
            val h = dp(4f) + l * dp(34f)
            c.drawRoundRect(x, midY - h / 2, x + barW, midY + h / 2, barW / 2, barW / 2, p)
            x += barW + gap
        }
        p.color = Theme.TEXT; p.textSize = dp(14f); p.textAlign = Paint.Align.CENTER
        val tip = when {
            selection == -1 -> if (tapMode) "点击取消" else "松手取消"
            tapMode -> "正在聆听 · 点模式卡片或「说完了」结束"
            else -> "正在聆听 · 上滑选择模式 · 松手结束"
        }
        c.drawText(tip, width / 2f, midY + dp(36f), p)

        val cy = height - dp(32f)
        cancel.set(dp(12f), cy - dp(20f), dp(104f), cy + dp(20f))
        p.color = if (selection == -1) Color.parseColor("#E5484D") else Color.argb(150, 255, 255, 255)
        c.drawRoundRect(cancel, dp(20f), dp(20f), p)
        p.color = if (selection == -1) Color.WHITE else Theme.TEXT; p.textSize = dp(15f)
        c.drawText("✕ 取消", cancel.centerX(), cancel.centerY() + dp(5f), p)
        if (tapMode) {
            done.set(width - dp(124f), cy - dp(20f), width - dp(12f), cy + dp(20f))
            p.color = Theme.ACCENT
            c.drawRoundRect(done, dp(20f), dp(20f), p)
            p.color = Color.WHITE
            c.drawText("说完了", done.centerX(), done.centerY() + dp(5f), p)
        } else done.setEmpty()
    }
}
