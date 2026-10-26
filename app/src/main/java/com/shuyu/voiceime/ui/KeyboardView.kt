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
        /** alt = produced by swipe-up / long-press (the key's secondary character) */
        fun onKey(key: Key, output: String, alt: Boolean)
        fun onCursorMove(delta: Int)
        fun onVoiceStart(): Boolean
        /** Finger position (screen coordinates) while holding the space bar during recording. */
        fun onVoiceTrack(rawX: Float, rawY: Float)
        /** Finger lifted (cancelled = gesture was aborted). */
        fun onVoiceRelease(cancelled: Boolean)
        fun onFeedback()
    }

    var layout: KbLayout = Layouts.qwerty(true)
        set(v) { field = v; leftScroll = 0f; requestLayout(); computeRects(); invalidate() }
    /** 0 = off, 1 = next letter upper, 2 = caps lock */
    var shiftState = 0
        set(v) { field = v; invalidate() }
    var enterLabel: String? = null
        set(v) { field = v; invalidate() }
    var rowHeightPx = dp(60f)

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

    var voiceActive = false; private set

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
    // Proportions measured from the reference screenshots (360dp wide, 60dp rows):
    // hint baseline at ~30% of the row, main label baseline at ~62%, letters 21sp, hints 13.5sp.

    /** Key showing the press bubble, and when it appeared (for the pop-in animation). */
    private var bubbleKey: Key? = null
    private var bubbleStart = 0L
    private val bubbleRect = RectF()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.BG)
        paint.color = Theme.SEP
        paint.strokeWidth = dp(0.8f)
        val rowsTop = dp(3f)
        val sepLeft = if (layout.leftColumn != null) leftRect.right + dp(10f) else dp(5f)
        val sepRight = if (layout.leftColumn != null) rects[layout.rows[0].last()]!!.left - dp(10f) else width - dp(5f)
        for (i in 1 until layout.rows.size) {
            val y = rowsTop + i * rowHeightPx
            c.drawLine(sepLeft, y, sepRight, y, paint)
        }
        if (layout.leftColumn != null) {
            c.drawLine(leftRect.right, leftRect.top + dp(10f), leftRect.right, leftRect.top + rowHeightPx * 2.6f, paint)
            c.save()
            c.clipRect(leftRect)
            c.translate(0f, -leftScroll)
            layout.leftColumn!!.forEach { drawKey(c, it, rects[it]!!) }
            c.restore()
        }
        for (row in layout.rows) for (k in row) drawKey(c, k, rects[k] ?: continue)
        drawBubble(c)
    }

    private fun showsBubble(k: Key) = (k.type == KeyType.TEXT || k.type == KeyType.T9) &&
        k !in (layout.leftColumn ?: emptyList()) && layout.name != "numbers"

    private fun drawBubble(c: Canvas) {
        val k = bubbleKey ?: return
        if (k !in pressed) { bubbleKey = null; return }
        val r = rects[k] ?: return
        val t = ((android.os.SystemClock.uptimeMillis() - bubbleStart) / 90f).coerceIn(0f, 1f)
        val ease = 1f - (1f - t) * (1f - t)
        val w = maxOf(r.width() + dp(8f), dp(44f))
        val h = rowHeightPx * 1.05f
        val bottom = r.top + dp(18f)
        bubbleRect.set(r.centerX() - w / 2, bottom - h, r.centerX() + w / 2, bottom)
        if (bubbleRect.left < dp(2f)) bubbleRect.offset(dp(2f) - bubbleRect.left, 0f)
        if (bubbleRect.right > width - dp(2f)) bubbleRect.offset(width - dp(2f) - bubbleRect.right, 0f)
        c.save()
        val scale = 0.6f + 0.4f * ease
        c.scale(scale, scale, bubbleRect.centerX(), bubbleRect.bottom)
        paint.alpha = 255
        c.drawRoundRect(bubbleRect.left, bubbleRect.top + dp(2f), bubbleRect.right, bubbleRect.bottom + dp(2f), dp(10f), dp(10f), shadow)
        paint.color = Theme.BUBBLE
        c.drawRoundRect(bubbleRect, dp(10f), dp(10f), paint)
        var label = if (k.type == KeyType.T9) k.label else k.label
        if (label.length == 1 && label[0].isLetter() && shiftState > 0) label = label.uppercase()
        paint.color = Theme.TEXT; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT
        paint.textSize = if (label.length > 2) dp(20f) else dp(30f)
        c.drawText(label, bubbleRect.centerX(), bubbleRect.centerY() + paint.textSize * 0.35f, paint)
        c.restore()
        if (t < 1f) postInvalidateOnAnimation()
    }

    private fun drawKey(c: Canvas, k: Key, r: RectF) {
        if (k in pressed && !(showsBubble(k) && bubbleKey === k)) {
            paint.color = Theme.PRESSED
            c.drawRoundRect(r.left + dp(2f), r.top + dp(4f), r.right - dp(2f), r.bottom - dp(4f), dp(6f), dp(6f), paint)
        }
        val cx = r.centerX()
        val hasHint = k.hint != null || k.hintIcon != null
        val baseline = if (hasHint) r.top + r.height() * 0.62f else -1f
        if (k.hint != null) {
            paint.color = Theme.HINT; paint.textSize = dp(13.5f); paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT
            c.drawText(k.hint, cx, r.top + r.height() * 0.30f, paint)
        }
        k.hintIcon?.let { Icons.draw(c, it, cx, r.top + r.height() * 0.24f, dp(14f), Theme.HINT) }
        val icon = when (k.type) {
            KeyType.SHIFT -> when (shiftState) { 1 -> Icon.SHIFT_ON; 2 -> Icon.SHIFT_LOCK; else -> Icon.SHIFT }
            else -> k.icon
        }
        if (k.type == KeyType.ENTER && enterLabel != null) {
            paint.color = Theme.TEXT; paint.textSize = dp(15f); paint.textAlign = Paint.Align.CENTER
            c.drawText(enterLabel!!, cx, r.centerY() + dp(5f), paint)
            return
        }
        if (icon != null) {
            if (icon == Icon.SPACE_MIC) {
                // mic above a "space bracket", as in the reference skin
                Icons.draw(c, Icon.MIC, cx, r.top + r.height() * 0.40f, dp(15f), Theme.TEXT)
                paint.color = Theme.TEXT; paint.strokeWidth = dp(2.4f); paint.strokeCap = Paint.Cap.ROUND
                val hw = minOf(r.width() * 0.36f, dp(28f)); val by = r.top + r.height() * 0.64f
                c.drawLine(cx - hw, by, cx + hw, by, paint)
                c.drawLine(cx - hw, by, cx - hw, by - dp(4f), paint)
                c.drawLine(cx + hw, by, cx + hw, by - dp(4f), paint)
            } else {
                val size = when (icon) { Icon.BACKSPACE -> 30f; Icon.ENTER -> 28f; else -> 22f }
                Icons.draw(c, icon, cx, r.centerY(), dp(size), Theme.TEXT)
            }
            return
        }
        var label = k.label
        if (k.type == KeyType.TEXT && label.length == 1 && label[0].isLetter() && shiftState > 0) label = label.uppercase()
        val inLeft = k in (layout.leftColumn ?: emptyList())
        paint.color = if (k.small) Theme.HINT else Theme.TEXT
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT
        paint.textSize = when {
            inLeft -> dp(17f)
            k.small -> dp(17f)
            layout.name == "numbers" && k.type == KeyType.TEXT -> dp(26f)
            k.type == KeyType.TEXT && label.length == 1 && label[0].isLetter() -> dp(21.5f)
            k.type == KeyType.T9 -> dp(17f)
            label.length >= 3 -> dp(15f)
            else -> dp(19f)
        }
        val y = if (baseline > 0) baseline else r.centerY() + paint.textSize * 0.36f
        c.drawText(label, cx, y, paint)
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
                    pressed.add(k)
                    if (showsBubble(k)) { bubbleKey = k; bubbleStart = android.os.SystemClock.uptimeMillis() }
                    invalidate()
                    listener.onFeedback()
                    scheduleLong(t)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    if (voiceActive) { listener.onVoiceTrack(e.getX(i) + e.rawX - e.x, e.getY(i) + e.rawY - e.y); continue }
                    move(t, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches[e.getPointerId(idx)] ?: return true
                if (voiceActive) {
                    listener.onVoiceTrack(e.getX(idx) + e.rawX - e.x, e.getY(idx) + e.rawY - e.y)
                    voiceActive = false
                    touches.remove(t.id)
                    listener.onVoiceRelease(false)
                    return true
                }
                finish(t, cancelled = false, upX = e.getX(idx), upY = e.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                if (voiceActive) { voiceActive = false; listener.onVoiceRelease(true) }
                touches.values.toList().forEach { finish(it, cancelled = true) }
            }
        }
        return true
    }

    private fun move(t: Touch, x: Float, y: Float) {
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
        if (k.type == KeyType.BACKSPACE) { listener.onKey(k, "", false); return }
        val swipedUp = t.y0 - upY > rowHeightPx * 0.38f
        val alt = swipedUp && k.hint != null
        listener.onKey(k, if (alt) k.hint!! else k.text, alt)
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
                        voiceActive = true
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
                        listener.onKey(k, "", false)
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
                        listener.onKey(k, k.hint, true)
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

    /** Called when the keyboard is hidden mid-gesture. */
    fun abortGestures() {
        if (voiceActive) { voiceActive = false; listener.onVoiceRelease(true) }
        touches.clear(); pressed.clear(); cancelTimers(); invalidate()
    }
}
