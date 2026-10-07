package com.cwcontest

import java.text.SimpleDateFormat
import java.util.*

// ── Contest Modes ──────────────────────────────────────────────────────────────
enum class ContestMode(val displayName: String, val shortName: String) {
    CQ_WW_CW("CQ WW CW", "CQWW"),
    CQ_WW_VHF("CQ WW VHF", "CQVHF"),
    CQ_WPX_CW("CQ WPX CW", "CQWPX"),
    /** Satellite QSOs: the exchange is just the signal report (5NN). */
    SATELLITE("卫星", "SAT")
}

// ── Amateur Radio Bands ────────────────────────────────────────────────────────
enum class Band(val mhz: Double, val displayName: String, val freqStart: Double, val freqEnd: Double) {
    BAND_160(1.8,  "160m", 1.800,  2.000),
    BAND_80( 3.5,  "80m",  3.500,  4.000),
    BAND_40( 7.0,  "40m",  7.000,  7.300),
    BAND_20(14.0,  "20m", 14.000, 14.350),
    BAND_15(21.0,  "15m", 21.000, 21.450),
    BAND_10(28.0,  "10m", 28.000, 29.700),
    BAND_6( 50.0,  "6m",  50.000, 54.000),
    BAND_2(144.0,  "2m", 144.000,148.000),
    BAND_70(435.0, "70cm",430.000,440.000);

    companion object {
        fun fromFreq(mhz: Double): Band? = values().firstOrNull {
            mhz >= it.freqStart && mhz <= it.freqEnd
        }
        /** Bands valid for a given contest mode */
        fun forContest(mode: ContestMode): List<Band> = when (mode) {
            ContestMode.CQ_WW_CW, ContestMode.CQ_WPX_CW ->
                listOf(BAND_160, BAND_80, BAND_40, BAND_20, BAND_15, BAND_10)
            ContestMode.CQ_WW_VHF ->
                listOf(BAND_6, BAND_2)
            // Satellites: 10 m (AO-7 / RS-15 downlink) plus the usual 2 m /
            // 70 cm transponder pair. 2 m is listed last so it becomes the
            // default band when this mode is selected.
            ContestMode.SATELLITE ->
                listOf(BAND_10, BAND_70, BAND_2)
        }
    }
}

// ── Keyer Modes ───────────────────────────────────────────────────────────────
enum class KeyerMode(val displayName: String) {
    STRAIGHT("直发"),
    IAMBIC_A("Iambic A"),
    IAMBIC_B("Iambic B")
}

// ── QSO Log Entry ─────────────────────────────────────────────────────────────
data class QSOEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Date = Date(),
    val callsign: String,
    val sentExchange: String,
    val rcvdExchange: String,
    val band: Band,
    val frequency: Double,          // MHz as typed by user
    val contestMode: ContestMode,
    val serialNumber: Int? = null,  // WPX / WW serial
    val notes: String = ""
) {
    val timeStr: String get() = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(timestamp)
    val dateStr: String get() = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(timestamp)
}

// ── Macro Definition ──────────────────────────────────────────────────────────
data class Macro(
    val id: Int,
    val label: String,
    /**
     * Template supports: {MYCALL} {CALL} {RST} {SERIAL} {NR} {ZONE} {GRID}
     * {THEIRGRID} {BAND} {FREQ}
     */
    val template: String,
    val functionKey: Int       // 1..12 → F1..F12
)

// ── Application Settings ──────────────────────────────────────────────────────
data class AppSettings(
    // CW
    val wpm: Int = 25,
    val keyerMode: KeyerMode = KeyerMode.STRAIGHT,
    // Serial port
    val useRTS: Boolean = true,
    val useDTR: Boolean = false,
    val invertLogic: Boolean = false,  // swap HIGH/LOW
    val baudRate: Int = 9600,
    // Sidetone
    val sidetoneEnabled: Boolean = true,
    val sidetoneFreqHz: Int = 700,
    val sidetoneVolume: Float = 0.5f,
    // Contest
    val contestMode: ContestMode = ContestMode.CQ_WW_CW,
    val myCallsign: String = "",
    val myGrid: String = "",          // Maidenhead 4-char e.g. OM12
    val myZone: Int = 14,             // CQ zone
    val startSerial: Int = 1,
    // UI
    val darkMode: Boolean = true,
    val cqLoopEnabled: Boolean = false,
    val cqLoopIntervalMs: Int = 5000, // gap between repeats
    /** Extra dot-units of silence appended after every transmitted message */
    val tailGapUnits: Int = 1
)

// ── Macro Variable Context ─────────────────────────────────────────────────────
data class MacroContext(
    val myCallsign: String = "",
    val theirCallsign: String = "",
    val rst: String = "599",
    val serial: String = "001",
    val zone: String = "",
    val grid: String = "",
    /** Their exchange as copied from the exchange box (VHF grid, WW zone, WPX nr…) */
    val nr: String = "",
    /** Band label, e.g. "20m" */
    val band: String = "",
    /** Frequency in MHz, e.g. "14.025" */
    val freq: String = ""
)
