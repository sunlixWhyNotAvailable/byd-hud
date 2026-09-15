package com.bydhud.app

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateOperationTest {
    private val first = AppUpdateManager.UpdateInfo("3.2.4", "https://example/first.apk", "first")
    private val second = AppUpdateManager.UpdateInfo("3.2.5", "https://example/second.apk", "second")

    private class Driver : AppUpdateOperationDriver {
        val prepareGate = CompletableDeferred<AppUpdateReadyApk>()
        var preparedUpdate: AppUpdateManager.UpdateInfo? = null
        var prepareCalls = 0
        var handoffCalls = 0
        var cancelCalls = 0
        var ignorePrepareCancellation = false
        val handoffOrder = mutableListOf<String>()
        var recovered: AppUpdateOperationSnapshot? = null
        var recoverGate: CompletableDeferred<AppUpdateOperationSnapshot?>? = null
        var ignoreRecoveryCancellation = false
        var prepareFailure: Exception? = null
        var handoffFailure: Exception? = null
        var cancelFailure: Exception? = null
        var ownedRoot: File? = null

        override suspend fun recover(): AppUpdateOperationSnapshot? {
            val gate = recoverGate ?: return recovered
            return if (ignoreRecoveryCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
        }

        override suspend fun prepare(
            operationId: Long,
            update: AppUpdateManager.UpdateInfo,
            onPreparing: () -> Unit,
            onProgress: (String) -> Unit
        ): AppUpdateReadyApk {
            prepareCalls++
            preparedUpdate = update
            ownedRoot?.let { root ->
                root.mkdirs()
                File(root, "op-$operationId.download").writeText("download")
                File(root, "op-$operationId.part").writeText("part")
            }
            onProgress("41%")
            prepareFailure?.let { throw it }
            return if (ignorePrepareCancellation) {
                withContext(NonCancellable) { prepareGate.await() }
            } else {
                prepareGate.await()
            }.also { onPreparing() }
        }

        override suspend fun handoff(
            operationId: Long,
            ready: AppUpdateReadyApk,
            onExposed: (AppUpdateReadyApk) -> Unit
        ) {
            handoffCalls++
            handoffOrder += "persisted"
            onExposed(ready.copy(exposed = true))
            handoffOrder += "uri"
            handoffFailure?.let { throw it }
        }

        override suspend fun cancelUnexposed(operationId: Long) {
            cancelCalls++
            cancelFailure?.let { throw it }
            ownedRoot?.listFiles().orEmpty()
                .filter { it.name.startsWith("op-$operationId.") }
                .forEach(File::delete)
        }
    }

    @Test fun doubleAdmissionAndNewMetadataObserveOneImmutableOperation() {
        val driver = Driver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            val id = controller.start(first)
            assertEquals(id, controller.start(first))
            assertEquals(id, controller.start(second))
            assertEquals(1, driver.prepareCalls)
            assertEquals(first, driver.preparedUpdate)
            assertEquals(first, controller.snapshot.value?.update)
            assertEquals("41%", controller.snapshot.value?.progress)
        } finally {
            scope.cancel()
        }
    }

    @Test fun completionHandsOffOnceAndReadyApkNeedsAnExplicitPressForRetry() {
        val driver = Driver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.start(first)
            driver.prepareGate.complete(AppUpdateReadyApk("ready.apk", 102L))
            val ready = controller.snapshot.value
            assertEquals(AppUpdateOperationPhase.READY, ready?.phase)
            assertTrue(ready?.ready?.exposed == true)
            assertEquals(1, driver.handoffCalls)
            assertEquals(listOf("persisted", "uri"), driver.handoffOrder)

            assertTrue(controller.retryInstall())
            assertEquals(2, driver.handoffCalls)
            assertEquals(AppUpdateOperationPhase.READY, controller.snapshot.value?.phase)
            assertEquals(2, driver.handoffCalls) //Observation/recreation itself launches nothing.
        } finally {
            scope.cancel()
        }
    }

    @Test fun aDifferentReleaseCanStartAfterReadyWithoutCancellingTheEarlierExposure() {
        val driver = Driver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            val firstId = controller.start(first)
            driver.prepareGate.complete(AppUpdateReadyApk("first-ready.apk", 102L))
            assertEquals(AppUpdateOperationPhase.READY, controller.snapshot.value?.phase)
            val secondId = controller.start(second)
            assertTrue(secondId > firstId)
            assertEquals(second, controller.snapshot.value?.update)
            assertEquals(2, driver.prepareCalls)
            assertEquals(0, driver.cancelCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun shutdownCancelsOwnershipAndFencesLateNonCooperativePreparation() {
        val driver = Driver().apply { ignorePrepareCancellation = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.start(first)
            controller.shutdown()
            assertNull(controller.snapshot.value)
            assertEquals(1, driver.cancelCalls)
            driver.prepareGate.complete(AppUpdateReadyApk("late.apk", 102L))
            assertNull(controller.snapshot.value)
            assertEquals(0, driver.handoffCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun recoveredReadyStateIsPublishedWithoutAutomaticInstallerHandoff() {
        val driver = Driver().apply {
            recovered = AppUpdateOperationSnapshot(
                7L,
                AppUpdateManager.UpdateInfo("3.2.4", "", ""),
                AppUpdateOperationPhase.READY,
                "100%",
                AppUpdateReadyApk("kept.apk", 102L, exposed = true)
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.recover()
            assertEquals(7L, controller.snapshot.value?.id)
            assertEquals(0, driver.handoffCalls)
            assertTrue(controller.retryInstall())
            assertEquals(1, driver.handoffCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun admissionWaitsForRecoveryAndKeepsTheFirstRequestedRelease() {
        val gate = CompletableDeferred<AppUpdateOperationSnapshot?>()
        val driver = Driver().apply { recoverGate = gate }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.recover()
            val firstId = controller.start(first)
            assertEquals(firstId, controller.start(second))
            assertEquals(0, driver.prepareCalls)
            gate.complete(null)
            assertEquals(1, driver.prepareCalls)
            assertEquals(first, driver.preparedUpdate)
        } finally {
            scope.cancel()
        }
    }

    @Test fun recoveredOwnershipWinsOverARequestAdmittedWhileRecoveryWasPending() {
        val gate = CompletableDeferred<AppUpdateOperationSnapshot?>()
        val driver = Driver().apply { recoverGate = gate }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.recover()
            controller.start(second)
            gate.complete(
                AppUpdateOperationSnapshot(
                    9L,
                    AppUpdateManager.UpdateInfo("3.2.4", "", ""),
                    AppUpdateOperationPhase.READY,
                    "100%",
                    AppUpdateReadyApk("recovered.apk", 102L)
                )
            )
            assertEquals(9L, controller.snapshot.value?.id)
            assertEquals("3.2.4", controller.snapshot.value?.update?.version)
            assertEquals(0, driver.prepareCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun shutdownFencesLateRecoveryAndDropsItsQueuedAdmission() {
        val gate = CompletableDeferred<AppUpdateOperationSnapshot?>()
        val driver = Driver().apply {
            recoverGate = gate
            ignoreRecoveryCancellation = true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.recover()
            controller.start(first)
            controller.shutdown()
            gate.complete(
                AppUpdateOperationSnapshot(
                    8L,
                    first,
                    AppUpdateOperationPhase.READY,
                    ready = AppUpdateReadyApk("late.apk", 102L)
                )
            )
            assertNull(controller.snapshot.value)
            assertEquals(0, driver.prepareCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun downloadCopyAndValidationFailuresCleanOwnedFilesAndPublishFailure() = withRoots { staging, _ ->
        listOf("download failed", "copy failed", "validation failed").forEach { failure ->
            staging.listFiles().orEmpty().forEach(File::delete)
            val driver = Driver().apply {
                ownedRoot = staging
                prepareFailure = IllegalStateException(failure)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val controller = AppUpdateOperationController(scope, driver)
                controller.start(first)
                assertEquals(AppUpdateOperationPhase.FAILED, controller.snapshot.value?.phase)
                assertEquals(failure, controller.snapshot.value?.error)
                assertTrue(staging.listFiles().orEmpty().isEmpty())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun installerFailureKeepsExposedReadyFileAndAllowsExplicitRetry() = withRoots { staging, _ ->
        val readyFile = File(staging, "ready.apk").apply { writeText("validated") }
        val driver = Driver().apply { handoffFailure = IllegalStateException("installer unavailable") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.start(first)
            driver.prepareGate.complete(AppUpdateReadyApk(readyFile.absolutePath, 102L))
            assertEquals(AppUpdateOperationPhase.READY, controller.snapshot.value?.phase)
            assertEquals("installer unavailable", controller.snapshot.value?.error)
            assertTrue(controller.snapshot.value?.ready?.exposed == true)
            assertTrue(readyFile.isFile)
            assertEquals(0, driver.cancelCalls)
            assertTrue(controller.retryInstall())
            assertEquals(2, driver.handoffCalls)
            assertTrue(readyFile.isFile)
        } finally {
            scope.cancel()
        }
    }

    @Test fun recoveryFailureDoesNotDeleteOwnershipAndExplicitRetryCanProceed() = withRoots { staging, _ ->
        val owned = File(staging, "exposed.apk").apply { writeText("keep") }
        val gate = CompletableDeferred<AppUpdateOperationSnapshot?>()
        val driver = Driver().apply { recoverGate = gate }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.recover()
            val firstId = controller.start(first)
            gate.completeExceptionally(IllegalStateException("recovery read failed"))
            assertEquals(AppUpdateOperationPhase.FAILED, controller.snapshot.value?.phase)
            assertEquals("recovery read failed", controller.snapshot.value?.error)
            assertTrue(owned.isFile)
            controller.dismissTerminalFailure()
            assertTrue(controller.start(first) > firstId)
            assertEquals(1, driver.prepareCalls)
            assertTrue(owned.isFile)
        } finally {
            scope.cancel()
        }
    }

    @Test fun cleanupFailureCannotStrandFailurePublication() {
        val driver = Driver().apply {
            prepareFailure = IllegalStateException("validation failed")
            cancelFailure = IllegalStateException("cleanup denied")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.start(first)
            assertEquals(AppUpdateOperationPhase.FAILED, controller.snapshot.value?.phase)
            assertEquals("validation failed; cleanup failed: cleanup denied", controller.snapshot.value?.error)
        } finally {
            scope.cancel()
        }
    }

    @Test fun unavailableStorageAndDownloadServiceAreDeferredUntilGuardedIo() = withRoots { _, downloads ->
        var storageReads = 0
        var serviceReads = 0
        val missingStorage = AppUpdateEnvironmentResolver<File>(
            downloadRoot = { storageReads++; null },
            downloadService = { serviceReads++; downloads }
        )
        assertEquals(0, storageReads)
        assertEquals(0, serviceReads)
        assertEquals("Update storage is unavailable", failureMessage { missingStorage.resolve() })
        assertEquals(1, storageReads)
        assertEquals(0, serviceReads)

        val missingService = AppUpdateEnvironmentResolver<File>(
            downloadRoot = { downloads },
            downloadService = { null }
        )
        assertEquals("Android download service is unavailable", failureMessage { missingService.resolve() })
    }

    @Test fun readyInstallerFailureMapsToVisibleRetryStateWithoutLosingTheApk() {
        val ready = AppUpdateReadyApk("ready.apk", 102L, exposed = true)
        val operation = AppUpdateOperationSnapshot(
            1L,
            first,
            AppUpdateOperationPhase.READY,
            "100%",
            ready,
            error = "activity missing"
        )
        val failedUi = updateCheckStateFor(AppUpdateManager.Snapshot(), operation)
        assertTrue(failedUi is UpdateCheckState.Ready && failedUi.installerError)
        assertEquals(first, (failedUi as UpdateCheckState.Ready).info)
        val normalUi = updateCheckStateFor(AppUpdateManager.Snapshot(), operation.copy(error = null))
        assertTrue(normalUi is UpdateCheckState.Ready && !normalUi.installerError)
    }

    @Test fun shutdownCleanupExceptionIsContainedAndLateWorkCannotResurrectState() {
        val uncaught = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> uncaught += error }
        val driver = Driver().apply {
            ignorePrepareCancellation = true
            cancelFailure = IllegalStateException("cleanup denied")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
        try {
            val controller = AppUpdateOperationController(scope, driver)
            controller.start(first)
            controller.shutdown()
            driver.prepareGate.complete(AppUpdateReadyApk("late.apk", 102L))
            assertTrue(uncaught.isEmpty())
            assertNull(controller.snapshot.value)
            assertEquals(0, driver.handoffCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun ownershipRecoveryCleansOnlyUnfinishedOwnedFiles() = withRoots { staging, downloads ->
        val store = AppUpdateOwnershipStore(staging, downloads)
        val record = record(AppUpdateOwnershipPhase.ACTIVE)
        store.write(record)
        val owned = listOf(store.downloadFile(record), store.partFile(record), store.readyFile(record))
        owned.forEach { it.writeText("owned") }
        val unrelated = File(staging, "keep.apk").apply { writeText("unrelated") }
        val removed = mutableListOf<Long>()

        val result = AppUpdateOwnershipRecovery(store, { 101L }, removed::add).recover()

        assertNull(result)
        assertEquals(listOf(44L), removed)
        assertTrue(owned.none(File::exists))
        assertTrue(unrelated.isFile)
        assertNull(store.read(record.operationId))
    }

    @Test fun readyAndExposedFilesSurviveUntilExactInstalledVersionIsConfirmed() = withRoots { staging, downloads ->
        val store = AppUpdateOwnershipStore(staging, downloads)
        val readyRecord = record(AppUpdateOwnershipPhase.READY)
        store.write(readyRecord)
        store.readyFile(readyRecord).writeText("validated")
        store.downloadFile(readyRecord).writeText("transient")
        assertNotNull(AppUpdateOwnershipRecovery(store, { 101L }, {}).recover())
        assertTrue(store.readyFile(readyRecord).isFile)
        assertFalse(store.downloadFile(readyRecord).exists())

        val exposed = readyRecord.copy(phase = AppUpdateOwnershipPhase.EXPOSED)
        store.write(exposed)
        AppUpdateOwnershipRecovery(store, { 101L }, {}).cancelUnexposed(exposed.operationId)
        assertTrue(store.readyFile(exposed).isFile)
        assertNotNull(store.read(exposed.operationId))

        val removed = mutableListOf<Long>()
        assertNull(AppUpdateOwnershipRecovery(store, { 102L }, removed::add).recover())
        assertFalse(store.readyFile(exposed).exists())
        assertNull(store.read(exposed.operationId))
        assertEquals(listOf(44L), removed)
    }

    @Test fun newerReadyOwnershipDoesNotOverwriteAnEarlierExposedInstallerFile() = withRoots { staging, downloads ->
        val store = AppUpdateOwnershipStore(staging, downloads)
        val exposed = record(AppUpdateOwnershipPhase.EXPOSED)
        val newer = exposed.copy(
            operationId = 4L,
            version = "3.2.5",
            downloadId = 45L,
            downloadName = "op-4-download.apk",
            partName = "op-4-ready.apk.part",
            readyName = "op-4-ready.apk",
            targetVersionCode = 103L,
            phase = AppUpdateOwnershipPhase.READY
        )
        store.write(exposed)
        store.readyFile(exposed).writeText("first")
        store.write(newer)
        store.readyFile(newer).writeText("second")

        val selected = AppUpdateOwnershipRecovery(store, { 101L }, {}).recover()

        assertEquals(newer.operationId, selected?.operationId)
        assertTrue(store.readyFile(exposed).isFile)
        assertTrue(store.readyFile(newer).isFile)
        assertNotNull(store.read(exposed.operationId))
        assertNotNull(store.read(newer.operationId))
    }

    private fun record(phase: AppUpdateOwnershipPhase) = AppUpdateOwnershipRecord(
        operationId = 3L,
        version = "3.2.4",
        downloadId = 44L,
        downloadName = "op-3-download.apk",
        partName = "op-3-ready.apk.part",
        readyName = "op-3-ready.apk",
        targetVersionCode = 102L,
        phase = phase
    )

    private fun withRoots(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("bydhud-update-test").toFile()
        val staging = File(root, "staging").apply { mkdirs() }
        val downloads = File(root, "downloads").apply { mkdirs() }
        try {
            block(staging, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun failureMessage(block: () -> Unit): String {
        return try {
            block()
            ""
        } catch (error: IllegalStateException) {
            error.message.orEmpty()
        }
    }
}
