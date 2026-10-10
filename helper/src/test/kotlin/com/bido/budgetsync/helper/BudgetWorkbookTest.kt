package com.bido.budgetsync.helper

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BudgetWorkbookTest {
    private val dir: Path = Files.createTempDirectory("budgetsync-test")
    private val xlsx: Path = dir.resolve("Budget.xlsx")
    private val ledger = SyncLedger(dir.resolve("synced-ids.json"))
    private val book = BudgetWorkbook(xlsx, dir)
    private val today = LocalDate.now()

    /** Mirrors the real workbook: Tracker A5:A8 with SUMIFS over Log, Plan cells, Log rows 2..1000 pre-styled. */
    private fun fixture() {
        val wb = XSSFWorkbook()
        val tracker = wb.createSheet("Tracker")
        val plan = wb.createSheet("Plan")
        val log = wb.createSheet("Log")
        tracker.createRow(1).apply {
            createCell(0).setCellValue("Month start")
            createCell(1).setCellFormula("DATE(YEAR(TODAY()),MONTH(TODAY()),1)")
        }
        val cats = listOf("Claude subscription", "Breakfast", "Going out", "Other")
        cats.forEachIndexed { i, c ->
            val r = 5 + i
            tracker.createRow(r - 1).apply {
                createCell(0).setCellValue(c)
                createCell(1).setCellValue(if (i == 3) 0.0 else 1000.0)
                createCell(2).setCellFormula(
                    "SUMIFS(Log!\$D\$2:\$D\$1000,Log!\$B\$2:\$B\$1000,A$r,Log!\$A\$2:\$A\$1000,\">=\"&\$B\$2,Log!\$A\$2:\$A\$1000,\"<\"&EDATE(\$B\$2,1))"
                )
                createCell(3).setCellFormula("IF(B$r=0,0,C$r/B$r)")
                createCell(4).setCellFormula("IF(B$r=0,IF(C$r>0,\"UNPLANNED\",\"OK\"),IF(C$r/B$r>=1,\"OVER\",IF(C$r/B$r>=0.8,\"WATCH\",\"OK\")))")
            }
        }
        tracker.createRow(8).apply {
            createCell(0).setCellValue("Total")
            createCell(1).setCellFormula("SUM(B5:B8)")
            createCell(2).setCellFormula("SUM(C5:C8)")
            createCell(3).setCellFormula("IF(B9=0,0,C9/B9)")
            createCell(4).setCellFormula("IF(C9>B9,\"OVER\",\"OK\")")
        }
        tracker.createRow(10).createCell(2).setCellFormula("Plan!D7")
        tracker.createRow(11).createCell(2).setCellFormula("C11-C9")
        // Plan mirrors the real sheet: inputs D5:D6, total D7, planned spending D10:D13, leftover D15, split B18:B20 / D18:D20
        fun planRow(r: Int, label: String? = null) = plan.getRow(r - 1) ?: plan.createRow(r - 1).also { row -> label?.let { row.createCell(0).setCellValue(it) } }
        planRow(5, "Job income").createCell(3).setCellValue(8000.0)
        planRow(6, "Allowance").createCell(3).setCellValue(2000.0)
        planRow(7, "Total income").createCell(3).setCellFormula("SUM(D5:D6)")
        planRow(10, "Claude subscription").apply { createCell(1).setCellValue(1200.0); createCell(2).setCellValue(1.0); createCell(3).setCellFormula("B10*C10") }
        planRow(13, "Total planned spending").createCell(3).setCellFormula("SUM(D10:D12)")
        planRow(15, "Leftover after planned spending").createCell(3).setCellFormula("D7-D13")
        listOf(0.5, 0.3, 0.2).forEachIndexed { i, share ->
            planRow(18 + i).apply { createCell(1).setCellValue(share); createCell(3).setCellFormula("\$D\$15*B${18 + i}") }
        }
        planRow(25, "WATCH level").createCell(1).setCellValue(0.8)
        planRow(26, "OVER level").createCell(1).setCellValue(1.0)
        planRow(29, "old note").createCell(0)
        planRow(30, "old note").createCell(0)

        val dateStyle = wb.createCellStyle().apply { dataFormat = wb.creationHelper.createDataFormat().getFormat("yyyy-mm-dd") }
        log.createRow(0).apply { listOf("Date", "Category", "Description", "Amount (EGP)").forEachIndexed { i, h -> createCell(i).setCellValue(h) } }
        log.getRow(0).createCell(5).setCellValue("How to use")
        for (r in 1..999) log.createRow(r).apply {
            createCell(0).cellStyle = dateStyle
            createCell(1); createCell(2); createCell(3)
        }
        log.getRow(1).apply {
            getCell(0).setCellValue(today.toEpochDay() + 25569.0); getCell(1).setCellValue("Going out")
            getCell(2).setCellValue("Drinks"); getCell(3).setCellValue(175.0)
        }
        log.getRow(1).createCell(5).setCellValue("note that must survive")
        Files.newOutputStream(xlsx).use { wb.write(it) }
        wb.close()
    }

    private fun expense(id: String, cat: String = "Breakfast", desc: String = "Foul", amount: Double = 50.0) =
        NewExpense(id, today, cat, desc, amount)

    @Test
    fun readsStateWithEvaluatedTracker() {
        fixture()
        val s = book.readState()
        assertEquals(listOf("Claude subscription", "Breakfast", "Going out", "Other"), s.categories)
        assertEquals(1, s.log.size)
        assertEquals(175.0, s.tracker[2].actual)
        assertEquals("OK", s.tracker[2].status)
        assertEquals(10000.0 - 175.0, s.leftAfterActual)
    }

    @Test
    fun appendsToFirstEmptyRowAndUpdatesTracker() {
        fixture()
        val r = book.append(listOf(expense("a"), expense("b", "Going out", "Taxi", 900.0)), ledger)
        assertEquals(listOf(3, 4), r.map { it.row })
        val s = book.readState()
        assertEquals(50.0, s.tracker[1].actual)
        assertEquals(1075.0, s.tracker[2].actual)
        assertEquals("OVER", s.tracker[2].status)
    }

    @Test
    fun retriesDoNotDuplicate() {
        fixture()
        book.append(listOf(expense("a")), ledger)
        val again = book.append(listOf(expense("a")), ledger)
        assertEquals("already", again.single().status)
        assertEquals(2, book.readState().log.size)
        // a fresh ledger object (helper restart) must also not duplicate
        val reloaded = SyncLedger(dir.resolve("synced-ids.json"))
        assertEquals("already", book.append(listOf(expense("a")), reloaded).single().status)
        assertEquals(2, book.readState().log.size)
    }

    @Test
    fun phoneExpenseMatchingAnExistingRowClaimsItInsteadOfDuplicating() {
        fixture()   // row 2 is today's "Going out" 175 (as if typed by the 9pm routine)
        val r = book.append(listOf(expense("p1", "Going out", "Drinks with friends", 175.0)), ledger)
        assertEquals("already", r.single().status)
        assertEquals(2, r.single().row)
        assertEquals(1, book.readState().log.size)
        // a second identical purchase from the phone is a genuine new one
        val second = book.append(listOf(expense("p2", "Going out", "Another round", 175.0)), ledger)
        assertEquals("added", second.single().status)
        assertEquals(2, book.readState().log.size)
        // and retrying the first one stays idempotent
        assertEquals("already", book.append(listOf(expense("p1", "Going out", "Drinks with friends", 175.0)), ledger).single().status)
        assertEquals(2, book.readState().log.size)
    }

    @Test
    fun datesAreTheSameDayInEveryTimeZone() {
        val saved = java.util.TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Africa/Cairo", "Asia/Tokyo", "America/Los_Angeles", "Pacific/Kiritimati")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                fixture()
                book.append(listOf(expense("z-$zone", "Breakfast", "Foul", 10.0)), SyncLedger(dir.resolve("ledger-${zone.replace('/', '_')}.json")))
                val rows = book.readState().log
                assertEquals(today.toString(), rows.first().date, "first row in $zone")
                assertEquals(today.toString(), rows.last().date, "added row in $zone")
            }
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    private fun ref(row: Int = 2, amount: Double? = 175.0, date: String? = today.toString(), sheet: String = "Log") =
        EntryRef(sheet, row, date, amount)

    @Test
    fun monthClosingsAreRecordedAndReadBack() {
        fixture()
        assertEquals(0, book.readState().closings.size)
        val r = book.setClosings(listOf(ClosingRow("2026-09", reset = true, invested = 300.0, splurged = 100.0)))
        assertEquals("added", r.single().status)
        book.setClosings(listOf(ClosingRow("2026-10", reset = false, invested = 0.0, splurged = 0.0)))
        // a second decision for the same month replaces the first
        assertEquals("updated", book.setClosings(listOf(ClosingRow("2026-09", reset = false, invested = 0.0, splurged = 0.0))).single().status)
        val c = book.readState().closings
        assertEquals(listOf("2026-09", "2026-10"), c.map { it.month })
        assertEquals(listOf(false, false), c.map { it.reset })
        book.setClosings(listOf(ClosingRow("2026-09", reset = true, invested = 250.0, splurged = 50.0)))
        val again = book.readState().closings.first { it.month == "2026-09" }
        assertEquals(true, again.reset)
        assertEquals(250.0, again.invested)
        assertEquals(50.0, again.splurged)
        assertFailsWith<IllegalArgumentException> { book.setClosings(listOf(ClosingRow("September", false, 0.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { book.setClosings(listOf(ClosingRow("2026-09", true, -1.0, 0.0))) }
    }

    private fun trackerBalance(): Double =
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(BudgetWorkbook.RUNNING_LABEL, wb.getSheet("Tracker").getRow(13).getCell(0).stringCellValue)
            wb.getSheet("Tracker").getRow(13).getCell(2).numericCellValue
        }

    @Test
    fun runningBalanceRowMatchesWhatThePhoneShows() {
        fixture()
        val lastMonth = today.minusMonths(1)
        book.append(
            listOf(
                NewExpense("a", today, "Income", "Salary", 1000.0),
                NewExpense("b", lastMonth, "Income", "Salary", 500.0),
                NewExpense("c", lastMonth, "Breakfast", "Foul", 100.0),
            ),
            ledger,
        )
        // nothing decided yet: everything counts. This month: 1000 in, 175 out. Last month: 500 in, 100 out.
        book.setClosings(listOf(ClosingRow(java.time.YearMonth.from(lastMonth).toString(), reset = false, invested = 0.0, splurged = 0.0)))
        assertEquals(1000.0 - 175.0 + 500.0 - 100.0, trackerBalance())
        // last month closed "start at 0": only this month counts
        book.setClosings(listOf(ClosingRow(java.time.YearMonth.from(lastMonth).toString(), reset = true, invested = 200.0, splurged = 200.0)))
        assertEquals(1000.0 - 175.0, trackerBalance())
        // the figures also still feed the existing monthly Tracker numbers untouched
        assertEquals(175.0, book.readState().total.actual)
    }

    @Test
    fun runningBalanceRowIsCreatedOnceAndLeavesTheTrackerAlone() {
        fixture()
        book.setClosings(listOf(ClosingRow("2025-01", reset = false, invested = 0.0, splurged = 0.0)))
        book.setClosings(listOf(ClosingRow("2025-02", reset = false, invested = 0.0, splurged = 0.0)))
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            val t = wb.getSheet("Tracker")
            assertEquals(BudgetWorkbook.RUNNING_LABEL, t.getRow(13).getCell(0).stringCellValue)
            assertEquals("Total", t.getRow(8).getCell(0).stringCellValue)             // existing rows untouched
            assertEquals("SUM(C5:C8)", t.getRow(8).getCell(2).cellFormula)
        }
    }

    @Test
    fun planOnAPlainTrackerCellIsSetDirectly() {
        fixture()
        book.addCategories(listOf(NewCategory("Gym", 100.0)))
        val r = book.setPlans(listOf(PlanChange("breakfast", 2500.0), PlanChange("Gym", 400.0), PlanChange("Nope", 5.0)))
        assertEquals(listOf("updated", "updated", "unknown"), r.map { it.status })
        val s = book.readState()
        assertEquals(2500.0, s.tracker.first { it.category == "Breakfast" }.planned)
        assertEquals(400.0, s.extras.single().planned)
        assertFailsWith<IllegalArgumentException> { book.setPlans(listOf(PlanChange("Gym", -1.0))) }
    }

    @Test
    fun planFedFromThePlanSheetKeepsItsTimesPerMonth() {
        fixture()
        // like the real workbook: Tracker B5 takes its plan from Plan!D10 = unit cost (B10) x times a month (C10)
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.getSheet("Tracker").getRow(4).getCell(1).cellFormula = "Plan!D10"
            wb.getSheet("Plan").getRow(9).getCell(2).setCellValue(4.0)
            Files.newOutputStream(xlsx).use { wb.write(it) }
        }
        assertEquals("updated", book.setPlans(listOf(PlanChange("Claude subscription", 600.0))).single().status)
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.creationHelper.createFormulaEvaluator().evaluateAll()
            val p = wb.getSheet("Plan")
            assertEquals(150.0, p.getRow(9).getCell(1).numericCellValue)    // unit cost
            assertEquals(4.0, p.getRow(9).getCell(2).numericCellValue)      // times per month untouched
            assertEquals(600.0, p.getRow(9).getCell(3).numericCellValue)    // monthly = what was asked for
        }
        assertEquals(600.0, book.readState().tracker.first { it.category == "Claude subscription" }.planned)
    }

    @Test
    fun editingAnEntryRewritesItAndMovesTheTrackerNumbers() {
        fixture()
        val yesterday = today.minusDays(1)
        book.updateEntry(EntryChange(ref(), yesterday, "breakfast", "Foul and eggs", 80.0))
        val s = book.readState()
        val row = s.log.single()
        assertEquals("Breakfast", row.category)               // case fixed to the real category name
        assertEquals("Foul and eggs", row.description)
        assertEquals(80.0, row.amount)
        assertEquals(yesterday.toString(), row.date)
        assertEquals(80.0, s.total.actual)
        assertEquals(0.0, s.tracker[2].actual)                // nothing left under Going out
    }

    @Test
    fun aChangedRowIsNeverOverwritten() {
        fixture()
        // the phone thinks row 2 holds 999, but Excel has 175
        assertFailsWith<ConflictException> { book.updateEntry(EntryChange(ref(amount = 999.0), today, "Other", "x", 5.0)) }
        assertFailsWith<ConflictException> { book.updateEntry(EntryChange(ref(date = "2020-01-01"), today, "Other", "x", 5.0)) }
        assertFailsWith<ConflictException> { book.deleteEntry(ref(amount = 1.0), ledger) }
        assertEquals(175.0, book.readState().log.single().amount)
        // an empty row cannot be edited, but deleting one is already done
        assertFailsWith<ConflictException> { book.updateEntry(EntryChange(ref(row = 9, amount = null, date = null), today, "Other", "x", 5.0)) }
        assertEquals("gone", book.deleteEntry(ref(row = 9, amount = null, date = null), ledger))
    }

    @Test
    fun deletingFreesTheRowAndTheLedgerClaim() {
        fixture()
        book.append(listOf(expense("a", "Breakfast", "Foul", 50.0)), ledger)       // goes to row 3
        assertEquals("deleted", book.deleteEntry(ref(row = 3, amount = 50.0), ledger))
        assertEquals(1, book.readState().log.size)
        assertEquals(null, ledger.slotFor("a"))
        assertEquals("gone", book.deleteEntry(ref(row = 3, amount = 50.0), ledger))   // deleting again is harmless
        // the freed row is reused
        assertEquals(3, book.append(listOf(expense("b", "Breakfast", "Eggs", 20.0)), ledger).single().row)
    }

    @Test
    fun editRulesForCategories() {
        fixture()
        assertFailsWith<IllegalArgumentException> { book.updateEntry(EntryChange(ref(), today, "Nonsense", "x", 5.0)) }
        assertFailsWith<IllegalArgumentException> { book.updateEntry(EntryChange(ref(), today, "Income", "x", 5.0)) }
        book.addCategories(listOf(NewCategory("Gym", 100.0)))
        book.updateEntry(EntryChange(ref(), today, "gym", "Pass", 120.0))
        assertEquals("Gym", book.readState().log.single().category)
    }

    @Test
    fun incomeEntriesCanBeEditedAndDeleted() {
        fixture()
        book.append(listOf(expense("i", "Income", "Freelance", 2500.0)), ledger)
        val incomeRef = ref(row = 2, amount = 2500.0, sheet = "Income")
        book.updateEntry(EntryChange(incomeRef, today, "Income", "Freelance logo", 3000.0))
        assertEquals(3000.0, book.readState().incomeReceived)
        assertEquals("Freelance logo", book.readState().incomeLog.single().source)
        book.deleteEntry(ref(row = 2, amount = 3000.0, sheet = "Income"), ledger)
        assertEquals(0, book.readState().incomeLog.size)
        assertEquals(0.0, book.readState().incomeReceived)
    }

    @Test
    fun retryIsNotMistakenForAnotherDaysEntryWithTheSameAmount() {
        fixture()   // row 2 is today's 175. Pretend the ledger reserved that row for a different entry (yesterday, also 175).
        ledger.putAll(mapOf("y1" to Slot("Log", 2)))
        val yesterday = NewExpense("y1", today.minusDays(1), "Going out", "Taxi", 175.0)
        val r = book.append(listOf(yesterday), ledger)
        assertEquals("added", r.single().status)         // not "already": the date differs, so row 2 is not this entry
        assertEquals(3, r.single().row)
        assertEquals(2, book.readState().log.size)               // the original row plus the new one
    }

    @Test
    fun sameAmountDifferentCategoryIsNotATwin() {
        fixture()
        assertEquals("added", book.append(listOf(expense("p1", "Breakfast", "Foul", 175.0)), ledger).single().status)
    }

    @Test
    fun customCategoryLivesOnItsOwnSheetAndCountsInTotals() {
        fixture()
        val added = book.addCategories(listOf(NewCategory("Gym", 300.0)))
        assertEquals("added", added.single().status)
        book.append(listOf(expense("g1", "Gym", "Monthly pass", 100.0), expense("g2", "gym", "Towel", 50.0)), ledger)
        val s = book.readState()
        assertEquals(listOf("Claude subscription", "Breakfast", "Going out", "Other", "Gym"), s.categories)
        val gym = s.extras.single()
        assertEquals(300.0, gym.planned)
        assertEquals(150.0, gym.actual)                 // both rows, matched case-insensitively
        assertEquals("OK", gym.status)
        assertEquals(listOf("Gym", "Gym"), s.log.takeLast(2).map { it.category })   // not folded into "Other"
        assertEquals(175.0 + 150.0, s.total.actual)     // Tracker total includes the extras
        assertEquals(3000.0 + 300.0, s.total.planned)
        assertEquals(0.8, s.watchAt)
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(1200.0 + 300.0, wb.getSheet("Plan").getRow(12).getCell(3).numericCellValue)   // planned spending D13
        }
    }

    @Test
    fun categoryNamesAreCheckedAndNotDuplicated() {
        fixture()
        val r = book.addCategories(listOf(NewCategory("going out", 0.0), NewCategory("Income", 0.0), NewCategory("Gym", 0.0), NewCategory("GYM", 5.0)))
        assertEquals(listOf("exists", "exists", "added", "exists"), r.map { it.status })
        assertEquals(1, book.readState().extras.size)
        assertFailsWith<IllegalArgumentException> { book.addCategories(listOf(NewCategory("  ", 0.0))) }
        assertFailsWith<IllegalArgumentException> { book.addCategories(listOf(NewCategory("x", -1.0))) }
        // adding the same list again changes nothing
        assertEquals(listOf("exists"), book.addCategories(listOf(NewCategory("Gym", 0.0))).map { it.status })
    }

    @Test
    fun overBudgetExtraCategoryShowsOver() {
        fixture()
        book.addCategories(listOf(NewCategory("Gym", 100.0)))
        book.append(listOf(expense("g", "Gym", "Pass", 120.0)), ledger)
        assertEquals("OVER", book.readState().extras.single().status)
    }

    @Test
    fun leavesFormulasAndOtherColumnsAlone() {
        fixture()
        book.append(listOf(expense("a")), ledger)
        WorkbookFactory.create(xlsx.toFile()).use { wb ->
            val t = wb.getSheet("Tracker")
            assertEquals(
                "SUMIFS(Log!\$D\$2:\$D\$1000,Log!\$B\$2:\$B\$1000,A6,Log!\$A\$2:\$A\$1000,\">=\"&\$B\$2,Log!\$A\$2:\$A\$1000,\"<\"&EDATE(\$B\$2,1))",
                t.getRow(5).getCell(2).cellFormula,
            )
            assertEquals(10000.0, wb.getSheet("Plan").getRow(6).getCell(3).numericCellValue)
            assertEquals("note that must survive", wb.getSheet("Log").getRow(1).getCell(5).stringCellValue)
            assertNull(wb.getSheet("Log").getRow(2).getCell(4))
            assertEquals("yyyy-mm-dd", wb.getSheet("Log").getRow(2).getCell(0).cellStyle.dataFormatString)
        }
    }

    @Test
    fun unknownCategoryFallsBackToOther() {
        fixture()
        book.append(listOf(expense("a", "Groceries")), ledger)
        assertEquals("Other", book.readState().log.last().category)
    }

    @Test
    fun incomeGoesToItsOwnSheetAndDoesNotTouchLog() {
        fixture()
        val r = book.append(listOf(expense("i1", "Income", "Freelance", 2500.0)), ledger)
        assertEquals(2, r.single().row)
        val s = book.readState()
        assertEquals(1, s.log.size)                       // Log untouched
        assertEquals("Freelance", s.incomeLog.single().source)
        assertEquals(2500.0, s.incomeReceived)
        assertEquals(175.0, s.total.actual)
        // retry and a same-day identical income from the phone
        assertEquals("already", book.append(listOf(expense("i1", "Income", "Freelance", 2500.0)), ledger).single().status)
        assertEquals(1, book.readState().incomeLog.size)
    }

    @Test
    fun incomeAndExpenseRowNumbersDoNotCollide() {
        fixture()
        book.append(listOf(expense("e1", "Breakfast", "Foul", 50.0), expense("i1", "Income", "Job", 1000.0)), ledger)
        // expense went to Log row 3, income to Income row 2; another expense must not be treated as a twin of the income row
        val r = book.append(listOf(expense("e2", "Breakfast", "Foul again", 1000.0)), ledger)
        assertEquals("added", r.single().status)
        assertEquals(4, r.single().row)
    }

    @Test
    fun upgradeLinksTrackerToIncomeAndMovesOldIncomeRows() {
        fixture()
        // an income row the phone put in Log before the Income sheet existed
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.getSheet("Log").getRow(2).apply {
                getCell(0).setCellValue(today.toEpochDay() + 25569.0); getCell(1).setCellValue("Income")
                getCell(2).setCellValue("Job"); getCell(3).setCellValue(2500.0)
            }
            Files.newOutputStream(xlsx).use { wb.write(it) }
        }
        ledger.putAll(mapOf("old-income" to Slot("Log", 3)))
        val report = book.upgrade(ledger)
        assertEquals(1, report.movedRows)
        assertEquals(true, Files.exists(report.backup!!))
        val s = book.readState()
        assertEquals(1, s.log.size)                            // only the 175 expense remains in Log
        assertEquals(2500.0, s.incomeReceived)
        assertEquals(2500.0, s.income)                         // Tracker income line is now actual
        assertEquals(2500.0 - 175.0, s.leftAfterActual)
        assertEquals(2500.0, s.plannedIncome)                  // Plan income now follows what is logged
        assertEquals(Slot("Income", 2), ledger.slotFor("old-income"))
        assertEquals(true, book.upgrade(ledger).alreadyUpgraded)
    }

    @Test
    fun planIncomeSplitsJobFromOtherAndLeftoverUsesRealIncome() {
        fixture()
        book.upgrade(ledger)
        book.append(
            listOf(expense("j", "Income", "Job salary", 5500.0), expense("a", "Income", "Allowance", 2000.0), expense("x", "Income", "Gift", 300.0)),
            ledger,
        )
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.creationHelper.createFormulaEvaluator().evaluateAll()
            val plan = wb.getSheet("Plan")
            fun d(row: Int) = plan.getRow(row - 1).getCell(3).numericCellValue
            assertEquals(5500.0, d(5))         // "job" in the source
            assertEquals(2300.0, d(6))         // allowance + gift
            assertEquals(7800.0, d(7))
            assertEquals(7800.0 - 1200.0, d(15))         // real income minus planned spending
            assertEquals(0.5 * d(15), d(18))             // 50% share of the real leftover
            assertEquals(0.3 * d(15), d(19))
        }
        assertEquals(7800.0, book.readState().income)   // Tracker income line agrees
    }

    @Test
    fun leftoverSplitNeverGoesNegative() {
        fixture()
        book.upgrade(ledger)
        WorkbookFactory.create(java.io.ByteArrayInputStream(Files.readAllBytes(xlsx))).use { wb ->
            wb.creationHelper.createFormulaEvaluator().evaluateAll()
            val plan = wb.getSheet("Plan")
            assertEquals(-1200.0, plan.getRow(14).getCell(3).numericCellValue)   // nothing logged yet: leftover is negative
            listOf(18, 19, 20).forEach { assertEquals(0.0, plan.getRow(it - 1).getCell(3).numericCellValue) }   // split floors at 0
            assertEquals("Job income (logged this month)", plan.getRow(4).getCell(0).stringCellValue)
        }
    }

    @Test
    fun busyWhenExcelLockFileExists() {
        fixture()
        Files.createFile(dir.resolve("~\$Budget.xlsx"))
        assertFailsWith<BusyException> { book.append(listOf(expense("a")), ledger) }
        assertEquals(1, book.readState().log.size)
    }

    @Test
    fun busyWhenFileCannotBeReplacedAndRetrySucceeds() {
        fixture()
        val lock = java.io.RandomAccessFile(File(xlsx.toString()), "rw")
        val channel = lock.channel
        try {
            // On Windows an open handle without share-delete blocks the replace; on other systems this may not block.
            val result = runCatching { book.append(listOf(expense("a")), ledger) }
            channel.close(); lock.close()
            if (result.isFailure) assertFailsWith<BusyException> { throw result.exceptionOrNull()!! }
        } finally { runCatching { lock.close() } }
        book.append(listOf(expense("a")), ledger)
        assertEquals(1, book.readState().log.count { it.description == "Foul" })
    }

    @Test
    fun reportsFullWhenLogHasNoRoom() {
        fixture()
        val many = (1..999).map { expense("x$it", amount = 1.0) }
        assertFailsWith<FullException> { book.append(many, ledger) }
    }
}
