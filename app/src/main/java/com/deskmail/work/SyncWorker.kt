package com.deskmail.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deskmail.deskMail
import java.util.concurrent.TimeUnit

/**
 * Looks for new mail while no window of the app is open, and tells the servers what was done
 * from a notification. Two pieces of work under two names:
 *
 * - [PERIODIC]: every account's inbox is brought up to date every so often ([schedule]); what
 *   arrived is told of by the notifier, which hangs on every sync. The system decides when
 *   exactly: never more often than every quarter of an hour, and less often while the device
 *   sleeps.
 * - [CHANGES]: what waits to be told to a server is told ([tellServers]), now or when there is a
 *   network.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val mail = applicationContext.deskMail.mail
        if (inputData.getBoolean(CHANGES_ONLY, false)) {
            // worth another try while the server cannot be reached; what waits is kept either way
            return if (mail.tellAll() && runAttemptCount < TRIES) Result.retry() else Result.success()
        }
        mail.syncAll()
        // An account the watch gave up on (its server refused the sign-in) may be in order again
        // after this look, and a watch the system ended may be allowed to begin again.
        applicationContext.deskMail.arrangeBackground(restart = false)
        // whatever came of it, the next look is due in its own time
        return Result.success()
    }

    companion object {
        const val PERIODIC = "sync"
        const val CHANGES = "changes"

        /** The flag of the work that only tells the servers what waits for them. */
        internal const val CHANGES_ONLY = "changes"
        private const val TRIES = 5
        private const val FIRST_RETRY_SECONDS = 30L

        private val online: Constraints get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Has the mail looked for every [minutes] minutes; 0 stops it.
         *
         * @param restart true when the interval was just chosen: it counts from now, and what was
         *   scheduled before is replaced. False leaves what is scheduled as it is and only makes
         *   sure there is something scheduled.
         */
        fun schedule(context: Context, minutes: Int, restart: Boolean) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (minutes <= 0) {
                manager.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES).setConstraints(online).build()
            manager.enqueueUniquePeriodicWork(PERIODIC, if (restart) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Has the servers told what is waiting for them, as soon as there is a network. */
        fun tellServers(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(Data.Builder().putBoolean(CHANGES_ONLY, true).build())
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_RETRY_SECONDS, TimeUnit.SECONDS)
                .build()
            // after what is under way, never instead of it: a run that is telling a server is not cut short
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(CHANGES, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
