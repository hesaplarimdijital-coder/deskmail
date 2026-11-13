package com.deskmail.watch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.deskmail.MainActivity
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.deskMail
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What keeps the process, and with it the lines that wait for mail ([MailWatch]), alive while no
 * window is open: a foreground service. Its own notification is silent and sits in a channel of
 * its own, which the user can turn off without ending the watch.
 *
 * Besides living, it does three things:
 * - every [REFRESH_MS] it has the waits begun anew. One alarm for all accounts; on Android 17 the
 *   exact alarm that calls back into the running process, which is made for apps that hold a line
 *   open; before that an inexact one that the system may let wait while the device sleeps;
 * - it has the lines made anew when the network changes;
 * - it keeps the device awake while one of these is being done, and not otherwise.
 *
 * The service type is "special use": nothing is being synced that would end ("data sync" is
 * limited to six hours a day), a line is being held.
 */
class WatchService : Service() {

    private val log: AppLog get() = deskMail.log
    private var begun = false
    private var network: ConnectivityManager.NetworkCallback? = null
    private val refreshDue = AlarmManager.OnAlarmListener { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // whoever starts a foreground service owes the system its notification, every time
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!begun) {
            begun = true
            running = true
            log.info(TAG, "the watch begins")
            watchNetwork()
            scheduleRefresh()
        }
        if (intent?.action == ACTION_REFRESH) {
            refresh()
        } else {
            // also when it runs already: an account may have been added, reconnected or removed
            deskMail.background.launch { deskMail.watch.arrange() }
        }
        // ended by the system, it is started again when the system allows
        return START_STICKY
    }

    override fun onDestroy() {
        if (begun) {
            running = false
            log.info(TAG, "the watch ends")
            val alarms = getSystemService(AlarmManager::class.java)
            runCatching { alarms.cancel(refreshDue) }
            runCatching { alarms.cancel(refreshIntent()) }
            network?.let { callback -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) } }
            network = null
            deskMail.background.launch { deskMail.watch.stop() }
        }
        super.onDestroy()
    }

    /** The waits are begun anew: the device is kept awake until every server has answered or was given up on. */
    private fun refresh() {
        val app = deskMail
        // taken here: what woke the device for the alarm lets go when this returns
        app.awake.hold()
        scheduleRefresh()
        app.background.launch {
            try {
                withTimeoutOrNull(REFRESH_LIMIT_MS) {
                    // a watch begun by this alarm (the app's process was ended meanwhile) has nothing to refresh yet;
                    // and an account whose watcher gave up is watched anew
                    app.watch.arrange()
                    app.watch.refresh()
                }
            } finally {
                app.awake.release()
            }
        }
    }

    private fun scheduleRefresh() {
        val alarms = getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + REFRESH_MS
        try {
            if (Build.VERSION.SDK_INT >= ANDROID_17) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, ALARM_TAG, mainExecutor, refreshDue)
                return
            }
        } catch (e: SecurityException) {
            log.warn(TAG, "the exact alarm was refused ($e); an inexact one is set")
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, refreshIntent())
    }

    private fun refreshIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, 0, Intent(this, WatchService::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Tells the watch when the device is on another network than before. */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            // the network the lines were made over; the system names it first thing, which is no change
            private var current: Network? = manager.activeNetwork

            override fun onAvailable(network: Network) {
                if (network == current) return
                current = network
                deskMail.watch.networkChanged()
            }

            override fun onLost(network: Network) {
                // the same network coming back is a change too: its lines are gone
                if (network == current) current = null
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            network = callback
        } catch (e: RuntimeException) {
            log.warn(TAG, "changes of the network cannot be followed: $e")
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.watch_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.watch_channel_note)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(getString(R.string.watch_title))
            .setContentText(getString(R.string.watch_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "watch"

        /** The channel of the service's own notification: silent, and the user's to turn off. */
        const val CHANNEL = "watch"
        const val NOTIFICATION_ID = 1

        internal const val ACTION_REFRESH = "com.deskmail.watch.REFRESH"
        private const val ALARM_TAG = "deskmail:watch-refresh"

        /** Android 17, where the exact alarm that calls back into a running process arrived. */
        private const val ANDROID_17 = 37

        /**
         * How often the waits are begun anew. A server may end a wait that was left alone for half
         * an hour (RFC 2177 asks for 29 minutes at the latest), and the networks in between forget a
         * silent line sooner than that now and then; 24 minutes leaves room for an alarm that comes late.
         */
        const val REFRESH_MS = 24 * 60_000L

        /** How long a round of beginning anew may keep the device awake. */
        private const val REFRESH_LIMIT_MS = 90_000L

        /** Whether the watch runs in this process right now. */
        @Volatile
        var running = false
            private set

        /**
         * Starts the watch. False when the system does not allow it from here (from the background,
         * with battery optimisation on): it is started the next time a window opens.
         */
        fun start(context: Context, log: AppLog): Boolean = try {
            context.startForegroundService(Intent(context, WatchService::class.java))
            true
        } catch (e: RuntimeException) {
            log.warn(TAG, "the watch cannot be started from here: $e")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
