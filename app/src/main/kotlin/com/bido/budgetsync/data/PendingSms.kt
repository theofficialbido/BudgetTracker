package com.bido.budgetsync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A spending SMS waiting for the user to name and categorise it. The message text itself is not stored. */
@Entity(tableName = "pending_sms")
data class PendingSms(
    @PrimaryKey val key: String,       // sender|timestamp|hash, so rescans never duplicate
    val sender: String,
    val amount: Double,
    val merchant: String,
    val receivedAt: Long,
    val status: String = PENDING,
) {
    companion object {
        const val PENDING = "PENDING"
        const val CONFIRMED = "CONFIRMED"
        const val DISMISSED = "DISMISSED"
    }
}
