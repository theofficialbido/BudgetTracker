package com.bido.budgetsync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bido.budgetsync.MainActivity
import com.bido.budgetsync.R
import com.bido.budgetsync.data.AppDatabase
import com.bido.budgetsync.data.Calc
import com.bido.budgetsync.data.Prefs
import com.bido.budgetsync.data.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.util.Locale

/**
 * Home-screen widget: what is left after spending this month, and two buttons that open the app straight onto the
 * Add screen. The number comes from the same offline calculation as the app, so it needs no connection.
 */
class BudgetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = build(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Redraws every placed widget, for example after a sync or a new entry. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, BudgetWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        private fun open(context: Context, what: String?, code: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction("open-${what ?: "app"}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            what?.let { intent.putExtra(MainActivity.EXTRA_OPEN, it) }
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private suspend fun build(context: Context): RemoteViews {
            val prefs = Prefs(context)
            val db = AppDatabase.get(context)
            val state = prefs.cachedState?.let { runCatching { ServerState.fromJson(it) }.getOrNull() }
            val entries = Calc.entries(state, db.expenseDao().notInCacheNow())
            val summary = Calc.summarize(
                state, entries, db.customCategoryDao().allNow(), YearMonth.now(),
                Calc.planMap(db.planDao().allNow()), Calc.mergeClosings(state, db.monthClosingDao().allNow()),
            )
            val waiting = entries.count { it.waiting }
            return RemoteViews(context.packageName, R.layout.widget_budget).apply {
                setTextViewText(R.id.widget_left, String.format(Locale.US, "%,.0f EGP", summary.left))
                setTextViewText(
                    R.id.widget_sub,
                    "left to spend" + if (waiting > 0) " · $waiting waiting to sync" else "",
                )
                setOnClickPendingIntent(R.id.widget_root, open(context, null, 10))
                setOnClickPendingIntent(R.id.widget_add_expense, open(context, "expense", 11))
                setOnClickPendingIntent(R.id.widget_add_income, open(context, "income", 12))
            }
        }
    }
}
