package com.bido.budgetsync.helper

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BusyException(message: String) : Exception(message)
class FullException(sheet: String, last: Int = 1000) : Exception("The $sheet sheet is full (rows 2 to $last). Archive old rows in Budget.xlsx.")

data class NewExpense(val id: String, val date: LocalDate, val category: String, val description: String, val amount: Double)
data class AppendResult(val id: String, val row: Int, val status: String)
data class LogRow(val row: Int, val date: String?, val category: String, val description: String, val amount: Double?)
data class IncomeRow(val row: Int, val date: String?, val source: String, val amount: Double?)
data class TrackerRow(val category: String, val planned: Double, val actual: Double, val percent: Double, val status: String)
data class State(
    val month: String,
    val categories: List<String>,
    val log: List<LogRow>,
    val incomeLog: List<IncomeRow>,
    val tracker: List<TrackerRow>,
    val total: TrackerRow,
    val income: Double,            // Tracker "income" line (actual once the workbook is upgraded)
    val plannedIncome: Double,     // Plan!D7
    val leftAfterActual: Double,
    val incomeReceived: Double,    // Income sheet rows dated this month
    val extras: List<TrackerRow>,  // categories added from the phone, kept on the "More categories" sheet
    val watchAt: Double,           // Plan!B25, alert level for WATCH (0.8 = 80%)
    val overAt: Double,            // Plan!B26, alert level for OVER
)
data class NewCategory(val name: String, val planned: Double)
data class CategoryResult(val name: String, val status: String)
data class UpgradeReport(val alreadyUpgraded: Boolean, val movedRows: Int, val backup: Path?)

/** Reads and appends to Budget.xlsx. Expenses go to Log A:D, income to the Income sheet A:C; nothing else is written. */
class BudgetWorkbook(private val path: Path, private val dataDir: Path) {
    companion object {
        const val FIRST_ROW = 2      // 1-based
        const val LAST_ROW = 1000
        const val INCOME = "Income"          // category the phone uses for income entries
        const val LOG = "Log"
        const val INCOME_SHEET = "Income"
        const val EXTRA_SHEET = "More categories"   // categories added from the phone: name, planned, then live actual/status
        const val EXTRA_FIRST = 2
        const val EXTRA_LAST = 51                    // room for 50 extra categories
        private const val EXTRA_REF = "'More categories'!"
        private const val MONTH_OF_LOG =
            "Log!\$A\$2:\$A\$1000,\">=\"&Tracker!\$B\$2,Log!\$A\$2:\$A\$1000,\"<\"&EDATE(Tracker!\$B\$2,1)"
        const val INCOME_FORMULA =
            "SUMIFS(Income!\$C\$2:\$C\$1000,Income!\$A\$2:\$A\$1000,\">=\"&\$B\$2,Income!\$A\$2:\$A\$1000,\"<\"&EDATE(\$B\$2,1))"
    }

    private val lockFile: Path get() = path.resolveSibling("~$" + path.fileName)

    /** Column layout of a sheet that holds entries. */
    private class Layout(val sheet: String, val columns: Int, val amountCol: Int)
    private val logLayout = Layout(LOG, 4, 3)
    private val incomeLayout = Layout(INCOME_SHEET, 3, 2)

