package org.mundhenk.cambackup.app

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
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps uploading to the home server until nothing is left, without anyone tapping "Sync":
 * after a camera session, after a tap, and whenever the phone has a network again. Scheduled
 * through WorkManager, so it also runs when the app is closed; the periodic job is the safety
 * net for the home Wi-Fi coming back while the app is not running.
 */
object AutoSync {
    const val WORK_NOW = "cambackup-sync-now"
    const val WORK_PERIODIC = "cambackup-sync-periodic"

    /** Something changed (session ended, settings saved, app opened): make sure the right jobs exist. */
    fun schedule(context: Context) {
        val prefs = Prefs(context)
        Transfers.init(context)
        val wm = WorkManager.getInstance(context.applicationContext)
        val wanted = prefs.mode.usesServer && prefs.autoSync && prefs.backendUrl.isNotBlank() && Transfers.pending().isNotEmpty()
        if (!wanted) { cancel(context); return }
        val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build())
        LogBus.log("Auto-sync: ${Transfers.pending().size} file(s) waiting, will upload whenever ${prefs.backendUrl} is reachable")
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(WORK_NOW); wm.cancelUniqueWork(WORK_PERIODIC)
    }

    /** One attempt; WorkManager retries with backoff while the server is unreachable and files wait. */
    class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val ctx = applicationContext
            val prefs = Prefs(ctx)
            if (!prefs.mode.usesServer || !prefs.autoSync) return Result.success()
            val summary = Uploader.sync(ctx)
            Transfers.init(ctx)
            val left = Transfers.pending().size
            return when {
                left == 0 -> { cancel(ctx); Result.success() }
                summary.contains("not reachable") || summary.contains("failed") -> Result.retry()
                else -> Result.success()          // e.g. files missing on the phone: nothing a retry would change
            }
        }
    }
}
