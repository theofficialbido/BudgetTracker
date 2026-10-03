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
