package com.cwcontest

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsMgr: SettingsManager
    private lateinit var settings: AppSettings

    private lateinit var etMyCall:    EditText
    private lateinit var etMyGrid:    EditText
    private lateinit var etMyZone:    EditText
    private lateinit var etSerial:    EditText
    private lateinit var switchRts:   Switch
    private lateinit var switchDtr:   Switch
    private lateinit var switchInv:   Switch
    private lateinit var spinBaud:    Spinner
    private lateinit var spinKeyer:   Spinner
    private lateinit var switchSide:  Switch
    private lateinit var etSideFreq:  EditText
    private lateinit var seekSideVol: SeekBar
    private lateinit var switchCqLoop:Switch
    private lateinit var etCqInterval:EditText
    private lateinit var switchDark:  Switch
    private lateinit var etTailGap:   EditText
    private var appliedTheme = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = SettingsManager(this).load()
        appliedTheme = if (current.darkMode) R.style.Theme_CWContest
                       else R.style.Theme_CWContest_Light
        setTheme(appliedTheme)
        setContentView(R.layout.activity_settings)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "设置" }

        settingsMgr = SettingsManager(this)
        settings    = current

        etMyCall     = findViewById(R.id.etMyCall)
        etMyGrid     = findViewById(R.id.etMyGrid)
        etMyZone     = findViewById(R.id.etMyZone)
        etSerial     = findViewById(R.id.etSerial)
        switchRts    = findViewById(R.id.switchRts)
        switchDtr    = findViewById(R.id.switchDtr)
        switchInv    = findViewById(R.id.switchInvert)
        spinBaud     = findViewById(R.id.spinBaud)
        spinKeyer    = findViewById(R.id.spinKeyer)
        switchSide   = findViewById(R.id.switchSidetone)
        etSideFreq   = findViewById(R.id.etSidetoneFreq)
        seekSideVol  = findViewById(R.id.seekSidetoneVol)
        switchCqLoop = findViewById(R.id.switchCqLoop)
        etCqInterval = findViewById(R.id.etCqInterval)
        switchDark   = findViewById(R.id.switchDark)
        etTailGap    = findViewById(R.id.etTailGap)

        val bauds = arrayOf("1200","2400","4800","9600","19200","38400","57600","115200")
        spinBaud.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, bauds).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val keyerModes = KeyerMode.values().map { it.displayName }
        spinKeyer.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, keyerModes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Populate fields
        etMyCall.setText(settings.myCallsign)
        etMyGrid.setText(settings.myGrid)
        etMyZone.setText(settings.myZone.toString())
        etSerial.setText(settings.startSerial.toString())
        switchRts.isChecked   = settings.useRTS
        switchDtr.isChecked   = settings.useDTR
        switchInv.isChecked   = settings.invertLogic
        val baudList = listOf(1200,2400,4800,9600,19200,38400,57600,115200)
        spinBaud.setSelection(baudList.indexOf(settings.baudRate).coerceAtLeast(0))
        spinKeyer.setSelection(KeyerMode.values().indexOf(settings.keyerMode))
        switchSide.isChecked   = settings.sidetoneEnabled
        etSideFreq.setText(settings.sidetoneFreqHz.toString())
        seekSideVol.progress   = (settings.sidetoneVolume * 100).toInt()
        switchCqLoop.isChecked = settings.cqLoopEnabled
        etCqInterval.setText((settings.cqLoopIntervalMs / 1000.0).toString())
        switchDark.isChecked  = settings.darkMode
        etTailGap.setText(settings.tailGapUnits.toString())

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            save(); finish()
        }
    }

    private fun save() {
        val baudList = listOf(1200,2400,4800,9600,19200,38400,57600,115200)
        val updated = settings.copy(
            myCallsign       = etMyCall.text.toString().trim().uppercase(),
            myGrid           = etMyGrid.text.toString().trim().uppercase().take(4),
            myZone           = etMyZone.text.toString().toIntOrNull() ?: settings.myZone,
            startSerial      = etSerial.text.toString().toIntOrNull() ?: 1,
            useRTS           = switchRts.isChecked,
            useDTR           = switchDtr.isChecked,
            invertLogic      = switchInv.isChecked,
            baudRate         = baudList.getOrElse(spinBaud.selectedItemPosition) { 9600 },
            keyerMode        = KeyerMode.values()
                .getOrElse(spinKeyer.selectedItemPosition) { settings.keyerMode },
            sidetoneEnabled  = switchSide.isChecked,
            sidetoneFreqHz   = etSideFreq.text.toString().toIntOrNull() ?: 700,
            sidetoneVolume   = seekSideVol.progress / 100f,
            cqLoopEnabled    = switchCqLoop.isChecked,
            cqLoopIntervalMs = ((etCqInterval.text.toString().toDoubleOrNull()
                                 ?: 5.0) * 1000).toInt().coerceAtLeast(500),
            darkMode         = switchDark.isChecked,
            tailGapUnits     = (etTailGap.text.toString().toIntOrNull() ?: 1).coerceIn(0, 10)
        )
        settingsMgr.save(updated)
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
