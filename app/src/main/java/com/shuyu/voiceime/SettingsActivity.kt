package com.shuyu.voiceime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.shuyu.voiceime.ui.dp
import com.shuyu.voiceime.voice.CloudApi
import com.shuyu.voiceime.voice.ModelManager
import com.shuyu.voiceime.voice.OfflineAsr
import java.util.concurrent.Executors

/** Settings screen, built in code (no XML layouts). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var setupStatus: TextView
    private lateinit var dictInfo: TextView
    private lateinit var onlineBox: LinearLayout
    private lateinit var offlineBox: LinearLayout
    private var tryField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Color.parseColor("#F2F2F2")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#F2F2F2"))
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(20f), px(16f), px(40f)) }
        scroll.addView(col)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshDictInfo()
        // Used by the CI screenshot job: focus the test field so the keyboard shows.
        if (intent.getBooleanExtra("demo", false)) tryField?.let { f ->
            f.post {
                f.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(f, 0)
            }
        }
    }

    private fun px(v: Float) = dp(v).toInt()

    // ------------------------------------------------------------ building blocks

    private fun title(text: String) {
        col.addView(TextView(this).apply {
            this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#222222")); setPadding(px(4f), 0, 0, px(12f))
        })
    }

    private fun section(name: String): LinearLayout {
        col.addView(TextView(this).apply {
            text = name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Color.parseColor("#2F7BEA"))
            setPadding(px(6f), px(18f), 0, px(6f))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(8f), px(14f), px(10f))
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.WHITE) }
        }
        col.addView(card)
        return card
    }

    private fun note(parent: LinearLayout, text: String): TextView {
        val t = TextView(this).apply { this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.GRAY); setPadding(0, px(4f), 0, px(4f)) }
        parent.addView(t)
        return t
    }

    private fun field(parent: LinearLayout, label: String, value: String, hint: String = "", multiline: Boolean = false,
                      password: Boolean = false, onChange: (String) -> Unit): EditText {
        parent.addView(TextView(this).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Color.DKGRAY); setPadding(0, px(8f), 0, 0) })
        val e = EditText(this)
        e.setText(value)
        e.hint = hint
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        if (multiline) { e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; e.minLines = 4; e.gravity = Gravity.TOP }
        else e.inputType = InputType.TYPE_CLASS_TEXT or (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
        parent.addView(e)
        return e
    }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        parent.addView(b)
        return b
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(r)
        return r
    }

    private fun switch(parent: LinearLayout, label: String, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null) {
        val s = Switch(this)
        s.text = label
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        s.isChecked = prefs.sp.getBoolean(key, def)
        s.setPadding(0, px(8f), 0, px(8f))
        s.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> prefs.sp.edit().putBoolean(key, v).apply(); onChange?.invoke(v) }
        parent.addView(s)
    }

    // ------------------------------------------------------------ screen

    private fun build() {
        title("TypeSome 设置")

        // ---- setup
        val setup = section("开始使用")
        setupStatus = note(setup, "")
        val r1 = row(setup)
        button(r1, "① 启用输入法") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button(r1, "② 切换为当前") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        val r2 = row(setup)
        button(r2, "麦克风权限") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        button(r2, "通讯录权限") { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2) }
        button(r2, "短信权限") { requestPermissions(arrayOf(Manifest.permission.READ_SMS), 3) }
        val r2b = row(setup)
        button(r2b, "验证码：允许读取通知") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        note(setup, "验证码填充有两种来源：读短信（需要短信权限）和读通知（推荐，需要「通知使用权」）。" +
            "小米/红米、OPPO/一加/realme、vivo、华为/荣耀等系统自带「验证码保护」，会挡住第三方 App 读取验证码短信。" +
            "打开「允许读取通知」后一般就能正常填充；如果仍然不行，请到系统「短信」App 的设置（或 手机管家/隐私 设置）里关闭「验证码保护 / 短信验证码安全保护」。")
        note(setup, "使用方法：长按空格开始说话，手指不松开往上滑到「智能整理」或「翻译」再松手，就会用对应模式输出；滑到左下角「取消」松手则放弃。" +
            "也可以点键盘顶部的话筒图标，说完后点模式卡片结束。手写首次使用需要联网下载约 20MB 的模型（需能访问 Google）。")
        tryField = field(setup, "在这里试一试", "", "点这里弹出键盘") {}.apply { inputType = InputType.TYPE_CLASS_TEXT }

        // ---- ASR
        val asr = section("语音识别")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbOnline = RadioButton(this).apply { text = "在线识别（默认）"; id = View.generateViewId() }
        val rbOffline = RadioButton(this).apply { text = "离线模型"; id = View.generateViewId() }
        group.addView(rbOnline); group.addView(rbOffline)
        asr.addView(group)
        onlineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        offlineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        asr.addView(onlineBox); asr.addView(offlineBox)
        note(onlineBox, "使用 OpenAI 兼容的 /audio/transcriptions 接口。默认是硅基流动（siliconflow.cn），注册后在「API 密钥」里创建一个 Key 填到下面，SenseVoice 识别免费；也可以换成 OpenAI（whisper-1）、Groq 等。")
        field(onlineBox, "接口地址", prefs.asrBaseUrl, "https://api.siliconflow.cn/v1") { prefs.asrBaseUrl = it }
        field(onlineBox, "API Key", prefs.asrApiKey, "sk-...", password = true) { prefs.asrApiKey = it }
        field(onlineBox, "模型", prefs.asrModel, "FunAudioLLM/SenseVoiceSmall") { prefs.asrModel = it }
        field(onlineBox, "识别语言（可留空自动识别，如 zh）", prefs.asrLanguage, "") { prefs.asrLanguage = it }
        buildOffline()
        if (prefs.asrEngine == "offline") rbOffline.isChecked = true else rbOnline.isChecked = true
        updateEngineBoxes()
        group.setOnCheckedChangeListener { _, id ->
            prefs.asrEngine = if (id == rbOffline.id) "offline" else "online"
            updateEngineBoxes()
        }

        // ---- LLM
        val llm = section("大模型（智能整理 / 翻译）")
        note(llm, "使用 OpenAI 兼容的 /chat/completions 接口。Key 留空时自动沿用上面语音识别的 Key（同一家服务商时很方便）。")
        field(llm, "接口地址", prefs.llmBaseUrl, "https://api.siliconflow.cn/v1") { prefs.llmBaseUrl = it }
        field(llm, "API Key（可留空）", prefs.llmApiKey, "留空 = 使用语音识别的 Key", password = true) { prefs.llmApiKey = it }
        field(llm, "模型", prefs.llmModel, "Qwen/Qwen2.5-7B-Instruct") { prefs.llmModel = it }

        llm.addView(TextView(this).apply { text = "翻译目标语言"; setTextColor(Color.DKGRAY); setPadding(0, px(10f), 0, 0) })
        val langs = Prefs.LANGUAGES + "自定义…"
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        llm.addView(spinner)
        val custom = field(llm, "自定义语言", if (prefs.targetLanguage in Prefs.LANGUAGES) "" else prefs.targetLanguage, "例如：粤语、文言文") {
            if (it.isNotBlank()) prefs.targetLanguage = it.trim()
        }
        val idx = Prefs.LANGUAGES.indexOf(prefs.targetLanguage)
        spinner.setSelection(if (idx >= 0) idx else langs.size - 1)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val isCustom = pos == langs.size - 1
                custom.isEnabled = isCustom
                if (!isCustom) prefs.targetLanguage = langs[pos]
                else if (custom.text.isNotBlank()) prefs.targetLanguage = custom.text.toString().trim()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val summary = field(llm, "「智能整理」提示词", prefs.summaryPrompt, multiline = true) { prefs.summaryPrompt = it }
        val translate = field(llm, "「翻译」提示词（{lang} 会替换成目标语言）", prefs.translatePrompt, multiline = true) { prefs.translatePrompt = it }
        val r3 = row(llm)
        button(r3, "恢复默认提示词") {
            summary.setText(Prefs.DEFAULT_SUMMARY_PROMPT); translate.setText(Prefs.DEFAULT_TRANSLATE_PROMPT)
        }
        button(r3, "测试整理效果") { testLlm() }

        // ---- keyboard
        val kb = section("键盘")
        switch(kb, "按键振动", "vibrate", true)
        switch(kb, "模糊音（z=zh、c=ch、s=sh、n=l、an=ang、en=eng、in=ing）", "fuzzy_pinyin", false)
        switch(kb, "拼音自动纠错（如 ign→ing）", "auto_correct", true)
        switch(kb, "联想下一个词", "associate", true)
        switch(kb, "记住我选过的词（用户词库）", "learn_words", true)
        switch(kb, "算式自动计算（输入 1+1 候选栏出 2）", "calculator", true)
        switch(kb, "验证码快捷填充（需要短信权限，或复制验证码短信）", "otp", true)
        switch(kb, "输入通讯录姓名后提示手机号", "contacts", false) { on ->
            if (on && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
            Core.reloadContacts(this)
        }
        kb.addView(TextView(this).apply { text = "皮肤"; setTextColor(Color.DKGRAY); setPadding(0, px(8f), 0, 0) })
        val skins = listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色", "classic" to "经典灰")
        val skinGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((key, name) in skins) skinGroup.addView(RadioButton(this).apply {
            text = name; id = View.generateViewId(); tag = key; isChecked = prefs.skin == key
        })
        skinGroup.setOnCheckedChangeListener { g, id -> prefs.skin = g.findViewById<View>(id).tag as String }
        kb.addView(skinGroup)
        kb.addView(TextView(this).apply { text = "键盘高度"; setTextColor(Color.DKGRAY); setPadding(0, px(8f), 0, 0) })
        kb.addView(SeekBar(this).apply {
            max = 50; progress = prefs.keyboardHeightPercent - 75
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { prefs.sp.edit().putInt("kb_height", 75 + p).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        // ---- dictionary
        val dict = section("用户词库")
        dictInfo = note(dict, "")
        note(dict, "包括你选过的词和手动添加的词，换手机时可以导出成文本文件，再在新手机上导入。通讯录姓名不会被导出。")
        val r4 = row(dict)
        button(r4, "导出") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "TypeSome词库.txt"), REQ_EXPORT)
        }
        button(r4, "导入") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), REQ_IMPORT)
        }
        button(r4, "添加") { addWordDialog() }
        val r5 = row(dict)
        button(r5, "查看 / 删除") { manageWords() }
        button(r5, "清空词库") {
            AlertDialog.Builder(this).setTitle("清空用户词库？").setMessage("学到的词和手动添加的词都会删除，无法恢复。建议先导出备份。")
                .setPositiveButton("清空") { _, _ -> Core.dictionary(this).clearUser(); refreshDictInfo() }
                .setNegativeButton("取消", null).show()
        }

        // ---- about
        val about = section("关于")
        note(about, "版本 ${packageManager.getPackageInfo(packageName, 0).versionName}\n拼音词库基于 jieba 词频与 pypinyin 注音生成；离线识别使用 sherpa-onnx。")
    }

    private fun updateEngineBoxes() {
        val offline = prefs.asrEngine == "offline"
        onlineBox.visibility = if (offline) View.GONE else View.VISIBLE
        offlineBox.visibility = if (offline) View.VISIBLE else View.GONE
    }

    private fun refreshSetup() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        fun ok(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        setupStatus.text = buildString {
            append(if (enabled) "✅ 已启用" else "⬜ 未启用输入法"); append("    ")
            append(if (current) "✅ 当前输入法" else "⬜ 不是当前输入法"); append("\n")
            append(if (ok(Manifest.permission.RECORD_AUDIO)) "✅ 麦克风" else "⬜ 麦克风（语音输入必需）"); append("    ")
            append(if (ok(Manifest.permission.READ_CONTACTS)) "✅ 通讯录" else "⬜ 通讯录"); append("    ")
            append(if (ok(Manifest.permission.READ_SMS)) "✅ 短信" else "⬜ 短信"); append("    ")
            append(if (com.shuyu.voiceime.data.CodeListener.enabled(this@SettingsActivity)) "✅ 通知读取" else "⬜ 通知读取")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
        if (requestCode == 2) Core.reloadContacts(this)
    }

    // ------------------------------------------------------------ offline models

    @Volatile private var cancelDownload = false
    private var downloading: String? = null

    private fun buildOffline() {
        offlineBox.removeAllViews()
        note(offlineBox, "模型从 GitHub（sherpa-onnx 官方发布）下载，下载后完全离线识别，不上传录音。国内网络下载慢可以在下面填加速前缀。")
        for (m in ModelManager.MODELS) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(10f), 0, px(4f)) }
            val installed = ModelManager.isInstalled(this, m)
            val inUse = installed && prefs.offlineModelId == m.id
            box.addView(TextView(this).apply {
                text = m.title + when { inUse -> "  · 使用中"; installed -> "  · 已下载"; else -> "" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Color.BLACK)
            })
            val status = note(box, m.desc)
            val r = row(box)
            if (!installed) {
                button(r, if (downloading == m.id) "取消下载" else "下载") {
                    if (downloading == m.id) cancelDownload = true else startDownload(m, status)
                }
            } else {
                if (!inUse) button(r, "使用这个") { prefs.offlineModelId = m.id; OfflineAsr.release(); buildOffline() }
                button(r, "删除") {
                    if (prefs.offlineModelId == m.id) { prefs.offlineModelId = ""; OfflineAsr.release() }
                    ModelManager.delete(this, m); buildOffline()
                }
            }
            offlineBox.addView(box)
        }
        field(offlineBox, "下载加速前缀（可选）", prefs.downloadMirror, "例如 https://ghfast.top/") { prefs.downloadMirror = it }
    }

    private fun startDownload(m: ModelManager.Model, status: TextView) {
        if (downloading != null) { Toast.makeText(this, "已有模型在下载", Toast.LENGTH_SHORT).show(); return }
        downloading = m.id; cancelDownload = false
        buildOffline()
        io.execute {
            try {
                ModelManager.download(this, m, prefs.downloadMirror, { done, total ->
                    main.post {
                        val mb = done / 1048576.0
                        val text = if (total > 0) "下载中 %.1f / %.1f MB".format(mb, total / 1048576.0) else "下载中 %.1f MB".format(mb)
                        findStatus(m)?.text = if (total > 0 && done >= total) "解压中…" else text
                    }
                }, { cancelDownload })
                main.post {
                    downloading = null
                    if (prefs.offlineModelId.isEmpty() || !ModelManager.byId(prefs.offlineModelId)!!.let { ModelManager.isInstalled(this, it) })
                        prefs.offlineModelId = m.id
                    Toast.makeText(this, "${m.title} 下载完成", Toast.LENGTH_SHORT).show()
                    buildOffline()
                }
            } catch (e: Exception) {
                ModelManager.delete(this, m)
                main.post { downloading = null; Toast.makeText(this, e.message ?: "下载失败", Toast.LENGTH_LONG).show(); buildOffline() }
            }
        }
    }

    /** The description line of a model row (rebuilt views make keeping references fragile). */
    private fun findStatus(m: ModelManager.Model): TextView? {
        for (i in 0 until offlineBox.childCount) {
            val box = offlineBox.getChildAt(i) as? LinearLayout ?: continue
            val title = box.getChildAt(0) as? TextView ?: continue
            if (title.text.startsWith(m.title)) return box.getChildAt(1) as? TextView
        }
        return null
    }

    // ------------------------------------------------------------ LLM test

    private fun testLlm() {
        val sample = "嗯那个就是我想说一下啊，明天的会议呢改到下午三点，呃不对是三点半，然后地点还是在那个三楼会议室，大家记得带电脑哈"
        val dlg = AlertDialog.Builder(this).setTitle("测试中…").setMessage("原文：$sample").setPositiveButton("关闭", null).show()
        io.execute {
            val out = try { CloudApi.chat(prefs.llmBaseUrl, prefs.effectiveLlmKey, prefs.llmModel, prefs.summaryPrompt, sample) }
            catch (e: Exception) { "出错：${e.message}" }
            main.post { dlg.setTitle("整理结果"); dlg.setMessage("原文：$sample\n\n结果：\n$out") }
        }
    }

    // ------------------------------------------------------------ dictionary

    private fun refreshDictInfo() {
        if (!::dictInfo.isInitialized) return
        val d = Core.dictionary(this)
        dictInfo.text = if (d.loaded) "用户词库共 ${d.userEntries().count { it.source != "contact" }} 个词" else "词库加载中…"
        if (!d.loaded) main.postDelayed({ refreshDictInfo() }, 800)
    }

    private fun addWordDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), 0) }
        val word = EditText(this).apply { hint = "词语，例如：TypeSome" }
        val py = EditText(this).apply { hint = "拼音（可留空自动生成），例如：sheng jian shu ru fa" }
        box.addView(word); box.addView(py)
        AlertDialog.Builder(this).setTitle("添加词条").setView(box)
            .setPositiveButton("添加") { _, _ ->
                val ok = Core.dictionary(this).addManual(word.text.toString().trim(), py.text.toString().trim().ifEmpty { null })
                Toast.makeText(this, if (ok) "已添加" else "无法自动生成拼音，请手动填写", Toast.LENGTH_SHORT).show()
                refreshDictInfo()
            }.setNegativeButton("取消", null).show()
    }

    private fun manageWords() {
        val d = Core.dictionary(this)
        val items = d.userEntries().filter { it.source != "contact" }.sortedByDescending { it.count }
        if (items.isEmpty()) { Toast.makeText(this, "用户词库是空的", Toast.LENGTH_SHORT).show(); return }
        val labels = items.map { "${it.word}    ${it.key.replace('\'', ' ')}    ×${it.count}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("点击词条删除").setItems(labels) { _, which ->
            d.removeUser(items[which]); refreshDictInfo(); manageWords()
        }.setNegativeButton("完成", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val d = Core.dictionary(this)
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use { it.write(d.exportText().toByteArray()) }
                    Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show()
                }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val n = d.importText(text)
                    Toast.makeText(this, "导入了 $n 个词", Toast.LENGTH_SHORT).show()
                    refreshDictInfo()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_EXPORT = 11
        private const val REQ_IMPORT = 12
    }
}
