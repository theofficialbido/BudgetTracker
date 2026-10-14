package com.bido.budgetsync.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bido.budgetsync.sms.SmsInboxScanner
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { SmsInboxScanner.scan(applicationContext) }
        val outcome = Syncer.run(applicationContext)
        return if (outcome.retry) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    /**
     * Any connection, not just unmetered Wi-Fi: a phone that hosts its own hotspot is on mobile data, and the laptop on its
     * hotspot is only reachable then. Off the home network a try fails fast (it only looks at private addresses) and backs off.
     */
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Background flush whenever the phone is online (also picks up new SMS from the inbox). UPDATE moves older installs off the Wi-Fi-only rule. */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** After a save: retry with backoff until the laptop is reachable. */
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
    }
}
