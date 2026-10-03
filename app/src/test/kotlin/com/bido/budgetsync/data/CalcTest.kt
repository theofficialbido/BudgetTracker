package com.bido.budgetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CalcTest {
    private val sep = YearMonth.of(2026, 9)

    private fun line(name: String, planned: Double, actual: Double = 0.0) = TrackerLine(name, planned, actual, 0.0, "OK")

    private fun state(log: List<LogLine> = emptyList(), income: List<IncomeLine> = emptyList(), extras: List<TrackerLine> = emptyList()) =
        ServerState(
            month = "2026-09",
            categories = listOf("Claude subscription", "Breakfast", "Going out", "Other"),
            log = log,
            tracker = listOf(line("Claude subscription", 1200.0), line("Breakfast", 3200.0), line("Going out", 1200.0), line("Other", 0.0)),
            total = line("Total", 5600.0),
            income = 0.0, leftAfterActual = 0.0, incomeLog = income, extras = extras,
        )

    private fun logRow(row: Int, date: String, cat: String, amount: Double, desc: String = "") = LogLine(row, date, cat, desc, amount)

    private fun phone(cat: String, amount: Double, date: String = "2026-09-30", synced: Boolean = false, inCache: Boolean = false) =
        Expense(date = date, category = cat, description = "x", amount = amount, synced = synced, inCache = inCache)

    private fun summarize(s: ServerState?, local: List<Expense> = emptyList(), custom: List<CustomCategory> = emptyList(), month: YearMonth = sep) =
        Calc.summarize(s, Calc.entries(s, local), custom, month)

    @Test fun addsPhoneEntriesOnTopOfCachedWorkbookData() {
        val s = state(log = listOf(logRow(2, "2026-09-29", "Going out", 175.0)))
        val sum = summarize(s, listOf(phone("Going out", 100.0), phone("Breakfast", 50.0)))
        assertEquals(275.0, sum.categories.first { it.name == "Going out" }.actual, 0.001)
        assertEquals(50.0, sum.categories.first { it.name == "Breakfast" }.actual, 0.001)
        assertEquals(325.0, sum.total.actual, 0.001)
        assertEquals(5600.0, sum.total.planned, 0.001)
    }

    @Test fun entriesAlreadyInTheCachedDataAreNotCountedTwice() {
        val s = state(log = listOf(logRow(2, "2026-09-30", "Breakfast", 50.0)))
        // the phone made it, the laptop wrote it, and a later fetch included it
        val sum = summarize(s, listOf(phone("Breakfast", 50.0, synced = true, inCache = true)))
        assertEquals(50.0, sum.categories.first { it.name == "Breakfast" }.actual, 0.001)
        // synced but the follow-up fetch failed: still counted from the phone, once
        val sum2 = summarize(state(), listOf(phone("Breakfast", 50.0, synced = true, inCache = false)))
        assertEquals(50.0, sum2.categories.first { it.name == "Breakfast" }.actual, 0.001)
    }

    @Test fun statusMatchesTheTrackerRules() {
        assertEquals("OK", Calc.status(1000.0, 799.0, 0.8, 1.0))
        assertEquals("WATCH", Calc.status(1000.0, 800.0, 0.8, 1.0))
        assertEquals("OVER", Calc.status(1000.0, 1000.0, 0.8, 1.0))
        assertEquals("UNPLANNED", Calc.status(0.0, 1.0, 0.8, 1.0))
        assertEquals("OK", Calc.status(0.0, 0.0, 0.8, 1.0))
        val sum = summarize(state(), listOf(phone("Claude subscription", 1200.0), phone("Other", 10.0)))
        assertEquals("OVER", sum.categories.first { it.name == "Claude subscription" }.status)
        assertEquals("UNPLANNED", sum.categories.first { it.name == "Other" }.status)
    }

    @Test fun incomeAndLeftAfterSpendingFollowTheMonth() {
        val s = state(
            log = listOf(logRow(2, "2026-09-10", "Breakfast", 200.0), logRow(3, "2026-08-31", "Breakfast", 999.0)),
            income = listOf(IncomeLine(2, "2026-09-01", "Salary", 5000.0), IncomeLine(3, "2026-08-01", "Salary", 4000.0)),
        )
        val sum = summarize(s, listOf(phone("Income", 100.0)))
        assertEquals(5100.0, sum.incomeReceived, 0.001)
        assertEquals(200.0, sum.total.actual, 0.001)            // August row ignored
        assertEquals(4900.0, sum.left, 0.001)
        // moving to another month works offline too
        val aug = summarize(s, month = YearMonth.of(2026, 8))
        assertEquals(4000.0, aug.incomeReceived, 0.001)
        assertEquals(999.0, aug.total.actual, 0.001)
    }

    @Test fun customCategoriesFromTheLaptopAndFromThePhoneAreIncluded() {
        val s = state(extras = listOf(TrackerLine("Gym", 300.0, 0.0, 0.0, "OK")))
        val custom = listOf(CustomCategory("Books", 100.0), CustomCategory("gym", 5.0))   // "gym" is the same as "Gym"
        val sum = summarize(s, listOf(phone("Gym", 150.0), phone("Books", 120.0)), custom)
        assertEquals(listOf("Claude subscription", "Breakfast", "Going out", "Other", "Gym", "Books"), sum.categories.map { it.name })
        assertEquals(300.0, sum.categories.first { it.name == "Gym" }.planned, 0.001)
        assertEquals("OK", sum.categories.first { it.name == "Gym" }.status)
        assertEquals("OVER", sum.categories.first { it.name == "Books" }.status)
        assertTrue(sum.categories.first { it.name == "Books" }.custom)
        assertEquals(5600.0 + 300.0 + 100.0, sum.total.planned, 0.001)
        assertEquals(listOf("Claude subscription", "Breakfast", "Going out", "Other", "Gym", "Books"), Calc.categoryNames(s, custom))
    }

    @Test fun worksBeforeTheFirstSyncWithDefaults() {
        val sum = summarize(null, listOf(phone("Breakfast", 40.0)))
        assertEquals(4, sum.categories.size)
        assertEquals(40.0, sum.total.actual, 0.001)
        assertEquals("UNPLANNED", sum.categories.first { it.name == "Breakfast" }.status)
    }

    @Test fun unknownCategoryCountsUnderOther() {
        val sum = summarize(state(), listOf(phone("Mystery", 25.0)))
        assertEquals(25.0, sum.categories.first { it.name == "Other" }.actual, 0.001)
    }

    @Test fun waitingFlagFollowsSyncState() {
        val e = Calc.entries(state(), listOf(phone("Breakfast", 1.0), phone("Breakfast", 2.0, synced = true)))
        assertEquals(listOf(true, false), e.map { it.waiting })
    }
}
