package com.deskmail.watch

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.deskmail.DeskMailApplication
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.data.AccountStore
import com.deskmail.data.StuckGuard
import com.deskmail.data.eventually
import com.deskmail.data.testBox
import com.deskmail.deskMail
import com.deskmail.settings.AppSettings
import com.deskmail.work.SyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The service that keeps the watch alive, and when the app starts and stops it: what the system
 * is shown, what is scheduled, and how the choice in the settings, a switched-on device and an
 * update lead to it. (The watching itself is MailWatchTest's.)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr")
class WatchServiceTest {

    @get:Rule
    val guard = StuckGuard()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app: DeskMailApplication get() = context.deskMail
    private var service: ServiceController<WatchService>? = null

    @Before
    fun startWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun endService() {
        service?.destroy()
        // what the service began on the main thread is let through before the watch is ended
        idle()
        runBlocking { app.watch.stop() }
    }

    private fun started(): ServiceController<WatchService> =
        Robolectric.buildService(WatchService::class.java).create().startCommand(0, 1).also { service = it }

    /** An account on a server nobody answers at: enough for there to be something to watch. */
    private suspend fun account() {
        val server = ServerAddress("127.0.0.1", 9, Security.None)
        AccountStore(app.database, testBox(), app.log).add("deniz@example.org", "Posta", "", MailSettings(server, server, "deniz", AuthKind.Password), "a-test-password")
    }

    private fun periodic(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.PERIODIC).get().filter { !it.state.isFinished }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theServiceShowsASilentNotificationOfItsOwnAndSchedulesTheNextBeginning() {
        val before = SystemClock.elapsedRealtime()

        val controller = started()

        assertTrue(WatchService.running)
        val shadow = shadowOf(controller.get())
        assertEquals(WatchService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        val shown = shadow.lastForegroundNotification
        assertEquals(WatchService.CHANNEL, shown.channelId)
        assertEquals("Yeni iletiler bekleniyor", shown.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        // a channel of its own, silent, which the user may turn off
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(WatchService.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Anlık bildirim hizmeti", channel.name.toString())
        // the waits are begun anew in 24 minutes, also while the device sleeps
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)
        assertTrue(alarm.triggerAtTime - before in WatchService.REFRESH_MS..WatchService.REFRESH_MS + 5_000)
        assertTrue(app.log.lines().any { it.contains("the watch begins") })
    }

    @Test
    fun theSystemStartsItAgainWhenItEndedIt() {
        val controller = Robolectric.buildService(WatchService::class.java).create().also { service = it }

        assertEquals(Service.START_STICKY, controller.get().onStartCommand(null, 0, 1))
    }

    @Test
    fun endingTheServiceEndsTheWatchAndItsAlarm() {
        val controller = started()

        controller.destroy()
        service = null

        assertFalse(WatchService.running)
        assertNull(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm())
        assertTrue(app.log.lines().any { it.contains("the watch ends") })
    }

    @Test
    fun whenItsTimeHasComeTheWaitsAreBegunAnewAndTheNextTimeIsSet() {
        val controller = started()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val first = alarms.peekNextScheduledAlarm()!!

        // the alarm, as the system delivers it
        controller.get().onStartCommand(Intent(context, WatchService::class.java).setAction(WatchService.ACTION_REFRESH), 0, 2)
        idle()

        val next = alarms.peekNextScheduledAlarm()
        assertNotNull(next)
        assertTrue(next!!.triggerAtTime >= first.triggerAtTime)
        // nothing keeps the device awake once that is done
        eventually("the device may sleep again") {
            idle()
            !app.awake.held
        }
    }

    @Test
    fun withAnAccountAndMailToBeToldOfAsItComesTheWatchIsStarted() = runBlocking {
        account()
        assertEquals(AppSettings.CHECK_INSTANT, app.settings.checkMinutes)

        app.arrangeBackground(restart = false)

        assertEquals(WatchService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        // and the mail is looked for from time to time besides: what is left should the system end the watch
        assertEquals(DeskMailApplication.INSTANT_FALLBACK_MINUTES * 60_000L, periodic().single().periodicityInfo?.repeatIntervalMillis)

        // an interval is chosen instead: the watch is stopped, the look follows the interval
        app.settings.updateCheckMinutes(30)
        app.arrangeBackground(restart = true)
        assertEquals(WatchService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        assertEquals(30 * 60_000L, periodic().single().periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun withoutAnAccountNothingIsStarted() = runBlocking {
        app.arrangeBackground(restart = false)

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(periodic().isEmpty())
    }

    @Test
    fun aRunningWatchLooksAtTheAccountsAgainInsteadOfBeingStartedOnceMore() = runBlocking {
        started()
        // what started it in the test is not the app's doing
        while (shadowOf(app).nextStartedService != null) Unit
        account()

        app.arrangeBackground(restart = false)

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun aDeviceThatIsSwitchedOnAndAnAppThatWasUpdatedStartTheWatch() = runBlocking {
        account()

        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            StartReceiver().onReceive(context, Intent(action))
            eventually("the watch is started after $action") {
                idle()
                shadowOf(app).nextStartedService?.component?.className == WatchService::class.java.name
            }
        }
        // anything else is none of its business
        StartReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        idle()
        assertNull(shadowOf(app).nextStartedService)
    }
}
