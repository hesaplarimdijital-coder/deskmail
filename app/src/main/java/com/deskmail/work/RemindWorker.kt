package com.deskmail.work

import android.content.Context
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
 * Looks again when an answer that is waited for is due ([com.deskmail.notify.ReplyReminder]): every
 * account is synced first, so that an answer that came meanwhile is known of, and what is still
 * unanswered is then reminded of. It waits for a network: without one, nothing could be known of
 * answers.
 */
class RemindWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext.deskMail
        // each account that is synced has the reminder look at it ...
        app.mail.syncAll()
        // ... and an account that could not be is looked at with what the device knows
        app.replies.checkAll()
        return Result.success()
    }

    companion object {
        /** The name of the work for the reminders due at a time: "replies-" and the time. */
        private const val NAME = "replies-"

        /**
         * Has the reminders looked at once [dueAt] (in milliseconds since 1970) has come. A piece of
         * work for every time a reminder is due: one that is there already is left as it is, and
         * none is ever replaced while it runs. One whose message was answered in the meantime finds
         * nothing to remind of.
         */
        fun schedule(context: Context, dueAt: Long, now: Long = System.currentTimeMillis()) {
            val request = OneTimeWorkRequestBuilder<RemindWorker>()
                .setInitialDelay((dueAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME + dueAt, ExistingWorkPolicy.KEEP, request)
        }
    }
}
