package com.cwcontest

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * MainActivity – N1MM-style CW contest interface.
 *
 *  ┌ top bar : contest | frequency | WPM − slider + | RTS/DTR | serial | USB | ⋮ ⚙ ┐
 *  │ status  : last message sent / CQ loop state                                 │
 *  │ left    : callsign, received exchange, TX hint, LOG/ABORT, DOT/DASH paddle  │
 *  │ right   : F1–F12 macro grid (per-contest, editable, persisted)              │
 *  └ bottom  : live QSO log ─────────────────────────────────────────────────────┘
 *
 * The CW engine keeps running while the activity is in the background, so an
 * in-flight message and the CQ loop survive screen-off / app switching. It is
 * torn down in [onDestroy] only.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_WRITE_STORAGE = 1001
    }

    // ── Managers ──────────────────────────────────────────────────────────────
    private lateinit var settingsMgr: SettingsManager
    private lateinit var contestMgr:  ContestManager
    private lateinit var logMgr:      LogManager
    private lateinit var cwEngine:    CWEngine
    private lateinit var usbMgr:      USBSerialManager

    private var settings: AppSettings = AppSettings()
    private var macros: List<Macro>   = emptyList()

    // ── Frequency / band (manual – the app never talks to the radio) ──────────
    private var currentFreqMhz = 14.025
    private var currentBand    = Band.BAND_20

    // ── UI ────────────────────────────────────────────────────────────────────
    private lateinit var spinnerContest: Spinner
    private lateinit var tvFreq:         TextView
    private lateinit var seekWpm:        SeekBar
    private lateinit var tvWpm:          TextView
    private lateinit var tvRts:          TextView
    private lateinit var tvDtr:          TextView
    private lateinit var tvStatus:       TextView
    private lateinit var tvSerial:       TextView
    private lateinit var tvTxHint:       TextView
    private lateinit var etCallsign:     EditText
    private lateinit var etExchange:     EditText
    private lateinit var btnLog:         Button
    private lateinit var btnAbort:       Button
    private lateinit var btnDot:         Button
    private lateinit var btnDash:        Button
    private lateinit var macroGrid:      GridLayout
    private lateinit var rvLog:          RecyclerView

    // ── Rows that step aside while the soft keyboard is on screen ─────────────
    private lateinit var rootLayout:      View
    private lateinit var tvExchangeLabel: TextView
    private lateinit var rowPaddle:       View
    private lateinit var rowLogHeader:    View
    private var rowTopKeys: View?         = null

    /** True while the on-screen keyboard is up – see [setupImeAwareLayout]. */
    private var typingMode = false
    private var fullRootHeightPx = 0

    // ── CQ loop ───────────────────────────────────────────────────────────────
    private val cqHandler = Handler(Looper.getMainLooper())
    private var cqLooping = false

    // ── Misc ──────────────────────────────────────────────────────────────────
    private lateinit var logAdapter: LogAdapter
    private var appliedTheme = 0
    private var pendingExport: (() -> String?)? = null
    /** Text of the last macro actually keyed – stored with the next QSO */
    private var lastSentMacro = ""

    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsMgr = SettingsManager(this)
        settings    = settingsMgr.load()
        applyTheme()

        setContentView(R.layout.activity_main)

        initManagers()
        bindViews()
        setupImeAwareLayout()
        setupContestSpinner()
        setupWpmControls()
        setupCallsignInput()
        setupMacroGrid()
        setupLogView()
        setupButtons()
        setupPaddles()

        cwEngine.start()
        applyModeVisibility()
        updateTxHint()
        updateSerial()
    }

    override fun onResume() {
        super.onResume()
        usbMgr.registerReceivers()
        // Settings may have changed while we were away (theme, callsign, …)
        val before = settings
        settings = settingsMgr.load()
        if (before.darkMode != settings.darkMode) {
            applyTheme()
            recreate()
            return
        }
        cwEngine.applySettings(settings)
        contestMgr.applySettings(settings)
        cwEngine.start()
        seekWpm.progress = (settings.wpm - 5).coerceIn(0, 55)
        tvWpm.text = "${settings.wpm} WPM"
        if (before.contestMode != settings.contestMode) {
            spinnerContest.setSelection(ContestMode.values().indexOf(settings.contestMode))
            setupMacroGrid()
        }
        applyModeVisibility()
        syncBandToMode()
        updateTxHint()
        updateSerial()
    }

    override fun onPause() {
        super.onPause()
        usbMgr.unregisterReceivers()
        // NOTE: the CW engine deliberately keeps running – queued messages and
        // the CQ loop must survive the app going to the background.
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCqLoop()
        cwEngine.stop()
        usbMgr.closePort()
    }

    // ── Theming ───────────────────────────────────────────────────────────────

    private fun applyTheme() {
        val style = if (settings.darkMode) R.style.Theme_CWContest
                    else R.style.Theme_CWContest_Light
        if (style != appliedTheme) {
            appliedTheme = style
            setTheme(style)
        }
    }

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId)
               else tv.data
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private fun initManagers() {
        contestMgr = ContestManager(settings)
        logMgr     = LogManager(this)
        cwEngine   = CWEngine(settings)
        usbMgr     = USBSerialManager(this)
        cwEngine.usbManager = usbMgr
        cwEngine.listener   = cwListener
        usbMgr.listener     = usbListener
    }

    private fun bindViews() {
        spinnerContest = findViewById(R.id.spinnerContest)
        tvFreq         = findViewById(R.id.tvFreq)
        seekWpm        = findViewById(R.id.seekWpm)
        tvWpm          = findViewById(R.id.tvWpm)
        tvRts          = findViewById(R.id.tvRts)
        tvDtr          = findViewById(R.id.tvDtr)
        tvStatus       = findViewById(R.id.tvStatus)
        tvSerial       = findViewById(R.id.tvSerial)
        tvTxHint       = findViewById(R.id.tvTxHint)
        etCallsign     = findViewById(R.id.etCallsign)
        etExchange     = findViewById(R.id.etExchange)
        btnLog         = findViewById(R.id.btnLog)
        btnAbort       = findViewById(R.id.btnAbort)
        btnDot         = findViewById(R.id.btnDot)
        btnDash        = findViewById(R.id.btnDash)
        macroGrid      = findViewById(R.id.macroGrid)
        rvLog          = findViewById(R.id.rvLog)

        rootLayout      = findViewById(R.id.rootLayout)
        tvExchangeLabel = findViewById(R.id.tvExchangeLabel)
        rowPaddle       = findViewById(R.id.rowPaddle)
        rowLogHeader    = findViewById(R.id.rowLogHeader)
        // Only the portrait layout splits the top bar in two rows; the
        // landscape one keeps everything in a single row.
        rowTopKeys      = findViewById(R.id.rowTopKeys)
    }

    // ── Contest mode ──────────────────────────────────────────────────────────

    private fun setupContestSpinner() {
        val modes = ContestMode.values()
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, modes.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerContest.adapter = adapter
        spinnerContest.setSelection(modes.indexOf(settings.contestMode).coerceAtLeast(0))

        spinnerContest.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (settings.contestMode == modes[pos]) return
                settings = settings.copy(contestMode = modes[pos])
                settingsMgr.save(settings)
                contestMgr.applySettings(settings)
                stopCqLoop()
                setupMacroGrid()
                applyModeVisibility()
                syncBandToMode()
                updateTxHint()
                updateSerial()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Each contest runs on its own band set – pick a sensible default band */
    private fun syncBandToMode() {
        val allowed = Band.forContest(settings.contestMode)
        if (allowed.contains(currentBand)) {
            updateFreqDisplay()
            return
        }
        // 20m for the HF contests, and the last (lowest) band of the mode's
        // own set otherwise – 2m for CQ WW VHF and for satellites.
        val fallback = if (allowed.contains(Band.BAND_20)) Band.BAND_20 else allowed.last()
        currentBand    = fallback
        currentFreqMhz = fallback.freqStart + (if (fallback == Band.BAND_2) 0.1 else 0.025)
        updateFreqDisplay()
    }

    // ── Mode-dependent widgets ────────────────────────────────────────────────

    /**
     * Satellite QSOs are just a signal report between the two stations: there is
     * no serial number to track and nothing to type into the exchange box, so
     * both are hidden while that mode is selected.
     */
    private fun applyModeVisibility() {
        val satellite = settings.contestMode == ContestMode.SATELLITE

        tvSerial.visibility         = if (satellite) View.GONE else View.VISIBLE
        tvExchangeLabel.visibility  = if (satellite) View.GONE else View.VISIBLE
        etExchange.visibility       = if (satellite) View.GONE else View.VISIBLE

        // With the exchange box gone the callsign field is the last input, so
        // its IME action logs the QSO instead of jumping to a hidden field.
        etCallsign.imeOptions = if (satellite) EditorInfo.IME_ACTION_DONE
                                else EditorInfo.IME_ACTION_NEXT
        if (satellite && etExchange.hasFocus()) etCallsign.requestFocus()
    }

    // ── Soft keyboard (IME) handling ──────────────────────────────────────────

    /**
     * The screen is locked to portrait and the window uses `adjustResize`, so
     * the on-screen keyboard shrinks the layout. Once it is up there is not
     * enough room for everything, and the F1–F12 macro buttons – being the
     * lowest block – used to be pushed off screen and disappear.
     *
     * While the keyboard is visible the optional rows (paddle, TX hint, QSO
     * list, the second top-bar row) step aside so the callsign / exchange boxes
     * and the whole macro grid stay on screen. They come back as soon as the
     * keyboard is dismissed.
     */
    private fun setupImeAwareLayout() {
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            val height = rootLayout.height
            if (height <= 0) return@addOnGlobalLayoutListener
            if (height > fullRootHeightPx) fullRootHeightPx = height
            val keyboardUp = height < fullRootHeightPx - dp(110)
            if (keyboardUp != typingMode) applyTypingMode(keyboardUp)
        }
    }

    private fun applyTypingMode(typing: Boolean) {
        typingMode = typing
        val vis = if (typing) View.GONE else View.VISIBLE
        rowTopKeys?.visibility  = vis
        tvTxHint.visibility     = vis
        rowPaddle.visibility    = vis
        rowLogHeader.visibility = vis
        rvLog.visibility        = vis
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── WPM ───────────────────────────────────────────────────────────────────

    private fun setupWpmControls() {
        seekWpm.max      = 55                       // 5..60 WPM
        seekWpm.progress = (settings.wpm - 5).coerceIn(0, 55)
        tvWpm.text       = "${settings.wpm} WPM"

        seekWpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                applyWpm(progress + 5, save = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyWpm(seekWpm.progress + 5, true) }
        })

        findViewById<Button>(R.id.btnWpmMinus).setOnClickListener {
            applyWpm(cwEngine.wpm() - 1, true)
        }
        findViewById<Button>(R.id.btnWpmPlus).setOnClickListener {
            applyWpm(cwEngine.wpm() + 1, true)
        }
        // Numeric entry: tap the "25 WPM" label
        tvWpm.setOnClickListener { showWpmDialog() }
    }

    private fun applyWpm(wpm: Int, save: Boolean) {
        val w = wpm.coerceIn(5, 60)
        tvWpm.text     = "$w WPM"
        seekWpm.progress = w - 5
        cwEngine.setWpm(w)
        if (save) {
            settings = settings.copy(wpm = w)
            settingsMgr.save(settings)
        }
    }

    private fun showWpmDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cwEngine.wpm().toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("CW 速度 (5–60 WPM)")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                input.text.toString().toIntOrNull()?.let { applyWpm(it, true) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── Callsign / frequency input ────────────────────────────────────────────

    /**
     * Parse the callsign box content as a frequency in MHz.
     * Accepts "14.025", "14025" (kHz) and "144.1"; returns null for callsigns.
     */
    private fun parseFrequency(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty() || !t.matches(Regex("\\d{1,4}(\\.\\d{1,6})?"))) return null
        val value = t.toDoubleOrNull() ?: return null
        val mhz = when {
            t.contains('.')  -> value              // already MHz
            value >= 1000    -> value / 1000.0     // 14025 → 14.025 MHz
            value >= 1.8     -> value              // 14 → 14 MHz
            else             -> return null
        }
        return mhz.takeIf { it in 1.8..450.0 }      // 160 m … 70 cm
    }

    private fun setupCallsignInput() {
        etCallsign.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val text = e?.toString() ?: return
                if (text.isBlank()) {
                    etCallsign.setTextColor(color(R.attr.cwText))
                    return
                }
                val freq = parseFrequency(text)
                if (freq != null) {
                    // Typing a frequency only updates the display – no hardware.
                    applyFrequency(freq)
                    etCallsign.setTextColor(color(R.attr.cwText))
                    return
                }
                val call = text.trim().uppercase()
                val dupe = logMgr.isDupe(call, currentBand, settings.contestMode)
                etCallsign.setTextColor(color(if (dupe) R.attr.cwWarn else R.attr.cwText))
                if (dupe) updateStatus("重复: $call 已在 ${currentBand.displayName} 通联过")
            }
        })

        etCallsign.setOnEditorActionListener { _, actionId, _ ->
            val text = etCallsign.text.toString()
            val freq = parseFrequency(text)
            when {
                freq != null -> {
                    applyFrequency(freq)
                    etCallsign.setText("")
                    true
                }
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_DONE -> {
                    // Satellite mode hides the exchange box – log straight away.
                    if (etExchange.visibility == View.VISIBLE) etExchange.requestFocus()
                    else logQso()
                    true
                }
                else -> false
            }
        }

        etExchange.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO) {
                logQso(); true
            } else false
        }
    }

    private fun applyFrequency(mhz: Double) {
        currentFreqMhz = mhz
        Band.fromFreq(mhz)?.let { currentBand = it }
        updateFreqDisplay()
    }

    private fun updateFreqDisplay() {
        val bandOk = Band.forContest(settings.contestMode).contains(currentBand)
        val label  = if (bandOk) currentBand.displayName else "${currentBand.displayName}!"
        // Only the band is shown; the exact frequency stays internal (it still
        // goes into the QSO log and the {FREQ} macro variable).
        tvFreq.text = label
        tvFreq.setTextColor(color(if (bandOk) R.attr.cwAccentAlt else R.attr.cwWarn))
    }

    /** Show what this station must send for the current contest mode */
    private fun updateTxHint() {
        val exch = contestMgr.buildSentExchange(serial = contestMgr.peekNextSerial())
        val warn = when {
            settings.myCallsign.isBlank() -> "  ⚠ 请设置呼号"
            settings.contestMode == ContestMode.CQ_WW_VHF && settings.myGrid.isBlank() ->
                "  ⚠ 请设置网格"
            else -> ""
        }
        tvTxHint.text = "发: $exch$warn"
    }

    // ── Macro grid F1–F12 ─────────────────────────────────────────────────────

    private fun setupMacroGrid() {
        macros = settingsMgr.loadMacros(
            settings.contestMode,
            contestMgr.defaultMacros(settings.contestMode)
        )
        macroGrid.removeAllViews()
        val cols = resources.getInteger(R.integer.macro_columns)
        macroGrid.columnCount = cols
        macroGrid.rowCount    = (macros.size + cols - 1) / cols

        macros.forEach { macro ->
            val btn = Button(this).apply {
                text     = "F${macro.functionKey}  ${macro.label}"
                textSize = 9f
                // Button's default 48dp min height would make the 3-row grid
                // taller than the space the portrait screen can spare.
                minHeight = 0
                minWidth  = 0
                setPadding(2, 2, 2, 2)
                setBackgroundColor(color(R.attr.cwButton))
                setTextColor(color(R.attr.cwText))
                setOnClickListener   { sendMacro(macro) }
                setOnLongClickListener { showMacroMenu(macro); true }
            }
            val col = (macro.functionKey - 1) % cols
            val row = (macro.functionKey - 1) / cols
            val lp  = GridLayout.LayoutParams(
                GridLayout.spec(row, GridLayout.FILL, 1f),
                GridLayout.spec(col, GridLayout.FILL, 1f)
            ).apply {
                width  = 0
                height = dp(40)
                setMargins(2, 2, 2, 2)
            }
            macroGrid.addView(btn, lp)
        }
    }

    private fun showMacroMenu(macro: Macro) {
        AlertDialog.Builder(this)
            .setTitle("F${macro.functionKey} · ${macro.label}")
            .setItems(arrayOf("编辑模板", "重命名按钮", "立即发送", "恢复全部默认")) { _, which ->
                when (which) {
                    0 -> editMacro(macro)
                    1 -> renameMacro(macro)
                    2 -> sendMacro(macro)
                    3 -> {
                        settingsMgr.resetMacros(settings.contestMode)
                        setupMacroGrid()
                        toast("宏已恢复默认")
                    }
                }
            }
            .show()
    }

    private fun editMacro(macro: Macro) {
        val et = EditText(this).apply {
            setText(macro.template)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(color(R.attr.cwText))
            setBackgroundColor(color(R.attr.cwField))
            setPadding(12, 12, 12, 12)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑 F${macro.functionKey} 模板")
            .setMessage("可用变量: {MYCALL} {CALL} {RST} {SERIAL} {NR} {ZONE} {GRID} {THEIRGRID} {BAND} {FREQ}")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                saveMacro(macro.copy(template = et.text.toString().trim()))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameMacro(macro: Macro) {
        val et = EditText(this).apply {
            setText(macro.label)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("重命名 F${macro.functionKey}")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                saveMacro(macro.copy(label = et.text.toString().trim().take(10)))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveMacro(updated: Macro) {
        val list = macros.toMutableList()
        val idx  = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        macros = list
        settingsMgr.saveMacros(settings.contestMode, macros)
        setupMacroGrid()
    }

    // ── Log view ──────────────────────────────────────────────────────────────

    private fun setupLogView() {
        logAdapter = LogAdapter(logMgr.entries.toMutableList()) { entry -> showQsoOptions(entry) }
        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter
    }

    // ── Buttons / menu ────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnLog.setOnClickListener   { logQso() }
        btnAbort.setOnClickListener {
            stopCqLoop()
            cwEngine.abort()
        }
        findViewById<Button>(R.id.btnUsb).setOnClickListener { showUsbDialog() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menuInflater.inflate(R.menu.menu_main, menu)
                setOnMenuItemClickListener { item -> onOptionsItemSelected(item) }
                show()
            }
        }
    }

    private fun setupPaddles() {
        val touch = View.OnTouchListener { v, event ->
            val pressed = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
                else -> return@OnTouchListener false
            }
            when (v.id) {
                R.id.btnDot  -> cwEngine.setDotPaddle(pressed)
                R.id.btnDash -> cwEngine.setDashPaddle(pressed)
            }
            v.isPressed = pressed     // manual visual feedback (we consume the event)
            true
        }
        btnDot.setOnTouchListener(touch)
        btnDash.setOnTouchListener(touch)
    }

    // ── USB device dialog ─────────────────────────────────────────────────────

    private fun showUsbDialog() {
        val devices = usbMgr.listDevices()
        if (devices.isEmpty()) {
            toast("未找到 USB 串口设备。\n请接入 CH340 / CP210x / FTDI 转接板。")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择 USB CW 接口")
            .setItems(devices.map { it.displayName }.toTypedArray()) { _, idx ->
                usbMgr.requestPermissionAndOpen(
                    devices[idx], settings.baudRate, idleLevel = settings.invertLogic
                )
            }
            .setNegativeButton("断开连接") { _, _ -> usbMgr.closePort() }
            .show()
    }

    // ── Send macro ────────────────────────────────────────────────────────────

    private fun sendMacro(macro: Macro) {
        val text = MacroEngine.safeExpand(macro.template, buildMacroContext())
        lastSentMacro = text
        cwEngine.send(text)

        // F1 with CQ loop enabled starts the loop (N1MM behaviour)
        if (macro.functionKey == 1 && settings.cqLoopEnabled && !cqLooping) {
            startCqLoop(macro)
        }
    }

    private fun buildMacroContext() = MacroContext(
        myCallsign    = settings.myCallsign,
        theirCallsign = etCallsign.text.toString().trim(),
        rst           = "599",
        serial        = contestMgr.formatSerial(contestMgr.peekNextSerial()),
        zone          = "%02d".format(settings.myZone),
        grid          = settings.myGrid,
        nr            = etExchange.text.toString().trim(),
        band          = currentBand.displayName,
        freq          = String.format(Locale.US, "%.3f", currentFreqMhz)
    )

    // ── CQ loop ───────────────────────────────────────────────────────────────

    private fun startCqLoop(macro: Macro) {
        cqLooping = true
        updateStatus("CQ 循环中 – 正在重复 F1")
        scheduleNextCq(macro)
    }

    private fun scheduleNextCq(macro: Macro) {
        if (!cqLooping) return
        cqHandler.postDelayed({
            if (cqLooping) {
                if (!cwEngine.isBusy()) {
                    sendMacro(macro)
                    vibrate(40)
        updateStatus("CQ 循环中 – 下一轮已排队")
                }
                scheduleNextCq(macro)     // interval is measured end-to-end
            }
        }, settings.cqLoopIntervalMs.toLong().coerceAtLeast(500L))
    }

    private fun stopCqLoop() {
        if (!cqLooping) return
        cqLooping = false
        cqHandler.removeCallbacksAndMessages(null)
        updateStatus("就绪")
    }

    // ── Log QSO ───────────────────────────────────────────────────────────────

    private fun logQso() {
        val call = etCallsign.text.toString().trim().uppercase()
        if (call.isEmpty() || parseFrequency(call) != null) {
            toast("请先输入呼号")
            return
        }

        val dupe = logMgr.isDupe(call, currentBand, settings.contestMode)
        val typed = etExchange.text.toString().trim().uppercase()
        // Satellite mode has no exchange box; the QSO is exchanged with the
        // same signal report we sent, so log that in the "rcvd" column.
        val rcvd = if (typed.isEmpty() && settings.contestMode == ContestMode.SATELLITE)
                       contestMgr.buildSentExchange() else typed
        val serial = contestMgr.consumeSerial()                 // null for WW / VHF
        val sent   = contestMgr.buildSentExchange(
            serial = serial ?: contestMgr.peekNextSerial()
        )

        logMgr.addQSO(
            QSOEntry(
                callsign     = call,
                sentExchange = sent,
                rcvdExchange = rcvd,
                band         = currentBand,
                frequency    = currentFreqMhz,
                contestMode  = settings.contestMode,
                serialNumber = serial,
                notes        = lastSentMacro
            )
        )
        contestMgr.markWorked(call, currentBand, settings.contestMode)
        logAdapter.prepend(logMgr.entries.first())
        rvLog.scrollToPosition(0)

        etCallsign.setText("")
        etExchange.setText("")
        etCallsign.requestFocus()
        updateSerial()
        updateTxHint()
        vibrate(30)

        lastSentMacro = ""
        if (dupe) toast("已记录重复: $call (${currentBand.displayName})")
        else      updateStatus("已记录 $call  ${sent}  /  $rcvd")
    }

    private fun showQsoOptions(entry: QSOEntry) {
        AlertDialog.Builder(this)
            .setTitle("${entry.callsign}  ·  ${entry.timeStr}Z  ·  ${entry.band.displayName}")
            .setItems(arrayOf("重发交换信息", "删除此条", "取消")) { _, which ->
                when (which) {
                    0 -> {
                        cwEngine.send("${entry.callsign} ${entry.sentExchange}")
                        updateStatus("正在重发 ${entry.callsign}")
                    }
                    1 -> {
                        logMgr.removeQSO(entry.id)
                        logAdapter.remove(entry.id)
                    }
                }
            }
            .show()
    }

    // ── Hardware keyboard ─────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val fNum = keyCode - KeyEvent.KEYCODE_F1 + 1
        if (fNum in 1..12) {
            macros.firstOrNull { it.functionKey == fNum }?.let { sendMacro(it); return true }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                stopCqLoop(); cwEngine.abort(); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                cwEngine.setDashPaddle(true); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                cwEngine.setDotPaddle(true); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                cwEngine.setDashPaddle(false); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                cwEngine.setDotPaddle(false); return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export_adif -> {
            exportWithPermission { logMgr.saveAdifToDownloads() }
            true
        }
        R.id.action_export_csv -> {
            exportWithPermission { logMgr.saveCsvToDownloads() }
            true
        }
        R.id.action_reset_macros -> {
            settingsMgr.resetMacros(settings.contestMode)
            setupMacroGrid()
            toast("已恢复 ${settings.contestMode.displayName} 的默认宏")
            true
        }
        R.id.action_clear_log -> {
            AlertDialog.Builder(this)
                .setTitle("清空日志？")
                .setMessage("要删除全部 ${logMgr.count()} 条 QSO 记录吗？此操作不可撤销。")
                .setPositiveButton("清空") { _, _ ->
                    logMgr.clear()
                    contestMgr.reset()
                    contestMgr.clearDupes()
                    logAdapter.clearAll()
                    updateSerial()
                    updateTxHint()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** XML onClick target for the ⚙ button in the layout */
    fun openSettings(@Suppress("UNUSED_PARAMETER") view: View) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ── Storage permission (only needed on Android 8/9) ──────────────────────

    private fun exportWithPermission(saver: () -> String?) {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            toast(saver()?.let { "已保存到:\n$it" } ?: "导出失败")
            return
        }
        pendingExport = saver
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val cb = pendingExport
            pendingExport = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                toast(cb?.invoke()?.let { "已保存到:\n$it" } ?: "导出失败")
            } else {
                toast("未授予存储权限 – 导出已取消")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateSerial() {
        tvSerial.text = "序号:${contestMgr.formatSerial(contestMgr.peekNextSerial())}"
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vib.vibrate(ms)
        }
    }

    // ── CW engine listener ────────────────────────────────────────────────────

    private val cwListener = object : CWEngine.Listener {
        override fun onKeyDown() {
            runOnUiThread {
                tvRts.setTextColor(color(R.attr.cwAccentAlt))
                tvDtr.setTextColor(color(R.attr.cwAccentAlt))
            }
        }
        override fun onKeyUp() {
            runOnUiThread {
                tvRts.setTextColor(color(R.attr.cwTextDim))
                tvDtr.setTextColor(color(R.attr.cwTextDim))
            }
        }
        override fun onMessageStart(text: String) {
            runOnUiThread { updateStatus("▶ $text") }
        }
        override fun onMessageComplete() {
            runOnUiThread {
                updateStatus(if (cqLooping) "CQ 循环中" else "就绪")
                vibrate(15)
            }
        }
        override fun onAborted() {
            runOnUiThread { updateStatus("已中止") }
        }
    }

    // ── USB listener ──────────────────────────────────────────────────────────

    private val usbListener = object : USBSerialManager.Listener {
        override fun onDeviceAttached(device: android.hardware.usb.UsbDevice) {
            runOnUiThread { toast("USB: ${device.productName ?: "device"} 已接入") }
        }
        override fun onDeviceDetached() {
            runOnUiThread { updateStatus("USB 已断开") }
        }
        override fun onPortOpened() {
            cwEngine.setIdle()
            runOnUiThread {
                updateStatus("USB 已连接 – 键控线空闲")
                tvRts.setTextColor(color(R.attr.cwTextDim))
            }
        }
        override fun onPortClosed() {
            runOnUiThread { updateStatus("USB 已关闭") }
        }
        override fun onError(msg: String) {
            runOnUiThread { toast("USB: $msg") }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// QSO log adapter
// ══════════════════════════════════════════════════════════════════════════════

class LogAdapter(
    private val items: MutableList<QSOEntry>,
    private val onClick: (QSOEntry) -> Unit
) : RecyclerView.Adapter<LogAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvLogTime)
        val tvCall: TextView = v.findViewById(R.id.tvLogCall)
        val tvSent: TextView = v.findViewById(R.id.tvLogSent)
        val tvRcvd: TextView = v.findViewById(R.id.tvLogRcvd)
        val tvBand: TextView = v.findViewById(R.id.tvLogBand)
        val tvNr:   TextView = v.findViewById(R.id.tvLogNr)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_qso, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = items[pos]
        h.tvTime.text = e.timeStr
        h.tvCall.text = e.callsign
        h.tvSent.text = e.sentExchange
        h.tvRcvd.text = e.rcvdExchange
        h.tvBand.text = e.band.displayName
        h.tvNr.text   = e.serialNumber?.let { "%03d".format(it) } ?: ""
        h.itemView.setOnClickListener { onClick(e) }
    }

    override fun getItemCount() = items.size

    fun prepend(e: QSOEntry) { items.add(0, e); notifyItemInserted(0) }

    fun remove(id: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }

    fun clearAll() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }
}
