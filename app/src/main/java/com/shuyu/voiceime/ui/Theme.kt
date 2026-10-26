package com.shuyu.voiceime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Keyboard colors.
 *  - seamless (default): flat, no key caps, thin row separators; pure white by day, gray ("classic") at night
 *  - white / classic: the two seamless variants fixed
 *  - light / dark / auto: rounded key caps on a soft background
 */
object Theme {
    var BG = 0; var BAR = 0; var BAR_LINE = 0
    /** Icons and function-key labels. */
    var TEXT = 0
    /** Letters and digits on the keys (darker than icons on the seamless skins). */
    var LETTER = 0
    var HINT = 0; var SEP = 0; var PRESSED = 0
    var ACCENT = 0; var ON_ACCENT = Color.WHITE
    var CARD = 0; var OUTLINE = 0; var BUBBLE = 0
    /** Key caps (letter keys), function keys, and their pressed shades. */
    var KEY = 0; var KEY_FN = 0; var KEY_DOWN = 0; var KEY_FN_DOWN = 0; var KEY_SHADOW = 0
    /** Classic flat skin: no key caps. */
    var flat = false; private set
    var dark = false; private set
    /** Changes whenever the effective skin changes, so the service knows to rebuild its views. */
    var signature = ""; private set

    private fun c(s: String) = Color.parseColor(s)

    /** skin: "seamless" | "white" | "classic" | "auto" | "light" | "dark" */
    fun apply(ctx: Context, skin: String) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        palette(when (skin) {
            "seamless" -> if (night) "classic" else "white"
            "auto" -> if (night) "dark" else "light"
            else -> skin
        })
    }

    private fun palette(s: String) {
        signature = s
        flat = s == "classic" || s == "white"; dark = s == "dark"
        when (s) {
            "dark" -> {
                BG = c("#1B1B1D"); BAR = BG; BAR_LINE = c("#2A2A2D")
                KEY = c("#3B3B3F"); KEY_FN = c("#2A2A2E"); KEY_DOWN = c("#56565C"); KEY_FN_DOWN = c("#44444A"); KEY_SHADOW = c("#0E0E10")
                TEXT = c("#EDEDF0"); LETTER = TEXT; HINT = c("#8E8E95"); SEP = c("#333337"); PRESSED = c("#38383D")
                ACCENT = c("#4C8DFF"); CARD = c("#2C2C30"); OUTLINE = c("#4A4A50"); BUBBLE = c("#4A4A50")
            }
            // sampled from the user's screenshot of the night (gray) skin
            "classic" -> {
                BG = c("#B0B0B0"); BAR = BG; BAR_LINE = c("#999999")
                KEY = BG; KEY_FN = BG; KEY_DOWN = c("#9C9C9C"); KEY_FN_DOWN = KEY_DOWN; KEY_SHADOW = 0
                TEXT = c("#4A4E5A"); LETTER = c("#2A2E3A"); HINT = c("#7B7B7B"); SEP = c("#9D9D9D"); PRESSED = c("#9C9C9C")
                ACCENT = c("#2F7BEA"); CARD = c("#C2C2C2"); OUTLINE = c("#8A8A8A"); BUBBLE = c("#F4F4F6")
            }
            "white" -> {
                BG = Color.WHITE; BAR = BG; BAR_LINE = c("#E3E3E3")
                KEY = BG; KEY_FN = BG; KEY_DOWN = c("#E8E9EB"); KEY_FN_DOWN = KEY_DOWN; KEY_SHADOW = 0
                TEXT = c("#5C6370"); LETTER = c("#333844"); HINT = c("#A3A8B0"); SEP = c("#E6E6E6"); PRESSED = c("#E8E9EB")
                ACCENT = c("#2F7BEA"); CARD = c("#F1F2F4"); OUTLINE = c("#D2D4D8"); BUBBLE = Color.WHITE
            }
            else -> {
                BG = c("#E4E6EB"); BAR = BG; BAR_LINE = c("#D5D8DE")
                KEY = Color.WHITE; KEY_FN = c("#C9CDD5"); KEY_DOWN = c("#D9DCE2"); KEY_FN_DOWN = c("#B3B8C2"); KEY_SHADOW = c("#A9AEB8")
                TEXT = c("#1D2129"); LETTER = TEXT; HINT = c("#868C96"); SEP = c("#D0D3D9"); PRESSED = c("#D3D6DC")
                ACCENT = c("#2F6FEB"); CARD = Color.WHITE; OUTLINE = c("#C3C7CF"); BUBBLE = Color.WHITE
            }
        }
    }

    init { palette("white") }
}

/** Linear blend of two ARGB colors. */
fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xff) + (((b shr s) and 0xff) - ((a shr s) and 0xff)) * t).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Float): Int = context.dp(v).toInt()

enum class Icon { MIC, SHIFT, SHIFT_ON, SHIFT_LOCK, BACKSPACE, ENTER, KEYBOARD, CURSOR, SMILE, HIDE, LOGO,
    GLOBE, LOCK, UNLOCK, UP, DOWN, LEFT, RIGHT, CHEVRON_LEFT, CHEVRON_UP, CHEVRON_DOWN, HOME, END, CLOSE, CLOCK, SPACE_MIC }

