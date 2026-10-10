package com.bido.budgetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(200.0, sum.total.actual, 0.001)            // August row ignored for this month's spending
        assertEquals(4000.0 - 999.0, sum.carriedIn, 0.001)      // but August's leftover carries in
        assertEquals(3001.0 + 5100.0 - 200.0, sum.left, 0.001)
        // moving to another month works offline too
        val aug = summarize(s, month = YearMonth.of(2026, 8))
        assertEquals(4000.0, aug.incomeReceived, 0.001)
        assertEquals(999.0, aug.total.actual, 0.001)
        assertEquals(4000.0 - 999.0, aug.left, 0.001)
    }

    private fun close(month: YearMonth, reset: Boolean) = Closing(month, reset)
    private val aug = YearMonth.of(2026, 8)

    @Test fun startingAtZeroDropsWhatWasLeftOver() {
        val s = state(
            log = listOf(logRow(1, "2026-08-10", "Breakfast", 1000.0), logRow(2, "2026-09-05", "Breakfast", 200.0)),
            income = listOf(IncomeLine(1, "2026-08-01", "Salary", 5000.0), IncomeLine(2, "2026-09-01", "Salary", 3000.0)),
        )
        val e = Calc.entries(s, emptyList())
        // carried: August ended with 4000 left
        assertEquals(4000.0 + 3000.0 - 200.0, Calc.summarize(s, e, emptyList(), sep).left, 0.001)
        // started at 0: September has only its own income and spending
        val reset = Calc.summarize(s, e, emptyList(), sep, closings = listOf(close(aug, true)))
        assertEquals(0.0, reset.carriedIn, 0.001)
        assertEquals(3000.0 - 200.0, reset.left, 0.001)
        // carrying explicitly is the same as no decision yet
        assertEquals(6800.0, Calc.summarize(s, e, emptyList(), sep, closings = listOf(close(aug, false))).left, 0.001)
    }

    @Test fun anOverspentMonthCarriesItsShortfallUnlessStartedAtZero() {
        val s = state(
            log = listOf(logRow(1, "2026-08-10", "Going out", 700.0)),
            income = listOf(IncomeLine(1, "2026-08-01", "Allowance", 500.0), IncomeLine(2, "2026-09-01", "Allowance", 500.0)),
        )
        val e = Calc.entries(s, emptyList())
        assertEquals(500.0 - 700.0 + 500.0, Calc.summarize(s, e, emptyList(), sep).left, 0.001)   // 300
        assertEquals(500.0, Calc.summarize(s, e, emptyList(), sep, closings = listOf(close(aug, true))).left, 0.001)
    }

    @Test fun aDecisionOnlyAffectsTheMonthAfterItAndTheOnesThatFollow() {
        val s = state(
            income = listOf(IncomeLine(1, "2026-08-01", "Salary", 1000.0), IncomeLine(2, "2026-09-01", "Salary", 1000.0), IncomeLine(3, "2026-10-01", "Salary", 1000.0)),
        )
        val e = Calc.entries(s, emptyList())
        val oct = YearMonth.of(2026, 10)
        // Aug carries 1000, Sep ends with 2000, Oct opens with 2000 and ends with 3000
        assertEquals(3000.0, Calc.summarize(s, e, emptyList(), oct).left, 0.001)
        // Aug started at 0 (its 1000 leaves), Sep ends with 1000, Oct ends with 2000
        assertEquals(2000.0, Calc.summarize(s, e, emptyList(), oct, closings = listOf(close(aug, true))).left, 0.001)
        // September started at 0: only October's own 1000 counts
        assertEquals(1000.0, Calc.summarize(s, e, emptyList(), oct, closings = listOf(close(sep, true))).left, 0.001)
        // and August itself is not changed by deciding about it
        assertEquals(1000.0, Calc.summarize(s, e, emptyList(), aug, closings = listOf(close(aug, true))).left, 0.001)
    }

    @Test fun theEarliestUnclosedMonthWithMoneyInItIsAsked() {
        val s = state(
            log = listOf(logRow(1, "2026-08-10", "Breakfast", 100.0)),
            income = listOf(IncomeLine(1, "2026-08-01", "Salary", 1000.0), IncomeLine(2, "2026-09-01", "Salary", 500.0)),
        )
        val e = Calc.entries(s, emptyList())
        val oct = YearMonth.of(2026, 10)
        val first = Calc.pendingClose(e, emptyList(), oct)!!
        assertEquals(aug, first.month)
        assertEquals(900.0, first.balance, 0.001)
        // once August is decided, September is next, with August's carried money in it
        val second = Calc.pendingClose(e, listOf(close(aug, false)), oct)!!
        assertEquals(sep, second.month)
        assertEquals(1400.0, second.balance, 0.001)
        // starting August at 0 changes what September ended with
        assertEquals(500.0, Calc.pendingClose(e, listOf(close(aug, true)), oct)!!.balance, 0.001)
        // nothing left to decide, and the current month is never asked
        assertNull(Calc.pendingClose(e, listOf(close(aug, false), close(sep, false)), oct))
        assertNull(Calc.pendingClose(e, emptyList(), sep.minusMonths(1)))
    }

    @Test fun monthsWithNothingInThemAreNotAsked() {
        val s = state(income = listOf(IncomeLine(1, "2026-06-01", "Salary", 1000.0)))
        val e = Calc.entries(s, emptyList())
        val p = Calc.pendingClose(e, listOf(close(YearMonth.of(2026, 6), false)), YearMonth.of(2026, 10))
        assertNull(p)   // July to September are empty; June was decided
        // a month that ends exactly at 0 needs no decision either
        val zero = state(log = listOf(logRow(1, "2026-06-05", "Breakfast", 1000.0)), income = listOf(IncomeLine(1, "2026-06-01", "Salary", 1000.0)))
        assertNull(Calc.pendingClose(Calc.entries(zero, emptyList()), emptyList(), YearMonth.of(2026, 7)))
    }

    @Test fun plansSetInTheAppWinOverTheWorkbooksAndDriveStatus() {
        val s = state()
        val e = Calc.entries(s, listOf(phone("Breakfast", 900.0)))
        val book = Calc.summarize(s, e, emptyList(), sep)
        assertEquals("OK", book.categories.first { it.name == "Breakfast" }.status)               // 900 of 3200
        val app = Calc.summarize(s, e, emptyList(), sep, plans = mapOf("breakfast" to 1000.0))     // 90%
        val line = app.categories.first { it.name == "Breakfast" }
        assertEquals(1000.0, line.planned, 0.001)
        assertEquals("WATCH", line.status)
        assertEquals(5600.0 - 3200.0 + 1000.0, app.total.planned, 0.001)
    }

    @Test fun planCanBeSetForACategoryTheWorkbookHasNeverSeen() {
        val sum = Calc.summarize(null, emptyList(), listOf(CustomCategory("Books", 0.0)), sep, plans = mapOf("Books" to 250.0))
        assertEquals(250.0, sum.categories.first { it.name == "Books" }.planned, 0.001)
    }

    @Test fun phoneDecisionWinsOverTheLaptopsCopy() {
        val s = state().copy(closings = listOf(ClosingLine("2026-08", reset = true, invested = 10.0, splurged = 5.0), ClosingLine("2026-07", false, 0.0, 0.0)))
        val merged = Calc.mergeClosings(s, listOf(MonthClosing("2026-08", reset = false, invested = 0.0, splurged = 0.0)))
        assertEquals(2, merged.size)
        assertEquals(false, merged.first { it.month == aug }.reset)               // the phone's choice
        assertEquals(false, merged.first { it.month == YearMonth.of(2026, 7) }.reset)  // only the laptop knows July
        assertEquals(true, Calc.mergeClosings(s, emptyList()).first { it.month == aug }.reset)
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

    private val oct10 = java.time.LocalDate.of(2026, 10, 10)

    @Test fun templatesAreWhatYouRepeat() {
        val rows = listOf(
            logRow(1, "2026-10-01", "Breakfast", 50.0, "Foul"), logRow(2, "2026-10-02", "Breakfast", 50.0, "foul"),
            logRow(3, "2026-10-03", "Breakfast", 50.0, "Foul"), logRow(4, "2026-10-04", "Going out", 175.0, "Cafe"),
            logRow(5, "2026-10-05", "Going out", 175.0, "Cafe"), logRow(6, "2026-10-06", "Going out", 99.0, "Once"),
            logRow(7, "2026-01-01", "Breakfast", 50.0, "Foul"),   // too old to count
        )
        val t = Calc.templates(Calc.entries(state(log = rows), emptyList()), oct10, income = false)
        assertEquals(listOf("Foul", "Cafe"), t.map { it.description })        // most repeated first, one-offs left out
        assertEquals(listOf(3, 2), t.map { it.count })
        assertEquals("Foul · 50", t[0].label)
        val inc = Calc.templates(Calc.entries(state(income = listOf(IncomeLine(1, "2026-10-01", "Salary", 5000.0), IncomeLine(2, "2026-09-20", "Salary", 5000.0))), emptyList()), oct10, income = true)
        assertEquals(listOf("Salary"), inc.map { it.description })
    }

    @Test fun safeDailyAmountSpreadsWhatIsLeftOverTheDaysLeft() {
        // Oct has 31 days; on the 10th there are 22 days left including today
        val e = Calc.entries(state(), listOf(phone("Income", 3000.0, date = "2026-10-01"), phone("Breakfast", 600.0, date = "2026-10-05")))
        val f = Calc.forecast(state(), e, emptyList(), oct10)
        assertEquals(22, f.daysLeft)
        assertEquals(5600.0 - 600.0, f.remainingPlan, 0.001)         // the plan still has this much unused
        assertEquals(3000.0 - 600.0, f.leftToSpend, 0.001)           // but you can only spend what you've received
        assertEquals((3000.0 - 600.0) / 22, f.safePerDay, 0.001)
    }

    @Test fun carriedMoneyCountsTowardTheDailyAllowance() {
        val e = Calc.entries(state(), listOf(phone("Income", 1000.0, date = "2026-09-01"), phone("Breakfast", 100.0, date = "2026-09-10")))
        val f = Calc.forecast(state(), e, emptyList(), oct10)
        assertEquals(900.0, f.leftToSpend, 0.001)
        assertEquals(900.0 / 22, f.safePerDay, 0.001)
        // started October at 0 instead: nothing to spend until October income arrives
        val g = Calc.forecast(state(), e, emptyList(), oct10, closings = listOf(Closing(YearMonth.of(2026, 9), reset = true)))
        assertEquals(0.0, g.leftToSpend, 0.001)
        assertEquals(0.0, g.safePerDay, 0.0)
    }

    @Test fun anOverspentMonthHasNothingSafeToSpend() {
        val f = Calc.forecast(state(), Calc.entries(state(), listOf(phone("Other", 9000.0, date = "2026-10-02"))), emptyList(), oct10)
        assertEquals(0.0, f.safePerDay, 0.0)
        assertEquals(0.0, f.remainingPlan, 0.0)
    }

    @Test fun aSubscriptionPaidEarlyIsNotProjectedAsADailyHabit() {
        val s = state()
        val e = Calc.entries(s, listOf(phone("Claude subscription", 1200.0, date = "2026-10-01"), phone("Breakfast", 100.0, date = "2026-10-02")))
        val f = Calc.forecast(s, e, emptyList(), oct10)
        // Claude: one big entry (>= half of its 1200 plan) is a one-off, so it stays at 1200
        val total = f.projectedTotal!!
        // Breakfast: 100 so far over 10 days, 21 days to go: 100 + 10/day * 21 = 310; everything else 0
        assertEquals(1200.0 + 310.0, total, 0.001)
    }

    @Test fun fasterThanPlanIsFlaggedBeforeItGoesOver() {
        val s = state()
        // Going out: plan 1200, spent 700 in the first 10 days at 70/day: on pace for 700 + 70*21 = 2170
        val e = Calc.entries(s, (1..10).map { phone("Going out", 70.0, date = "2026-10-%02d".format(it)) })
        val f = Calc.forecast(s, e, emptyList(), oct10)
        assertEquals(listOf("Going out"), f.atRisk.map { it.name })
        assertEquals(2170.0, f.atRisk[0].projected, 0.001)
    }

    @Test fun noProjectionsInTheFirstDays() {
        val f = Calc.forecast(state(), emptyList(), emptyList(), java.time.LocalDate.of(2026, 10, 3))
        assertNull(f.projectedTotal)
        assertTrue(f.atRisk.isEmpty())
        assertEquals(29, f.daysLeft)
    }

    @Test fun historyListsTheLastMonthsOldestFirst() {
        val s = state(
            log = listOf(logRow(1, "2026-08-10", "Breakfast", 100.0), logRow(2, "2026-09-10", "Breakfast", 300.0), logRow(3, "2026-10-02", "Going out", 50.0)),
            income = listOf(IncomeLine(1, "2026-09-01", "Salary", 5000.0)),
        )
        val h = Calc.history(s, Calc.entries(s, emptyList()), emptyList(), YearMonth.of(2026, 10), count = 3)
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)), h.map { it.month })
        assertEquals(listOf(100.0, 300.0, 50.0), h.map { it.spent })
        assertEquals(listOf(0.0, 5000.0, 0.0), h.map { it.income })
    }

    @Test fun workbookEntriesKnowTheirSheetAndRow() {
        val s = state(log = listOf(logRow(7, "2026-10-01", "Breakfast", 10.0)), income = listOf(IncomeLine(3, "2026-10-01", "Salary", 5.0)))
        val e = Calc.entries(s, emptyList())
        assertEquals("Log" to 7, e.first { !it.isIncome }.let { it.sheet to it.row })
        assertEquals("Income" to 3, e.first { it.isIncome }.let { it.sheet to it.row })
    }

    @Test fun waitingFlagFollowsSyncState() {
        val e = Calc.entries(state(), listOf(phone("Breakfast", 1.0), phone("Breakfast", 2.0, synced = true)))
        assertEquals(listOf(true, false), e.map { it.waiting })
    }
}
