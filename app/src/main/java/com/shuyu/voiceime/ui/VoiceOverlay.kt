package com.shuyu.voiceime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sits over the key area (never larger) while recording: three mode cards at the top,
 * a pulsing mic with a live waveform in the middle, cancel / done pills at the bottom.
 * Two ways to drive it:
 *  - hold mode: the finger stays on the space bar; the keyboard forwards screen coordinates via [track]
 *  - tap mode: started from the toolbar mic; the overlay takes taps itself
 */
@SuppressLint("ViewConstructor")
class VoiceOverlay(ctx: Context, private val onFeedback: () -> Unit, private val onFinish: (Int) -> Unit) : View(ctx) {

    var labels = arrayOf("语音输入" to "直接转文字", "智能整理" to "去口水词 · 更有条理", "翻译" to "说中文 → 英语")
    var selection = 0; private set
    private var tapMode = false
    private val levels = FloatArray(18)
    private var smooth = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cards = Array(3) { RectF() }
    /** Animated 0..1 "selectedness" per card; index 3 is the cancel pill. */
    private val sel = FloatArray(4)
    private val cancel = RectF()
    private val done = RectF()
    private val loc = IntArray(2)
    private var shownAt = 0L

    /** Never take more height than the parent gives exactly (prevents the IME window from growing). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    fun show(tap: Boolean) {
        tapMode = tap
        selection = 0
        sel.fill(0f); sel[0] = 1f
        levels.fill(0f)
        shownAt = SystemClock.uptimeMillis()
        animate().cancel()
        alpha = 0f
        translationY = dp(18f)
        visibility = VISIBLE
        animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        invalidate()
    }

    fun hide() {
        if (visibility != VISIBLE) return
        animate().cancel()
        animate().alpha(0f).translationY(dp(12f)).setDuration(140).withEndAction { visibility = GONE }.start()
    }

    val isShowing get() = visibility == VISIBLE && alpha > 0f

    fun setLevel(l: Float) {
        smooth = smooth * 0.55f + l * 0.45f
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = smooth
    }

    /** Hold mode: finger position in screen coordinates. */
    fun track(rawX: Float, rawY: Float) {
        getLocationOnScreen(loc)
        select(rawX - loc[0], rawY - loc[1])
    }

    private fun hit(x: Float, y: Float): Int {
        if (cancel.contains(x, y) || (x < cancel.right + dp(12f) && y > cancel.top - dp(12f))) return -1
        for (i in 0..2) if (x >= cards[i].left - dp(5f) && x <= cards[i].right + dp(5f) && y < cards[i].bottom + dp(28f)) return i
        return -2
    }

    private fun select(x: Float, y: Float) {
        val old = selection
        val h = hit(x, y)
        selection = if (h == -2) 0 else h
        if (old != selection) onFeedback()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!tapMode) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val h = hit(e.x, e.y)
                if (h != -2 && h != selection) { selection = h; onFeedback() }
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

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xff) + ((b shr s and 0xff) - (a shr s and 0xff)) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        // ease the per-card highlight toward the current selection
        for (i in 0..3) {
            val target = if ((i < 3 && selection == i) || (i == 3 && selection == -1)) 1f else 0f
            sel[i] += (target - sel[i]) * 0.3f
            if (abs(target - sel[i]) < 0.01f) sel[i] = target
        }
        c.drawColor(Theme.BG)

        // ---- mode cards
        val pad = dp(10f)
        val cw = (width - pad * 4) / 3f
        val top = dp(12f)
        val ch = minOf(height * 0.30f, dp(64f))
        val white = Color.argb(140, 255, 255, 255)
        for (i in 0..2) {
            val t = sel[i]
            val r = cards[i]
            r.set(pad + i * (cw + pad), top, pad + i * (cw + pad) + cw, top + ch)
            c.save()
            val s = 1f + 0.05f * t
            c.scale(s, s, r.centerX(), r.centerY())
            p.color = mix(white, Theme.ACCENT, t)
            c.drawRoundRect(r, dp(14f), dp(14f), p)
            p.textAlign = Paint.Align.CENTER
            p.color = mix(Theme.TEXT, Color.WHITE, t)
            p.textSize = dp(16f); p.isFakeBoldText = true
            c.drawText(labels[i].first, r.centerX(), r.top + ch * 0.46f, p)
            p.isFakeBoldText = false
            p.textSize = dp(11f)
            p.color = mix(Theme.HINT, Color.argb(225, 255, 255, 255), t)
            c.drawText(labels[i].second, r.centerX(), r.top + ch * 0.76f, p)
            c.restore()
        }

        // ---- pulsing mic with a symmetric waveform
        val cy = top + ch + (height - top - ch - dp(56f)) / 2f + dp(4f)
        val cx = width / 2f
        val micR = dp(24f)
        val accent = if (selection == -1) Theme.OUTLINE else Theme.ACCENT
        val breath = (sin((now - shownAt) / 1000.0 * 2 * PI * 0.8) * 0.5 + 0.5).toFloat()
        p.color = accent
        p.alpha = (40 + 50 * smooth).toInt()
        c.drawCircle(cx, cy, micR + dp(6f) + dp(16f) * smooth + dp(3f) * breath, p)
        p.alpha = 255
        c.drawCircle(cx, cy, micR, p)
        Icons.draw(c, Icon.MIC, cx, cy, dp(26f), Color.WHITE)
        val barW = dp(3.5f); val gap = dp(4f)
        for (i in levels.indices) {
            val l = levels[levels.size - 1 - i]
            val h = dp(4f) + l * dp(30f) * (1f - i / levels.size.toFloat() * 0.5f)
            val off = micR + dp(14f) + i * (barW + gap)
            p.color = accent; p.alpha = (230 - i * 9).coerceAtLeast(60)
            c.drawRoundRect(cx + off, cy - h / 2, cx + off + barW, cy + h / 2, barW / 2, barW / 2, p)
            c.drawRoundRect(cx - off - barW, cy - h / 2, cx - off, cy + h / 2, barW / 2, barW / 2, p)
        }
        p.alpha = 255

        // ---- bottom: tip, cancel, done
        val by = height - dp(30f)
        cancel.set(dp(12f), by - dp(19f), dp(100f), by + dp(19f))
        val ct = sel[3]
        p.color = mix(white, Color.parseColor("#E5484D"), ct)
        c.drawRoundRect(cancel, dp(19f), dp(19f), p)
        p.color = mix(Theme.TEXT, Color.WHITE, ct); p.textSize = dp(14.5f); p.textAlign = Paint.Align.CENTER
        c.drawText("✕ 取消", cancel.centerX(), cancel.centerY() + dp(5f), p)
        if (tapMode) {
            done.set(width - dp(112f), by - dp(19f), width - dp(12f), by + dp(19f))
            p.color = Theme.ACCENT
            c.drawRoundRect(done, dp(19f), dp(19f), p)
            p.color = Color.WHITE
            c.drawText("说完了", done.centerX(), done.centerY() + dp(5f), p)
        } else done.setEmpty()
        p.color = Theme.HINT; p.textSize = dp(13f)
        val tip = when {
            selection == -1 -> if (tapMode) "点击取消" else "松手取消"
            tapMode -> "点卡片选择模式并结束"
            else -> "上滑选模式 · 松手结束"
        }
        val tipX = if (tapMode) (cancel.right + done.left) / 2 else (cancel.right + width) / 2 - dp(6f)
        c.drawText(tip, tipX, by + dp(5f), p)

        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
