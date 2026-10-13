package com.bido.budgetsync.helper

import com.bido.budgetsync.sync.BudgetSnapshot
import com.bido.budgetsync.sync.EntryReceipt
import com.bido.budgetsync.sync.SyncReceipt
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes [text] to [file] through a temp file, so a crash never leaves a half-written file behind. */
fun writeAtomic(file: Path, text: String) {
    file.parent?.let { Files.createDirectories(it) }
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Takes one snapshot from the phone: keeps it as the redundancy copy, writes the briefing files, and applies whatever the
 * phone has not synced yet to the workbook through the same idempotent operations the individual endpoints use.
 * Applying twice is harmless, so a retry after a dropped connection never duplicates anything.
 */
class SnapshotService(
    private val book: BudgetWorkbook,
    private val ledger: SyncLedger,
    private val dataDir: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    val snapshotFile: Path get() = dataDir.resolve("snapshot.json")
    val briefDir: Path get() = dataDir.resolve("briefs")

    fun receive(snapshot: BudgetSnapshot, rawJson: String): SyncReceipt {
        // 1. the copy that does not depend on Excel: if the phone is lost, this is the record
        writeAtomic(snapshotFile, rawJson)

        // 2. apply what is new, in the order that keeps references valid: categories before the entries that use them
        val categories = snapshot.categories.filter { !it.synced }
        val tookCategories = if (categories.isEmpty()) emptyList()
        else book.addCategories(categories.map { NewCategory(it.name, it.planned) }).map { it.name }

        val closings = snapshot.closings.filter { !it.synced }
        val tookClosings = if (closings.isEmpty()) emptyList()
        else book.setClosings(closings.map { ClosingRow(it.month, it.reset, it.invested, it.splurged) }).map { it.name }

        val plans = snapshot.plans.filter { !it.synced }
        val tookPlans = if (plans.isEmpty()) emptyList()
        else book.setPlans(plans.map { PlanChange(it.name, it.planned) }).filter { it.status != "unknown" }.map { it.name }

        // entries the laptop cannot use (no amount, no id) are skipped, not allowed to fail the whole batch
        val entries = snapshot.entries.filter { !it.synced && it.id.isNotBlank() && it.amount.isFinite() && it.amount > 0 }
        val tookEntries = if (entries.isEmpty()) emptyList()
        else book.append(
            entries.map { NewExpense(it.id, LocalDate.ofEpochDay(it.day), it.category.trim(), it.description.trim(), it.amount) },
            ledger,
        ).map { EntryReceipt(it.id, it.row, it.status) }

        // 3. the numbers as the workbook now has them, so the phone also sees rows the 9pm routine or Excel added
        val state = book.readState()
        BriefWriter.write(briefDir, snapshot, state)
        return SyncReceipt(tookEntries, tookCategories, tookPlans, tookClosings, mapper.writeValueAsString(state))
    }
}

/**
 * Plain files for the morning and evening briefs to read instead of opening Excel: the phone's own numbers
 * (budget.md for reading, budget.json for programs), rewritten after every sync.
 */
object BriefWriter {
    private fun money(v: Double) = String.format(Locale.US, "%,.0f", v)

    fun write(dir: Path, s: BudgetSnapshot, state: State?) {
        val synced = Instant.ofEpochMilli(s.generatedAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val waiting = s.entries.count { !it.synced }
        val sum = s.summary
        val md = StringBuilder()
        md.appendLine("# Budget, from the phone (synced $synced)")
        md.appendLine()
        if (sum != null) {
            md.appendLine("**Left to spend: ${money(sum.leftToSpend)} EGP**" + if (sum.carriedIn != 0.0) " (includes ${money(sum.carriedIn)} carried over)" else "")
            md.appendLine()
            md.appendLine("This month (${sum.month}): received ${money(sum.received)}, spent ${money(sum.spent)}, planned ${money(sum.planned)}.")
            md.appendLine()
            md.appendLine("| Category | Spent | Plan | Status |")
            md.appendLine("|---|---:|---:|---|")
            sum.categories.forEach { md.appendLine("| ${it.name} | ${money(it.actual)} | ${money(it.planned)} | ${it.status} |") }
            md.appendLine()
            val attention = sum.categories.filter { it.status in setOf("WATCH", "OVER", "UNPLANNED") }
            if (attention.isNotEmpty()) md.appendLine("Needs attention: " + attention.joinToString(", ") { "${it.name} (${it.status})" } + ".")
        } else {
            md.appendLine("The phone sent no summary this time.")
        }
        if (waiting > 0) md.appendLine("\n$waiting entr${if (waiting == 1) "y" else "ies"} on the phone had not reached the workbook before this sync.")
        val recent = s.entries
            .sortedWith(compareByDescending<com.bido.budgetsync.sync.SnapshotEntry> { it.day }.thenByDescending { it.createdAt })
            .take(10)
        if (recent.isNotEmpty()) {
            md.appendLine("\n## Latest entries")
            recent.forEach {
                val note = if (it.description.isBlank()) "" else " (${it.description})"
                md.appendLine("- ${LocalDate.ofEpochDay(it.day)}  ${if (it.income) "+" else ""}${money(it.amount)} EGP  ${it.category}$note")
            }
        }
        val decided = s.closings.sortedByDescending { it.month }.take(3)
        if (decided.isNotEmpty()) {
            md.appendLine("\n## Month-end decisions")
            decided.forEach {
                md.appendLine("- ${it.month}: " + if (it.reset) "started the next month at 0 (invested ${money(it.invested)}, treated myself ${money(it.splurged)})" else "carried the leftover over")
            }
        }
        writeAtomic(dir.resolve("budget.md"), md.toString())

        val json = com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
            put("syncedAt", synced)
            put("device", s.device)
            put("waitingOnPhone", waiting)
            sum?.let {
                putObject("thisMonth").apply {
                    put("month", it.month); put("leftToSpend", it.leftToSpend); put("carriedIn", it.carriedIn)
                    put("received", it.received); put("spent", it.spent); put("planned", it.planned)
                    putArray("categories").also { a -> it.categories.forEach { c -> a.addObject().put("name", c.name).put("planned", c.planned).put("actual", c.actual).put("status", c.status) } }
                }
            }
            state?.let { put("workbookLeftAfterActualSpending", it.leftAfterActual) }
        }
        writeAtomic(dir.resolve("budget.json"), json.toPrettyString())
    }
}
