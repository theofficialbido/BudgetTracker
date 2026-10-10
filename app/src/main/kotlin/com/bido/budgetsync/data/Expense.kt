package com.bido.budgetsync.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An expense or income entry made on the phone ("Income" is just a category).
 * [synced] flips once the laptop has written it to Budget.xlsx; [inCache] once a state fetched after that includes it,
 * so the offline totals count it exactly once (from the phone until the cached workbook data contains it).
 */
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,          // yyyy-MM-dd
    val category: String,
    val description: String,
    val amount: Double,
    val synced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val inCache: Boolean = false,
)

/** What you chose when a month ended: [reset] true means "invest or treat myself and start the next month at 0". */
@Entity(tableName = "month_closings")
data class MonthClosing(
    @PrimaryKey val month: String,     // yyyy-MM
    val reset: Boolean,
    val invested: Double,
    val splurged: Double,
    val synced: Boolean = false,
)

/** A monthly plan set in the app for one category. It wins over what was last read from the workbook. */
@Entity(tableName = "plans")
data class PlanOverride(
    @PrimaryKey val name: String,
    val planned: Double,
    val synced: Boolean = false,
)

/** A category the user added on the phone. It is sent to the laptop, which keeps it on the "More categories" sheet. */
@Entity(tableName = "custom_categories")
data class CustomCategory(
    @PrimaryKey val name: String,
    val planned: Double = 0.0,
    val synced: Boolean = false,
)