    @Synchronized
    fun readState(): State {
        open().use { wb ->
            val ev = wb.creationHelper.createFormulaEvaluator()
            val tracker = wb.getSheet("Tracker") ?: error("Sheet 'Tracker' not found")
            val log = wb.getSheet(LOG) ?: error("Sheet 'Log' not found")
            val rows = (5..8).map { trackerRow(tracker, it, ev) }
            val month = LocalDate.now().toString().substring(0, 7)
            val income = wb.getSheet(INCOME_SHEET)?.let { readIncome(it) } ?: emptyList()
            val extras = wb.getSheet(EXTRA_SHEET)?.let { readExtras(it, ev) } ?: emptyList()
            val plan = wb.getSheet("Plan")
            return State(
                month = month,
                categories = rows.map { it.category } + extras.map { it.category },
                extras = extras,
                watchAt = plan?.cell(25, 1)?.let { num(it, ev) }?.takeIf { it > 0 } ?: 0.8,
                overAt = plan?.cell(26, 1)?.let { num(it, ev) }?.takeIf { it > 0 } ?: 1.0,
                log = readLog(log),
                incomeLog = income,
                tracker = rows,
                total = trackerRow(tracker, 9, ev),
                income = num(tracker.cell(11, 2), ev),
                plannedIncome = num(wb.getSheet("Plan")?.cell(7, 3), ev),
                leftAfterActual = num(tracker.cell(12, 2), ev),
                incomeReceived = income.filter { it.date?.startsWith(month) == true }.sumOf { it.amount ?: 0.0 },
            )
        }
    }

    @Synchronized
    fun append(items: List<NewExpense>, ledger: SyncLedger): List<AppendResult> {
        if (Files.exists(lockFile)) throw BusyException("Budget.xlsx is open in Excel")
        val before = mtime()
        open().use { wb ->
            val log = wb.getSheet(LOG) ?: error("Sheet 'Log' not found")
            val categories = (wb.getSheet("Tracker")
                ?.let { t -> (5..8).map { str(t.cell(it, 0)) }.filter { c -> c.isNotBlank() } } ?: emptyList()) +
                (wb.getSheet(EXTRA_SHEET)?.let { extraNames(it) } ?: emptyList())
            val results = ArrayList<AppendResult>()
            val assigned = LinkedHashMap<String, Slot>()
            var wrote = false
            for (item in items) {
                val isIncome = item.category.equals(INCOME, ignoreCase = true)
                val layout = if (isIncome) incomeLayout else logLayout
                val sheet = if (isIncome) ensureIncomeSheet(wb, log) else log
                val known = ledger.slotFor(item.id)?.takeIf { it.sheet == layout.sheet }?.row
                if (known != null && matches(sheet, layout, known, item)) {
                    results += AppendResult(item.id, known, "already")
                    continue
                }
                // Same date (and category, for expenses) and amount as a row nobody on the phone owns (typed in Excel or
                // logged by the 9pm routine): that is this entry already, so claim it instead of adding a second copy.
                val twin = if (known == null) findUnclaimedTwin(sheet, layout, item, isIncome, ledger.claimedRows(layout.sheet) +
                    assigned.values.filter { it.sheet == layout.sheet }.map { it.row }) else null
                if (twin != null) {
                    assigned[item.id] = Slot(layout.sheet, twin)
                    results += AppendResult(item.id, twin, "already")
                    continue
                }
                val row = if (known != null && isEmpty(sheet, layout, known)) known
                else firstEmpty(sheet, layout) ?: throw FullException(layout.sheet)
                if (isIncome) writeIncome(sheet, row, item) else {
                    val category = categories.firstOrNull { it.equals(item.category, ignoreCase = true) }
                        ?: categories.firstOrNull { it == "Other" } ?: categories.firstOrNull() ?: item.category
                    writeLog(sheet, row, item.copy(category = category))
                }
                assigned[item.id] = Slot(layout.sheet, row)
                results += AppendResult(item.id, row, "added")
                wrote = true
            }
            if (assigned.isEmpty()) return results
            if (!wrote) { ledger.putAll(assigned); return results }   // only claimed existing rows: no workbook change
            // Reserve rows first: a crash before the replace is healed on retry (the row is still empty).
            ledger.putAll(assigned)
            finish(wb, before)
            return results
        }
    }

