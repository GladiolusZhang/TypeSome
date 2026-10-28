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
import com.shuyu.voiceime.input.Handwriting
import com.shuyu.voiceime.ui.HandwritingView
import com.shuyu.voiceime.ui.VoiceOverlay
import com.shuyu.voiceime.data.EmojiWords
import com.shuyu.voiceime.pinyin.Dictionary
import com.shuyu.voiceime.pinyin.EnglishWords
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
    private lateinit var en: EnglishWords
    /** English suggestions are off in password, e-mail and URL fields. */
    private var enSuggest = true
    private lateinit var engine: PinyinEngine
    private lateinit var voice: VoiceController
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var topBar: TopBar
    private lateinit var keyboard: KeyboardView
    private lateinit var handPad: HandwritingView
    private lateinit var overlay: VoiceOverlay
    private var barHeight = 0

    private var chinese = true
    private var numbers = false
    /** Chinese layout: "pinyin", "pinyin9" or "handwriting" */
    private var cnLayout = "pinyin"
    private var handCands: List<String> = emptyList()
    private var lastT9Options: List<String>? = null
    private var shift = 0
    private var lastShiftTap = 0L
    private var editorInfo: EditorInfo? = null

    // smart suggestions currently shown in the candidate strip
    private enum class Smart { NONE, CALC, PHONE, OTP, ASSOC, WORDS }
    private var smart = Smart.NONE
    private var smartActions: List<() -> Unit> = emptyList()

    private val clipHistory = ArrayList<String>()
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
    private var smsObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dict = Core.dictionary(this)
        en = Core.english(this)
        engine = PinyinEngine(dict, en)
        engine.buildInBackground { main.post(it) }
        engine.onReady = { if (engine.isComposing) showComposing() }
        Core.bg.execute { EmojiWords.load(this) }
        engine.emojiFor = { w -> if (prefs.emojiWords) EmojiWords.forWord(w) else emptyList() }
        voice = VoiceController(this, this)
        chinese = prefs.chineseMode
        cnLayout = prefs.sp.getString("cn_layout", "pinyin") ?: "pinyin"
        loadClipHistory()
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        watchOutsideTouches()
    }

    // ================================================================ tap outside to hide

    private var outsideDownAt = 0L
    private val hideFromOutside = Runnable {
        // the editor changed right after the tap (a send button cleared it, …): that tap was meant for the app
        if (isInputViewShown && !voice.isRecording && lastEditorChange < outsideDownAt) requestHideSelf(0)
    }
    private var lastEditorChange = 0L

    /**
     * Touches outside the keyboard arrive as ACTION_OUTSIDE (no coordinates). Hide shortly after, unless the
     * tap turns out to be in a text field: that moves the cursor or starts new input, which cancels the hide.
     */
    private fun watchOutsideTouches() {
        val w = window.window ?: return
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val cb = w.callback
        w.callback = object : android.view.Window.Callback by cb {
            override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
                if (e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                    outsideDownAt = SystemClock.uptimeMillis()
                    if (prefs.hideOnOutsideTap) { main.removeCallbacks(hideFromOutside); main.postDelayed(hideFromOutside, OUTSIDE_HIDE_DELAY) }
                    return true
                }
                return cb.dispatchTouchEvent(e)
            }
        }
    }

    private fun keepShown() {
        lastEditorChange = SystemClock.uptimeMillis()
        main.removeCallbacks(hideFromOutside)
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        keepShown()
        return super.onShowInputRequested(flags, configChange)
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
        Theme.apply(this, prefs.skin)
        barHeight = dp(44f).toInt()
        root = FrameLayout(this)
        // The strip above the keyboard is transparent (the app shows through) and only holds the composing tab.
        tabHeight = dp(30f).toInt()
        root.setPadding(0, tabHeight, 0, 0)
        root.clipToPadding = false
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        topBar = TopBar(this, this)
        keyboard = KeyboardView(this, this)
        keyboard.rowHeightPx = dp(60f) * prefs.keyboardHeightPercent / 100f
        handPad = HandwritingView(this) { strokes -> recognizeInk(strokes) }
        overlay = VoiceOverlay(this, { onFeedback() }) { mode -> finishVoice(mode) }
        overlay.visibility = View.GONE
        val keyArea = FrameLayout(this)
        val keyCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyCol.addView(handPad, LinearLayout.LayoutParams(-1, (keyboard.rowHeightPx * 3).toInt()))
        keyCol.addView(keyboard, LinearLayout.LayoutParams(-1, -2))
        keyArea.addView(keyCol, FrameLayout.LayoutParams(-1, -2))
        keyArea.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        page.addView(topBar, LinearLayout.LayoutParams(-1, barHeight))
        page.addView(keyArea, LinearLayout.LayoutParams(-1, -2))
        // let the key-press bubble of the top row draw over the toolbar
        for (g in listOf(keyCol, keyArea, page, root)) { g.clipChildren = false; g.clipToPadding = false }
        root.addView(page, FrameLayout.LayoutParams(-1, -2))
        panelHost = FrameLayout(this)
        panelHost.visibility = View.GONE
        root.addView(panelHost, FrameLayout.LayoutParams(-1, -1))
        // composing tab: sits on the bar's top-left corner, overlapping its top edge by 1dp
        root.addView(topBar.composingTab, FrameLayout.LayoutParams(-2, tabHeight + dp(1f).toInt()).apply { topMargin = -tabHeight })
        window.window?.let { w ->
            w.navigationBarColor = Theme.BG
            // dark navigation buttons on light skins
            @Suppress("DEPRECATION")
            val flags = w.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (Theme.dark || Theme.signature == "classic") flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        applyLayout()
        return root
    }

    private val mode get() = when {
        numbers -> "numbers"
        !chinese -> "english"
        else -> cnLayout
    }

    private fun applyLayout() {
        if (!::keyboard.isInitialized) return
        engine.t9 = mode == "pinyin9"
        lastT9Options = null
        val hand = mode == "handwriting"
        handPad.visibility = if (hand) View.VISIBLE else View.GONE
        handPad.layoutParams.height = (keyboard.rowHeightPx * 3).toInt()
        handPad.clear(); handCands = emptyList()
        keyboard.layout = when (mode) {
            "numbers" -> Layouts.numbers()
            "english" -> Layouts.qwerty(false)
            "pinyin9" -> Layouts.t9(emptyList())
            "handwriting" -> Layouts.handwritingBar()
            else -> Layouts.qwerty(true)
        }
        keyboard.shiftState = shift
        updateVoiceLabels()
        if (hand) Handwriting.prepare { err -> if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
    }

    /** 9-key: the left column shows spellings of the first syllable while composing. */
    private fun refreshT9Column() {
        if (mode != "pinyin9") return
        val opts = if (engine.isComposing) engine.t9FirstOptions() else emptyList()
        if (opts == lastT9Options) return
        lastT9Options = opts
        keyboard.layout = Layouts.t9(opts)
    }

    private fun updateVoiceLabels() {
        overlay.labels = arrayOf(
            "语音输入" to (if (prefs.asrEngine == "offline") "离线 · 直接转文字" else "直接转文字"),
            "智能整理" to (if (prefs.audioLlm) "直接听录音 · 去口水词" else "去口水词 · 更有条理"),
            "翻译" to "说中文 → ${prefs.targetLanguage}")
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keepShown()
        editorInfo = info
        prefs = Prefs(this)
        // skin changed in settings, or the system switched light/dark: rebuild the views
        val sig = Theme.signature
        Theme.apply(this, prefs.skin)
        if (sig != Theme.signature) setInputView(onCreateInputView())
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
        enSuggest = !englishOnly && info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        keyboard.rowHeightPx = dp(60f) * prefs.keyboardHeightPercent / 100f
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
        if (::overlay.isInitialized) overlay.hide()
        if (voice.isRecording) voice.cancel()
        engine.reset()
        dict.flush()
        en.flush()
    }

    override fun onEvaluateFullscreenMode() = false

    private var tabHeight = 0
    private val tabRect = android.graphics.Rect()

    /** Only the keyboard (and the composing tab when shown) takes touches; the strip above it is see-through. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::root.isInitialized || !root.isShown) return
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val top = loc[1] + tabHeight
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, root.width, loc[1] + root.height)
        val tab = topBar.composingTab
        if (tab.visibility == View.VISIBLE) {
            tabRect.set(0, loc[1], tab.width, top)
            outInsets.touchableRegion.op(tabRect, android.graphics.Region.Op.UNION)
        }
    }

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

    override fun onKey(key: Key, output: String, alt: Boolean) {
        when (key.type) {
            KeyType.TEXT -> typeText(output)
            KeyType.T9 -> {
                if (alt) { if (engine.isComposing) finishComposing(raw = false); commit(output) }
                else if (output == "1" && !engine.isComposing) commit(if (chinese) "，" else ",")
                else { startComposing(); engine.input(output[0]); showComposing() }
            }
            KeyType.CLEAR -> { if (engine.isComposing) { engine.reset(); topBar.showToolbar(); refreshT9Column() } }
            KeyType.PICK -> {
                engine.pickT9First(if (engine.t9First == output) null else output)
                showComposing()
            }
            KeyType.BACKSPACE -> backspace()
            KeyType.SPACE -> {
                if (engine.isComposing || handCands.isNotEmpty()) pickCandidate(0)
                else {
                    val word = englishWordBefore()
                    commit(" ")
                    if (word != null) afterEnglishWord(word)
                }
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
                numbers = false
                applyLayout()
            }
            KeyType.NUM_PAD -> { finishComposing(raw = false); numbers = true; applyLayout() }
            KeyType.BACK -> { numbers = false; applyLayout() }
            KeyType.SYMBOLS -> { finishComposing(raw = false); showPanel(if (alt) "emoji" else "symbols") }
        }
    }

    private fun typeText(s: String) {
        if (s.isEmpty()) return
        val c = s[0]
        val isLetter = s.length == 1 && c in 'a'..'z'
        if (isLetter && chinese && !numbers && shift == 0) {
            startComposing()
            engine.input(c)
            showComposing()
            return
        }
        if (isLetter) {
            finishComposing(raw = false)
            commit(if (shift > 0) s.uppercase() else s)
            if (shift == 1) { shift = 0; keyboard.shiftState = 0 }
            refreshEnglish()
            return
        }
        if (s == "'" && engine.isComposing) { engine.input('\''); showComposing(); return }
        if (engine.isComposing) finishComposing(raw = false)
        commit(if (chinese && !numbers) toChinesePunct(s) else s)
        if (!chinese) { if (s == "'") refreshEnglish() else if (smart == Smart.WORDS || smart == Smart.ASSOC) clearSmart() }
    }

    // ================================================================ English suggestions

    private val wordEnd = Regex("[A-Za-z][A-Za-z']*$")

    /** The English word the cursor is right after, if suggestions apply here. */
    private fun englishWordBefore(): String? {
        if (chinese || numbers || !enSuggest) return null
        val before = currentInputConnection?.getTextBeforeCursor(40, 0) ?: return null
        return wordEnd.find(before)?.value
    }

    /** Completions of the word being typed (the letters go straight into the editor). */
    private fun refreshEnglish() {
        if (engine.isComposing || !en.loaded) return
        val typed = englishWordBefore()
        val list = if (typed == null || !prefs.associate) emptyList() else en.suggest(typed)
        if (list.isEmpty()) { if (smart == Smart.WORDS || smart == Smart.ASSOC) clearSmart(); return }
        // a word that names an emoji (love, fire) also offers it, in second place
        val emoji = if (prefs.emojiWords) EmojiWords.forWord(typed!!).take(2) else emptyList()
        val items = list.map { w ->
            val shown = EnglishWords.matchCase(w, typed!!)
            TopBar.Suggestion(shown) to {
                val ic = currentInputConnection
                if (ic != null) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(typed.length, 0)
                    ic.commitText("$shown ", 1)
                    ic.endBatchEdit()
                }
                afterEnglishWord(shown)
            }
        }.toMutableList()
        for ((k, e) in emoji.withIndex()) items.add(minOf(1 + k, items.size), TopBar.Suggestion(e) to {
            currentInputConnection?.let { ic ->
                ic.beginBatchEdit(); ic.deleteSurroundingText(typed!!.length, 0); ic.commitText(e, 1); ic.endBatchEdit()
            }
            remember("recent_emoji", e, EMOJI_HISTORY)
        })
        showSmart(Smart.WORDS, items)
    }

    /** A word was finished: count it and offer the words that usually follow. */
    private fun afterEnglishWord(word: String) {
        if (engine.learning) en.learn(word)
        val next = if (prefs.associate && en.loaded) en.nextWords(word) else emptyList()
        if (next.isEmpty()) { if (smart == Smart.WORDS || smart == Smart.ASSOC) clearSmart(); return }
        showSmart(Smart.ASSOC, next.map { w ->
            val shown = EnglishWords.matchCase(w, "")
            TopBar.Suggestion(shown) to { commit("$shown "); afterEnglishWord(shown) }
        })
    }

    private fun toChinesePunct(s: String) = when (s) {
        "?" -> "？"; "!" -> "！"; ":" -> "："; ";" -> "；"; "(" -> "（"; ")" -> "）"; "," -> "，"; "." -> "。"
        else -> s
    }

    override fun backspace() {
        if (engine.isComposing) {
            engine.backspace()
            if (engine.isComposing) showComposing() else { topBar.showToolbar(); refreshT9Column() }
            return
        }
        if (::handPad.isInitialized && !handPad.isEmpty) { handPad.clear(); handCands = emptyList(); topBar.showToolbar(); return }
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
        main.post { refreshCalc(); if (!chinese && smart == Smart.NONE) refreshEnglish() }
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

    /** The last few characters before the cursor (what the next word follows). */
    private fun textBefore(): String = currentInputConnection?.getTextBeforeCursor(8, 0)?.toString() ?: ""

    /** The first letter of a new word: the text before it steers the candidate ranking. */
    private fun startComposing() {
        if (!engine.isComposing) engine.context = textBefore()
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
        refreshT9Column()
        if (!engine.isComposing) { topBar.showToolbar(); return }
        val (text, caret) = engine.displayText()
        // while newer candidates are being built the letters show at once and the old list stays
        shownCandidates = engine.readyCandidates?.take(SHOWN_CANDIDATES)?.map { it.text }
        topBar.showCandidates(text, caret, shownCandidates)
        smart = Smart.NONE
    }

    /** What the candidate bar shows (null while it shows an older list). */
    private var shownCandidates: List<String>? = null

    /** A tap on the bar picks what it showed, even when that list was a key behind. */
    override fun onCandidate(index: Int) {
        if (handCands.isEmpty() && engine.readyCandidates == null) {
            val tapped = topBar.shownCandidate(index)
            val i = engine.candidates.indexOfFirst { it.text == tapped }
            if (i < 0) { showComposing(); return }
            pickCandidate(i)
            return
        }
        pickCandidate(index)
    }

    private fun pickCandidate(index: Int) {
        if (handCands.isNotEmpty()) {
            val w = handCands.getOrNull(index) ?: return
            handCands = emptyList(); handPad.clear()
            commit(w); topBar.showToolbar(); afterWordCommitted(w)
            return
        }
        val context = engine.context
        val picked = engine.candidates.getOrNull(index)
        if (picked?.label == PinyinEngine.EMOJI) remember("recent_emoji", picked.text, EMOJI_HISTORY)
        val done = engine.select(index)
        if (done != null) {
            if (engine.learning) dict.learnNext(context, done)
            commit(done)
            topBar.showToolbar()
            afterWordCommitted(done)
            refreshT9Column()
        } else showComposing()
    }

    override fun onExpandCandidates() {
        if (!engine.isComposing && handCands.isEmpty()) return
        val cands = if (handCands.isNotEmpty()) handCands else engine.candidates.map { it.text }
        showPanelView(CandidateGridPanel(this, this, cands) { i -> closePanel(); pickCandidate(i) })
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
            // the editor may hold more than the word (or, in some apps, not report it yet)
            val context = textBefore().takeIf { it.endsWith(word) } ?: word
            val next = dict.associations(context)
            if (next.isNotEmpty()) {
                showSmart(Smart.ASSOC, next.map { s -> TopBar.Suggestion(s) to {
                    if (engine.learning) dict.learnNext(context, s)
                    commit(s); afterWordCommitted(s)
                } })
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
    }

    override fun onCloseSuggestions() {
        // a dismissed code shouldn't come back through another channel either
        if (smart == Smart.OTP) shownOtp?.let { markOtpUsed(it) }
        clearSmart()
    }

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
        keepShown()
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (smart == Smart.CALC) refreshCalc()
        if (smart == Smart.OTP) dropOtpIfEntered()
        if (::panelHost.isInitialized) (panelHost.getChildAt(0) as? EditPanel)?.selectionChanged(newSelStart != newSelEnd)
    }

    // ================================================================ verification codes

    private fun checkOtp() {
        if (!prefs.otpEnabled) return
        Core.bg.execute {
            val fromSms = Otp.latestFromNotification() ?: Otp.latestFromSms(this)
            // a copied code counts only while fresh: the clipboard keeps it long after it was used
            val fromClip = clipOtp?.takeIf { System.currentTimeMillis() - it.second < OTP_FRESH_MS }?.first
            val code = fromSms ?: fromClip ?: return@execute
            main.post { showOtp(code) }
        }
    }

    /**
     * One code, one offer: the same code often arrives through several channels (notification, SMS,
     * the system copying it to the clipboard — vivo does all three), so codes already filled in are remembered.
     */
    private fun showOtp(code: String) {
        if (engine.isComposing || otpUsed(code)) return
        if (smart == Smart.OTP && smartActions.isNotEmpty() && shownOtp == code) return
        shownOtp = code
        showSmart(Smart.OTP, listOf(TopBar.Suggestion(code, "验证码", highlight = true) to { commit(code); markOtpUsed(code) }))
    }

    private var shownOtp: String? = null
    /** Code found in the clipboard and when it was copied. */
    private var clipOtp: Pair<String, Long>? = null

    /** The offered code is gone once it is in the field, however it got there (typed, pasted, autofilled). */
    private fun dropOtpIfEntered() {
        val code = shownOtp ?: return
        if (smart != Smart.OTP) return
        val ic = currentInputConnection ?: return
        val around = "${ic.getTextBeforeCursor(64, 0) ?: ""}${ic.getTextAfterCursor(64, 0) ?: ""}"
        if (code in around) { markOtpUsed(code); clearSmart() }
    }

    private fun otpUsed(code: String): Boolean {
        val s = prefs.sp.getString("used_otp", "") ?: ""
        return s.split(',').any { it.substringBefore('@') == code && System.currentTimeMillis() - (it.substringAfter('@').toLongOrNull() ?: 0) < OTP_REMEMBER_MS }
    }

    private fun markOtpUsed(code: String) {
        val now = System.currentTimeMillis()
        val keep = (prefs.sp.getString("used_otp", "") ?: "").split(',')
            .filter { it.contains('@') && now - (it.substringAfter('@').toLongOrNull() ?: 0) < OTP_REMEMBER_MS }
        prefs.sp.edit().putString("used_otp", (keep + "$code@$now").takeLast(10).joinToString(",")).apply()
    }

    private fun watchSms() {
        Otp.onNew = { code -> if (isInputViewShown && prefs.otpEnabled) showOtp(code) }
        if (smsObserver != null || !prefs.otpEnabled || !Otp.granted(this)) return
        smsObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { if (isInputViewShown) checkOtp() }
        }
        try { contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver!!) } catch (_: Exception) {}
    }

    // ================================================================ voice

    private fun voiceAllowed(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先在「TypeSome」设置里授予麦克风权限", Toast.LENGTH_LONG).show()
            openSettings()
            return false
        }
        return true
    }

    /** Long-press on space. */
    override fun onVoiceStart(): Boolean {
        if (!voiceAllowed()) return false
        finishComposing(raw = false)
        clearSmart()
        updateVoiceLabels()
        if (!voice.start()) return false
        overlay.show(tap = false)
        return true
    }

    override fun onVoiceTrack(rawX: Float, rawY: Float) = overlay.track(rawX, rawY)

    override fun onVoiceRelease(cancelled: Boolean) = finishVoice(if (cancelled) -1 else overlay.selection)

    /** Toolbar mic: tap to start, then tap a mode card to finish. */
    private fun startTapVoice() {
        if (overlay.isShowing) { finishVoice(overlay.selection); return }
        if (!voiceAllowed()) return
        finishComposing(raw = false)
        clearSmart()
        updateVoiceLabels()
        if (voice.start()) overlay.show(tap = true)
    }

    private fun finishVoice(mode: Int) {
        overlay.hide()
        if (mode < 0) voice.cancel() else voice.finish(VoiceController.Mode.values()[mode], currentInputEditorInfo?.packageName)
    }

    override fun onLevel(level: Float) { if (::overlay.isInitialized) overlay.setLevel(level) }

    override fun onStatus(text: String?) { topBar.setStatus(text) }

    override fun onResult(text: String) { commit(text) }

    override fun onError(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onCancelStatus() { voice.cancel() }

    // ================================================================ handwriting

    private fun recognizeInk(strokes: List<List<FloatArray>>) {
        if (!Handwriting.ready) {
            Handwriting.prepare { err -> if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show() else recognizeInk(strokes) }
            return
        }
        Handwriting.recognize(strokes, { list ->
            handCands = list.filter { it.isNotBlank() }.take(30)
            if (handCands.isNotEmpty()) topBar.showCandidates("", -1, handCands)
        }, { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() })
    }

    // ================================================================ toolbar & panels

    override fun onToolbar(action: String) {
        onFeedback()
        when (action) {
            "settings" -> openSettings()
            "hide" -> requestHideSelf(0)
            "voice" -> startTapVoice()
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
            "emoji" -> EmojiPanel(this, this, recent("recent_emoji")) { remember("recent_emoji", it, EMOJI_HISTORY) }
            "edit" -> EditPanel(this, this, barHeight).apply { selectionChanged(!currentInputConnection?.getSelectedText(0).isNullOrEmpty()) }
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
        v.alpha = 0f
        v.translationY = dp(14f)
        v.animate().alpha(1f).translationY(0f).setDuration(170).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    override fun closePanel() {
        if (!::panelHost.isInitialized) return
        panelHost.removeAllViews()
        panelHost.visibility = View.GONE
        page.visibility = View.VISIBLE
    }

    override val currentMode: String get() = if (chinese) cnLayout else "english"

    override fun selectMode(mode: String) {
        when (mode) {
            "pinyin", "pinyin9", "handwriting" -> {
                chinese = true; numbers = false; prefs.chineseMode = true
                cnLayout = mode; prefs.sp.edit().putString("cn_layout", mode).apply()
            }
            "english" -> { chinese = false; numbers = false; prefs.chineseMode = false }
            else -> { Toast.makeText(this, "暂未支持，后续版本加入", Toast.LENGTH_SHORT).show(); return }
        }
        engine.reset()
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
            "cut" -> {
                val sel = ic.getSelectedText(0)
                if (sel.isNullOrEmpty()) Toast.makeText(this, "先选中要剪切的文字", Toast.LENGTH_SHORT).show()
                else ic.performContextMenuAction(android.R.id.cut)
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
        val isNew = clipHistory.firstOrNull() != text
        clipHistory.remove(text)
        clipHistory.add(0, text)
        while (clipHistory.size > 30) clipHistory.removeAt(clipHistory.size - 1)
        prefs.sp.edit().putString("clip_history", JSONArray(clipHistory).toString()).apply()
        if (!isNew) return
        val code = Otp.extract(text) ?: return
        clipOtp = code to System.currentTimeMillis()
        if (isInputViewShown && prefs.otpEnabled) showOtp(code)
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

    /** Most recent first; using one again moves it to the front, and the oldest drops off past [limit]. */
    private fun remember(key: String, s: String, limit: Int = 32) {
        val list = recent(key).toMutableList()
        list.remove(s); list.add(0, s)
        prefs.sp.edit().putString(key, list.take(limit).joinToString("\u0001")).apply()
    }

    private companion object {
        const val EMOJI_HISTORY = 24
        /** Candidates in the bar; the expanded grid shows them all. */
        const val SHOWN_CANDIDATES = 30
        /** Codes are offered only this long after they arrive… */
        const val OTP_FRESH_MS = 5 * 60_000L
        /** …and a used code is never offered again within a day. */
        const val OTP_REMEMBER_MS = 24 * 3600_000L
        /**
         * Outside touches only report the finger going down. Wait long enough for a normal tap to finish, so a
         * button there (e.g. 发送) gets its click before the app re-lays out under a closing keyboard.
         */
        const val OUTSIDE_HIDE_DELAY = 650L
    }
}
