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
    /** Where it lives in Budget.xlsx ("Log" or "Income", and the row), for entries already there. Null for phone-only ones. */
    val sheet: String? = null,
    val row: Int? = null,
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
    /** What carried in from earlier months (a running balance), unless a month end was closed with "start at 0". */
    val carriedIn: Double,
    /** What is left to spend: carried in + income declared this month - spending declared this month. */
    val left: Double,
)

/** What was decided when [month] ended: carry its leftover into the next month, or invest / treat yourself and start at 0. */
data class Closing(val month: YearMonth, val reset: Boolean, val invested: Double = 0.0, val splurged: Double = 0.0)

/** The earliest finished month that still needs a decision, and what it ended with (negative means overspent). */
data class PendingClose(val month: YearMonth, val balance: Double)

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
            Entry("r-${it.row}", parse(it.date), it.category, it.description, it.amount ?: 0.0, false, sheet = "Log", row = it.row)
        } + (state?.incomeLog ?: emptyList()).map {
            Entry("i-${it.row}", parse(it.date), INCOME, it.source, it.amount ?: 0.0, false, sheet = "Income", row = it.row)
        }
        val fromPhone = local.filter { !it.inCache }.map {
            Entry("w-${it.id}", parse(it.date), it.category, it.description, it.amount, waiting = !it.synced, local = it)
        }
        return fromWorkbook + fromPhone
    }

    /** Month-end decisions from the laptop's copy and from this phone; a decision made here wins for the same month. */
    fun mergeClosings(state: ServerState?, local: List<MonthClosing>): List<Closing> {
        val fromPhone = local.mapNotNull { c ->
            runCatching { Closing(YearMonth.parse(c.month), c.reset, c.invested, c.splurged) }.getOrNull()
        }
        val fromLaptop = (state?.closings ?: emptyList()).mapNotNull { c ->
            runCatching { Closing(YearMonth.parse(c.month), c.reset, c.invested, c.splurged) }.getOrNull()
        }.filter { l -> fromPhone.none { it.month == l.month } }
        return fromPhone + fromLaptop
    }

    fun planMap(plans: List<PlanOverride>): Map<String, Double> = plans.associate { it.name to it.planned }

    /** All category names to offer: the workbook's, those from the laptop's extras sheet, and ones made on the phone. */
    fun categoryNames(state: ServerState?, custom: List<CustomCategory>): List<String> {
        val names = ArrayList<String>()
        (state?.tracker?.map { it.category }?.ifEmpty { null } ?: DEFAULT_CATEGORIES).forEach { names += it }
        state?.extras?.forEach { e -> if (names.none { it.equals(e.category, true) }) names += e.category }
        custom.forEach { c -> if (names.none { it.equals(c.name, true) }) names += c.name }
        return names
    }

    /**
     * [plans] are the plans set in the app; they win over the ones last read from the workbook. [closings] are the month-end
     * decisions, which decide what carries into [month].
     */
    fun summarize(
        state: ServerState?, entries: List<Entry>, custom: List<CustomCategory>, month: YearMonth,
        plans: Map<String, Double> = emptyMap(), closings: List<Closing> = emptyList(),
    ): MonthSummary {
        val watch = state?.watchAt ?: 0.8
        val over = state?.overAt ?: 1.0
        val byName = plans.mapKeys { it.key.lowercase() }
        fun planned(name: String, fromBook: Double) = byName[name.lowercase()] ?: fromBook
        val base = (state?.tracker?.map { it.category to it.planned }?.ifEmpty { null } ?: DEFAULT_CATEGORIES.map { it to 0.0 })
            .map { (n, p) -> n to planned(n, p) }
        val extras = (state?.extras?.map { it.category to it.planned } ?: emptyList()).map { (n, p) -> n to planned(n, p) }
        val phoneOnly = custom
            .filter { c -> (base + extras).none { it.first.equals(c.name, true) } }
            .map { it.name to planned(it.name, it.planned) }
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
        val carried = openingFor(entries, closings, month)
        return MonthSummary(month, lines, total, income, carried, carried + income - spending.sumOf { it.amount })
    }

    // ---- left to spend: a running balance with a decision at each month end ----------------------------------------

    private fun received(entries: List<Entry>, m: YearMonth) =
        entries.filter { it.isIncome && it.date != null && YearMonth.from(it.date) == m }.sumOf { it.amount }

    private fun spent(entries: List<Entry>, m: YearMonth) =
        entries.filter { !it.isIncome && it.date != null && YearMonth.from(it.date) == m }.sumOf { it.amount }

    private fun firstMonth(entries: List<Entry>): YearMonth? = entries.mapNotNull { it.date }.minOrNull()?.let { YearMonth.from(it) }

    /**
     * What carries into [month] from everything before it. Each earlier month hands on what it ended with, unless it was
     * closed with "start at 0" (or has no decision yet, which counts as carrying on until you decide).
     */
    fun openingFor(entries: List<Entry>, closings: List<Closing>, month: YearMonth): Double {
        var m = firstMonth(entries) ?: return 0.0
        var opening = 0.0
        while (m < month) {
            val end = opening + received(entries, m) - spent(entries, m)
            opening = if (closings.any { it.month == m && it.reset }) 0.0 else end
            m = m.plusMonths(1)
        }
        return opening
    }

    /** The earliest month before [current] that had entries and has no decision yet, or null. */
    fun pendingClose(entries: List<Entry>, closings: List<Closing>, current: YearMonth): PendingClose? {
        var m = firstMonth(entries) ?: return null
        var opening = 0.0
        while (m < current) {
            val end = opening + received(entries, m) - spent(entries, m)
            val closed = closings.any { it.month == m }
            val busy = entries.any { it.date != null && YearMonth.from(it.date) == m }
            if (!closed && busy && Math.abs(end) >= 0.5) return PendingClose(m, end)
            opening = if (closings.any { it.month == m && it.reset }) 0.0 else end
            m = m.plusMonths(1)
        }
        return null
    }

    // ---- templates: the entries you repeat -------------------------------------------------------------------------

    data class Template(val category: String, val description: String, val amount: Double, val count: Int) {
        val label get() = listOf(description.ifBlank { category }, moneyText(amount)).joinToString(" · ")
    }

    /**
     * Entries you log again and again (same category, description and amount, at least twice in the last 90 days),
     * most repeated first. [income] picks income or expense templates.
     */
    fun templates(entries: List<Entry>, today: LocalDate, income: Boolean, limit: Int = 6): List<Template> {
        val since = today.minusDays(90)
        return entries
            .filter { it.isIncome == income && it.date != null && !it.date.isBefore(since) && it.amount > 0 }
            .groupBy { Triple(it.category.lowercase(), it.description.trim().lowercase(), Math.round(it.amount * 100)) }
            .filterValues { it.size >= 2 }
            .map { (_, list) ->
                val newest = list.maxBy { it.date!! }
                Template(newest.category, newest.description.trim(), newest.amount, list.size) to newest.date!!
            }
            .sortedWith(compareByDescending<Pair<Template, LocalDate>> { it.first.count }.thenByDescending { it.second })
            .map { it.first }
            .take(limit)
    }

    private fun moneyText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)

    // ---- pace: what you can spend today and where the month is heading --------------------------------------------

    data class CategoryPace(val name: String, val planned: Double, val actual: Double, val projected: Double)

    data class Forecast(
        val daysLeft: Int,                 // including today
        val remainingPlan: Double,         // planned spending not used yet this month
        val leftToSpend: Double,           // the running balance: income declared minus spending declared
        val safePerDay: Double,            // what is left to spend, spread over the days left (never below 0)
        val projectedTotal: Double?,       // null until a few days of data exist
        val plannedTotal: Double,
        val atRisk: List<CategoryPace>,    // not over yet, but on pace to go over their plan
    )

    /**
     * Daily allowance and month-end projection for the month [today] is in. A category's projection is what it has
     * spent plus its everyday rate for the days still to come. A single big entry (half the plan or more, like a
     * subscription) is treated as a one-off, not as a daily habit, so paying it on day 1 does not predict a huge bill.
     */
    fun forecast(
        state: ServerState?, entries: List<Entry>, custom: List<CustomCategory>, today: LocalDate,
        plans: Map<String, Double> = emptyMap(), closings: List<Closing> = emptyList(),
    ): Forecast {
        val month = YearMonth.from(today)
        val summary = summarize(state, entries, custom, month, plans, closings)
        val elapsed = today.dayOfMonth
        val daysAfterToday = month.lengthOfMonth() - elapsed
        val spending = entries.filter { !it.isIncome && it.date != null && YearMonth.from(it.date) == month && !it.date.isAfter(today) }

        val paces = summary.categories.map { c ->
            val mine = spending.filter { it.category.equals(c.name, true) }
            val regular = mine.filter { c.planned <= 0 || it.amount < 0.5 * c.planned }.sumOf { it.amount }
            CategoryPace(c.name, c.planned, c.actual, c.actual + regular / elapsed * daysAfterToday)
        }
        val remaining = (summary.total.planned - summary.total.actual).coerceAtLeast(0.0)
        val enough = elapsed >= 5
        return Forecast(
            daysLeft = daysAfterToday + 1,
            remainingPlan = remaining,
            leftToSpend = summary.left,
            safePerDay = summary.left.coerceAtLeast(0.0) / (daysAfterToday + 1),
            projectedTotal = if (enough) paces.sumOf { it.projected } else null,
            plannedTotal = summary.total.planned,
            atRisk = if (!enough) emptyList() else paces
                .filter { it.planned > 0 && it.actual < it.planned && it.projected > it.planned }
                .sortedByDescending { it.projected - it.planned }
                .take(3),
        )
    }

    // ---- history ---------------------------------------------------------------------------------------------------

    data class MonthPoint(val month: YearMonth, val spent: Double, val income: Double, val planned: Double, val left: Double = 0.0)

    /** The [count] months ending at [end], oldest first. */
    fun history(
        state: ServerState?, entries: List<Entry>, custom: List<CustomCategory>, end: YearMonth, count: Int = 6,
        plans: Map<String, Double> = emptyMap(), closings: List<Closing> = emptyList(),
    ): List<MonthPoint> =
        (count - 1 downTo 0).map { back ->
            val m = end.minusMonths(back.toLong())
            val s = summarize(state, entries, custom, m, plans, closings)
            MonthPoint(m, s.total.actual, s.incomeReceived, s.total.planned, s.left)
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
