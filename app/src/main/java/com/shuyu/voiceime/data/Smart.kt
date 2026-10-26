package com.shuyu.voiceime.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Contact names -> phone numbers. */
object Contacts {
    @Volatile var phones: Map<String, List<String>> = emptyMap(); private set

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(ctx: Context): Map<String, List<String>> {
        if (!granted(ctx)) return emptyMap()
        val map = LinkedHashMap<String, MutableList<String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim() ?: continue
                val num = c.getString(1)?.replace(Regex("[\\s-]"), "") ?: continue
                if (name.isEmpty() || num.isEmpty()) continue
                val list = map.getOrPut(name) { ArrayList() }
                if (num !in list) list.add(num)
            }
        }
        phones = map
        return map
    }
}

/** Finds verification codes in recent SMS (needs READ_SMS) or in the clipboard. */
object Otp {
    private val KEYWORDS = Regex("验证码|校验码|动态码|确认码|验证代码|code|Code|CODE|OTP|激活码|口令")
    private val CODE = Regex("(?<![0-9A-Za-z])([0-9]{4,8}|[A-Za-z0-9]{4,8})(?![0-9A-Za-z])")

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Latest code seen in a notification, with the time it arrived. */
    @Volatile var notified: Pair<String, Long>? = null; private set
    /** Called on the main thread when a new code arrives from a notification. */
    @Volatile var onNew: ((String) -> Unit)? = null

    fun offer(code: String) {
        notified = code to System.currentTimeMillis()
        android.os.Handler(android.os.Looper.getMainLooper()).post { onNew?.invoke(code) }
    }

    fun latestFromNotification(maxAgeMs: Long = 5 * 60_000): String? =
        notified?.takeIf { System.currentTimeMillis() - it.second < maxAgeMs }?.first

    fun extract(text: String): String? {
        if (!KEYWORDS.containsMatchIn(text)) return null
        val kw = KEYWORDS.find(text)!!.range.first
        // Prefer digit codes closest to the keyword.
        return CODE.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.any(Char::isDigit) && !(it.length >= 7 && it.all(Char::isDigit) && text.contains("1$it")) }
            .minByOrNull { c -> kotlin.math.abs(text.indexOf(c) - kw) + if (c.all(Char::isDigit)) 0 else 50 }
    }

    /** Latest code received within [maxAgeMs]. */
    fun latestFromSms(ctx: Context, maxAgeMs: Long = 5 * 60_000): String? {
        if (!granted(ctx)) return null
        val since = System.currentTimeMillis() - maxAgeMs
        return try {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("body", "date"),
                "date > ?", arrayOf(since.toString()), "date DESC")?.use { c ->
                while (c.moveToNext()) {
                    val code = extract(c.getString(0) ?: continue)
                    if (code != null) return code
                }
                null
            }
        } catch (_: Exception) { null }
    }
}

/** Tiny arithmetic evaluator for the candidate bar (supports + - * / × ÷ % ^ and parentheses). */
object Calc {
    private val TAIL = Regex("[0-9.()+\\-*/×÷%^＋－＊／（）]+$")

    /** Returns (expression, result) when the text before the cursor ends with a computable expression. */
    fun tryEval(before: String): Pair<String, String>? {
        val m = TAIL.find(before) ?: return null
        var expr = m.value
        while (expr.isNotEmpty() && (expr[0] in "+*/×÷%^)＋＊／）")) expr = expr.substring(1)
        if (expr.length < 3 || expr.none { it in "+-*/×÷%^＋－＊／" } || !expr.last().let { it.isDigit() || it == ')' || it == '）' || it == '%' }) return null
        if (expr.all { it.isDigit() || it == '-' } && expr.count { it == '-' } >= 2) return null // looks like a date / phone
        val v = try { Parser(normalize(expr)).parse() } catch (_: Exception) { return null } ?: return null
        return expr to format(v)
    }

    private fun normalize(s: String) = s.replace('×', '*').replace('÷', '/').replace('＋', '+').replace('－', '-')
        .replace('＊', '*').replace('／', '/').replace('（', '(').replace('）', ')')

    private fun format(v: BigDecimal): String {
        val r = v.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        return if (r.scale() < 0) r.setScale(0).toPlainString() else r.toPlainString()
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): BigDecimal? { val v = expr(); return if (i == s.length) v else null }
        fun expr(): BigDecimal {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val r = term()
                v = if (op == '+') v.add(r) else v.subtract(r)
            }
            return v
        }
        fun term(): BigDecimal {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]; val r = power()
                v = if (op == '*') v.multiply(r) else v.divide(r, MathContext.DECIMAL64)
            }
            return v
        }
        fun power(): BigDecimal {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; val e = power(); return b.pow(e.intValueExact(), MathContext.DECIMAL64) }
            return b
        }
        fun unary(): BigDecimal {
            if (i < s.length && s[i] == '-') { i++; return unary().negate() }
            var v = atom()
            while (i < s.length && s[i] == '%') { i++; v = v.divide(BigDecimal(100)) }
            return v
        }
        fun atom(): BigDecimal {
            if (i < s.length && s[i] == '(') {
                i++; val v = expr()
                if (i < s.length && s[i] == ')') i++ else throw IllegalArgumentException()
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException()
            return BigDecimal(s.substring(start, i))
        }
    }
}
