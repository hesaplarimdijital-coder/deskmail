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
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Sends what waits in the outbox ([com.deskmail.data.MailSend]) as background work: it runs when
 * there is a network, whether or not a window of the app is open, and is tried again later, at
 * growing intervals, while the server cannot be reached.
 *
 * A message that is still within its time to be taken back is waited for here, those few
 * seconds, rather than asked for as new work from inside this one.
 */
class SendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val sender = applicationContext.deskMail.sender
        while (true) {
            val round = sender.sendDue()
            val wait = round.nextInMs
            if (wait == null) return if (round.unreachable) Result.retry() else Result.success()
            delay(wait)
        }
    }

    companion object {
        /** One piece of work under this name at a time: the outbox is looked at as a whole, whoever asks. */
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
                // In place of what was asked for before: the newest appointment is the one that knows
                // when the first message is to go. Work that is in the middle of handing a message
                // over is not cut short by this (MailSend sees a message through); the work that
                // takes its place waits for it.
                WorkManager.getInstance(app).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
