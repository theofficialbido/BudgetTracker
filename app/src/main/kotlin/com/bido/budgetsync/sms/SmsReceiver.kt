package com.bido.budgetsync.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.bido.budgetsync.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Picks up new messages from the chosen senders as they arrive. The text never leaves the phone. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs(context).smsEnabled) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = parts.groupBy { it.originatingAddress ?: "" }
        // Phone clock, like the inbox's "date" column, so a later inbox scan lines up with this broadcast.
        val at = System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                bySender.forEach { (sender, msgs) ->
                    if (sender.isNotBlank()) {
                        SmsInboxScanner.consider(context, sender, msgs.joinToString("") { it.messageBody ?: "" }, at)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
