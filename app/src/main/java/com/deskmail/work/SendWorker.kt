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
import com.deskmail.data.SendScheduler
import com.deskmail.deskMail
import java.util.concurrent.TimeUnit

/**
 * Sends what waits in the outbox ([com.deskmail.data.MailSend]) as background work: it runs when
 * there is a network, whether or not a window of the app is open, and is tried again later, at
 * growing intervals, while the server cannot be reached.
 */
class SendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val again = applicationContext.deskMail.sender.sendDue()
        return if (again) Result.retry() else Result.success()
    }

    companion object {
        /** One piece of work under this name at a time; what is asked for meanwhile runs after it. */
        const val NAME = "send"

        private const val FIRST_RETRY_SECONDS = 30L

        /** Has the outbox sent through WorkManager. */
        fun scheduler(context: Context): SendScheduler {
            val app = context.applicationContext
            return SendScheduler { delayMs ->
                val request = OneTimeWorkRequestBuilder<SendWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_RETRY_SECONDS, TimeUnit.SECONDS)
                    .build()
                // After what is under way, never instead of it: work that is sending is not cut short.
                WorkManager.getInstance(app).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
    }
}
