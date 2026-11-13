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
 * Brings snoozed messages back when they are due ([com.deskmail.data.SnoozeSync]): every account that
 * has something snoozed is looked at, its list read anew from the server, and what is due moved
 * back; the folders it came back to are then synced, so that it is told of as new mail. It waits
 * for a network: the messages lie on the server.
 */
class SnoozeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext.deskMail
        var woke = 0
        for (accountId in app.database.snoozes().accounts()) woke += app.snoozes.run(accountId, force = true)
        if (woke > 0) app.mail.syncAll()
        return Result.success()
    }

    companion object {
        /** The name of the work for the snoozes due at a time: "snoozes-" and the time. */
        private const val NAME = "snoozes-"

        /**
         * Has the snoozes looked at once [dueAt] (in milliseconds since 1970) has come: a piece of
         * work for every such time, left as it is when it is there already, never replaced while it runs.
         */
        fun schedule(context: Context, dueAt: Long, now: Long = System.currentTimeMillis()) {
            val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
                .setInitialDelay((dueAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME + dueAt, ExistingWorkPolicy.KEEP, request)
        }
    }
}