/** Hand-drawn vector icons so the keyboard needs no image resources. */
object Icons {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, badge: String? = null) {
        val s = size / 24f
        p.color = color
        p.strokeWidth = 2f * s
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.style = Paint.Style.STROKE
        c.save()
        c.translate(cx - 12 * s, cy - 12 * s)
        c.scale(s, s)
        p.strokeWidth = 2f
        when (icon) {
            Icon.MIC, Icon.SPACE_MIC -> {
                p.style = Paint.Style.FILL
                r.set(9.5f, 3f, 14.5f, 14f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f
                r.set(7f, 7f, 17f, 16.5f); c.drawArc(r, 0f, 180f, false, p)
                c.drawLine(12f, 16.5f, 12f, 19.5f, p)
                if (icon == Icon.MIC) c.drawLine(9f, 19.5f, 15f, 19.5f, p)
            }
            Icon.SHIFT, Icon.SHIFT_ON, Icon.SHIFT_LOCK -> {
                path.reset()
                path.moveTo(12f, 3.5f); path.lineTo(21f, 12.5f); path.lineTo(16f, 12.5f); path.lineTo(16f, 19f)
                path.lineTo(8f, 19f); path.lineTo(8f, 12.5f); path.lineTo(3f, 12.5f); path.close()
                if (icon == Icon.SHIFT && !Theme.flat) { p.strokeWidth = 1.7f; c.drawPath(path, p) }
                else if (icon == Icon.SHIFT) { p.style = Paint.Style.FILL; c.drawPath(path, p) }
                else { p.style = Paint.Style.FILL; p.color = Theme.ACCENT; c.drawPath(path, p) }
                if (icon == Icon.SHIFT_LOCK) { r.set(7f, 20.5f, 17f, 22.5f); c.drawRect(r, p) }
            }
            Icon.BACKSPACE -> {
                path.reset()
                path.moveTo(2f, 12f); path.lineTo(8f, 5.5f); path.lineTo(22f, 5.5f); path.lineTo(22f, 18.5f); path.lineTo(8f, 18.5f); path.close()
                if (Theme.flat) {
                    // seamless skins: solid key with the cross cut out, as in the reference
                    p.style = Paint.Style.FILL; c.drawPath(path, p)
                    p.color = Theme.BG; p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                    c.drawLine(11.5f, 9f, 17.5f, 15f, p); c.drawLine(17.5f, 9f, 11.5f, 15f, p)
                } else {
                    p.strokeWidth = 1.7f; c.drawPath(path, p)
                    c.drawLine(12f, 9.5f, 17f, 14.5f, p); c.drawLine(17f, 9.5f, 12f, 14.5f, p)
                }
            }
            Icon.ENTER -> {
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(2.5f, 13f); path.lineTo(9f, 7.5f); path.lineTo(9f, 11f); path.lineTo(18.5f, 11f); path.lineTo(18.5f, 7f)
                path.lineTo(21.5f, 7f); path.lineTo(21.5f, 15f); path.lineTo(9f, 15f); path.lineTo(9f, 18.5f); path.close()
                c.drawPath(path, p)
            }
            Icon.KEYBOARD -> {
                r.set(2f, 5f, 22f, 19f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                p.style = Paint.Style.FILL
                for (row in 0..1) for (col in 0..4) { r.set(4.8f + col * 3.2f, 8f + row * 3f, 6.6f + col * 3.2f, 9.6f + row * 3f); c.drawRect(r, p) }
                r.set(7.5f, 14.5f, 16.5f, 16f); c.drawRect(r, p)
                if (badge != null) {
                    p.color = Theme.TEXT; r.set(14f, 13f, 24f, 23f); c.drawRoundRect(r, 2f, 2f, p)
                    p.color = Color.WHITE; p.textSize = 7f; p.textAlign = Paint.Align.CENTER
                    c.drawText(badge, 19f, 20.6f, p)
                }
            }
            Icon.CURSOR -> {
                c.drawLine(12f, 4f, 12f, 20f, p)
                c.drawLine(9.5f, 4f, 14.5f, 4f, p); c.drawLine(9.5f, 20f, 14.5f, 20f, p)
                c.drawLine(6f, 9f, 3f, 12f, p); c.drawLine(3f, 12f, 6f, 15f, p)
                c.drawLine(18f, 9f, 21f, 12f, p); c.drawLine(21f, 12f, 18f, 15f, p)
            }
            Icon.SMILE -> {
                c.drawCircle(12f, 12f, 9.5f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(8.8f, 9.8f, 1.3f, p); c.drawCircle(15.2f, 9.8f, 1.3f, p)
                p.style = Paint.Style.STROKE
                r.set(7.5f, 9.5f, 16.5f, 16.5f); c.drawArc(r, 20f, 140f, false, p)
            }
            Icon.HIDE -> {
                p.style = Paint.Style.FILL
                r.set(2f, 3.5f, 22f, 20.5f); c.drawRoundRect(r, 3f, 3f, p)
                p.color = Theme.BAR
                path.reset(); path.moveTo(7f, 10f); path.lineTo(17f, 10f); path.lineTo(12f, 15f); path.close()
                c.drawPath(path, p)
            }
            Icon.LOGO -> {
                // TypeSome mark: "T" with two sound waves
                p.strokeWidth = 3.2f
                c.drawLine(3.5f, 6f, 13.5f, 6f, p); c.drawLine(8.5f, 6f, 8.5f, 19f, p)
                p.strokeWidth = 2f
                r.set(10.5f, 8.5f, 16.5f, 15.5f); c.drawArc(r, -60f, 120f, false, p)
                r.set(11.5f, 5f, 21.5f, 19f); c.drawArc(r, -60f, 120f, false, p)
            }
            Icon.GLOBE -> {
                c.drawCircle(12f, 12f, 9.5f, p)
                r.set(7.5f, 2.5f, 16.5f, 21.5f); c.drawOval(r, p)
                c.drawLine(2.5f, 12f, 21.5f, 12f, p); c.drawLine(12f, 2.5f, 12f, 21.5f, p)
                c.drawLine(4f, 7f, 20f, 7f, p); c.drawLine(4f, 17f, 20f, 17f, p)
            }
            Icon.LOCK, Icon.UNLOCK -> {
                p.style = Paint.Style.FILL
                r.set(4f, 11f, 18f, 21f); c.drawRoundRect(r, 2f, 2f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f
                if (icon == Icon.LOCK) { r.set(7f, 4f, 15f, 14f); c.drawArc(r, 180f, 180f, false, p); c.drawLine(7f, 9f, 7f, 11f, p); c.drawLine(15f, 9f, 15f, 11f, p) }
                else { r.set(13f, 3f, 21f, 13f); c.drawArc(r, 180f, 180f, false, p); c.drawLine(13f, 8f, 13f, 11f, p) }
                p.color = Theme.BG; p.style = Paint.Style.FILL; c.drawCircle(11f, 16f, 1.5f, p)
            }
            Icon.UP, Icon.DOWN -> {
                p.style = Paint.Style.FILL
                path.reset()
                if (icon == Icon.UP) { path.moveTo(4f, 16f); path.lineTo(20f, 16f); path.lineTo(12f, 8f) }
                else { path.moveTo(4f, 8f); path.lineTo(20f, 8f); path.lineTo(12f, 16f) }
                path.close(); c.drawPath(path, p)
            }
            Icon.LEFT, Icon.CHEVRON_LEFT -> { c.drawLine(15f, 5f, 8f, 12f, p); c.drawLine(8f, 12f, 15f, 19f, p) }
            Icon.RIGHT -> { c.drawLine(9f, 5f, 16f, 12f, p); c.drawLine(16f, 12f, 9f, 19f, p) }
            Icon.CHEVRON_UP -> { c.drawLine(5f, 15f, 12f, 8f, p); c.drawLine(12f, 8f, 19f, 15f, p) }
            Icon.CHEVRON_DOWN -> { c.drawLine(5f, 9f, 12f, 16f, p); c.drawLine(12f, 16f, 19f, 9f, p) }
            Icon.HOME -> { c.drawLine(4f, 5f, 4f, 19f, p); c.drawLine(4f, 12f, 20f, 12f, p); c.drawLine(4f, 12f, 10f, 6f, p); c.drawLine(4f, 12f, 10f, 18f, p) }
            Icon.END -> { c.drawLine(20f, 5f, 20f, 19f, p); c.drawLine(4f, 12f, 20f, 12f, p); c.drawLine(20f, 12f, 14f, 6f, p); c.drawLine(20f, 12f, 14f, 18f, p) }
            Icon.CLOSE -> { c.drawCircle(12f, 12f, 9.5f, p); c.drawLine(8.5f, 8.5f, 15.5f, 15.5f, p); c.drawLine(15.5f, 8.5f, 8.5f, 15.5f, p) }
            Icon.CLOCK -> { c.drawCircle(12f, 12f, 9.5f, p); c.drawLine(12f, 6.5f, 12f, 12f, p); c.drawLine(12f, 12f, 16f, 14f, p) }
        }
        c.restore()
    }
}

/** A small view that draws one icon (toolbar buttons, panel buttons). */
class IconView(ctx: Context, var icon: Icon, var color: Int = Theme.TEXT, private val sizeDp: Float = 26f, var badge: String? = null) : View(ctx) {
    private var pressedNow = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Theme.PRESSED }
    override fun onDraw(canvas: Canvas) {
        if (pressedNow) canvas.drawRoundRect(dp(4f), dp(4f), width - dp(4f), height - dp(4f), dp(8f), dp(8f), bg)
        Icons.draw(canvas, icon, width / 2f, height / 2f, dp(sizeDp), color, badge)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedNow = true; invalidate() }
            MotionEvent.ACTION_UP -> { pressedNow = false; invalidate(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick() }
            MotionEvent.ACTION_CANCEL -> { pressedNow = false; invalidate() }
        }
        return true
    }
}
