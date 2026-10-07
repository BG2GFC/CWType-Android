package com.cwcontest

/**
 * ITU Morse code table + encoder.
 *
 * Each entry maps a character to its dot/dash sequence.
 * '.' = dot   '-' = dash
 */
object MorseCode {

    private val TABLE: Map<Char, String> = mapOf(
        'A' to ".-",   'B' to "-...", 'C' to "-.-.", 'D' to "-..",
        'E' to ".",    'F' to "..-.", 'G' to "--.",  'H' to "....",
        'I' to "..",   'J' to ".---", 'K' to "-.-",  'L' to ".-..",
        'M' to "--",   'N' to "-.",   'O' to "---",  'P' to ".--.",
        'Q' to "--.-", 'R' to ".-.",  'S' to "...",  'T' to "-",
        'U' to "..-",  'V' to "...-", 'W' to ".--",  'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----",'1' to ".----",'2' to "..---",'3' to "...--",
        '4' to "....-",'5' to ".....",'6' to "-....",'7' to "--...",
        '8' to "---..",'9' to "----.",
        '.' to ".-.-.-",  ',' to "--..--",  '?' to "..--..",
        '\'' to ".----.",  '!' to "-.-.--",  '/' to "-..-.",
        '(' to "-.--.",    ')' to "-.--.-",  '&' to ".-...",
        ':' to "---...",   ';' to "-.-.-.",  '=' to "-...-",
        '+' to ".-.-.",    '-' to "-....-",  '_' to "..--.-",
        '"' to ".-..-.",   '$' to "...-..-", '@' to ".--.-.",
        ' ' to " "   // word space marker (handled separately in encode())
    )

    // ── Timing (PARIS standard) ─────────────────────────────────────
    // dot unit = 1200 / WPM  (ms)
    // dash     = 3 units
    // inter-element gap = 1 unit   (between elements of the same character)
    // inter-letter gap  = 3 units  (between characters)
    // inter-word gap    = 7 units  (between words)
    //
    // Sanity check: "PARIS" = 43 units, plus one 7-unit word space = 50 units,
    // which is exactly one word under the PARIS calibration standard.

    fun dotMs(wpm: Int): Long = (1200.0 / wpm.coerceIn(1, 60)).toLong()

    // ── Encode a string to a list of CW elements ────────────────────
    sealed class Element {
        object DotOn  : Element()
        object DashOn : Element()
        /** Silence for [units] dot-lengths */
        data class Silence(val units: Int) : Element()
    }

    /**
     * Convert [text] to a list of [Element]s ready to feed the CW engine.
     *
     * The gap *before* each element is emitted explicitly, so the returned
     * element list is self-contained: playing every element back-to-back at
     * the correct dot length yields correct CW spacing. The engine must NOT
     * add any extra silence of its own between elements.
     *
     * Units per character: (sum of element lengths) + 1 unit between elements,
     * 3 units between characters and 7 units between words.
     */
    fun encode(text: String): List<Element> {
        val elements = mutableListOf<Element>()
        var gapBeforeNext = 0     // silence units owed before the next element

        text.uppercase().forEach { ch ->
            if (ch == ' ') {
                // Word gap: 7 units total, measured from the end of the last element.
                if (elements.isNotEmpty()) gapBeforeNext = 7
                return@forEach
            }
            val pattern = TABLE[ch] ?: return@forEach

            pattern.forEachIndexed { ei, sym ->
                when {
                    ei > 0              -> elements.add(Element.Silence(1))
                    gapBeforeNext > 0   -> elements.add(Element.Silence(gapBeforeNext))
                }
                when (sym) {
                    '.' -> elements.add(Element.DotOn)
                    '-' -> elements.add(Element.DashOn)
                }
            }
            gapBeforeNext = 3      // inter-letter gap for the next character
        }
        return elements
    }

    /** Total length of [encode] output in dot units (used by tests/estimates) */
    fun units(text: String): Int = encode(text).sumOf { element ->
        when (element) {
            is Element.DotOn  -> 1
            is Element.DashOn -> 3
            is Element.Silence -> element.units
        }
    }

    /** Estimated transmission time of [text] in milliseconds at [wpm] */
    fun durationMs(text: String, wpm: Int): Long = units(text) * dotMs(wpm)

    /** Human-readable dot-dash string for display / debugging */
    fun toPattern(text: String): String =
        text.uppercase().map { ch ->
            TABLE[ch]?.let { "$it " } ?: if (ch == ' ') "/ " else "? "
        }.joinToString("")

    /** Check if all characters in [text] are encodable */
    fun isEncodable(text: String): Boolean =
        text.uppercase().all { it in TABLE }
}
