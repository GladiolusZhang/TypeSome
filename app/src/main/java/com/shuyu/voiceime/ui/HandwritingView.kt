package com.shuyu.voiceime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/** Writing pad. Strokes are handed to [onInk] shortly after the pen stops. */
@SuppressLint("ViewConstructor")
class HandwritingView(ctx: Context, private val onInk: (List<List<FloatArray>>) -> Unit) : View(ctx) {

    /** Each point is (x, y, timeMillis). */
    private val strokes = ArrayList<ArrayList<FloatArray>>()
    private var current: ArrayList<FloatArray>? = null
    private val paths = ArrayList<Path>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Theme.TEXT
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.HINT; textAlign = Paint.Align.CENTER }
    private val main = Handler(Looper.getMainLooper())
    /** Times are stored relative to the first stroke so they fit in a float. */
    private var base = 0L
    private val recognize = Runnable { if (strokes.isNotEmpty()) onInk(strokes.map { it.toList() }) }

    val isEmpty get() = strokes.isEmpty()

    fun clear() {
        strokes.clear(); paths.clear(); current = null; base = 0L
        main.removeCallbacks(recognize)
        invalidate()
    }

    private val paint0 = Paint()

    override fun onDraw(c: Canvas) {
        paint0.color = Theme.BG; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint0)  // not drawColor: parents don't clip children
        if (strokes.isEmpty()) {
            hint.textSize = dp(16f)
            c.drawText("在此书写，支持连写", width / 2f, height / 2f, hint)
        }
        p.strokeWidth = dp(5f)
        for (path in paths) c.drawPath(path, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (strokes.isEmpty() && e.actionMasked == MotionEvent.ACTION_DOWN) base = SystemClock.uptimeMillis()
        val t = (SystemClock.uptimeMillis() - base).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(recognize)
                current = arrayListOf(floatArrayOf(e.x, e.y, t)).also { strokes.add(it) }
                paths.add(Path().apply { moveTo(e.x, e.y) })
            }
            MotionEvent.ACTION_MOVE -> {
                val cur = current ?: return true
                for (h in 0 until e.historySize) { cur.add(floatArrayOf(e.getHistoricalX(h), e.getHistoricalY(h), (e.getHistoricalEventTime(h) - base).toFloat())); paths.last().lineTo(e.getHistoricalX(h), e.getHistoricalY(h)) }
                cur.add(floatArrayOf(e.x, e.y, t)); paths.last().lineTo(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                main.postDelayed(recognize, 350)
            }
        }
        invalidate()
        return true
    }
}
