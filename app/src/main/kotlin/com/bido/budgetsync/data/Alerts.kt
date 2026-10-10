package com.bido.budgetsync.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bido.budgetsync.MainActivity
import com.bido.budgetsync.R
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** One category-status step, for deciding what is worth a notification. */
object AlertRules {
    private fun rank(status: String) = when (status) { "OVER" -> 2; "WATCH", "UNPLANNED" -> 1; else -> 0 }

    /**
     * Statuses newly reached since last time. Each category is announced once per step up, so moving from OK to WATCH
     * notifies, WATCH to OVER notifies again, and staying put (or dropping back) stays quiet.
     */
    fun newlyReached(previous: Map<String, String>, month: YearMonth, lines: List<CategorySummary>): List<CategorySummary> =
        lines.filter { c ->
            val before = previous["$month|${c.name}"] ?: "OK"
            c.status in setOf("WATCH", "OVER") && rank(c.status) > rank(before)
        }

    fun nextState(previous: Map<String, String>, month: YearMonth, lines: List<CategorySummary>): Map<String, String> =
        previous.filterKeys { it.startsWith("$month|") } + lines.associate { "$month|${it.name}" to it.status }

    val REMINDER_TIME: LocalTime = LocalTime.of(21, 0)

    /**
     * The evening reminder goes out every day, whatever has been logged: you may have entered some expenses but not all
     * of them. It is sent once a day, from 9pm on (a missed alarm is caught by the periodic check until midnight).
     */
    fun shouldRemind(now: java.time.LocalDateTime, lastReminded: String): Boolean =
        now.toLocalTime() >= REMINDER_TIME && lastReminded != now.toLocalDate().toString()

    /** The next 9pm: today's if it hasn't come yet, otherwise tomorrow's. Set 30 seconds late so it never fires early. */
    fun nextReminder(now: java.time.LocalDateTime): java.time.LocalDateTime {
        val today = now.toLocalDate().atTime(REMINDER_TIME).plusSeconds(30)
        return if (now.isBefore(today)) today else today.plusDays(1)
    }

    /** Title and text of the reminder, from what has been logged today. */
    fun reminderText(expenseCount: Int, total: Double): Pair<String, String> =
        if (expenseCount == 0) {
            "Log today's expenses" to "Nothing logged today yet. Tap to add what you spent."
        } else {
            val what = if (expenseCount == 1) "1 expense" else "$expenseCount expenses"
            "Anything else to log today?" to "You've logged $what (${String.format(java.util.Locale.US, "%,.0f", total)} EGP) so far. Tap to add the rest."
        }
}

/** Notifications about the budget, worked out on the phone from the same numbers as the home screen. */
object Alerts {
    private const val CHANNEL = "budget_alerts"
    private const val WORK = "alerts"

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops the background check and the 9pm alarm to match the two settings. */
    fun reschedule(context: Context) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        if (prefs.alertsCategory || prefs.alertsReminder) {
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<AlertsWorker>(30, TimeUnit.MINUTES).build())
        } else {
            wm.cancelUniqueWork(WORK)
        }
        if (prefs.alertsReminder) scheduleReminder(context) else cancelReminder(context)
    }

    private fun reminderIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 9100, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets the alarm for the next 9pm. It runs even when the phone is idle, and is set again after each reminder and each reboot. */
    fun scheduleReminder(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val at = AlertRules.nextReminder(java.time.LocalDateTime.now()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, reminderIntent(context))
    }

    fun cancelReminder(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(reminderIntent(context))
    }

    /** Remember where every category stands now, without notifying, so turning alerts on does not announce old news. */
    suspend fun snapshot(context: Context) = evaluate(context, notify = false)

    suspend fun evaluate(context: Context, notify: Boolean = true) {
        val prefs = Prefs(context)
        if (!prefs.alertsCategory && !prefs.alertsReminder && notify) return
        val db = AppDatabase.get(context)
        val state = prefs.cachedState?.let { runCatching { ServerState.fromJson(it) }.getOrNull() }
        val entries = Calc.entries(state, db.expenseDao().notInCacheNow())
        val now = java.time.LocalDateTime.now()
        val month = YearMonth.from(now)
        val summary = Calc.summarize(
            state, entries, db.customCategoryDao().allNow(), month,
            Calc.planMap(db.planDao().allNow()), Calc.mergeClosings(state, db.monthClosingDao().allNow()),
        )

        if (prefs.alertsCategory || !notify) {
            val previous = prefs.alertState
            if (notify) AlertRules.newlyReached(previous, month, summary.categories).forEach { c ->
                val pct = (c.percent * 100).toInt()
                post(
                    context, id = ("cat-" + c.name).hashCode(),
                    title = if (c.status == "OVER") "${c.name} is over budget" else "${c.name} is at $pct% of its plan",
                    text = "${money(c.actual)} of ${money(c.planned)} EGP used this month.",
                )
            }
            prefs.alertState = AlertRules.nextState(previous, month, summary.categories)
        }
        if (notify && prefs.alertsReminder && AlertRules.shouldRemind(now, prefs.lastReminderDate)) {
            val today: LocalDate = now.toLocalDate()
            val todays = entries.filter { !it.isIncome && it.date == today }
            prefs.lastReminderDate = today.toString()
            val (title, text) = AlertRules.reminderText(todays.size, todays.sumOf { it.amount })
            post(context, id = 9001, title = title, text = text, openAdd = true)
        }
    }

    private fun money(v: Double) = String.format(java.util.Locale.US, "%,.0f", v)

    /** [openAdd] makes a tap on the notification open the Add expense screen directly. */
    private fun post(context: Context, id: Int, title: String, text: String, openAdd: Boolean = false) {
        if (!hasPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (openAdd) "open-expense" else "open-app")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (openAdd) intent.putExtra(MainActivity.EXTRA_OPEN, "expense")
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }   // permission can be revoked at any time
    }
}

/** Fires at 9pm: sends the reminder, then sets the alarm for the next evening. */
class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                runCatching { Alerts.evaluate(context) }
            } finally {
                if (Prefs(context).alertsReminder) Alerts.scheduleReminder(context)
                pending.finish()
            }
        }
    }
}

/** Alarms are lost on reboot and on app update, so set the 9pm one again. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Alerts.reschedule(context)
        }
    }
}

class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Alerts.evaluate(applicationContext) }
        return Result.success()
    }
}