    /**
     * One-time upgrade: creates the Income sheet, points the Tracker's income line at this month's logged income,
     * and moves any income rows the phone put in Log over to the Income sheet. Safe to run again.
     */
    @Synchronized
    fun upgrade(ledger: SyncLedger): UpgradeReport {
        if (Files.exists(lockFile)) throw BusyException("Budget.xlsx is open in Excel")
        val before = mtime()
        open().use { wb ->
            val log = wb.getSheet(LOG) ?: error("Sheet 'Log' not found")
            val tracker = wb.getSheet("Tracker") ?: error("Sheet 'Tracker' not found")
            val existed = wb.getSheet(INCOME_SHEET) != null
            val income = ensureIncomeSheet(wb, log)
            val c11 = tracker.cell(11, 2) ?: error("Tracker!C11 not found")
            val linked = c11.cellType == CellType.FORMULA && c11.cellFormula.contains("Income!")
            val moves = LinkedHashMap<Slot, Slot>()
            for (r in FIRST_ROW..LAST_ROW) {
                if (isEmpty(log, logLayout, r) || !str(log.cell(r, 1)).trim().equals(INCOME, ignoreCase = true)) continue
                val target = firstEmpty(income, incomeLayout) ?: throw FullException(INCOME_SHEET)
                val t = income.getRow(FIRST_ROW - 1)
                log.cell(r, 0)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue
                    ?.let { cellAt(income, target, 0, t).setCellValue(it) }
                cellAt(income, target, 1, t).setCellValue(str(log.cell(r, 2)))
                log.cell(r, 3)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue
                    ?.let { cellAt(income, target, 2, t).setCellValue(it) }
                (0..3).forEach { c -> log.cell(r, c)?.setBlank() }
                moves[Slot(LOG, r)] = Slot(INCOME_SHEET, target)
            }
            val plan = wb.getSheet("Plan")
            val planD7 = plan?.cell(7, 3)
            val planLinked = plan == null || planD7 == null || (planD7.cellType == CellType.FORMULA && planD7.cellFormula.contains("Income!"))
            val extrasLinked = wb.getSheet(EXTRA_SHEET) != null && extrasAreLinked(wb)
            if (existed && linked && planLinked && extrasLinked && moves.isEmpty()) return UpgradeReport(true, 0, null)
            val backup = backup()
            if (!extrasLinked) { ensureExtraSheet(wb, log); linkExtras(wb) }
            if (!linked) {
                c11.cellFormula = INCOME_FORMULA
                setText(tracker, 11, 0, "Income received this month")
            }
            if (!planLinked && plan != null) linkPlanToIncome(plan)
            finish(wb, before)
            ledger.relocate(moves)
            return UpgradeReport(false, moves.size, backup)
        }
    }

    /** Replace a cell's text keeping its style. Done by swapping the cell: an inline string would otherwise be half-updated by POI. */
    private fun setText(sheet: Sheet, row1: Int, col: Int, text: String) {
        val old = sheet.cell(row1, col) ?: return
        val style = old.cellStyle
        old.row.removeCell(old)
        old.row.createCell(col).apply { cellStyle = style; setCellValue(text) }
    }

    /**
     * Plan income follows the Income sheet for the current month (month start comes from Tracker!B2):
     * D7 = everything logged, D5 = rows whose source contains "job" or "salary", D6 = the rest. The leftover split then uses
     * real income and never goes negative. Shares (50/30/20) and alert levels are left as they were.
     */
    private fun linkPlanToIncome(plan: Sheet) {
        val month = "Income!\$A\$2:\$A\$1000,\">=\"&Tracker!\$B\$2,Income!\$A\$2:\$A\$1000,\"<\"&EDATE(Tracker!\$B\$2,1)"
        val formulaStyle = plan.cell(10, 3)?.cellStyle
        fun setFormula(row1: Int, f: String) = plan.cell(row1, 3)?.let { c ->
            c.cellFormula = f
            formulaStyle?.let { c.cellStyle = it }
        }
        setFormula(7, "SUMIFS(Income!\$C\$2:\$C\$1000,$month)")
        val amt = "Income!\$C\$2:\$C\$1000"
        val src = "Income!\$B\$2:\$B\$1000"
        // "job" or "salary" in the source, counted once even if a source contains both words
        setFormula(5, "SUMIFS($amt,$src,\"*job*\",$month)+SUMIFS($amt,$src,\"*salary*\",$month)-SUMIFS($amt,$src,\"*job*\",$src,\"*salary*\",$month)")
        setFormula(6, "D7-D5")
        setText(plan, 5, 0, "Job income (logged this month)")
        setText(plan, 6, 0, "Allowance and other (logged this month)")
        for (r in 18..20) plan.cell(r, 3)?.cellFormula = "MAX(0,\$D\$15)*B$r"
        setText(plan, 29, 0, "Subscription, breakfast (200/day, about 16 days a month), going out (300 x 4) and the 50/30/20 split are the user's own stated figures.")
        setText(plan, 30, 0, "Income is not a plan: it follows what you log on the phone (Income sheet). Rows whose source contains 'job' or 'salary' show as Job income, everything else as Allowance and other. The leftover and its split show 0 until income is logged this month.")
    }

