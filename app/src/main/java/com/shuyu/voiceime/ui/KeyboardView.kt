package com.shuyu.voiceime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Draws a whole key layout itself (like the old KeyboardView) so that gestures that start on one key
 * and travel across the keyboard — space long-press + slide up to pick a voice mode — are easy to track.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val listener: Listener) : View(ctx) {

    interface Listener {
        fun onKey(key: Key, output: String)
        fun onCursorMove(delta: Int)
        fun onVoiceStart(): Boolean
        /** mode 0..2, or -1 for cancel */
        fun onVoiceEnd(mode: Int)
        fun onFeedback()
    }

    var layout: KbLayout = Layouts.qwerty(true)
        set(v) { field = v; leftScroll = 0f; requestLayout(); computeRects(); invalidate() }
    /** 0 = off, 1 = next letter upper, 2 = caps lock */
    var shiftState = 0
        set(v) { field = v; invalidate() }
    var enterLabel: String? = null
        set(v) { field = v; invalidate() }
    var rowHeightPx = dp(54f)
    var voiceModeLabels = arrayOf("语音输入" to "直接转文字", "智能整理" to "去口水词 · 更有条理", "翻译" to "→ 英语")

    private val rects = HashMap<Key, RectF>()
    private var leftRect = RectF()
    private var leftScroll = 0f

    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------------------------------------------------------------- touch state
    private inner class Touch(val id: Int, val key: Key?, val x0: Float, val y0: Float) {
        var consumed = false
        var cursorMode = false
        var lastCursorX = x0
        var inLeftColumn = false
        var lastY = y0
        var scrolled = false
    }
    private val touches = HashMap<Int, Touch>()
    private val pressed = HashSet<Key>()
    private var longPressRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null

    // voice overlay
    var voiceActive = false; private set
    private var voiceSel = 0
    private var voiceLevel = 0f
    private val levels = FloatArray(24)

    fun setVoiceLevel(l: Float) {
        voiceLevel = voiceLevel * 0.5f + l * 0.5f
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = voiceLevel
        if (voiceActive) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rowHeightPx * layout.rows.size + dp(6f)).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = computeRects()

    private fun computeRects() {
        rects.clear()
        if (width == 0) return
        val pad = dp(3f)
        var left = pad
        val right = width - pad
        val top = dp(3f)
        if (layout.leftColumn != null) {
            val lw = (right - left) * 0.16f
            leftRect = RectF(left, top, left + lw, top + rowHeightPx * layout.rows.size)
            left += lw
        }
        for ((ri, row) in layout.rows.withIndex()) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            // English/Chinese 2nd row is indented like a real keyboard (9 keys on a 10-key grid)
            var x = left
            var avail = right - left
            if (layout.leftColumn == null && ri == 1 && row.size == 9) { x += avail / 20f; avail -= avail / 10f }
            val unit = avail / total
            val y = top + ri * rowHeightPx
            for (k in row) {
                rects[k] = RectF(x, y, x + unit * k.weight, y + rowHeightPx)
                x += unit * k.weight
            }
        }
        if (layout.leftColumn != null) {
            val itemH = rowHeightPx * 0.8f
            layout.leftColumn!!.forEachIndexed { i, k ->
                rects[k] = RectF(leftRect.left, leftRect.top + i * itemH, leftRect.right, leftRect.top + (i + 1) * itemH)
            }
        }
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.BG)
        paint.color = Theme.SEP
        paint.strokeWidth = dp(1f)
        val rowsTop = dp(3f)
        val sepLeft = if (layout.leftColumn != null) leftRect.right + dp(10f) else dp(12f)
        val sepRight = if (layout.leftColumn != null) rects[layout.rows[0].last()]!!.left - dp(10f) else width - dp(12f)
        for (i in 1 until layout.rows.size) {
            val y = rowsTop + i * rowHeightPx
            c.drawLine(sepLeft, y, sepRight, y, paint)
        }
        if (layout.leftColumn != null) {
            c.drawLine(leftRect.right, leftRect.top + dp(8f), leftRect.right, leftRect.top + rowHeightPx * 2.6f, paint)
            c.save()
            c.clipRect(leftRect)
            c.translate(0f, -leftScroll)
            layout.leftColumn!!.forEach { drawKey(c, it, rects[it]!!) }
            c.restore()
        }
        for (row in layout.rows) for (k in row) drawKey(c, k, rects[k] ?: continue)
        if (voiceActive) drawVoice(c)
    }

    private fun drawKey(c: Canvas, k: Key, r: RectF) {
        if (k in pressed) {
            paint.color = Theme.PRESSED
            c.drawRoundRect(r.left + dp(2f), r.top + dp(3f), r.right - dp(2f), r.bottom - dp(3f), dp(6f), dp(6f), paint)
        }
        val cx = r.centerX()
        val hasHint = k.hint != null || k.hintIcon != null
        val labelY = if (hasHint) r.top + r.height() * 0.70f else r.centerY()
        if (k.hint != null) {
            paint.color = Theme.HINT; paint.textSize = dp(13f); paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT
            c.drawText(k.hint, cx, r.top + r.height() * 0.32f, paint)
        }
        k.hintIcon?.let { Icons.draw(c, it, cx, r.top + r.height() * 0.28f, dp(14f), Theme.HINT) }
        val icon = when (k.type) {
            KeyType.SHIFT -> when (shiftState) { 1 -> Icon.SHIFT_ON; 2 -> Icon.SHIFT_LOCK; else -> Icon.SHIFT }
            else -> k.icon
        }
        if (k.type == KeyType.ENTER && enterLabel != null) {
            paint.color = Theme.TEXT; paint.textSize = dp(16f); paint.textAlign = Paint.Align.CENTER
            c.drawText(enterLabel!!, cx, r.centerY() + dp(6f), paint)
            return
        }
        if (icon != null) {
            if (icon == Icon.SPACE_MIC) {
                Icons.draw(c, Icon.MIC, cx, r.centerY() - dp(4f), dp(20f), Theme.TEXT)
                paint.color = Theme.TEXT; paint.strokeWidth = dp(2.2f); paint.strokeCap = Paint.Cap.ROUND
                val hw = minOf(r.width() * 0.32f, dp(60f)); val by = r.centerY() + dp(10f)
                c.drawLine(cx - hw, by, cx + hw, by, paint)
                c.drawLine(cx - hw, by, cx - hw, by - dp(5f), paint)
                c.drawLine(cx + hw, by, cx + hw, by - dp(5f), paint)
            } else {
                Icons.draw(c, icon, cx, r.centerY(), dp(if (icon == Icon.BACKSPACE) 26f else 28f), Theme.TEXT)
            }
            return
        }
        var label = k.label
        if (k.type == KeyType.TEXT && label.length == 1 && label[0].isLetter() && shiftState > 0) label = label.uppercase()
        paint.color = if (k.small) Theme.HINT else Theme.TEXT
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT
        paint.textSize = when {
            k.small -> dp(17f)
            k.type == KeyType.TEXT && layout.name == "numbers" && k !in (layout.leftColumn ?: emptyList()) -> dp(26f)
            k.type == KeyType.TEXT && label.length == 1 && label[0].isLetter() -> dp(25f)
            label.length >= 3 -> dp(17f)
            else -> dp(20f)
        }
        if (k in (layout.leftColumn ?: emptyList())) paint.textSize = dp(18f)
        c.drawText(label, cx, labelY + paint.textSize * 0.35f, paint)
    }

    private val cardRects = Array(3) { RectF() }
    private val cancelRect = RectF()

    private fun drawVoice(c: Canvas) {
        c.drawColor(Color.argb(235, 0xA8, 0xA8, 0xA8))
        val pad = dp(10f)
        val cw = (width - pad * 4) / 3f
        val ctop = dp(10f)
        val ch = minOf(height * 0.36f, dp(78f))
        for (i in 0..2) {
            val r = cardRects[i]
            r.set(pad + i * (cw + pad), ctop, pad + i * (cw + pad) + cw, ctop + ch)
            val sel = voiceSel == i
            paint.color = if (sel) Theme.ACCENT else Color.argb(150, 255, 255, 255)
            c.drawRoundRect(r, dp(12f), dp(12f), paint)
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (sel) Color.WHITE else Theme.TEXT
            paint.textSize = dp(17f); paint.isFakeBoldText = true
            c.drawText(voiceModeLabels[i].first, r.centerX(), r.top + ch * 0.45f, paint)
            paint.isFakeBoldText = false
            paint.textSize = dp(11.5f)
            paint.color = if (sel) Color.argb(220, 255, 255, 255) else Theme.HINT
            c.drawText(voiceModeLabels[i].second, r.centerX(), r.top + ch * 0.76f, paint)
        }
        // level bars
        val midY = ctop + ch + (height - ctop - ch) * 0.42f
        val barW = dp(4f); val gap = dp(3.5f)
        val total = levels.size * (barW + gap)
        var x = width / 2f - total / 2f
        paint.color = if (voiceSel == -1) Theme.HINT else Theme.ACCENT
        for (l in levels) {
            val h = dp(4f) + l * dp(34f)
            c.drawRoundRect(x, midY - h / 2, x + barW, midY + h / 2, barW / 2, barW / 2, paint)
            x += barW + gap
        }
        paint.color = Theme.TEXT; paint.textSize = dp(14f); paint.textAlign = Paint.Align.CENTER
        val tip = when (voiceSel) { -1 -> "松手取消"; else -> "正在聆听 · 上滑选择模式 · 松手结束" }
        c.drawText(tip, width / 2f, midY + dp(36f), paint)
        // cancel pill
        val cy = height - rowHeightPx / 2f
        cancelRect.set(dp(12f), cy - dp(20f), dp(12f) + dp(92f), cy + dp(20f))
        paint.color = if (voiceSel == -1) Color.parseColor("#E5484D") else Color.argb(150, 255, 255, 255)
        c.drawRoundRect(cancelRect, dp(20f), dp(20f), paint)
        paint.color = if (voiceSel == -1) Color.WHITE else Theme.TEXT; paint.textSize = dp(15f)
        c.drawText("✕ 取消", cancelRect.centerX(), cancelRect.centerY() + dp(5f), paint)
    }

    private fun updateVoiceSel(x: Float, y: Float) {
        val old = voiceSel
        val cardsBottom = cardRects[0].bottom + dp(24f)
        voiceSel = when {
            cancelRect.contains(x, y) || (x < cancelRect.right + dp(10f) && y > cancelRect.top - dp(10f)) -> -1
            y < cardsBottom -> ((x / width) * 3).toInt().coerceIn(0, 2)
            else -> 0
        }
        if (old != voiceSel) { listener.onFeedback(); invalidate() }
    }

    // ---------------------------------------------------------------- touch handling
    private fun keyAt(x: Float, y: Float): Key? {
        if (layout.leftColumn != null && leftRect.contains(x, y)) {
            val yy = y + leftScroll
            return layout.leftColumn!!.firstOrNull { rects[it]!!.let { r -> yy >= r.top && yy < r.bottom } }
        }
        for (row in layout.rows) for (k in row) {
            val r = rects[k] ?: continue
            if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return k
        }
        // nearest key in the same row (touches in padding)
        var best: Key? = null; var bd = Float.MAX_VALUE
        for (row in layout.rows) for (k in row) {
            val r = rects[k] ?: continue
            val d = abs(r.centerX() - x) + abs(r.centerY() - y) * 2
            if (d < bd) { bd = d; best = k }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (voiceActive) return true
                // Fast typing: a new finger finishes the previous key.
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) touches.values.toList().forEach { finish(it, cancelled = false) }
                val x = e.getX(idx); val y = e.getY(idx)
                val k = keyAt(x, y)
                val t = Touch(e.getPointerId(idx), k, x, y)
                t.inLeftColumn = layout.leftColumn != null && leftRect.contains(x, y)
                touches[t.id] = t
                if (k != null) {
                    pressed.add(k); invalidate()
                    listener.onFeedback()
                    scheduleLong(t)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    move(t, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches[e.getPointerId(idx)] ?: return true
                if (voiceActive) {
                    updateVoiceSel(e.getX(idx), e.getY(idx))
                    endVoice(voiceSel)
                    touches.remove(t.id)
                    return true
                }
                finish(t, cancelled = false, upX = e.getX(idx), upY = e.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                if (voiceActive) endVoice(-1)
                touches.values.toList().forEach { finish(it, cancelled = true) }
            }
        }
        return true
    }

    private fun move(t: Touch, x: Float, y: Float) {
        if (voiceActive) { updateVoiceSel(x, y); return }
        val k = t.key ?: return
        if (t.inLeftColumn) {
            val dy = y - t.lastY
            if (abs(y - t.y0) > dp(8f)) { t.scrolled = true; cancelTimers() }
            if (t.scrolled) {
                val maxScroll = (layout.leftColumn!!.size * rowHeightPx * 0.8f - leftRect.height()).coerceAtLeast(0f)
                leftScroll = (leftScroll - dy).coerceIn(0f, maxScroll)
                pressed.remove(k)
                invalidate()
            }
            t.lastY = y
            return
        }
        if (k.type == KeyType.SPACE) {
            if (!t.cursorMode && abs(x - t.x0) > dp(22f) && !t.consumed) {
                t.cursorMode = true; cancelTimers(); t.lastCursorX = x
            }
            if (t.cursorMode) {
                val step = dp(14f)
                while (x - t.lastCursorX > step) { listener.onCursorMove(1); t.lastCursorX += step }
                while (t.lastCursorX - x > step) { listener.onCursorMove(-1); t.lastCursorX -= step }
            }
        }
    }

    private fun finish(t: Touch, cancelled: Boolean, upX: Float = t.x0, upY: Float = t.y0) {
        cancelTimers()
        touches.remove(t.id)
        val k = t.key ?: return
        pressed.remove(k); invalidate()
        if (cancelled || t.consumed || t.cursorMode || t.scrolled) return
        if (k.type == KeyType.BACKSPACE) { listener.onKey(k, ""); return }
        val swipedUp = t.y0 - upY > rowHeightPx * 0.38f
        val out = if (swipedUp && k.hint != null) k.hint else if (swipedUp && k.type == KeyType.LANG) "" else k.text
        listener.onKey(k, out)
    }

    private fun scheduleLong(t: Touch) {
        val k = t.key ?: return
        cancelTimers()
        when {
            k.type == KeyType.SPACE -> {
                longPressRunnable = Runnable {
                    if (touches[t.id] === t && !t.cursorMode && listener.onVoiceStart()) {
                        t.consumed = true
                        pressed.clear()
                        voiceActive = true; voiceSel = 0
                        levels.fill(0f)
                        invalidate()
                    }
                }
                main.postDelayed(longPressRunnable!!, 320)
            }
            k.type == KeyType.BACKSPACE -> {
                repeatRunnable = object : Runnable {
                    override fun run() {
                        if (touches[t.id] !== t) return
                        t.consumed = true
                        listener.onKey(k, "")
                        main.postDelayed(this, 55)
                    }
                }
                main.postDelayed(repeatRunnable!!, 380)
            }
            k.hint != null -> {
                longPressRunnable = Runnable {
                    if (touches[t.id] === t) {
                        t.consumed = true
                        listener.onFeedback()
                        listener.onKey(k, k.hint)
                        pressed.remove(k); invalidate()
                    }
                }
                main.postDelayed(longPressRunnable!!, 380)
            }
        }
    }

    private fun cancelTimers() {
        longPressRunnable?.let { main.removeCallbacks(it) }; longPressRunnable = null
        repeatRunnable?.let { main.removeCallbacks(it) }; repeatRunnable = null
    }

    private fun endVoice(mode: Int) {
        voiceActive = false
        pressed.clear()
        invalidate()
        listener.onVoiceEnd(mode)
    }

    /** Called when the keyboard is hidden mid-gesture. */
    fun abortGestures() {
        if (voiceActive) endVoice(-1)
        touches.clear(); pressed.clear(); cancelTimers(); invalidate()
    }
}
