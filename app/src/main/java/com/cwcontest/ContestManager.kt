package com.cwcontest

/**
 * ContestManager
 *
 * Tracks per-contest state:
 *  - Serial number / QSO counter
 *  - Macro template sets per contest
 *  - Exchange field logic per contest mode
 *  - Dupe checking (call → worked list)
 */
class ContestManager(private var settings: AppSettings) {

    // ── Serial Numbers ────────────────────────────────────────────────────────
    // Only CQ WPX CW sends a serial number. Each contest mode keeps its own
    // counter so switching modes never disturbs an in-progress run.
    private val serialMap = mutableMapOf<ContestMode, Int>()

    init { reset() }

    fun reset() {
        ContestMode.values().forEach { serialMap[it] = settings.startSerial }
    }

    /** True when the contest exchange contains a serial number (CQ WPX only) */
    fun usesSerial(mode: ContestMode = settings.contestMode): Boolean =
        mode == ContestMode.CQ_WPX_CW

    fun currentSerial(mode: ContestMode = settings.contestMode): Int =
        serialMap[mode] ?: settings.startSerial

    fun incrementSerial(mode: ContestMode = settings.contestMode): Int {
        val next = (serialMap[mode] ?: settings.startSerial) + 1
        serialMap[mode] = next
        return next - 1   // return the one just used
    }

    /**
     * Serial number for a QSO that is being logged right now, or null when the
     * contest mode does not use one. Consuming advances the counter.
     */
    fun consumeSerial(mode: ContestMode = settings.contestMode): Int? =
        if (usesSerial(mode)) incrementSerial(mode) else null

    fun peekNextSerial(mode: ContestMode = settings.contestMode): Int =
        serialMap[mode] ?: settings.startSerial

    fun formatSerial(n: Int): String = "%03d".format(n)

    // ── Dupe Checking ─────────────────────────────────────────────────────────
    // Key: "MODE|BAND|CALLSIGN"
    private val workedSet = mutableSetOf<String>()

    fun markWorked(callsign: String, band: Band, mode: ContestMode) {
        workedSet.add(dupeKey(callsign, band, mode))
    }

    fun isDupe(callsign: String, band: Band, mode: ContestMode): Boolean =
        dupeKey(callsign, band, mode) in workedSet

    fun clearDupes() = workedSet.clear()

    private fun dupeKey(call: String, band: Band, mode: ContestMode) =
        "${mode.name}|${band.name}|${call.uppercase()}"

    // ── Exchange Validation ───────────────────────────────────────────────────

    data class ExchangeField(
        val label: String,
        val hint: String,
        val isRequired: Boolean,
        val isAutoFilled: Boolean   // auto-filled from settings or serial
    )

    fun exchangeFields(mode: ContestMode = settings.contestMode): List<ExchangeField> = when (mode) {
        ContestMode.CQ_WW_CW -> listOf(
            ExchangeField("RST",  "599",    true,  false),
            ExchangeField("Zone", "e.g. 14", true, false)
        )
        ContestMode.CQ_WW_VHF -> listOf(
            ExchangeField("Grid", "Maidenhead 4-char", true, true)
        )
        ContestMode.CQ_WPX_CW -> listOf(
            ExchangeField("RST",    "599", true,  false),
            ExchangeField("Serial", "NNN", true,  true)
        )
    }

    /** Build the SENT exchange string for a given contest mode */
    fun buildSentExchange(
        mode: ContestMode = settings.contestMode,
        serial: Int = peekNextSerial(mode)
    ): String = when (mode) {
        ContestMode.CQ_WW_CW  -> "599 ${"%02d".format(settings.myZone)}"
        ContestMode.CQ_WW_VHF -> settings.myGrid.take(4).uppercase()
        ContestMode.CQ_WPX_CW -> "599 ${formatSerial(serial)}"
    }

    // ── Default Macros per Contest ─────────────────────────────────────────────

    fun defaultMacros(mode: ContestMode = settings.contestMode): List<Macro> = when (mode) {

        ContestMode.CQ_WW_CW -> listOf(
            Macro(1,  "CQ",      "CQ CQ DE {MYCALL} {MYCALL} K",       1),
            Macro(2,  "Exchange","599 {ZONE}",                          2),
            Macro(3,  "TU",      "TU {MYCALL} K",                      3),
            Macro(4,  "His Call","{CALL}",                              4),
            Macro(5,  "AGN?",    "AGN?",                                5),
            Macro(6,  "NR?",     "NR?",                                 6),
            Macro(7,  "QRZ?",    "QRZ? {MYCALL}",                      7),
            Macro(8,  "CQ Short","CQ DE {MYCALL} K",                   8),
            Macro(9,  "?",       "?",                                   9),
            Macro(10, "Full Log","{CALL} 599 {ZONE} {MYCALL} K",      10),
            Macro(11, "S&P",     "{MYCALL}",                           11),
            Macro(12, "TU+CQ",   "TU {MYCALL} CQ CQ DE {MYCALL} K",  12)
        )

        ContestMode.CQ_WW_VHF -> listOf(
            Macro(1,  "CQ",      "CQ CQ DE {MYCALL} {MYCALL} K",       1),
            Macro(2,  "Exchange","{GRID}",                              2),
            Macro(3,  "TU",      "TU {MYCALL} K",                      3),
            Macro(4,  "His Call","{CALL}",                              4),
            Macro(5,  "AGN?",    "AGN?",                                5),
            Macro(6,  "Grid?",   "GRID?",                               6),
            Macro(7,  "QRZ?",    "QRZ? {MYCALL}",                      7),
            Macro(8,  "CQ Short","CQ DE {MYCALL} K",                   8),
            Macro(9,  "?",       "?",                                   9),
            Macro(10, "Full Log","{CALL} {MYCALL} {GRID} K",          10),
            Macro(11, "S&P",     "{MYCALL}",                           11),
            Macro(12, "TU+CQ",   "TU {MYCALL} CQ CQ DE {MYCALL} K",  12)
        )

        ContestMode.CQ_WPX_CW -> listOf(
            Macro(1,  "CQ",      "CQ CQ DE {MYCALL} {MYCALL} K",       1),
            Macro(2,  "Exchange","599 {SERIAL}",                        2),
            Macro(3,  "TU",      "TU {SERIAL} {MYCALL} K",             3),
            Macro(4,  "His Call","{CALL}",                              4),
            Macro(5,  "AGN?",    "AGN?",                                5),
            Macro(6,  "NR?",     "NR?",                                 6),
            Macro(7,  "QRZ?",    "QRZ? {MYCALL}",                      7),
            Macro(8,  "CQ Short","CQ DE {MYCALL} K",                   8),
            Macro(9,  "?",       "?",                                   9),
            Macro(10, "Full Log","{CALL} 599 {SERIAL} {MYCALL} K",   10),
            Macro(11, "S&P",     "{MYCALL}",                           11),
            Macro(12, "TU+CQ",   "TU {SERIAL} {MYCALL} CQ DE {MYCALL} K", 12)
        )
    }

    fun applySettings(s: AppSettings) { settings = s }
}
