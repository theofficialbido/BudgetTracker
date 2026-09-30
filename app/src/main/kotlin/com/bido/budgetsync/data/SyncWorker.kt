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
    private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    /** Background flush whenever the phone is on Wi-Fi (also picks up new SMS from the inbox). */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(wifi).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** After a save: retry with backoff until the laptop is reachable. */
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(wifi)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
    }
}
