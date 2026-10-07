package com.cwcontest

/**
 * MacroEngine
 *
 * Resolves variable placeholders in macro templates.
 *
 * Supported variables:
 *   {MYCALL}  – operator's callsign
 *   {CALL}    – the other station's callsign (from input box)
 *   {RST}     – signal report (default 599)
 *   {SERIAL}  – next serial number (auto-formatted 001…)
 *   {NR}      – alias for SERIAL
 *   {ZONE}    – CQ zone (two digits)
 *   {GRID}    – Maidenhead grid (operator's, 4-char)
 *   {THEIRGRID} / {THEIRNR} – other station's exchange (from the exchange box)
 *   {BAND}    – current band label, e.g. "20m"
 *   {FREQ}    – current frequency, e.g. "14.025"
 */
object MacroEngine {

    /** Expand all {VARIABLE} placeholders in [template] using [ctx] */
    fun expand(template: String, ctx: MacroContext): String {
        var result = template
        result = result.replace("{MYCALL}",    ctx.myCallsign.uppercase())
        result = result.replace("{CALL}",      ctx.theirCallsign.uppercase())
        result = result.replace("{RST}",       ctx.rst)
        result = result.replace("{SERIAL}",    ctx.serial)
        result = result.replace("{NR}",        ctx.serial)
        result = result.replace("{ZONE}",      ctx.zone)
        result = result.replace("{GRID}",      ctx.grid.uppercase())
        result = result.replace("{THEIRGRID}", ctx.nr.uppercase())
        result = result.replace("{THEIRNR}",   ctx.nr.uppercase())
        result = result.replace("{BAND}",      ctx.band)
        result = result.replace("{FREQ}",      ctx.freq)
        return result.trim()
    }

    /** List all placeholder names found in [template] */
    fun placeholders(template: String): List<String> {
        val regex = Regex("""\{([A-Z]+)}""")
        return regex.findAll(template).map { it.groupValues[1] }.distinct().toList()
    }

    /** True if [template] uses the SERIAL / NR placeholder */
    fun usesSerial(template: String): Boolean =
        template.contains("{SERIAL}") || template.contains("{NR}")

    /** True if [template] uses the CALL placeholder (needs callsign entered) */
    fun usesCall(template: String): Boolean = template.contains("{CALL}")

    /** Replace {CALL} with empty when no callsign is present */
    fun safeExpand(template: String, ctx: MacroContext): String {
        val safeCtx = if (ctx.theirCallsign.isBlank()) ctx.copy(theirCallsign = "") else ctx
        // Collapse whitespace left behind by empty placeholders so that a
        // missing callsign never produces a double letter gap mid-message.
        return expand(template, safeCtx).replace(Regex("\\s+"), " ").trim()
    }
}
