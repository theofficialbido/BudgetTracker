package com.bido.budgetsync.data

import android.content.Context
import android.os.Build
import com.bido.budgetsync.sync.BudgetSnapshot
import com.bido.budgetsync.sync.SnapshotCategory
import com.bido.budgetsync.sync.SnapshotCategoryLine
import com.bido.budgetsync.sync.SnapshotClosing
import com.bido.budgetsync.sync.SnapshotEntry
import com.bido.budgetsync.sync.SnapshotPlan
import com.bido.budgetsync.sync.SnapshotSummary
import com.bido.budgetsync.sync.SyncReceipt
import java.time.LocalDate
import java.time.YearMonth

/**
 * The phone's side of the laptop sync, in the style of the ToDo app: build one snapshot of everything, send it in a
 * single request, and let the receipt say what the laptop took. The app works fully without any of this.
 */
object LaptopSync {
    /** Everything the phone knows, as one document. The laptop keeps it as a copy and applies what is not synced yet. */
    suspend fun snapshot(context: Context, state: ServerState?): BudgetSnapshot {
        val db = AppDatabase.get(context)
        val all = db.expenseDao().allNow()
        val categories = db.customCategoryDao().allNow()
        val plans = db.planDao().allNow()
        val closings = db.monthClosingDao().allNow()

        val entries = Calc.entries(state, db.expenseDao().notInCacheNow())
        val month = YearMonth.now()
        val s = Calc.summarize(state, entries, categories, month, Calc.planMap(plans), Calc.mergeClosings(state, closings))
        val spent = s.total.actual
        return BudgetSnapshot(
            device = Build.MODEL ?: "phone",
            appVersion = Updater.installedVersion(context).second,
            appVersionCode = Updater.installedVersion(context).first,
            generatedAt = System.currentTimeMillis(),
            today = LocalDate.now().toEpochDay(),
            entries = all.map {
                SnapshotEntry(
                    id = it.id, day = runCatching { LocalDate.parse(it.date).toEpochDay() }.getOrDefault(0L),
                    category = it.category, description = it.description, amount = it.amount,
                    income = it.category.equals(Calc.INCOME, ignoreCase = true), synced = it.synced, createdAt = it.createdAt,
                )
            },
            categories = categories.map { SnapshotCategory(it.name, it.planned, it.synced) },
            plans = plans.map { SnapshotPlan(it.name, it.planned, it.synced) },
            closings = closings.map { SnapshotClosing(it.month, it.reset, it.invested, it.splurged, it.synced) },
            summary = SnapshotSummary(
                month = month.toString(), leftToSpend = s.left, carriedIn = s.carriedIn, received = s.incomeReceived,
                spent = spent, planned = s.total.planned,
                categories = s.categories.map { SnapshotCategoryLine(it.name, it.planned, it.actual, it.status) },
            ),
        )
    }

    /** Marks exactly what the laptop says it took as synced; anything it did not take stays queued for the next time. */
    suspend fun markTaken(db: AppDatabase, receipt: SyncReceipt) {
        if (receipt.categories.isNotEmpty()) db.customCategoryDao().markSynced(receipt.categories)
        if (receipt.closings.isNotEmpty()) db.monthClosingDao().markSynced(receipt.closings)
        if (receipt.plans.isNotEmpty()) db.planDao().markSynced(receipt.plans)
        if (receipt.entries.isNotEmpty()) db.expenseDao().markSynced(receipt.entries.map { it.id })
    }
}
