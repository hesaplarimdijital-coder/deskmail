package com.deskmail.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.deskmail.data.eventually
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Looking for new mail in the background as the app asks WorkManager for it: one periodic piece
 * of work that waits for a network, which follows the interval that is chosen and is gone with
 * "by hand"; and the work that tells the servers what was done from a notification. (What a look
 * for mail brings, and what the buttons do, is NotifyActionsTest's.)
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun startWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun scheduled(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @Test
    fun lookingForMailIsOnePeriodicPieceOfWorkThatFollowsTheIntervalChosen() {
        SyncWorker.schedule(context, 30, restart = false)
        val first = scheduled(SyncWorker.PERIODIC).single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(30 * 60_000L, first.periodicityInfo?.repeatIntervalMillis)

        // made sure of at the next start of the app: what is scheduled stays as it is
        SyncWorker.schedule(context, 30, restart = false)
        assertEquals(first.id, scheduled(SyncWorker.PERIODIC).single().id)

        // another interval was chosen: still one piece of work, at the new pace
        SyncWorker.schedule(context, 60, restart = true)
        assertEquals(60 * 60_000L, scheduled(SyncWorker.PERIODIC).single().periodicityInfo?.repeatIntervalMillis)

        // "by hand": nothing is left scheduled
        SyncWorker.schedule(context, 0, restart = true)
        assertTrue(scheduled(SyncWorker.PERIODIC).isEmpty())
    }

    @Test
    fun whatWasDoneFromANotificationIsToldByWorkThatWaitsForANetwork() {
        val manager = WorkManager.getInstance(context)

        SyncWorker.tellServers(context)
        val first = scheduled(SyncWorker.CHANGES).single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)

        // another button, while the first still waits: after it, never instead of it
        SyncWorker.tellServers(context)
        assertEquals(2, scheduled(SyncWorker.CHANGES).size)

        // there is a network: WorkManager makes the worker itself and runs it; nothing waits, and it ends well
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        eventually("the work has run") { manager.getWorkInfoById(first.id).get()?.state == WorkInfo.State.SUCCEEDED }
    }

    @Test
    fun withNoAccountALookForMailEndsWell() = runBlocking {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun withNothingToTellTheServersTheWorkEndsWell() = runBlocking {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(Data.Builder().putBoolean(SyncWorker.CHANGES_ONLY, true).build())
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
