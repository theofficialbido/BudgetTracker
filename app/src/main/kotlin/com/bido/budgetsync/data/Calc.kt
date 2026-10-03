package com.bido.budgetsync.data

import java.time.LocalDate
import java.time.YearMonth

/** One dated entry from either the cached workbook data or the phone. Income has category [Calc.INCOME]. */
data class Entry(
    val key: String,
    val date: LocalDate?,
    val category: String,
    val description: String,
    val amount: Double,
    /** True while the laptop has not written it to Budget.xlsx yet. */
    val waiting: Boolean,
    /** The phone-side record, if this entry was made here and is not yet part of the cached workbook data. */
    val local: Expense? = null,
) {
    val isIncome get() = category.equals(Calc.INCOME, ignoreCase = true)
}

data class CategorySummary(
    val name: String,
    val planned: Double,
    val actual: Double,
    val percent: Double,
    val status: String,
    val custom: Boolean = false,
)

data class MonthSummary(
    val month: YearMonth,
    val categories: List<CategorySummary>,
    val total: CategorySummary,
    val incomeReceived: Double,
    /** Income received this month minus actual spending, like the Tracker's "Left after actual spending". */
    val left: Double,
)

/**
 * Works out the Tracker numbers on the phone, the same way the workbook does, from the last data fetched from the laptop
 * (plans, alert levels, rows already in Budget.xlsx) plus what was entered on the phone since. No connection needed.
 */
object Calc {
    const val INCOME = "Income"
    val DEFAULT_CATEGORIES = listOf("Claude subscription", "Breakfast", "Going out", "Other")

    /** Rows in the cached workbook data plus phone entries it does not contain yet. */
    fun entries(state: ServerState?, local: List<Expense>): List<Entry> {
        val fromWorkbook = (state?.log ?: emptyList()).map {
            Entry("r-${it.row}", parse(it.date), it.category, it.description, it.amount ?: 0.0, false)
        } + (state?.incomeLog ?: emptyList()).map {
            Entry("i-${it.row}", parse(it.date), INCOME, it.source, it.amount ?: 0.0, false)
        }
        val fromPhone = local.filter { !it.inCache }.map {
            Entry("w-${it.id}", parse(it.date), it.category, it.description, it.amount, waiting = !it.synced, local = it)
        }
        return fromWorkbook + fromPhone
    }

    /** All category names to offer: the workbook's, those from the laptop's extras sheet, and ones made on the phone. */
    fun categoryNames(state: ServerState?, custom: List<CustomCategory>): List<String> {
        val names = ArrayList<String>()
        (state?.tracker?.map { it.category }?.ifEmpty { null } ?: DEFAULT_CATEGORIES).forEach { names += it }
        state?.extras?.forEach { e -> if (names.none { it.equals(e.category, true) }) names += e.category }
        custom.forEach { c -> if (names.none { it.equals(c.name, true) }) names += c.name }
        return names
    }

    fun summarize(state: ServerState?, entries: List<Entry>, custom: List<CustomCategory>, month: YearMonth): MonthSummary {
        val watch = state?.watchAt ?: 0.8
        val over = state?.overAt ?: 1.0
        val base = state?.tracker?.map { it.category to it.planned }?.ifEmpty { null } ?: DEFAULT_CATEGORIES.map { it to 0.0 }
        val extras = state?.extras?.map { it.category to it.planned } ?: emptyList()
        val phoneOnly = custom
            .filter { c -> (base + extras).none { it.first.equals(c.name, true) } }
            .map { it.name to it.planned }
        val all = base + extras + phoneOnly
        val customNames = (extras + phoneOnly).map { it.first }.toSet()

        val inMonth = entries.filter { it.date != null && YearMonth.from(it.date) == month }
        val spending = inMonth.filter { !it.isIncome }
        // A category the phone no longer knows (renamed in Excel) counts under "Other", as the helper does when writing.
        val orphans = spending.filter { s -> all.none { it.first.equals(s.category, true) } }.sumOf { it.amount }

        val lines = all.map { (name, planned) ->
            var actual = spending.filter { it.category.equals(name, true) }.sumOf { it.amount }
            if (name.equals("Other", true)) actual += orphans
            summary(name, planned, actual, watch, over, name in customNames)
        }
        val totalPlanned = lines.sumOf { it.planned }
        val totalActual = lines.sumOf { it.actual }
        val totalStatus = when {
            totalActual > totalPlanned -> "OVER"
            totalActual >= watch * totalPlanned -> "WATCH"
            else -> "OK"
        }
        val total = CategorySummary("Total", totalPlanned, totalActual, ratio(totalActual, totalPlanned), totalStatus)
        val income = inMonth.filter { it.isIncome }.sumOf { it.amount }
        return MonthSummary(month, lines, total, income, income - totalActual)
    }

    /** Same rules as the Tracker's Status column. */
    fun status(planned: Double, actual: Double, watch: Double, over: Double): String = when {
        planned == 0.0 -> if (actual > 0) "UNPLANNED" else "OK"
        actual / planned >= over -> "OVER"
        actual / planned >= watch -> "WATCH"
        else -> "OK"
    }

    private fun summary(name: String, planned: Double, actual: Double, watch: Double, over: Double, custom: Boolean) =
        CategorySummary(name, planned, actual, ratio(actual, planned), status(planned, actual, watch, over), custom)

    private fun ratio(actual: Double, planned: Double) = if (planned == 0.0) 0.0 else actual / planned

    private fun parse(date: String?): LocalDate? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
