package com.shuyu.voiceime

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.shuyu.voiceime.data.Calc
import com.shuyu.voiceime.data.Contacts
import com.shuyu.voiceime.data.Otp
import com.shuyu.voiceime.pinyin.Dictionary
import com.shuyu.voiceime.pinyin.PinyinEngine
import com.shuyu.voiceime.ui.CandidateGridPanel
import com.shuyu.voiceime.ui.ClipboardPanel
import com.shuyu.voiceime.ui.EditPanel
import com.shuyu.voiceime.ui.EmojiPanel
import com.shuyu.voiceime.ui.Key
import com.shuyu.voiceime.ui.KeyType
import com.shuyu.voiceime.ui.KeyboardView
import com.shuyu.voiceime.ui.Layouts
import com.shuyu.voiceime.ui.ModePanel
import com.shuyu.voiceime.ui.PanelHost
import com.shuyu.voiceime.ui.SymbolPanel
import com.shuyu.voiceime.ui.Theme
import com.shuyu.voiceime.ui.TopBar
import com.shuyu.voiceime.ui.dp
import com.shuyu.voiceime.voice.VoiceController
import org.json.JSONArray

class ImeService : android.inputmethodservice.InputMethodService(), KeyboardView.Listener, TopBar.Listener, PanelHost, VoiceController.Ui {

    private lateinit var prefs: Prefs
    private lateinit var dict: Dictionary
    private lateinit var engine: PinyinEngine
    private lateinit var voice: VoiceController
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var topBar: TopBar
    private lateinit var keyboard: KeyboardView
    private var barHeight = 0

    private var chinese = true
    private var numbers = false
    private var shift = 0
    private var lastShiftTap = 0L
    private var editorInfo: EditorInfo? = null

    // smart suggestions currently shown in the candidate strip
    private enum class Smart { NONE, CALC, PHONE, OTP, ASSOC }
    private var smart = Smart.NONE
    private var smartActions: List<() -> Unit> = emptyList()
    private var usedOtp: String? = null

