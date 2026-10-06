package com.bido.budgetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountsTest {
    @Test fun plainNumbers() {
        assertEquals(45.0, Amounts.parse("45")!!, 0.0)
        assertEquals(45.5, Amounts.parse("45.5")!!, 0.0)
        assertEquals(0.5, Amounts.parse(".5")!!, 0.0)
    }

    @Test fun thousandsSeparatorIsNotADecimalPoint() {
        assertEquals(1200.0, Amounts.parse("1,200")!!, 0.0)
        assertEquals(1234.5, Amounts.parse("1,234.50")!!, 0.0)
        assertEquals(1234567.0, Amounts.parse("1,234,567")!!, 0.0)
    }

    @Test fun decimalComma() {
        assertEquals(12.5, Amounts.parse("12,5")!!, 0.0)
        assertEquals(12.25, Amounts.parse("12,25")!!, 0.0)
    }

    @Test fun arabicDigits() {
        assertEquals(350.0, Amounts.parse("٣٥٠")!!, 0.0)
        assertEquals(12.5, Amounts.parse("١٢٫٥")!!, 0.0)
        assertEquals(1200.0, Amounts.parse("١٬٢٠٠")!!, 0.0)
    }

    @Test fun notNumbers() {
        assertNull(Amounts.parse(""))
        assertNull(Amounts.parse("  "))
        assertNull(Amounts.parse("1.2.3"))
        assertNull(Amounts.parse("abc"))
    }

    @Test fun cleanKeepsDigitsAndSeparatorsOnly() {
        assertEquals("1,200.5", Amounts.clean("1,2a00.5 EGP"))
        assertEquals("٣٥٠٫٥", Amounts.clean("٣٥٠٫٥ج"))
    }
}
