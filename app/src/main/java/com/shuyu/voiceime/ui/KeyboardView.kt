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
    /** Keys drawn with the darker "function" cap. */
    private val fnKeys = HashSet<Key>()
    /** When each key was released, for the fade-out of the pressed shade. */
    private val releasedAt = HashMap<Key, Long>()
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
        fnKeys.clear()
        val lastRow = layout.rows.lastIndex
        for ((ri, row) in layout.rows.withIndex()) for (k in row) {
            val fn = when (k.type) {
                KeyType.TEXT -> layout.name != "numbers" && ri == lastRow
                KeyType.T9, KeyType.SPACE, KeyType.PICK, KeyType.ENTER -> false
                else -> true
            }
            if (fn) fnKeys.add(k)
        }
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

    private val paint0 = Paint()

    override fun onDraw(c: Canvas) {
        paint0.color = Theme.BG; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint0)  // not drawColor: parents don't clip children
        val now = android.os.SystemClock.uptimeMillis()
        var animating = false
        if (Theme.flat) {
            paint.color = Theme.SEP
            paint.strokeWidth = dp(0.8f)
            val rowsTop = dp(3f)
            val sepLeft = if (layout.leftColumn != null) leftRect.right + dp(10f) else dp(5f)
            val sepRight = if (layout.leftColumn != null) rects[layout.rows[0].last()]!!.left - dp(10f) else width - dp(5f)
            for (i in 1 until layout.rows.size) {
                val y = rowsTop + i * rowHeightPx
                c.drawLine(sepLeft, y, sepRight, y, paint)
            }
            if (layout.leftColumn != null) c.drawLine(leftRect.right, leftRect.top + dp(10f), leftRect.right, leftRect.top + rowHeightPx * 2.6f, paint)
        }
        if (layout.leftColumn != null) {
            if (!Theme.flat) {
                // the whole column is one tall function cap
                capRect(leftRect)
                paint.color = Theme.KEY_SHADOW
                c.drawRoundRect(cap.left, cap.top + dp(1.2f), cap.right, cap.bottom + dp(1.2f), dp(7f), dp(7f), paint)
                paint.color = Theme.KEY_FN
                c.drawRoundRect(cap, dp(7f), dp(7f), paint)
            }
            c.save()
            if (Theme.flat) c.clipRect(leftRect) else { capRect(leftRect); c.clipRect(cap) }
            c.translate(0f, -leftScroll)
            layout.leftColumn!!.forEach { if (drawKey(c, it, rects[it]!!, now, true)) animating = true }
            c.restore()
        }
        for (row in layout.rows) for (k in row) if (drawKey(c, k, rects[k] ?: continue, now, false)) animating = true
        drawBubble(c)
        if (animating) postInvalidateOnAnimation()
    }

    private val cap = RectF()
    private fun capRect(r: RectF) = cap.set(r.left + dp(3f), r.top + dp(4f), r.right - dp(3f), r.bottom - dp(5f))

    /** 0..1 pressed shade, fading out over 160ms after release. */
    private fun pressLevel(k: Key, now: Long): Float {
        if (k in pressed) return 1f
        val t = releasedAt[k] ?: return 0f
        val f = 1f - (now - t) / 160f
        if (f <= 0f) { releasedAt.remove(k); return 0f }
        return f
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
        paint.color = Theme.LETTER; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT
        paint.textSize = if (label.length > 2) dp(20f) else dp(30f)
        c.drawText(label, bubbleRect.centerX(), bubbleRect.centerY() + paint.textSize * 0.35f, paint)
        c.restore()
        if (t < 1f) postInvalidateOnAnimation()
    }

    /** Draws one key; returns true while its press shade is still animating. */
    private fun drawKey(c: Canvas, k: Key, r: RectF, now: Long, inLeft: Boolean): Boolean {
        val level = pressLevel(k, now)
        val accent = k.type == KeyType.ENTER && !Theme.flat
        val fg = if (accent) Theme.ON_ACCENT else Theme.TEXT
        if (Theme.flat) {
            if (level > 0f) {
                paint.color = Theme.PRESSED; paint.alpha = (255 * level).toInt()
                c.drawRoundRect(r.left + dp(2f), r.top + dp(4f), r.right - dp(2f), r.bottom - dp(4f), dp(6f), dp(6f), paint)
                paint.alpha = 255
            }
        } else if (inLeft) {
            if (level > 0f) {
                paint.color = blend(Theme.KEY_FN, Theme.KEY_FN_DOWN, level)
                c.drawRect(r.left + dp(3f), r.top, r.right - dp(3f), r.bottom, paint)
            }
        } else {
            capRect(r)
            val fn = k in fnKeys
            val base = when { accent -> Theme.ACCENT; fn -> Theme.KEY_FN; else -> Theme.KEY }
            val down = when { accent -> blend(Theme.ACCENT, Color.BLACK, 0.18f); fn -> Theme.KEY_FN_DOWN; else -> Theme.KEY_DOWN }
            paint.color = Theme.KEY_SHADOW
            c.drawRoundRect(cap.left, cap.top + dp(1.2f), cap.right, cap.bottom + dp(1.2f), dp(7f), dp(7f), paint)
            paint.color = blend(base, down, level)
            c.drawRoundRect(cap, dp(7f), dp(7f), paint)
        }
        drawContent(c, k, r, fg, inLeft)
        return level > 0f && k !in pressed
    }

    private fun accent(k: Key) = k.type == KeyType.ENTER && !Theme.flat

    private fun drawContent(c: Canvas, k: Key, r: RectF, fg: Int, inLeft: Boolean) {
        val cx = r.centerX()
        // Classic skin and 9-key digits: hint centered above the label. Key caps: hint in the top-right corner.
        val stacked = Theme.flat || k.type == KeyType.T9
        val hasHint = k.hint != null || k.hintIcon != null
        val baseline = if (hasHint && stacked) r.top + r.height() * 0.62f else -1f
        paint.typeface = Typeface.DEFAULT
        if (k.hint != null) {
            paint.color = Theme.HINT
            if (stacked) {
                // the small "En" over 中 is quieter than the symbol hints over letters
                val quiet = k.type == KeyType.LANG
                paint.textSize = dp(if (Theme.flat) (if (quiet) 11.5f else 13.5f) else 11.5f); paint.textAlign = Paint.Align.CENTER
                c.drawText(k.hint, cx, r.top + r.height() * (if (Theme.flat) 0.30f else 0.34f), paint)
            } else {
                paint.textSize = dp(10.5f); paint.textAlign = Paint.Align.RIGHT
                c.drawText(k.hint, r.right - dp(8f), r.top + dp(17f), paint)
            }
        }
        k.hintIcon?.let {
            if (stacked) Icons.draw(c, it, cx, r.top + r.height() * 0.26f, dp(12f), Theme.HINT)
            else Icons.draw(c, it, r.right - dp(13f), r.top + dp(13.5f), dp(10f), Theme.HINT)
        }
        val icon = when (k.type) {
            KeyType.SHIFT -> when (shiftState) { 1 -> Icon.SHIFT_ON; 2 -> Icon.SHIFT_LOCK; else -> Icon.SHIFT }
            else -> k.icon
        }
        if (k.type == KeyType.ENTER && enterLabel != null) {
            paint.color = fg; paint.textSize = dp(15f); paint.textAlign = Paint.Align.CENTER
            c.drawText(enterLabel!!, cx, r.centerY() + dp(5f) - (if (Theme.flat) 0f else dp(0.5f)), paint)
            return
        }
        val cy = if (Theme.flat) r.centerY() else r.centerY() - dp(0.5f)
        if (icon != null) {
            if (icon == Icon.SPACE_MIC) {
                if (Theme.flat) {
                    // mic above a "space bracket", as in the reference skin
                    Icons.draw(c, Icon.MIC, cx, r.top + r.height() * 0.40f, dp(15f), Theme.TEXT)
                    paint.color = Theme.TEXT; paint.strokeWidth = dp(2.4f); paint.strokeCap = Paint.Cap.ROUND
                    val hw = minOf(r.width() * 0.36f, dp(28f)); val by = r.top + r.height() * 0.64f
                    c.drawLine(cx - hw, by, cx + hw, by, paint)
                    c.drawLine(cx - hw, by, cx - hw, by - dp(4f), paint)
                    c.drawLine(cx + hw, by, cx + hw, by - dp(4f), paint)
                } else if (r.width() > dp(110f)) {
                    paint.textSize = dp(13f); paint.textAlign = Paint.Align.LEFT; paint.color = Theme.HINT
                    val label = "长按说话"
                    val w = dp(16f) + paint.measureText(label)
                    Icons.draw(c, Icon.MIC, cx - w / 2 + dp(6f), cy, dp(15f), Theme.HINT)
                    c.drawText(label, cx - w / 2 + dp(16f), cy + dp(4.5f), paint)
                } else Icons.draw(c, Icon.MIC, cx, cy, dp(17f), Theme.HINT)
            } else {
                val size = when (icon) { Icon.BACKSPACE -> if (Theme.flat) 30f else 24f; Icon.ENTER -> if (Theme.flat) 28f else 24f; else -> 22f }
                Icons.draw(c, icon, cx, cy, dp(size), fg)
            }
            return
        }
        var label = k.label
        if (k.type == KeyType.TEXT && label.length == 1 && label[0].isLetter() && shiftState > 0) label = label.uppercase()
        val letter = !inLeft && (k.type == KeyType.T9 || (k.type == KeyType.TEXT && (Theme.flat || k !in fnKeys)))
        paint.color = when { k.small -> Theme.HINT; accent(k) -> fg; letter -> Theme.LETTER; else -> fg }
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = when {
            inLeft -> dp(17f)
            k.small -> dp(if (Theme.flat) 17f else 15f)
            layout.name == "numbers" && k.type == KeyType.TEXT -> dp(26f)
            k.type == KeyType.TEXT && label.length == 1 && label[0].isLetter() -> dp(21.5f)
            k.type == KeyType.T9 -> dp(17f)
            k.type == KeyType.LANG || k.type == KeyType.SYMBOLS -> dp(16.5f)
            label.length >= 3 -> dp(15.5f)
            k in fnKeys && !Theme.flat -> dp(17f)
            else -> dp(19f)
        }
        val y = if (baseline > 0) baseline else cy + paint.textSize * 0.36f
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
        if (pressed.remove(k)) releasedAt[k] = android.os.SystemClock.uptimeMillis()
        invalidate()
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
