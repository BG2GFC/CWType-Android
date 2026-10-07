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
        setupContestSpinner()
        setupWpmControls()
        setupCallsignInput()
        setupMacroGrid()
        setupLogView()
        setupButtons()
        setupPaddles()

        cwEngine.start()
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
        // 20m for the HF contests, 2m for the VHF contest
        val fallback = if (allowed.contains(Band.BAND_20)) Band.BAND_20 else allowed.last()
        currentBand    = fallback
        currentFreqMhz = fallback.freqStart + (if (fallback == Band.BAND_2) 0.1 else 0.025)
        updateFreqDisplay()
    }

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
            .setTitle("CW Speed (5–60 WPM)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toIntOrNull()?.let { applyWpm(it, true) }
            }
            .setNegativeButton("Cancel", null)
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
        return mhz.takeIf { it in 1.8..148.0 }
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
                if (dupe) updateStatus("DUPE: $call already worked on ${currentBand.displayName}")
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
                    etExchange.requestFocus()
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
        tvFreq.text = String.format(Locale.US, "%.3f MHz · %s", currentFreqMhz, label)
        tvFreq.setTextColor(color(if (bandOk) R.attr.cwAccentAlt else R.attr.cwWarn))
    }

    /** Show what this station must send for the current contest mode */
    private fun updateTxHint() {
        val exch = contestMgr.buildSentExchange(serial = contestMgr.peekNextSerial())
        val warn = when {
            settings.myCallsign.isBlank() -> "  ⚠ set callsign"
            settings.contestMode == ContestMode.CQ_WW_VHF && settings.myGrid.isBlank() ->
                "  ⚠ set grid"
            else -> ""
        }
        tvTxHint.text = "TX: $exch$warn"
    }

    // ── Macro grid F1–F12 ─────────────────────────────────────────────────────

    private fun setupMacroGrid() {
        macros = settingsMgr.loadMacros(
            settings.contestMode,
            contestMgr.defaultMacros(settings.contestMode)
        )
        macroGrid.removeAllViews()
        macroGrid.columnCount = 4
        macroGrid.rowCount    = 3

        macros.forEach { macro ->
            val btn = Button(this).apply {
                text     = "F${macro.functionKey}  ${macro.label}"
                textSize = 9f
                setPadding(2, 2, 2, 2)
                setBackgroundColor(color(R.attr.cwButton))
                setTextColor(color(R.attr.cwText))
                setOnClickListener   { sendMacro(macro) }
                setOnLongClickListener { showMacroMenu(macro); true }
            }
            val col = (macro.functionKey - 1) % 4
            val row = (macro.functionKey - 1) / 4
            val lp  = GridLayout.LayoutParams(
                GridLayout.spec(row, GridLayout.FILL, 1f),
                GridLayout.spec(col, GridLayout.FILL, 1f)
            ).apply {
                width  = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(2, 2, 2, 2)
            }
            macroGrid.addView(btn, lp)
        }
    }

    private fun showMacroMenu(macro: Macro) {
        AlertDialog.Builder(this)
            .setTitle("F${macro.functionKey} · ${macro.label}")
            .setItems(arrayOf("Edit template", "Rename button", "Send now", "Restore all defaults")) { _, which ->
                when (which) {
                    0 -> editMacro(macro)
                    1 -> renameMacro(macro)
                    2 -> sendMacro(macro)
                    3 -> {
                        settingsMgr.resetMacros(settings.contestMode)
                        setupMacroGrid()
                        toast("Macros restored to defaults")
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
            .setTitle("Edit F${macro.functionKey} template")
            .setMessage("Variables: {MYCALL} {CALL} {RST} {SERIAL} {NR} {ZONE} {GRID} {THEIRGRID} {BAND} {FREQ}")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                saveMacro(macro.copy(template = et.text.toString().trim()))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameMacro(macro: Macro) {
        val et = EditText(this).apply {
            setText(macro.label)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Rename F${macro.functionKey}")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                saveMacro(macro.copy(label = et.text.toString().trim().take(10)))
            }
            .setNegativeButton("Cancel", null)
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
            toast("No USB serial device found.\nConnect a CH340 / CP210x / FTDI interface.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Select USB CW interface")
            .setItems(devices.map { it.displayName }.toTypedArray()) { _, idx ->
                usbMgr.requestPermissionAndOpen(
                    devices[idx], settings.baudRate, idleLevel = settings.invertLogic
                )
            }
            .setNegativeButton("Disconnect") { _, _ -> usbMgr.closePort() }
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
        updateStatus("CQ LOOP ACTIVE – F1 repeating")
        scheduleNextCq(macro)
    }

    private fun scheduleNextCq(macro: Macro) {
        if (!cqLooping) return
        cqHandler.postDelayed({
            if (cqLooping) {
                if (!cwEngine.isBusy()) {
                    sendMacro(macro)
                    vibrate(40)
                    updateStatus("CQ LOOP ACTIVE – next call queued")
                }
                scheduleNextCq(macro)     // interval is measured end-to-end
            }
        }, settings.cqLoopIntervalMs.toLong().coerceAtLeast(500L))
    }

    private fun stopCqLoop() {
        if (!cqLooping) return
        cqLooping = false
        cqHandler.removeCallbacksAndMessages(null)
        updateStatus("Ready")
    }

    // ── Log QSO ───────────────────────────────────────────────────────────────

    private fun logQso() {
        val call = etCallsign.text.toString().trim().uppercase()
        if (call.isEmpty() || parseFrequency(call) != null) {
            toast("Enter a callsign first")
            return
        }

        val dupe = logMgr.isDupe(call, currentBand, settings.contestMode)
        val rcvd = etExchange.text.toString().trim().uppercase()
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
        if (dupe) toast("Logged DUPE: $call on ${currentBand.displayName}")
        else      updateStatus("Logged $call  ${sent}  /  $rcvd")
    }

    private fun showQsoOptions(entry: QSOEntry) {
        AlertDialog.Builder(this)
            .setTitle("${entry.callsign}  ·  ${entry.timeStr}Z  ·  ${entry.band.displayName}")
            .setItems(arrayOf("Re-send exchange", "Delete entry", "Cancel")) { _, which ->
                when (which) {
                    0 -> {
                        cwEngine.send("${entry.callsign} ${entry.sentExchange}")
                        updateStatus("Re-sending ${entry.callsign}")
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
            toast("Macros for ${settings.contestMode.displayName} restored")
            true
        }
        R.id.action_clear_log -> {
            AlertDialog.Builder(this)
                .setTitle("Clear log?")
                .setMessage("Delete all ${logMgr.count()} QSO entries? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    logMgr.clear()
                    contestMgr.reset()
                    contestMgr.clearDupes()
                    logAdapter.clearAll()
                    updateSerial()
                    updateTxHint()
                }
                .setNegativeButton("Cancel", null)
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
        if (granted) {
            toast(saver()?.let { "Saved to:\n$it" } ?: "Export failed")
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
                toast(cb?.invoke()?.let { "Saved to:\n$it" } ?: "Export failed")
            } else {
                toast("Storage permission denied – export cancelled")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateSerial() {
        tvSerial.text = "NR:${contestMgr.formatSerial(contestMgr.peekNextSerial())}"
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
                updateStatus(if (cqLooping) "CQ LOOP ACTIVE" else "Ready")
                vibrate(15)
            }
        }
        override fun onAborted() {
            runOnUiThread { updateStatus("Aborted") }
        }
    }

    // ── USB listener ──────────────────────────────────────────────────────────

    private val usbListener = object : USBSerialManager.Listener {
        override fun onDeviceAttached(device: android.hardware.usb.UsbDevice) {
            runOnUiThread { toast("USB: ${device.productName ?: "device"} attached") }
        }
        override fun onDeviceDetached() {
            runOnUiThread { updateStatus("USB disconnected") }
        }
        override fun onPortOpened() {
            cwEngine.setIdle()
            runOnUiThread {
                updateStatus("USB connected – key line idle")
                tvRts.setTextColor(color(R.attr.cwTextDim))
            }
        }
        override fun onPortClosed() {
            runOnUiThread { updateStatus("USB closed") }
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
