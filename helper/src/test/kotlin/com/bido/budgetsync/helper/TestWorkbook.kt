package com.bido.budgetsync.helper

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Builds a small workbook shaped like the real Budget.xlsx for tests. */
object TestWorkbook {
    /** Mirrors the real workbook: Tracker A5:A8 with SUMIFS over Log, Plan cells, Log rows 2..1000 pre-styled. */
    fun create(xlsx: Path, today: LocalDate) {
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
}
