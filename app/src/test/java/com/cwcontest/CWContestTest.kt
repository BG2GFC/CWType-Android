package com.cwcontest

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ══════════════════════════════════════════════════════════════════════════════
// MorseCode Tests
// ══════════════════════════════════════════════════════════════════════════════

class MorseCodeTest {

    @Test
    fun `dot duration for 25 WPM is 48 ms`() {
        assertEquals(48L, MorseCode.dotMs(25))
    }

    @Test
    fun `dot duration for 5 WPM is 240 ms`() {
        assertEquals(240L, MorseCode.dotMs(5))
    }

    @Test
    fun `dot duration for 60 WPM is 20 ms`() {
        assertEquals(20L, MorseCode.dotMs(60))
    }

    @Test
    fun `encode SOS produces correct elements`() {
        val elems = MorseCode.encode("SOS")
        // S = ... and O = ---
        // Count DotOn elements
        val dots  = elems.count { it is MorseCode.Element.DotOn }
        val dashes = elems.count { it is MorseCode.Element.DashOn }
        assertEquals("S·O·S → 6 dots", 6, dots)
        assertEquals("S·O·S → 3 dashes", 3, dashes)
    }

    @Test
    fun `encode single letter E is one dot`() {
        val elems = MorseCode.encode("E")
        assertEquals(1, elems.count { it is MorseCode.Element.DotOn })
        assertEquals(0, elems.count { it is MorseCode.Element.DashOn })
    }

    @Test
    fun `isEncodable returns true for all alphanumerics`() {
        val text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        assertTrue(MorseCode.isEncodable(text))
    }

    @Test
    fun `isEncodable returns false for unsupported character`() {
        assertFalse(MorseCode.isEncodable("ABC#"))
    }

    @Test
    fun `toPattern produces correct CQ`() {
        val pattern = MorseCode.toPattern("CQ")
        assertTrue(pattern.contains("-.-. "))  // C
        assertTrue(pattern.contains("--.- "))  // Q
    }

    @Test
    fun `inter-element gap is one unit`() {
        // "EE" → dot, gap(1), dot  (letters are 3 units apart)
        assertEquals(1 + 3 + 1, MorseCode.units("EE"))
    }

    @Test
    fun `word space is seven units`() {
        // "E E" → dot, word gap(7), dot
        assertEquals(9, MorseCode.units("E E"))
    }

    @Test
    fun `PARIS plus word space is exactly 50 units`() {
        // PARIS calibration: 1 word = 50 dot units, including the word space
        assertEquals(43, MorseCode.units("PARIS"))
        assertEquals(50, MorseCode.units("PARIS") + 7)
    }

    @Test
    fun `dash is three units`() {
        assertEquals(3, MorseCode.units("T"))
        assertEquals(3 + 3 + 3, MorseCode.units("TT"))   // dash, letter gap, dash
    }

    @Test
    fun `durationMs follows the PARIS standard`() {
        // 25 WPM → 48 ms per dot → "PARIS" (43 units) = 2064 ms
        assertEquals(43 * 48L, MorseCode.durationMs("PARIS", 25))
    }