    private fun backup(): Path {
        val dir = dataDir.resolve("backups")
        Files.createDirectories(dir)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Files.copy(path, dir.resolve("Budget-before-upgrade-$stamp.xlsx"))
    }

    /** Adds categories created on the phone to the "More categories" sheet. Existing names (any case) are left alone. */
    @Synchronized
    fun addCategories(items: List<NewCategory>): List<CategoryResult> {
        if (Files.exists(lockFile)) throw BusyException("Budget.xlsx is open in Excel")
        val before = mtime()
        open().use { wb ->
            val log = wb.getSheet(LOG) ?: error("Sheet 'Log' not found")
            val tracker = wb.getSheet("Tracker") ?: error("Sheet 'Tracker' not found")
            val ready = wb.getSheet(EXTRA_SHEET) != null && extrasAreLinked(wb)
            val sheet = ensureExtraSheet(wb, log)
            val reserved = (5..8).map { str(tracker.cell(it, 0)) } + INCOME
            val results = ArrayList<CategoryResult>()
            var wrote = false
            for (item in items) {
                val name = item.name.trim()
                require(name.length in 1..40 && name.none { it.isISOControl() }) { "category name must be 1 to 40 characters" }
                require(item.planned.isFinite() && item.planned >= 0) { "planned amount must be 0 or more" }
                if ((reserved + extraNames(sheet)).any { it.equals(name, ignoreCase = true) }) {
                    results += CategoryResult(name, "exists")
                    continue
                }
                val row = (EXTRA_FIRST..EXTRA_LAST).firstOrNull { r -> str(sheet.cell(r, 0)).isBlank() }
                    ?: throw FullException(EXTRA_SHEET, EXTRA_LAST)
                sheet.cell(row, 0)?.setCellValue(name)
                sheet.cell(row, 1)?.setCellValue(item.planned)
                results += CategoryResult(name, "added")
                wrote = true
            }
            if (!wrote && ready) return results
            if (!ready) { backup(); linkExtras(wb) }
            finish(wb, before)
            return results
        }
    }

    private fun extraNames(sheet: Sheet): List<String> =
        (EXTRA_FIRST..EXTRA_LAST).map { str(sheet.cell(it, 0)).trim() }.filter { it.isNotEmpty() }

    private fun readExtras(sheet: Sheet, ev: FormulaEvaluator): List<TrackerRow> = (EXTRA_FIRST..EXTRA_LAST).mapNotNull { r ->
        val name = str(sheet.cell(r, 0)).trim()
        if (name.isEmpty()) null
        else TrackerRow(name, num(sheet.cell(r, 1), ev), num(sheet.cell(r, 2), ev), num(sheet.cell(r, 3), ev), str(sheet.cell(r, 4), ev))
    }

    private fun extrasAreLinked(wb: Workbook): Boolean {
        val b9 = wb.getSheet("Tracker")?.cell(9, 1) ?: return true   // no Tracker total to link
        return b9.cellType == CellType.FORMULA && b9.cellFormula.contains("More categories")
    }

