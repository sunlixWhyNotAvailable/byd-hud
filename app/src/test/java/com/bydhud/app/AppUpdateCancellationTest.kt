package com.bydhud.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [AppUpdateCancellationTest.DownloadService::class])
class AppUpdateCancellationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val offer = AppUpdateManager.UpdateInfo("9.0.0", "https://example.com/update.apk", "Release notes")

    @Test(timeout = 15000) fun cancelStopsDownloadOrValidationAndAllowsFreshAttemptWithoutLateInstall() = runBlocking {
        for (duringValidation in listOf(false, true)) {
            val driver = FileDriver(temporary.newFolder(), duringValidation)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = AppUpdateOperationController(scope, driver)
            try {
                val id = controller.start(offer)
                driver.entered.await()
                val visible = updateCheckStateFor(AppUpdateManager.Snapshot(), controller.snapshot.value)
                assertTrue((visible as UpdateCheckState.Downloading).cancellable)
                assertTrue(controller.cancel(id))
                assertFalse(controller.cancel(id))
                driver.cancelRequested.await()
                if (duringValidation) {
                    assertEquals(AppUpdateOperationPhase.CANCELLING, controller.snapshot.value!!.phase)
                    assertEquals("cannot start while the old writer still runs", id, controller.start(offer))
                    assertEquals(AppUpdateOwnershipPhase.CANCELLED, driver.freshStore().read(id)!!.phase)
                    driver.validationRelease.countDown()
                }
                awaitPhase(controller, AppUpdateOperationPhase.CANCELLED)
                assertFalse(driver.downloadActive)
                assertEquals(0, driver.installs)
                assertNull(driver.freshStore().read(id))
                assertFalse(driver.download.exists())
                assertFalse(driver.part.exists())
                assertFalse(driver.ready.exists())
                val cancelled = updateCheckStateFor(AppUpdateManager.Snapshot(), controller.snapshot.value)
                assertEquals(offer, (cancelled as UpdateCheckState.Available).info)
                val recoveredOffer = controller.snapshot.value!!.copy(update = offer.copy(downloadUrl = ""))
                assertEquals(UpdateCheckState.Checking,
                    updateCheckStateFor(AppUpdateManager.Snapshot(checking = true), recoveredOffer))
                assertEquals(UpdateCheckState.Error("offline"), updateCheckStateFor(
                    AppUpdateManager.Snapshot(result = AppUpdateManager.CheckResult.Error("offline")), recoveredOffer))
                val next = controller.start(offer)
                assertNotEquals(id, next)
                awaitPhase(controller, AppUpdateOperationPhase.READY)
                assertEquals(1, driver.installs)
            } finally {
                driver.validationRelease.countDown()
                scope.cancel()
            }
        }
    }

    @Test(timeout = 15000) fun failedCleanupRemainsCancelledOnDiskAndRecoveryRemovesItWithoutResuming() = runBlocking {
        val driver = FileDriver(temporary.newFolder(), false).apply { failRemoval = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppUpdateOperationController(scope, driver)
        try {
            val id = controller.start(offer)
            driver.entered.await()
            assertTrue(controller.cancel(id))
            awaitPhase(controller, AppUpdateOperationPhase.CANCEL_FAILED)
            assertTrue((updateCheckStateFor(AppUpdateManager.Snapshot(), controller.snapshot.value)
                as UpdateCheckState.Downloading).cancellationFailed)
            assertEquals("failed cancellation must not admit another download", id, controller.start(offer))
            assertEquals(AppUpdateOwnershipPhase.CANCELLED, driver.freshStore().read(id)!!.phase)
            var serviceAvailable = false
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = driver.root
                override fun getExternalFilesDir(type: String?) = File(driver.root, "downloads")
                override fun getApplicationContext(): Context = this
                override fun getSystemService(name: String): Any? =
                    if (name == DOWNLOAD_SERVICE && !serviceAvailable) null else super.getSystemService(name)
            }
            val androidDriver = Class.forName("com.bydhud.app.AppUpdateManager\$AndroidUpdateOperationDriver")
                .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                .newInstance(context) as AppUpdateOperationDriver
            DownloadService.failRemoval = true
            val restarted = AppUpdateOperationController(scope, androidDriver)
            restarted.recover().join()
            assertTrue(updateCheckStateFor(AppUpdateManager.Snapshot(), restarted.snapshot.value) is UpdateCheckState.Error)
            assertTrue(driver.download.exists())
            serviceAvailable = true
            restarted.start(offer)
            awaitPhase(restarted, AppUpdateOperationPhase.CANCEL_FAILED)
            assertEquals(AppUpdateOperationPhase.CANCEL_FAILED, restarted.snapshot.value!!.phase)
            assertEquals(id, restarted.start(offer))
            assertTrue(driver.download.exists())
            assertEquals(AppUpdateOwnershipPhase.CANCELLED, driver.freshStore().read(id)!!.phase)
            DownloadService.failRemoval = false
            assertTrue(restarted.cancel(id))
            awaitPhase(restarted, AppUpdateOperationPhase.CANCELLED)
            assertTrue(DownloadService.removed.contains(id))
            assertNull(driver.freshStore().read(id))
            assertFalse(driver.download.exists())
            assertEquals(0, driver.installs)

            // A download recovered after process death remains cancellable during polling.
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 1)
            assertTrue(ShellRuntimeSession.arm(context))
            driver.freshStore().write(AppUpdateOwnershipRecord(id + 1, offer.version, id + 1,
                driver.download.name, driver.part.name, driver.ready.name, -1, AppUpdateOwnershipPhase.ACTIVE))
            driver.download.writeText("partial APK")
            DownloadService.queried = CompletableDeferred()
            val activeRecovery = AppUpdateOperationController(scope, androidDriver)
            val recovering = activeRecovery.recover()
            DownloadService.queried.await()
            assertTrue(activeRecovery.cancel(id + 1))
            awaitPhase(activeRecovery, AppUpdateOperationPhase.CANCELLED)
            recovering.join()
            assertNull(driver.freshStore().read(id + 1))
            assertFalse(driver.download.exists())
            assertFalse(driver.ready.exists())
            assertEquals(0, DownloadService.enqueued)
            assertNull(org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        } finally { scope.cancel() }
    }

    @Test(timeout = 15000) fun installerHandoffWinsRejectsCancelAndKeepsExposedApk() = runBlocking {
        val driver = FileDriver(temporary.newFolder(), false).apply { attempts = 1; holdInstaller = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppUpdateOperationController(scope, driver)
        try {
            val id = controller.start(offer)
            driver.installerEntered.await()
            assertFalse(controller.cancel(id))
            assertFalse((updateCheckStateFor(AppUpdateManager.Snapshot(), controller.snapshot.value)
                as UpdateCheckState.Downloading).cancellable)
            assertTrue(driver.ready.isFile)
            assertEquals(AppUpdateOwnershipPhase.EXPOSED, driver.freshStore().read(id)!!.phase)
            driver.installerRelease.complete(Unit)
            awaitPhase(controller, AppUpdateOperationPhase.READY)
            assertEquals(1, driver.installs)
        } finally { driver.installerRelease.complete(Unit); scope.cancel() }
    }

    private suspend fun awaitPhase(controller: AppUpdateOperationController, phase: AppUpdateOperationPhase) {
        withTimeout(5000) { while (controller.snapshot.value?.phase != phase) delay(10) }
    }

    @Implements(DownloadManager::class)
    class DownloadService {
        @Implementation fun enqueue(request: DownloadManager.Request): Long {
            enqueued++
            throw IllegalStateException("Unresolved recovery must not start another download")
        }
        @Implementation fun remove(vararg ids: Long): Int {
            if (failRemoval) throw IOException("DownloadManager removal failed")
            removed.addAll(ids.toList())
            return ids.size
        }
        @Implementation fun query(query: DownloadManager.Query): Cursor {
            queried.complete(Unit)
            return MatrixCursor(arrayOf(DownloadManager.COLUMN_STATUS)).apply {
                addRow(arrayOf(DownloadManager.STATUS_PENDING))
            }
        }
        companion object {
            @Volatile var failRemoval = false
            @Volatile var enqueued = 0
            val removed = java.util.concurrent.CopyOnWriteArrayList<Long>()
            var queried = CompletableDeferred<Unit>()
        }
    }

    private class FileDriver(val root: File, private val blockValidation: Boolean) : AppUpdateOperationDriver {
        private val staging = File(root, "updates").apply { mkdirs() }
        private val downloads = File(root, "downloads").apply { mkdirs() }
        val download = File(downloads, "update.apk")
        val part = File(staging, "update.part")
        val ready = File(staging, "update.apk")
        val entered = CompletableDeferred<Unit>()
        val cancelRequested = CompletableDeferred<Unit>()
        val validationRelease = CountDownLatch(1)
        val installerEntered = CompletableDeferred<Unit>()
        val installerRelease = CompletableDeferred<Unit>()
        @Volatile var failRemoval = false
        @Volatile var downloadActive = false
        @Volatile var attempts = 0
        @Volatile var installs = 0
        var holdInstaller = false
        fun freshStore() = AppUpdateOwnershipStore(staging, downloads)
        fun recovery(store: AppUpdateOwnershipStore) = AppUpdateOwnershipRecovery(store, { 1L }) {
            if (failRemoval) throw IOException("DownloadManager removal failed")
            downloadActive = false
        }
        override suspend fun recover(): AppUpdateOperationSnapshot? = null
        override suspend fun prepare(operationId: Long, update: AppUpdateManager.UpdateInfo,
            onPreparing: () -> Unit, onProgress: (String) -> Unit): AppUpdateReadyApk {
            val first = ++attempts == 1
            val store = freshStore()
            val record = AppUpdateOwnershipRecord(operationId, update.version, operationId,
                download.name, part.name, ready.name, -1, AppUpdateOwnershipPhase.ACTIVE)
            store.write(record)
            downloadActive = true
            download.writeText("APK fixture")
            if (first && !blockValidation) {
                onProgress("25%")
                entered.complete(Unit)
                awaitCancellation()
            }
            onPreparing()
            val prepared = prepareUpdateApk(download, part, ready) {
                if (first && blockValidation) {
                    entered.complete(Unit)
                    check(validationRelease.await(5, TimeUnit.SECONDS))
                    onProgress("100%") // Simulate a late result from synchronous APK validation.
                }
                900L
            }
            store.write(record.copy(targetVersionCode = 900, phase = AppUpdateOwnershipPhase.READY))
            return prepared
        }
        override suspend fun requestCancellation(operationId: Long) {
            val store = freshStore()
            store.markCancelled(operationId)
            cancelRequested.complete(Unit)
            if (failRemoval) throw IOException("DownloadManager removal failed")
            downloadActive = false
        }
        override suspend fun cancelUnexposed(operationId: Long) = recovery(freshStore()).cancelUnexposed(operationId)
        override suspend fun handoff(operationId: Long, ready: AppUpdateReadyApk,
            onExposed: (AppUpdateReadyApk) -> Unit) {
            handoffUpdateApk(operationId, ready, ::freshStore, onExposed) {
                installerEntered.complete(Unit)
                if (holdInstaller) installerRelease.await()
                installs++
            }
        }
    }
}
