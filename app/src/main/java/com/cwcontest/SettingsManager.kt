package com.cwcontest

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SettingsManager
 *
 * Persists [AppSettings] to SharedPreferences.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cwcontest_prefs", Context.MODE_PRIVATE)

    fun save(s: AppSettings) {
        prefs.edit().apply {
            putInt("wpm",              s.wpm)
            putString("keyerMode",     s.keyerMode.name)
            putBoolean("useRTS",       s.useRTS)
            putBoolean("useDTR",       s.useDTR)
            putBoolean("invertLogic",  s.invertLogic)
            putInt("baudRate",         s.baudRate)
            putBoolean("sidetone",     s.sidetoneEnabled)
            putInt("sidetoneFreq",     s.sidetoneFreqHz)
            putFloat("sidetoneVol",    s.sidetoneVolume)
            putString("contestMode",   s.contestMode.name)
            putString("myCallsign",    s.myCallsign)
            putString("myGrid",        s.myGrid)
            putInt("myZone",           s.myZone)
            putInt("startSerial",      s.startSerial)
            putBoolean("darkMode",     s.darkMode)
            putBoolean("cqLoop",       s.cqLoopEnabled)
            putInt("cqLoopMs",         s.cqLoopIntervalMs)
            putInt("tailGapUnits",     s.tailGapUnits)
        }.apply()
    }

    fun load(): AppSettings = AppSettings(
        wpm              = prefs.getInt("wpm", 25),
        keyerMode        = runCatching {
            KeyerMode.valueOf(prefs.getString("keyerMode", "STRAIGHT")!!)
        }.getOrDefault(KeyerMode.STRAIGHT),
        useRTS           = prefs.getBoolean("useRTS", true),
        useDTR           = prefs.getBoolean("useDTR", false),
        invertLogic      = prefs.getBoolean("invertLogic", false),
        baudRate         = prefs.getInt("baudRate", 9600),
        sidetoneEnabled  = prefs.getBoolean("sidetone", true),
        sidetoneFreqHz   = prefs.getInt("sidetoneFreq", 700),
        sidetoneVolume   = prefs.getFloat("sidetoneVol", 0.5f),
        contestMode      = runCatching {
            ContestMode.valueOf(prefs.getString("contestMode", "CQ_WW_CW")!!)
        }.getOrDefault(ContestMode.CQ_WW_CW),
        myCallsign       = prefs.getString("myCallsign", "") ?: "",
        myGrid           = prefs.getString("myGrid", "") ?: "",
        myZone           = prefs.getInt("myZone", 14),
        startSerial      = prefs.getInt("startSerial", 1),
        darkMode         = prefs.getBoolean("darkMode", true),
        cqLoopEnabled    = prefs.getBoolean("cqLoop", false),
        cqLoopIntervalMs = prefs.getInt("cqLoopMs", 5000),
        tailGapUnits     = prefs.getInt("tailGapUnits", 1)
    )

    // ── Macro sets (one editable set per contest mode) ────────────────────────

    /**
     * Load the user-edited macro set for [mode]. Falls back to [defaults] for
     * any function key the user has never customised, so new default macros
     * keep appearing after an app update.
     */
    fun loadMacros(mode: ContestMode, defaults: List<Macro>): List<Macro> {
        val raw = prefs.getString(macroKey(mode), null) ?: return defaults
        val saved = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Macro(
                    id          = o.getInt("id"),
                    label       = o.getString("label"),
                    template    = o.getString("template"),
                    functionKey = o.getInt("functionKey")
                )
            }
        }.getOrDefault(emptyList())
        if (saved.isEmpty()) return defaults
        // defaults first, then user overrides keyed by function key
        return defaults.map { def ->
            saved.firstOrNull { it.functionKey == def.functionKey } ?: def
        }
    }

    fun saveMacros(mode: ContestMode, macros: List<Macro>) {
        val arr = JSONArray()
        macros.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("label", m.label)
                put("template", m.template)
                put("functionKey", m.functionKey)
            })
        }
        prefs.edit().putString(macroKey(mode), arr.toString()).apply()
    }

    fun resetMacros(mode: ContestMode) {
        prefs.edit().remove(macroKey(mode)).apply()
    }

    private fun macroKey(mode: ContestMode) = "macros_${mode.name}"
}