    /** Tracker totals (planned B9, actual C9, so "left after actual spending" too) and Plan's planned spending (D13) include the extras. */
    private fun linkExtras(wb: Workbook) {
        val tracker = wb.getSheet("Tracker")
        tracker?.cell(9, 1)?.cellFormula = "SUM(B5:B8)+${EXTRA_REF}\$G\$2"
        tracker?.cell(9, 2)?.cellFormula = "SUM(C5:C8)+${EXTRA_REF}\$H\$2"
        wb.getSheet("Plan")?.cell(13, 3)?.cellFormula = "SUM(D10:D12)+${EXTRA_REF}\$G\$2"
    }

    private fun ensureExtraSheet(wb: Workbook, log: Sheet): Sheet {
        wb.getSheet(EXTRA_SHEET)?.let { return it }
        val tracker = wb.getSheet("Tracker")
        val sheet = wb.createSheet(EXTRA_SHEET)
        fun logStyle(r: Int, c: Int) = log.getRow(r)?.getCell(c)?.cellStyle
        fun trackerStyle(r: Int, c: Int) = tracker?.getRow(r)?.getCell(c)?.cellStyle
        sheet.createRow(0).apply {
            listOf("Category", "Planned (EGP)", "Actual so far", "% used", "Status").forEachIndexed { i, h ->
                createCell(i).apply { setCellValue(h); logStyle(0, 0)?.let { cellStyle = it } }
            }
            createCell(6).apply { setCellValue("Total planned"); logStyle(0, 0)?.let { cellStyle = it } }
            createCell(7).apply { setCellValue("Total actual"); logStyle(0, 0)?.let { cellStyle = it } }
            createCell(9).apply { setCellValue("How to use"); logStyle(0, 5)?.let { cellStyle = it } }
        }
        for (r in EXTRA_FIRST..EXTRA_LAST) {
            val row = sheet.createRow(r - 1)
            row.createCell(0).also { c -> logStyle(1, 1)?.let { c.cellStyle = it } }
            row.createCell(1).also { c -> logStyle(1, 3)?.let { c.cellStyle = it } }
            row.createCell(2).apply {
                cellFormula = "IF(\$A$r=\"\",0,SUMIFS(Log!\$D\$2:\$D\$1000,Log!\$B\$2:\$B\$1000,\$A$r,$MONTH_OF_LOG))"
                logStyle(1, 3)?.let { cellStyle = it }
            }
            row.createCell(3).apply { cellFormula = "IF(B$r=0,0,C$r/B$r)"; trackerStyle(4, 3)?.let { cellStyle = it } }
            row.createCell(4).apply {
                cellFormula = "IF(\$A$r=\"\",\"\",IF(B$r=0,IF(C$r>0,\"UNPLANNED\",\"OK\"),IF(C$r/B$r>=Plan!\$B\$26,\"OVER\",IF(C$r/B$r>=Plan!\$B\$25,\"WATCH\",\"OK\"))))"
                trackerStyle(4, 4)?.let { cellStyle = it }
            }
        }
        sheet.getRow(1).apply {
            createCell(6).apply { cellFormula = "SUM(B$EXTRA_FIRST:B$EXTRA_LAST)"; logStyle(1, 3)?.let { cellStyle = it } }
            createCell(7).apply { cellFormula = "SUM(C$EXTRA_FIRST:C$EXTRA_LAST)"; logStyle(1, 3)?.let { cellStyle = it } }
            createCell(9).apply {
                setCellValue("Categories added in the Budget Tracker app land here. To add one by hand, type a name in column A and a monthly plan in B. The Tracker totals include this sheet.")
                logStyle(1, 5)?.let { cellStyle = it }
            }
        }
        listOf(0 to 28, 1 to 16, 2 to 16, 3 to 10, 4 to 14, 6 to 16, 7 to 16, 9 to 70).forEach { (c, w) -> sheet.setColumnWidth(c, w * 256) }
        sheet.createFreezePane(0, 1)
        return sheet
    }

