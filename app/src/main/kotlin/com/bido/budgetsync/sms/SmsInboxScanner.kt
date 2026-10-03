package com.bido.budgetsync.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.bido.budgetsync.data.AppDatabase
import com.bido.budgetsync.data.PendingSms
import com.bido.budgetsync.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmsInboxScanner {
    private val inbox = Uri.parse("content://sms/inbox")

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun sameSender(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /** Turn one message into a pending item if it comes from a chosen sender and looks like spending. */
    suspend fun consider(context: Context, sender: String, body: String, receivedAt: Long) {
        val prefs = Prefs(context)
        if (prefs.senders.none { sameSender(it, sender) }) return
        val parsed = SmsParser.parse(body) ?: return
        // The broadcast and the inbox record the same message with slightly different times, so the time is not part of
        // the identity: same sender and same text within a few minutes is one message, however many times it is seen.
        val hash = body.trim().hashCode()
        val key = "$sender|$receivedAt|$hash"
        AppDatabase.get(context).pendingSmsDao()
            .insertIfNew(PendingSms(key, sender, parsed.amount, parsed.merchant, receivedAt, bodyHash = hash))
    }

    /** Reads inbox messages newer than the last scan from the chosen senders. */
    suspend fun scan(context: Context) = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        if (!prefs.smsEnabled || prefs.senders.isEmpty() || !hasPermission(context)) return@withContext
        val since = if (prefs.lastScanMs == 0L) System.currentTimeMillis() - 7L * 24 * 3600 * 1000 else prefs.lastScanMs
        val now = System.currentTimeMillis()
        context.contentResolver.query(
            inbox, arrayOf("address", "body", "date"), "date > ?", arrayOf(since.toString()), "date ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0) ?: continue
                consider(context, address, c.getString(1) ?: "", c.getLong(2))
            }
        }
        prefs.lastScanMs = now
    }

    /** Sender names found in the inbox, most active first, for the Settings picker. */
    suspend fun listSenders(context: Context): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        val counts = LinkedHashMap<String, Int>()
        context.contentResolver.query(inbox, arrayOf("address"), null, null, "date DESC LIMIT 1000")?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { counts.merge(it, 1, Int::plus) }
        }
        counts.entries.sortedByDescending { it.value }.map { it.key }
    }
}
