package com.bydhud.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class AppUpdateSessionTest {
    private val available = AppUpdateManager.CheckResult.Available(
        AppUpdateManager.UpdateInfo("9.0.0", "https://github.com/fixture.apk", "fixture notes")
    )

    //No Android, network, real timer or coroutine-test dependency. Deferred gates run synchronously.
    private class Harness(private val ignoreCancellation: Boolean = false) : AutoCloseable {
        var elapsed = 0L
        val delayLengths = mutableListOf<Long>()
        val delays = mutableListOf<CompletableDeferred<Unit>>()
        val channels = mutableListOf<Boolean>()
        val replies = mutableListOf<CompletableDeferred<AppUpdateManager.CheckResult>>()
        var cancelledReads = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AppUpdateManager.UpdateSession(scope, { elapsed }, { beta ->
            channels += beta
            val response = CompletableDeferred<AppUpdateManager.CheckResult>()
            replies += response
            try {
                if (ignoreCancellation) withContext(NonCancellable) { response.await() } else response.await()
            } catch (cancelled: CancellationException) {
                cancelledReads++
                throw cancelled
            }
        }, { duration ->
            delayLengths += duration
            val gate = CompletableDeferred<Unit>()
            delays += gate
            gate.await()
        })
        val state get() = session.snapshot.value
        fun enter(automatic: Boolean = true, beta: Boolean = false) = session.enter(automatic, beta)
        fun fire(index: Int = delays.lastIndex) {
            elapsed += AppUpdateManager.AUTO_CHECK_DELAY_MS
            delays[index].complete(Unit)
        }
        fun complete(result: AppUpdateManager.CheckResult, index: Int = replies.lastIndex) {
            replies[index].complete(result)
        }
        override fun close() = scope.cancel()
    }

    @Test fun coldBackgroundEntryRunsOneDelayedCheckWithoutAnyUi() {
        Harness().use { h ->
            repeat(5) { h.enter() }
            assertEquals(listOf(30_000L), h.delayLengths)
            assertFalse(h.state.checking)
            h.fire()
            assertEquals(listOf(false), h.channels)
            assertTrue(h.state.checking)
            assertFalse(h.state.dialogRequested)
            h.complete(available)
            assertEquals(available, h.state.result)
            assertTrue(h.state.dialogRequested)
            repeat(5) { h.enter() }
            assertEquals(1, h.delays.size)
            assertEquals(1, h.channels.size)
        }
    }

    @Test fun closeAcknowledgesResultAndSurvivesRepeatedEntriesWithoutClearingIt() {
        Harness().use { h ->
            h.enter(); h.fire(); h.complete(available)
            h.session.dismiss()
            repeat(5) { h.enter() }
            assertEquals(available, h.state.result)
            assertFalse(h.state.dialogRequested)
            assertEquals(1, h.channels.size)
            assertEquals(1, h.delays.size)
        }
    }

    @Test fun closeDuringManualCheckPreventsCompletionFromReopeningDialog() {
        Harness().use { h ->
            h.session.requestManual(false)
            assertTrue(h.state.checking)
            assertTrue(h.state.dialogRequested)
            h.session.dismiss()
            h.complete(available)
            assertEquals(available, h.state.result)
            assertFalse(h.state.dialogRequested)
        }
    }

    @Test fun automaticLatestAndFailureAreRetainedWithoutAnIntrusiveDialog() {
        for (result in listOf(AppUpdateManager.CheckResult.UpToDate, AppUpdateManager.CheckResult.Error("offline"))) {
            Harness().use { h ->
                h.enter(); h.fire(); h.complete(result)
                assertEquals(result, h.state.result)
                assertFalse(h.state.checking)
                assertFalse(h.state.dialogRequested)
                h.enter()
                assertEquals(1, h.channels.size)
            }
        }
    }

    @Test fun failedRequestPublishesErrorAndManualRetryFetchesImmediately() {
        Harness().use { h ->
            h.enter(); h.fire()
            h.replies.single().completeExceptionally(IllegalStateException("offline"))
            assertEquals(AppUpdateManager.CheckResult.Error("offline"), h.state.result)
            assertFalse(h.state.dialogRequested)
            h.session.requestManual(false)
            assertEquals(2, h.channels.size)
            assertTrue(h.state.dialogRequested)
            assertEquals(AppUpdateManager.CheckResult.Error("offline"), h.state.result)
            h.complete(AppUpdateManager.CheckResult.UpToDate)
            assertEquals(AppUpdateManager.CheckResult.UpToDate, h.state.result)
            assertTrue(h.state.dialogRequested)
        }
    }

    @Test fun manualCancelsStartupDelayAndRepeatedTapsJoinTheActiveRequest() {
        Harness().use { h ->
            h.enter()
            h.session.requestManual(false)
            repeat(5) { h.session.requestManual(false) }
            h.fire()
            assertEquals(1, h.channels.size)
            h.complete(AppUpdateManager.CheckResult.UpToDate)
            h.session.dismiss()
            h.session.requestManual(false)
            assertEquals(2, h.channels.size)
        }
    }

    @Test fun manualPromotesAutomaticRequestAndRemainsAvailableWhenAutoIsOff() {
        Harness().use { h ->
            h.enter(); h.fire()
            h.session.requestManual(false)
            h.session.disableAutomatic()
            assertEquals(1, h.channels.size)
            assertEquals(0, h.cancelledReads)
            h.complete(AppUpdateManager.CheckResult.UpToDate)
            assertTrue(h.state.dialogRequested)
            h.session.dismiss()
            h.enter(automatic = false)
            h.session.requestManual(false)
            assertEquals(2, h.channels.size)
        }
    }

    @Test fun automaticOffCancelsWaitingAndUnpromotedWorkButOnCanRearm() {
        Harness().use { h ->
            h.enter(automatic = false)
            assertTrue(h.delays.isEmpty())
            h.enter(); h.session.disableAutomatic(); h.fire()
            assertTrue(h.channels.isEmpty())
            h.enter(automatic = false)
            assertEquals(1, h.delays.size)
            h.enter(); h.fire()
            assertTrue(h.state.checking)
            h.session.disableAutomatic()
            assertEquals(1, h.cancelledReads)
            assertFalse(h.state.checking)
            assertNull(h.state.result)
            h.enter(); h.fire()
            assertEquals(2, h.channels.size)
        }
    }

    @Test fun channelChoiceCancelsPendingDelayWithoutArmingItsReplacement() {
        Harness().use { h ->
            h.enter()
            h.session.changeChannel(true)
            h.fire()
            assertTrue(h.channels.isEmpty())
            assertEquals(1, h.delays.size)
            h.enter(beta = true)
            assertEquals(2, h.delays.size)
            h.fire()
            assertEquals(listOf(true), h.channels)
        }
    }

    @Test fun hourExpiryIsLazyFromCompletionAndRetainsPreviousResultDuringRefresh() {
        Harness().use { h ->
            h.enter(); h.fire()
            h.elapsed += 120_000L
            h.complete(AppUpdateManager.CheckResult.UpToDate)
            h.elapsed += AppUpdateManager.SESSION_REFRESH_AGE_MS - 1
            h.enter()
            assertEquals(1, h.delays.size)
            h.elapsed++
            assertEquals(1, h.channels.size) //Passing time alone does not schedule/fetch.
            h.enter()
            assertEquals(2, h.delays.size)
            assertEquals(AppUpdateManager.CheckResult.UpToDate, h.state.result)
            h.fire()
            assertTrue(h.state.checking)
            assertEquals(AppUpdateManager.CheckResult.UpToDate, h.state.result)
            h.complete(available)
            assertEquals(available, h.state.result)
        }
    }

    @Test fun unacknowledgedResultDoesNotFetchAgainEvenAfterAnHour() {
        Harness().use { h ->
            h.enter(); h.fire(); h.complete(available)
            h.elapsed += AppUpdateManager.SESSION_REFRESH_AGE_MS
            h.enter()
            assertEquals(1, h.delays.size)
            h.session.dismiss()
            assertEquals(1, h.delays.size)
            h.enter()
            assertEquals(2, h.delays.size)
        }
    }

    @Test fun channelChangeIsChoiceOnlyAndFencesAnOldNoncooperativeCompletion() {
        Harness(ignoreCancellation = true).use { h ->
            h.session.requestManual(false)
            h.session.changeChannel(true)
            assertNull(h.state.result)
            assertFalse(h.state.checking)
            assertFalse(h.state.dialogRequested)
            assertTrue(h.delays.isEmpty())
            h.complete(available, 0)
            assertNull(h.state.result)
            h.enter(beta = true); h.fire()
            assertEquals(listOf(false, true), h.channels)
            h.complete(AppUpdateManager.CheckResult.UpToDate)
            assertEquals(AppUpdateManager.CheckResult.UpToDate, h.state.result)
        }
    }

    @Test fun shutdownClearsDataCancelsWorkAndRejectsLatePriorSessionResults() {
        Harness(ignoreCancellation = true).use { h ->
            h.session.requestManual(false)
            h.session.reset()
            assertEquals(AppUpdateManager.Snapshot(), h.state)
            h.enter(); h.fire()
            h.complete(available, 0)
            assertTrue(h.state.checking)
            assertNull(h.state.result)
            h.complete(AppUpdateManager.CheckResult.UpToDate, 1)
            assertEquals(AppUpdateManager.CheckResult.UpToDate, h.state.result)
            h.session.reset()
            h.enter()
            assertEquals(listOf(30_000L, 30_000L), h.delayLengths)
        }
    }

    @Test fun shutdownAlsoCancelsAnUnstartedDelayAndTheNextSessionGetsThirtySeconds() {
        Harness().use { h ->
            h.enter()
            h.session.reset()
            h.fire()
            assertTrue(h.channels.isEmpty())
            assertEquals(AppUpdateManager.Snapshot(), h.state)
            h.enter()
            assertEquals(listOf(30_000L, 30_000L), h.delayLengths)
        }
    }

    @Test fun cancellationIsNotACompletedErrorAndNextEntryCanRetry() {
        Harness().use { h ->
            h.session.requestManual(false)
            h.replies.single().cancel(CancellationException("cancelled fixture"))
            assertNull(h.state.result)
            assertFalse(h.state.checking)
            assertFalse(h.state.dialogRequested)
            h.enter()
            assertEquals(listOf(30_000L), h.delayLengths)
        }
    }

    @Test fun immediateInjectedDelayCannotLeaveACompletedScheduledJobStuck() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var elapsed = 0L
            var reads = 0
            val session = AppUpdateManager.UpdateSession(scope, { elapsed }, {
                reads++
                AppUpdateManager.CheckResult.UpToDate
            }, {})
            session.enter(true, false)
            elapsed += AppUpdateManager.SESSION_REFRESH_AGE_MS
            session.enter(true, false)
            assertEquals(2, reads)
        } finally { scope.cancel() }
    }

    @Test fun productionPolicyHasNoPersistentThrottleOrActivityOwnedRequest() {
        val source = String(Files.readAllBytes(Paths.get("src/main/java/com/bydhud/app/AppUpdateManager.kt")), Charsets.UTF_8)
        assertFalse(source.contains("last_check_ms"))
        assertFalse(source.contains("auto_check_ready_at_ms"))
        assertFalse(source.contains("CHECK_THROTTLE_MS"))
        assertFalse(source.contains("System.currentTimeMillis()"))
        assertTrue(source.contains("SystemClock::elapsedRealtime"))
        assertTrue(source.contains("context.applicationContext"))
        assertTrue(source.contains("catch (cancelled: CancellationException)"))
        val channelSetter = source.substring(source.indexOf("fun setBetaChannelEnabled"), source.indexOf("private suspend fun fetchUpdate"))
        assertFalse(channelSetter.contains("onSessionEntry"))
    }
}
