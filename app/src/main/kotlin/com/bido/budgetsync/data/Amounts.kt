package com.bido.budgetsync.data

/** Reads amounts the way people type them: Arabic or Western digits, "1,200" or "12,5". */
object Amounts {
    private val grouped = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")

    private fun westernDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫' -> '.'
                '٬' -> ','
                else -> c
            }
        )
        return sb.toString()
    }

    /** What to keep while typing into an amount field: digits (any script) and separators. */
    fun clean(text: String): String = text.filter { it.isDigit() || it == '.' || it == ',' || it == '٫' || it == '٬' }

    /**
     * "1,200" and "1,234.50" are thousands separators; any other comma is a decimal comma ("12,5" is 12.5).
     * Returns null for anything that is not a number, such as "" or "1.2.3".
     */
    fun parse(text: String): Double? {
        val t = westernDigits(text.trim())
        if (t.isEmpty()) return null
        return (if (grouped.matches(t)) t.replace(",", "") else t.replace(',', '.')).toDoubleOrNull()
    }
}
