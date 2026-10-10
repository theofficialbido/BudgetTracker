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

    /** Evening reminder: only in the evening, only once a day, and only if no expense was logged today. */
    fun shouldRemind(now: java.time.LocalDateTime, lastReminded: String, hasExpenseToday: Boolean): Boolean =
        now.toLocalTime() >= LocalTime.of(21, 0) && lastReminded != now.toLocalDate().toString() && !hasExpenseToday
}

/** Notifications about the budget, worked out on the phone from the same numbers as the home screen. */
object Alerts {
    private const val CHANNEL = "budget_alerts"
    private const val WORK = "alerts"

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops the background check to match the two settings. */
    fun reschedule(context: Context) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        if (prefs.alertsCategory || prefs.alertsReminder) {
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<AlertsWorker>(30, TimeUnit.MINUTES).build())
        } else {
            wm.cancelUniqueWork(WORK)
        }
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
        if (notify && prefs.alertsReminder) {
            val today: LocalDate = now.toLocalDate()
            val hasExpense = entries.any { !it.isIncome && it.date == today }
            if (AlertRules.shouldRemind(now, prefs.lastReminderDate, hasExpense)) {
                prefs.lastReminderDate = today.toString()
                post(context, id = 9001, title = "Nothing logged today", text = "Tap to add today's expenses before you forget.")
            }
        }
    }

    private fun money(v: Double) = String.format(java.util.Locale.US, "%,.0f", v)

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!hasPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }   // permission can be revoked at any time
    }
}

class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Alerts.evaluate(applicationContext) }
        return Result.success()
    }
}
