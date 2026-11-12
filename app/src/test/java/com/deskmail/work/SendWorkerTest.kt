package com.deskmail.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
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
 * The outbox's background work as the app asks WorkManager for it: one piece of work under one
 * name, waiting for a network, which WorkManager can start by itself and which ends well when
 * nothing waits. (What it sends, and what follows from that, is MailSendTest's.)
 */
@RunWith(AndroidJUnit4::class)
class SendWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun startWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun askingForTheOutboxToBeLookedAtIsOnePieceOfWorkThatWaitsForANetwork() {
        val scheduler = SendWorker.scheduler(context)
        val manager = WorkManager.getInstance(context)

        scheduler.schedule(10_000)
        val first = manager.getWorkInfosForUniqueWork(SendWorker.NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)

        // asked for again, sooner: in place of what was asked for before, not after it
        scheduler.schedule(0)
        val waiting = manager.getWorkInfosForUniqueWork(SendWorker.NAME).get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, waiting.size)
        assertTrue(waiting.single().id != first.id)

        // there is a network: WorkManager makes the worker itself and runs it, on the app's own outbox, which is empty
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(waiting.single().id)
        eventually("the work has run") { manager.getWorkInfoById(waiting.single().id).get()?.state == WorkInfo.State.SUCCEEDED }
    }

    @Test
    fun withNothingWaitingTheWorkEndsWell() = runBlocking {
        val worker = TestListenableWorkerBuilder<SendWorker>(context).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
