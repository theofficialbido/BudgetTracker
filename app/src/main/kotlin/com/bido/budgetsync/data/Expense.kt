package com.bido.budgetsync.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** An expense entered on the phone. [synced] flips once the laptop helper has written it to Budget.xlsx. */
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,          // yyyy-MM-dd
    val category: String,
    val description: String,
    val amount: Double,
    val synced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