    private fun ensureIncomeSheet(wb: Workbook, log: Sheet): Sheet {
        wb.getSheet(INCOME_SHEET)?.let { return it }
        val sheet = wb.createSheet(INCOME_SHEET)
        fun styleOf(row: Int, col: Int) = log.getRow(row)?.getCell(col)?.cellStyle
        sheet.createRow(0).apply {
            listOf("Date" to 0, "Source" to 1, "Amount (EGP)" to 3).forEachIndexed { i, (text, srcCol) ->
                createCell(i).apply { setCellValue(text); styleOf(0, srcCol)?.let { cellStyle = it } }
            }
            createCell(4).apply { setCellValue("How to use"); styleOf(0, 5)?.let { cellStyle = it } }
        }
        sheet.getRow(0).height = log.getRow(0)?.height ?: sheet.getRow(0).height
        for (r in 1 until LAST_ROW) {
            val row = sheet.createRow(r)
            listOf(0, 1, 3).forEachIndexed { i, srcCol -> row.createCell(i).also { c -> styleOf(1, srcCol)?.let { c.cellStyle = it } } }
        }
        sheet.getRow(1).createCell(4).apply {
            setCellValue("Income logged on the phone lands here. The Tracker adds up this month's rows.")
            styleOf(1, 5)?.let { cellStyle = it }
        }
        sheet.setColumnWidth(0, 14 * 256); sheet.setColumnWidth(1, 34 * 256)
        sheet.setColumnWidth(2, 16 * 256); sheet.setColumnWidth(4, 60 * 256)
        sheet.createFreezePane(0, 1)
        return sheet
    }

    private fun finish(wb: Workbook, before: FileTime) {
        try { wb.creationHelper.createFormulaEvaluator().evaluateAll() } catch (_: Exception) { }
        wb.setForceFormulaRecalculation(true)
        replace(wb, before)
    }

