package com.bido.budgetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

class AlertRulesTest {
    private val oct = YearMonth.of(2026, 10)
    private fun line(name: String, status: String) = CategorySummary(name, 100.0, 90.0, 0.9, status)

    @Test fun aStepUpIsAnnouncedOnce() {
        val first = AlertRules.newlyReached(emptyMap(), oct, listOf(line("Going out", "WATCH"), line("Breakfast", "OK")))
        assertEquals(listOf("Going out"), first.map { it.name })
        val remembered = AlertRules.nextState(emptyMap(), oct, listOf(line("Going out", "WATCH"), line("Breakfast", "OK")))
        // still WATCH: quiet. Then OVER: announced again.
        assertTrue(AlertRules.newlyReached(remembered, oct, listOf(line("Going out", "WATCH"))).isEmpty())
        assertEquals(listOf("Going out"), AlertRules.newlyReached(remembered, oct, listOf(line("Going out", "OVER"))).map { it.name })
    }

    @Test fun droppingBackAndOkAreQuiet() {
        val remembered = mapOf("2026-10|Going out" to "OVER")
        assertTrue(AlertRules.newlyReached(remembered, oct, listOf(line("Going out", "WATCH"))).isEmpty())
        assertTrue(AlertRules.newlyReached(remembered, oct, listOf(line("Going out", "OK"))).isEmpty())
    }

    @Test fun aNewMonthStartsFresh() {
        val old = mapOf("2026-09|Going out" to "OVER")
        assertEquals(1, AlertRules.newlyReached(old, oct, listOf(line("Going out", "WATCH"))).size)
        // last month's remembered statuses are dropped
        assertEquals(setOf("2026-10|Going out"), AlertRules.nextState(old, oct, listOf(line("Going out", "WATCH"))).keys)
    }

    @Test fun unplannedSpendingIsNotAnAlert() {
        assertTrue(AlertRules.newlyReached(emptyMap(), oct, listOf(line("Other", "UNPLANNED"))).isEmpty())
    }

    @Test fun reminderOnlyInTheEveningOnceAndWhenNothingLogged() {
        val evening = LocalDateTime.of(2026, 10, 6, 21, 30)
        assertTrue(AlertRules.shouldRemind(evening, "", hasExpenseToday = false))
        assertFalse(AlertRules.shouldRemind(evening, "", hasExpenseToday = true))
        assertFalse(AlertRules.shouldRemind(evening, "2026-10-06", hasExpenseToday = false))      // already reminded today
        assertTrue(AlertRules.shouldRemind(evening, "2026-10-05", hasExpenseToday = false))       // yesterday's does not count
        assertFalse(AlertRules.shouldRemind(LocalDateTime.of(2026, 10, 6, 15, 0), "", hasExpenseToday = false))
    }
}
