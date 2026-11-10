package io.github.jpbaking.smsgateway.maintenance

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping: deletes messages (and stored event stream events) past the retention
 * period, then compacts the database when enough space was freed to be worth it.
 *
 * SQLite doesn't give space back to the filesystem when rows are deleted; the freed pages are
 * only reused for new rows. VACUUM rewrites the file to actually shrink it, but it rewrites the
 * whole database, so it only runs when at least [VACUUM_MIN_FREE_FRACTION] of the file is free.
 */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as GatewayApp
        val days = app.settings.messageRetentionDays
        val deleted = if (days > 0) {
            app.db.messages().deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong()))
        } else {
            0
        }
        val events = app.events.cleanup(days)
        val vacuumed = compactIfWorthIt(app.db)
        Log.i(TAG, "Cleanup: deleted $deleted messages older than $days days, $events events, vacuumed=$vacuumed")
        Result.success()
    }

    private fun compactIfWorthIt(db: AppDatabase): Boolean {
        val sqlite = db.openHelper.writableDatabase
        val pages = sqlite.longPragma("page_count")
        val free = sqlite.longPragma("freelist_count")
        if (pages <= 0 || free.toDouble() / pages < VACUUM_MIN_FREE_FRACTION) return false
        // VACUUM can't run inside a transaction; execSQL on its own runs outside one.
        sqlite.execSQL("VACUUM")
        sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        return true
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longPragma(name: String): Long =
        query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }

    companion object {
        private const val TAG = "CleanupWorker"
        private const val PERIODIC_NAME = "daily-cleanup"
        private const val NOW_NAME = "cleanup-now"
        private const val VACUUM_MIN_FREE_FRACTION = 0.25

        /** Schedules the daily run. Safe to call on every app start: an existing schedule is kept. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Runs a cleanup right away, e.g. after the retention period is shortened. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CleanupWorker>().build())
        }
    }
}