    private fun replace(wb: Workbook, before: FileTime) {
        Files.createDirectories(dataDir)
        val tmp = dataDir.resolve("Budget.sync-tmp.xlsx")
        try {
            Files.newOutputStream(tmp).use { wb.write(it) }
            if (mtime() != before) throw BusyException("Budget.xlsx changed while syncing")
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw BusyException("Budget.xlsx is locked (${e.javaClass.simpleName})")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun mtime(): FileTime =
        try { Files.getLastModifiedTime(path) } catch (e: IOException) { throw BusyException("Cannot read Budget.xlsx") }

    private fun open(): Workbook = try {
        WorkbookFactory.create(ByteArrayInputStream(Files.readAllBytes(path)))
    } catch (e: IOException) {
        throw BusyException("Cannot read Budget.xlsx (${e.javaClass.simpleName})")
    }

    private fun Sheet.cell(row1: Int, col0: Int): Cell? = getRow(row1 - 1)?.getCell(col0)

    private fun isEmpty(sheet: Sheet, l: Layout, row1: Int) = (0 until l.columns).all { c ->
        val cell = sheet.cell(row1, c)
        cell == null || cell.cellType == CellType.BLANK || (cell.cellType == CellType.STRING && cell.stringCellValue.isBlank())
    }

    private fun firstEmpty(sheet: Sheet, l: Layout): Int? = (FIRST_ROW..LAST_ROW).firstOrNull { isEmpty(sheet, l, it) }

    private fun matches(sheet: Sheet, l: Layout, row1: Int, e: NewExpense): Boolean {
        val amount = sheet.cell(row1, l.amountCol)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue ?: return false
        val date = sheet.cell(row1, 0)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue ?: return false
        // Date and amount identify the entry. Description and category are not compared: a claimed row keeps whatever
        // wording the other side used, and an unknown category was filed under "Other" when it was written.
        return Math.floor(date).toLong() == e.date.toEpochDay() + 25569 && Math.abs(amount - e.amount) < 0.001
    }

    private fun findUnclaimedTwin(sheet: Sheet, l: Layout, e: NewExpense, isIncome: Boolean, taken: Set<Int>): Int? {
        val serial = e.date.toEpochDay() + 25569
        return (FIRST_ROW..LAST_ROW).firstOrNull { r ->
            if (r in taken || isEmpty(sheet, l, r)) return@firstOrNull false
            val date = sheet.cell(r, 0)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue ?: return@firstOrNull false
            val amount = sheet.cell(r, l.amountCol)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue ?: return@firstOrNull false
            Math.floor(date).toLong() == serial && Math.abs(amount - e.amount) < 0.001 &&
                (isIncome || str(sheet.cell(r, 1)).trim().equals(e.category, ignoreCase = true))
        }
    }

    private fun cellAt(sheet: Sheet, row1: Int, col: Int, template: Row?): Cell {
        val row = sheet.getRow(row1 - 1) ?: sheet.createRow(row1 - 1)
        return row.getCell(col) ?: row.createCell(col).also { c -> template?.getCell(col)?.let { c.cellStyle = it.cellStyle } }
    }

    private fun writeLog(log: Sheet, row1: Int, e: NewExpense) {
        val t = log.getRow(FIRST_ROW - 1)
        cellAt(log, row1, 0, t).setCellValue(e.date.toEpochDay() + 25569.0)
        cellAt(log, row1, 1, t).setCellValue(e.category)
        cellAt(log, row1, 2, t).setCellValue(e.description)
        cellAt(log, row1, 3, t).setCellValue(e.amount)
    }

    private fun writeIncome(sheet: Sheet, row1: Int, e: NewExpense) {
        val t = sheet.getRow(FIRST_ROW - 1)
        cellAt(sheet, row1, 0, t).setCellValue(e.date.toEpochDay() + 25569.0)
        cellAt(sheet, row1, 1, t).setCellValue(e.description)
        cellAt(sheet, row1, 2, t).setCellValue(e.amount)
    }

    private fun dateOf(c: Cell?): String? = c?.takeIf { it.cellType == CellType.NUMERIC && it.numericCellValue > 0 }
        ?.let { DateUtil.getJavaDate(it.numericCellValue).toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString() }

    private fun readLog(log: Sheet): List<LogRow> = (FIRST_ROW..LAST_ROW).mapNotNull { r ->
        if (isEmpty(log, logLayout, r)) return@mapNotNull null
        LogRow(r, dateOf(log.cell(r, 0)), str(log.cell(r, 1)), str(log.cell(r, 2)),
            log.cell(r, 3)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue)
    }

    private fun readIncome(sheet: Sheet): List<IncomeRow> = (FIRST_ROW..LAST_ROW).mapNotNull { r ->
        if (isEmpty(sheet, incomeLayout, r)) return@mapNotNull null
        IncomeRow(r, dateOf(sheet.cell(r, 0)), str(sheet.cell(r, 1)),
            sheet.cell(r, 2)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue)
    }

    private fun trackerRow(t: Sheet, r: Int, ev: FormulaEvaluator) = TrackerRow(
        category = str(t.cell(r, 0), ev), planned = num(t.cell(r, 1), ev), actual = num(t.cell(r, 2), ev),
        percent = num(t.cell(r, 3), ev), status = str(t.cell(r, 4), ev),
    )

    private fun num(c: Cell?, ev: FormulaEvaluator? = null): Double = when (c?.cellType) {
        CellType.NUMERIC -> c.numericCellValue
        CellType.FORMULA -> ev?.evaluate(c)?.takeIf { it.cellType == CellType.NUMERIC }?.numberValue ?: 0.0
        else -> 0.0
    }

    private fun str(c: Cell?, ev: FormulaEvaluator? = null): String = when (c?.cellType) {
        CellType.STRING -> c.stringCellValue
        CellType.NUMERIC -> c.numericCellValue.toString()
        CellType.FORMULA -> ev?.evaluate(c)?.takeIf { it.cellType == CellType.STRING }?.stringValue ?: ""
        else -> ""
    }
}