    @Test
    fun `encode skips characters it cannot encode`() {
        assertEquals(MorseCode.encode("AB"), MorseCode.encode("A#B"))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MacroEngine Tests
// ══════════════════════════════════════════════════════════════════════════════

class MacroEngineTest {

    private val ctx = MacroContext(
        myCallsign    = "BG4ABC",
        theirCallsign = "W1AW",
        rst           = "599",
        serial        = "007",
        zone          = "24",
        grid          = "OM12",
        nr            = ""
    )

    @Test
    fun `expands MYCALL`() {
        assertEquals("CQ DE BG4ABC K", MacroEngine.expand("CQ DE {MYCALL} K", ctx))
    }

    @Test
    fun `expands CALL`() {
        assertEquals("W1AW DE BG4ABC", MacroEngine.expand("{CALL} DE {MYCALL}", ctx))
    }

    @Test
    fun `expands SERIAL and NR as aliases`() {
        assertEquals("599 007", MacroEngine.expand("599 {SERIAL}", ctx))
        assertEquals("599 007", MacroEngine.expand("599 {NR}", ctx))
    }

    @Test
    fun `expands ZONE two digits`() {
        assertEquals("599 24", MacroEngine.expand("599 {ZONE}", ctx))
    }

    @Test
    fun `expands GRID uppercase`() {
        assertEquals("OM12", MacroEngine.expand("{GRID}", ctx))
    }

    @Test
    fun `safeExpand with empty callsign removes CALL placeholder`() {
        val emptyCtx = ctx.copy(theirCallsign = "")
        val result = MacroEngine.safeExpand("{CALL} 599 {ZONE}", emptyCtx)
        // Should not contain stray space but no crash
        assertFalse(result.trim().startsWith(" "))
    }

    @Test
    fun `usesSerial detects SERIAL placeholder`() {
        assertTrue(MacroEngine.usesSerial("599 {SERIAL}"))
        assertTrue(MacroEngine.usesSerial("TU {NR} K"))
        assertFalse(MacroEngine.usesSerial("CQ DE {MYCALL} K"))
    }

    @Test
    fun `usesCall detects CALL placeholder`() {
        assertTrue(MacroEngine.usesCall("{CALL} 599"))
        assertFalse(MacroEngine.usesCall("CQ DE {MYCALL} K"))
    }

    @Test
    fun `placeholders lists all variables`() {
        val vars = MacroEngine.placeholders("CQ {MYCALL} {CALL} 599 {ZONE}")
        assertTrue(vars.containsAll(listOf("MYCALL","CALL","ZONE")))
        assertEquals(3, vars.size)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ContestManager Tests
// ══════════════════════════════════════════════════════════════════════════════

class ContestManagerTest {

    private lateinit var mgr: ContestManager
    private val baseSettings = AppSettings(
        myCallsign = "BG4ABC",
        myGrid     = "OM12",
        myZone     = 24,
        startSerial = 1,
        contestMode = ContestMode.CQ_WW_CW
    )

    @Before
    fun setup() {
        mgr = ContestManager(baseSettings)
    }

    @Test
    fun `serial starts at 1 and increments`() {
        assertEquals(1, mgr.currentSerial())
        mgr.incrementSerial()
        assertEquals(2, mgr.currentSerial())
    }

    @Test
    fun `incrementSerial returns used number`() {
        val used = mgr.incrementSerial()
        assertEquals(1, used)
        assertEquals(2, mgr.currentSerial())
    }

    @Test
    fun `formatSerial pads to 3 digits`() {
        assertEquals("001", mgr.formatSerial(1))
        assertEquals("010", mgr.formatSerial(10))
        assertEquals("100", mgr.formatSerial(100))
    }

    @Test
    fun `reset restores serial to startSerial`() {
        mgr.incrementSerial(); mgr.incrementSerial()
        mgr.reset()
        assertEquals(1, mgr.currentSerial())
    }

    @Test
    fun `only WPX uses a serial number`() {
        assertTrue(mgr.usesSerial(ContestMode.CQ_WPX_CW))
        assertFalse(mgr.usesSerial(ContestMode.CQ_WW_CW))
        assertFalse(mgr.usesSerial(ContestMode.CQ_WW_VHF))
        assertFalse(mgr.usesSerial(ContestMode.SATELLITE))
    }

    @Test
    fun `consumeSerial returns null and does not advance for CQ WW`() {
        val s = mgr.consumeSerial(ContestMode.CQ_WW_CW)
        assertNull(s)
        assertEquals(1, mgr.currentSerial(ContestMode.CQ_WW_CW))
    }

    @Test
    fun `consumeSerial advances only the WPX counter`() {
        assertEquals(1, mgr.consumeSerial(ContestMode.CQ_WPX_CW))
        assertEquals(2, mgr.consumeSerial(ContestMode.CQ_WPX_CW))
        assertEquals(3, mgr.currentSerial(ContestMode.CQ_WPX_CW))
        // CQ WW counter is untouched by WPX activity
        assertEquals(1, mgr.currentSerial(ContestMode.CQ_WW_CW))
    }

    @Test
    fun `dupe check works correctly`() {
        assertFalse(mgr.isDupe("W1AW", Band.BAND_20, ContestMode.CQ_WW_CW))
        mgr.markWorked("W1AW", Band.BAND_20, ContestMode.CQ_WW_CW)
        assertTrue(mgr.isDupe("W1AW", Band.BAND_20, ContestMode.CQ_WW_CW))
    }

    @Test
    fun `same call on different band is not dupe`() {
        mgr.markWorked("W1AW", Band.BAND_20, ContestMode.CQ_WW_CW)
        assertFalse(mgr.isDupe("W1AW", Band.BAND_40, ContestMode.CQ_WW_CW))
    }

    @Test
    fun `clearDupes removes all worked stations`() {
        mgr.markWorked("W1AW", Band.BAND_20, ContestMode.CQ_WW_CW)
        mgr.clearDupes()
        assertFalse(mgr.isDupe("W1AW", Band.BAND_20, ContestMode.CQ_WW_CW))
    }

    @Test
    fun `CQWW sent exchange contains RST and zone`() {
        val exch = mgr.buildSentExchange(ContestMode.CQ_WW_CW)
        assertTrue(exch.startsWith("599"))
        assertTrue(exch.contains("24"))
    }

    @Test
    fun `CQWW VHF sent exchange is 4-char grid`() {
        val exch = mgr.buildSentExchange(ContestMode.CQ_WW_VHF)
        assertEquals("OM12", exch)
    }

    @Test
    fun `WPX sent exchange contains RST and serial`() {
        val exch = mgr.buildSentExchange(ContestMode.CQ_WPX_CW, serial = 1)
        assertTrue(exch.startsWith("599"))
        assertTrue(exch.contains("001"))
    }

    @Test
    fun `default macros for CQ WW CW contains F1 CQ macro`() {
        val macros = mgr.defaultMacros(ContestMode.CQ_WW_CW)
        val cqMacro = macros.first { it.functionKey == 1 }
        assertTrue(cqMacro.template.contains("{MYCALL}"))
    }

    @Test
    fun `default macros for CQ WW VHF use GRID not ZONE`() {
        val macros = mgr.defaultMacros(ContestMode.CQ_WW_VHF)
        val exMacro = macros.first { it.functionKey == 2 }
        assertTrue(exMacro.template.contains("{GRID}"))
        assertFalse(exMacro.template.contains("{ZONE}"))
    }

    @Test
    fun `default macros for WPX use SERIAL`() {
        val macros = mgr.defaultMacros(ContestMode.CQ_WPX_CW)
        val exMacro = macros.first { it.functionKey == 2 }
        assertTrue(exMacro.template.contains("{SERIAL}"))
    }

    @Test
    fun `satellite TU macro is 5NN TU`() {
        val macros = mgr.defaultMacros(ContestMode.SATELLITE)
        assertEquals("5NN TU", macros.first { it.functionKey == 3 }.template)
    }

    @Test
    fun `satellite TU+CQ macro is 5NN TU with both callsigns`() {
        val macros = mgr.defaultMacros(ContestMode.SATELLITE)
        val tuCq = macros.first { it.functionKey == 12 }.template
        assertEquals("5NN TU {MYCALL} {MYCALL} K", tuCq)
    }

    @Test
    fun `satellite CQ macros omit DE`() {
        val macros = mgr.defaultMacros(ContestMode.SATELLITE)
        assertEquals(
            "CQ CQ {MYCALL} {MYCALL} K",
            macros.first { it.functionKey == 1 }.template
        )
        assertEquals(
            "CQ {MYCALL} K",
            macros.first { it.functionKey == 8 }.template
        )
        assertTrue(macros.none { it.template.contains("DE {MYCALL}") })
    }

    @Test
    fun `satellite sent exchange is the 5NN report`() {
        assertEquals("5NN", mgr.buildSentExchange(ContestMode.SATELLITE))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Band Helper Tests
// ══════════════════════════════════════════════════════════════════════════════

class BandTest {

    @Test
    fun `fromFreq returns correct band for 14 200`() {
        assertEquals(Band.BAND_20, Band.fromFreq(14.200))
    }

    @Test
    fun `fromFreq returns correct band for 3 535`() {
        assertEquals(Band.BAND_80, Band.fromFreq(3.535))
    }

    @Test
    fun `fromFreq returns null for out-of-band frequency`() {
        assertNull(Band.fromFreq(15.0))
    }

    @Test
    fun `CQ WW valid bands are 6`() {
        val bands = Band.forContest(ContestMode.CQ_WW_CW)
        assertEquals(6, bands.size)
        assertTrue(bands.none { it == Band.BAND_6 || it == Band.BAND_2 })
    }

    @Test
    fun `CQ WW VHF valid bands are 2`() {
        val bands = Band.forContest(ContestMode.CQ_WW_VHF)
        assertEquals(2, bands.size)
        assertTrue(bands.containsAll(listOf(Band.BAND_6, Band.BAND_2)))
    }

    @Test
    fun `satellite bands include 2m 70cm and 10m`() {
        val bands = Band.forContest(ContestMode.SATELLITE)
        assertTrue(bands.containsAll(listOf(Band.BAND_2, Band.BAND_70, Band.BAND_10)))
        // 2m is the default band when the satellite mode is picked
        assertEquals(Band.BAND_2, bands.last())
    }

    @Test
    fun `fromFreq returns 70cm for 435 000`() {
        assertEquals(Band.BAND_70, Band.fromFreq(435.000))
    }
}