    private val clipHistory = ArrayList<String>()
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
    private var smsObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dict = Core.dictionary(this)
        engine = PinyinEngine(dict)
        voice = VoiceController(this, this)
        chinese = prefs.chineseMode
        loadClipHistory()
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        voice.release()
        dict.flush()
        super.onDestroy()
    }

    // ================================================================ views

    override fun onCreateInputView(): View {
        barHeight = dp(48f).toInt()
        root = FrameLayout(this)
        root.setBackgroundColor(Theme.BG)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBar = TopBar(this, this)
        keyboard = KeyboardView(this, this)
        keyboard.rowHeightPx = dp(54f) * prefs.keyboardHeightPercent / 100f
        page.addView(topBar, LinearLayout.LayoutParams(-1, barHeight))
        page.addView(keyboard, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -2))
        panelHost = FrameLayout(this)
        panelHost.visibility = View.GONE
        root.addView(panelHost, FrameLayout.LayoutParams(-1, -1))
        window.window?.navigationBarColor = Theme.BG
        applyLayout()
        return root
    }

    private fun applyLayout() {
        if (!::keyboard.isInitialized) return
        keyboard.layout = if (numbers) Layouts.numbers() else Layouts.qwerty(chinese)
        keyboard.shiftState = shift
        updateVoiceLabels()
    }

    private fun updateVoiceLabels() {
        keyboard.voiceModeLabels = arrayOf(
            "语音输入" to (if (prefs.asrEngine == "offline") "离线 · 直接转文字" else "直接转文字"),
            "智能整理" to "去口水词 · 更有条理",
            "翻译" to "说中文 → ${prefs.targetLanguage}")
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info
        prefs = Prefs(this)
        engine.fuzzy = prefs.fuzzyPinyin
        engine.autoCorrect = prefs.autoCorrect
        engine.learning = prefs.learnWords
        engine.reset()
        closePanel()
        shift = 0
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        numbers = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        val englishOnly = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        chinese = if (englishOnly) false else prefs.chineseMode
        keyboard.rowHeightPx = dp(54f) * prefs.keyboardHeightPercent / 100f
        keyboard.enterLabel = enterLabel(info)
        applyLayout()
        if (!topBar.isStatusShowing) topBar.showToolbar()
        smart = Smart.NONE
        checkOtp()
        watchSms()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (::keyboard.isInitialized) keyboard.abortGestures()
        if (voice.isRecording) voice.cancel()
        engine.reset()
        dict.flush()
    }

    override fun onEvaluateFullscreenMode() = false

    private fun enterLabel(info: EditorInfo): String? {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> null
        }
    }

    // ================================================================ key handling

    override fun onFeedback() {
        if (prefs.vibrate && ::keyboard.isInitialized) keyboard.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun feedback() = onFeedback()

    override fun onKey(key: Key, output: String) {
        when (key.type) {
            KeyType.TEXT -> typeText(output)
            KeyType.BACKSPACE -> backspace()
            KeyType.SPACE -> {
                if (engine.isComposing) onCandidate(0) else commit(" ")
            }
            KeyType.ENTER -> enter()
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == 0 && now - lastShiftTap < 300 -> 2
                    shift == 0 -> 1
                    shift == 1 && now - lastShiftTap < 300 -> 2
                    else -> 0
                }
                lastShiftTap = now
                keyboard.shiftState = shift
            }
            KeyType.LANG -> {
                finishComposing(raw = true)
                chinese = !chinese
                prefs.chineseMode = chinese
                applyLayout()
            }
            KeyType.NUM_PAD -> { finishComposing(raw = false); numbers = true; applyLayout() }
            KeyType.BACK -> { numbers = false; applyLayout() }
            KeyType.SYMBOLS -> { finishComposing(raw = false); showPanel("symbols") }
        }
    }

    private fun typeText(s: String) {
        if (s.isEmpty()) return
        val c = s[0]
        val isLetter = s.length == 1 && c in 'a'..'z'
        if (isLetter && chinese && !numbers && shift == 0) {
            engine.input(c)
            showComposing()
            return
        }
        if (isLetter) {
            finishComposing(raw = false)
            commit(if (shift > 0) s.uppercase() else s)
            if (shift == 1) { shift = 0; keyboard.shiftState = 0 }
            return
        }
        if (s == "'" && engine.isComposing) { engine.input('\''); showComposing(); return }
        if (engine.isComposing) finishComposing(raw = false)
        commit(if (chinese && !numbers) toChinesePunct(s) else s)
    }

    private fun toChinesePunct(s: String) = when (s) {
        "?" -> "？"; "!" -> "！"; ":" -> "："; ";" -> "；"; "(" -> "（"; ")" -> "）"; "," -> "，"; "." -> "。"
        else -> s
    }

    override fun backspace() {
        if (engine.isComposing) {
            engine.backspace()
            if (engine.isComposing) showComposing() else topBar.showToolbar()
            return
        }
        if (smart != Smart.NONE && smart != Smart.CALC) clearSmart()
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else {
            // delete a whole surrogate pair / emoji
            val before = ic.getTextBeforeCursor(2, 0)
            val n = if (before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
            if (before.isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) else ic.deleteSurroundingText(n, 0)
        }
        main.post { refreshCalc() }
    }

    private fun enter() {
        if (engine.isComposing) { finishComposing(raw = true); return }
        val ic = currentInputConnection ?: return
        val info = editorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onCursorMove(delta: Int) {
        if (engine.isComposing) { engine.moveCursor(delta); showComposing(); return }
        sendDownUpKeyEvents(if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
    }

    override fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
        main.post { refreshCalc() }
    }

    /** Ends composing: either the first candidate or the typed letters. */
    private fun finishComposing(raw: Boolean) {
        if (!engine.isComposing) return
        if (raw) commit(engine.commitRaw())
        else { val t = engine.select(0) ?: engine.commitRaw(); commit(t) }
        topBar.showToolbar()
    }

    // ================================================================ candidates

    private fun showComposing() {
        val (text, caret) = engine.displayText()
        topBar.showCandidates(text, caret, engine.candidates.take(60).map { it.text })
        smart = Smart.NONE
    }

    override fun onCandidate(index: Int) {
        val done = engine.select(index)
        if (done != null) {
            commit(done)
            topBar.showToolbar()
            afterWordCommitted(done)
        } else showComposing()
    }

    override fun onExpandCandidates() {
        if (!engine.isComposing) return
        val cands = engine.candidates.map { it.text }
        showPanelView(CandidateGridPanel(this, this, cands) { i -> closePanel(); onCandidate(i) })
    }

    override fun onComposingTap(displayOffset: Int) {
        val (text, caret) = engine.displayText()
        var off = displayOffset
        if (caret < text.length && off > caret) off--
        engine.setCursor(engine.displayToRaw(off))
        showComposing()
    }

    override fun onComposingCursor(delta: Int) {
        engine.moveCursor(delta); showComposing()
    }

    private fun afterWordCommitted(word: String) {
        val phones = Contacts.phones[word]
        if (phones != null && phones.isNotEmpty()) {
            showSmart(Smart.PHONE, phones.map { TopBar.Suggestion(it, "手机号") to { commit(it) } })
            return
        }
        if (prefs.associate && chinese) {
            val next = dict.associations(word)
            if (next.isNotEmpty()) {
                showSmart(Smart.ASSOC, next.map { s -> TopBar.Suggestion(s) to { commit(s); afterWordCommitted(word + s) } })
            }
        }
    }

    private fun showSmart(kind: Smart, items: List<Pair<TopBar.Suggestion, () -> Unit>>) {
        if (engine.isComposing || topBar.isStatusShowing) return
        smart = kind
        smartActions = items.map { it.second }
        topBar.showSuggestions(items.map { it.first })
    }

    private fun clearSmart() {
        smart = Smart.NONE
        smartActions = emptyList()
        if (!engine.isComposing) topBar.showToolbar()
    }

    override fun onSuggestion(index: Int) {
        val action = smartActions.getOrNull(index) ?: return
        val kind = smart
        clearSmart()
        action()
        if (kind == Smart.OTP) usedOtp = null
    }

    override fun onCloseSuggestions() = clearSmart()

    /** Shows "2" and "1+1=2" when the text before the cursor is an arithmetic expression. */
    private fun refreshCalc() {
        if (!prefs.calculator || engine.isComposing || !::topBar.isInitialized) return
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        val r = Calc.tryEval(before)
        if (r == null) { if (smart == Smart.CALC) clearSmart(); return }
        val (expr, value) = r
        showSmart(Smart.CALC, listOf(
            TopBar.Suggestion(value, highlight = true) to {
                currentInputConnection?.deleteSurroundingText(expr.length, 0)
                currentInputConnection?.commitText(value, 1)
            },
            TopBar.Suggestion("$expr=$value") to { currentInputConnection?.commitText("=$value", 1) },
        ))
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (smart == Smart.CALC) refreshCalc()
    }

    // ================================================================ verification codes

    private fun checkOtp() {
        if (!prefs.otpEnabled) return
        Core.bg.execute {
            val fromSms = Otp.latestFromSms(this)
            val fromClip = clipHistory.firstOrNull()?.let { Otp.extract(it) }
            val code = fromSms ?: fromClip ?: return@execute
            main.post {
                if (code == usedOtp || engine.isComposing) return@post
                showSmart(Smart.OTP, listOf(TopBar.Suggestion(code, "验证码", highlight = true) to { commit(code); usedOtp = code }))
            }
        }
    }

    private fun watchSms() {
        if (smsObserver != null || !prefs.otpEnabled || !Otp.granted(this)) return
        smsObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { if (isInputViewShown) checkOtp() }
        }
        try { contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver!!) } catch (_: Exception) {}
    }

    // ================================================================ voice

    override fun onVoiceStart(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先在「声键输入法」设置里授予麦克风权限", Toast.LENGTH_LONG).show()
            openSettings()
            return false
        }
        finishComposing(raw = false)
        clearSmart()
        updateVoiceLabels()
        return voice.start()
    }

    override fun onVoiceEnd(mode: Int) {
        if (mode < 0) voice.cancel() else voice.finish(VoiceController.Mode.values()[mode])
    }

    override fun onLevel(level: Float) { if (::keyboard.isInitialized) keyboard.setVoiceLevel(level) }

    override fun onStatus(text: String?) { topBar.setStatus(text) }

    override fun onResult(text: String) { commit(text) }

    override fun onError(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onCancelStatus() { voice.cancel() }

    // ================================================================ toolbar & panels

    override fun onToolbar(action: String) {
        onFeedback()
        when (action) {
            "settings" -> openSettings()
            "hide" -> requestHideSelf(0)
            else -> showPanel(action)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun showPanel(name: String) {
        finishComposing(raw = false)
        val v: View = when (name) {
            "symbols" -> SymbolPanel(this, this, recent("recent_symbols")) { remember("recent_symbols", it) }
            "emoji" -> EmojiPanel(this, this, recent("recent_emoji")) { remember("recent_emoji", it) }
            "edit" -> EditPanel(this, this, barHeight)
            "clipboard" -> ClipboardPanel(this, this, barHeight)
            "modes" -> ModePanel(this, this, barHeight)
            else -> return
        }
        showPanelView(v)
    }

    private fun showPanelView(v: View) {
        panelHost.removeAllViews()
        panelHost.addView(v, FrameLayout.LayoutParams(-1, page.height.takeIf { it > 0 } ?: -1))
        panelHost.visibility = View.VISIBLE
        page.visibility = View.INVISIBLE
    }

    override fun closePanel() {
        if (!::panelHost.isInitialized) return
        panelHost.removeAllViews()
        panelHost.visibility = View.GONE
        page.visibility = View.VISIBLE
    }

    override val currentMode: String get() = if (chinese) "pinyin" else "english"

    override fun selectMode(mode: String) {
        when (mode) {
            "pinyin" -> { chinese = true; numbers = false; prefs.chineseMode = true }
            "english" -> { chinese = false; numbers = false; prefs.chineseMode = false }
            else -> { Toast.makeText(this, "初版暂未支持，后续版本加入", Toast.LENGTH_SHORT).show(); return }
        }
        applyLayout()
        closePanel()
    }

    override fun sendKey(keyCode: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        if (shift) ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        if (shift) ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    override fun editAction(action: String) {
        val ic = currentInputConnection ?: return
        when (action) {
            "selectAll" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> {
                val sel = ic.getSelectedText(0)
                if (sel.isNullOrEmpty()) Toast.makeText(this, "先选中要复制的文字", Toast.LENGTH_SHORT).show()
                else ic.performContextMenuAction(android.R.id.copy)
            }
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "delete" -> backspace()
            "newline" -> ic.commitText("\n", 1)
            "clipboard" -> showPanel("clipboard")
            "resetComposing" -> { engine.reset(); topBar.showToolbar() }
        }
    }

    // ================================================================ clipboard history

    private fun captureClip() {
        val clip = clipboard?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return
        if (text.isEmpty() || text.length > 5000) return
        clipHistory.remove(text)
        clipHistory.add(0, text)
        while (clipHistory.size > 30) clipHistory.removeAt(clipHistory.size - 1)
        prefs.sp.edit().putString("clip_history", JSONArray(clipHistory).toString()).apply()
        if (isInputViewShown && prefs.otpEnabled) Otp.extract(text)?.let { code ->
            showSmart(Smart.OTP, listOf(TopBar.Suggestion(code, "验证码", highlight = true) to { commit(code); usedOtp = code }))
        }
    }

    private fun loadClipHistory() {
        val s = prefs.sp.getString("clip_history", null) ?: return
        try { val a = JSONArray(s); for (i in 0 until a.length()) clipHistory.add(a.getString(i)) } catch (_: Exception) {}
    }

    override fun clipboardItems(): List<String> = clipHistory.toList()

    override fun clearClipboard() {
        clipHistory.clear()
        prefs.sp.edit().remove("clip_history").apply()
    }

    // ================================================================ recent symbols / emoji

    private fun recent(key: String): List<String> =
        prefs.sp.getString(key, "")!!.split('\u0001').filter { it.isNotEmpty() }

    private fun remember(key: String, s: String) {
        val list = recent(key).toMutableList()
        list.remove(s); list.add(0, s)
        prefs.sp.edit().putString(key, list.take(32).joinToString("\u0001")).apply()
    }
}
