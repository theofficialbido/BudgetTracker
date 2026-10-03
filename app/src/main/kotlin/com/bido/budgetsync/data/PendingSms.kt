package com.bido.budgetsync.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A spending SMS waiting for the user to name and categorise it. The message text itself is not stored, only a hash of it. */
@Entity(tableName = "pending_sms")
data class PendingSms(
    @PrimaryKey val key: String,       // sender|timestamp|hash
    val sender: String,
    val amount: Double,
    val merchant: String,
    val receivedAt: Long,
    val status: String = PENDING,
    /** Hash of the message text: the same message seen twice (broadcast, then inbox scan) is recognised by sender, hash and time. */
    @ColumnInfo(defaultValue = "0") val bodyHash: Int = 0,
) {
    companion object {
        const val PENDING = "PENDING"
        const val CONFIRMED = "CONFIRMED"
        const val DISMISSED = "DISMISSED"

        /** The broadcast and the inbox record the same message a little apart; anything this close is one message. */
        const val SAME_MESSAGE_WINDOW_MS = 10 * 60 * 1000L
    }
}
