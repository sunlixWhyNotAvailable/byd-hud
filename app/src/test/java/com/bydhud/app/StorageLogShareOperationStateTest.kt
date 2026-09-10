package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StorageLogShareOperationStateTest {
    @Test
    fun olderHiddenCompletionUpdatesOnlyItsOperationAndKeepsTheNewerSelection() {
        val older = snapshot(
            id = "older",
            revision = 4,
            days = listOf("20260908"),
            dismissed = true
        )
        val newer = snapshot(
            id = "newer",
            revision = 7,
            days = listOf("20260910")
        )

        val (updated, completed) = updateStorageLogShareOperation(
            listOf(older, newer), "older") {
            storageLogShareTerminal(
                it, StorageLogSharePhase.SENT, "uploaded", "event-older", 45_000L)
        }

        assertEquals(listOf("older", "newer"), updated.map { it.operationId })
        assertEquals(StorageLogSharePhase.SENT, completed!!.phase)
        assertTrue(completed.dismissed)
        assertEquals(4, completed.selectionRevision)
        assertEquals(listOf("20260908"), completed.selectedDays)
        assertEquals(newer, updated[1])
        assertEquals(7, updated[1].selectionRevision)
        assertEquals(listOf("20260910"), updated[1].selectedDays)
    }

    @Test
    fun concurrentTerminalResultsRemainIndependentAndUnknownIdIsANoOp() {
        val first = snapshot("first", 1, listOf("20260908"))
        val second = snapshot("second", 2, listOf("20260909"))
        val (firstDone, _) = updateStorageLogShareOperation(listOf(first, second), "first") {
            storageLogShareTerminal(
                it, StorageLogSharePhase.FAILED, "network", "", 31_000L)
        }
        val (bothDone, _) = updateStorageLogShareOperation(firstDone, "second") {
            storageLogShareTerminal(
                it, StorageLogSharePhase.SENT, "uploaded", "event-second", 62_000L)
        }
        val (unchanged, missing) = updateStorageLogShareOperation(bothDone, "missing") {
            it.copy(dismissed = true)
        }

        assertEquals(StorageLogSharePhase.FAILED, bothDone[0].phase)
        assertEquals("network", bothDone[0].detail)
        assertEquals(StorageLogSharePhase.SENT, bothDone[1].phase)
        assertEquals("event-second", bothDone[1].eventId)
        assertFalse(bothDone[1].dismissed)
        assertEquals(bothDone, unchanged)
        assertNull(missing)
    }

    @Test
    fun shutdownBlocksQueuedAndLateUploadPublicationAndInterruptsActiveUpload() {
        val queued = StorageLogShareControl("queued")
        assertTrue(storageLogShareUploadMayPublish(queued, 4L, 4L, queued))
        queued.cancelWorker()
        assertFalse(storageLogShareUploadMayPublish(queued, 4L, 4L, queued))

        val stale = StorageLogShareControl("stale")
        assertFalse(storageLogShareUploadMayPublish(stale, 4L, 5L, stale))
        assertFalse(storageLogShareUploadMayPublish(stale, 5L, 5L, null))

        val active = StorageLogShareControl("active")
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val worker = Thread {
            active.worker = Thread.currentThread()
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                interrupted.set(true)
            }
        }
        worker.isDaemon = true
        worker.start()
        assertTrue(entered.await(1L, TimeUnit.SECONDS))
        active.cancelWorker()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertTrue(active.cancelled)
        assertTrue(interrupted.get())
    }

    @Test
    fun shutdownRegistrationAndTerminalPublicationShareOneAtomicMonitor() {
        val methods = StorageLogShareWorkflow::class.java.declaredMethods
        val register = methods.single { it.name == "registerUploadIfPreparationOwned" }
        val publish = methods.single { it.name == "publishUploadResultIfOwned" }
        val shutdown = methods.single { it.name == "shutdownLocked" }
        assertTrue(Modifier.isSynchronized(register.modifiers))
        assertTrue(Modifier.isSynchronized(publish.modifiers))
        assertTrue(Modifier.isSynchronized(shutdown.modifiers))

        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            StorageLogShareWorkflow.shutdown()
            completed.countDown()
        }
        worker.isDaemon = true
        synchronized(StorageLogShareWorkflow) {
            worker.start()
            assertTrue(started.await(1L, TimeUnit.SECONDS))
            assertFalse(completed.await(100L, TimeUnit.MILLISECONDS))
        }
        assertTrue(completed.await(1L, TimeUnit.SECONDS))
        worker.join(1_000L)
        assertFalse(worker.isAlive)
    }

    private fun snapshot(
        id: String,
        revision: Int,
        days: List<String>,
        dismissed: Boolean = false
    ) = StorageLogShareSnapshot(
        operationId = id,
        phase = StorageLogSharePhase.UPLOADING,
        startedAtEpochMs = 1_000L,
        startedAtElapsedMs = 2_000L,
        selectedDays = days,
        selectionRevision = revision,
        toDeveloper = true,
        dismissed = dismissed
    )
}
