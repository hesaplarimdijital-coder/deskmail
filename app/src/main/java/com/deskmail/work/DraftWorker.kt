package com.deskmail.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deskmail.deskMail
import java.util.concurrent.TimeUnit

/**
 * Brings what is being written to the Drafts folders of its accounts' servers
 * ([com.deskmail.data.DraftSync]) as background work: a moment after the typing pauses, when a
 * message is put away, and when one was sent or thrown away and its copy is of no use any more.
 * It runs when there is a network, whether or not a window of the app is open.
 */
class DraftWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val left = applicationContext.deskMail.drafts.run()
        // a server out of reach, or a draft written on while its copy was on the way: once more in a while
        return if (left && runAttemptCount < TRIES) Result.retry() else Result.success()
    }

    companion object {
        /** One piece of work under this name at a time: the drafts are looked at as a whole, whoever asks. */
        const val NAME = "drafts"

        private const val TRIES = 8
        private const val FIRST_RETRY_SECONDS = 30L

        /**
         * Has the drafts looked at in [delayMs]. What is already waiting to run is left as it is:
         * it finds whatever was written until it runs, so a draft is put on its server once in a
         * pause of the writing, not with every word.
         */
        fun schedule(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<DraftWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_RETRY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
