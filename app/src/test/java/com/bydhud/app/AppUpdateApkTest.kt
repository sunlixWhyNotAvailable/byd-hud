package com.bydhud.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AppUpdateApkTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun copiedBytesAreValidatedBeforePublishingAndVersionComesFromVerifier() = runBlocking {
        val download = temp.newFile("download.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val part = File(temp.root, "ready.apk.part")
        val ready = File(temp.root, "ready.apk")
        var calls = 0
        val result = prepareUpdateApk(download, part, ready) { inspected ->
            calls++
            assertEquals(part, inspected)
            assertArrayEquals(download.readBytes(), inspected.readBytes())
            assertFalse(ready.exists())
            123L
        }
        assertEquals(1, calls)
        assertEquals(123L, result.targetVersionCode)
        assertEquals(ready.absolutePath, result.path)
        assertArrayEquals(download.readBytes(), ready.readBytes())
        assertFalse(part.exists())
    }

    @Test fun invalidApkNeverReplacesPreviouslyPreparedBytes() = runBlocking {
        val download = temp.newFile("invalid.apk").apply { writeText("not an APK") }
        val part = File(temp.root, "partial")
        val ready = temp.newFile("ready.apk").apply { writeText("previous") }
        val failure = runCatching {
            prepareUpdateApk(download, part, ready) { throw IOException("invalid signature") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("previous", ready.readText())
        assertTrue(download.exists())
    }

    @Test fun missingDownloadCannotValidateOrPublish() = runBlocking {
        val ready = File(temp.root, "ready.apk")
        assertTrue(runCatching {
            prepareUpdateApk(File(temp.root, "missing"), File(temp.root, "part"), ready) {
                fail("missing input must not reach verifier")
                123L
            }
        }.exceptionOrNull() is IllegalStateException)
        assertFalse(ready.exists())
    }

    @Test fun failedPublicationPreservesTheConflictingDestination() = runBlocking {
        val download = temp.newFile("download.apk").apply { writeText("bytes") }
        val ready = temp.newFolder("ready.apk")
        val existing = File(ready, "keep").apply { writeText("unrelated") }
        assertTrue(runCatching {
            prepareUpdateApk(download, File(temp.root, "part"), ready) { 123L }
        }.exceptionOrNull() is IllegalStateException)
        assertEquals("unrelated", existing.readText())
    }

    @Test fun cancelledPreparationCannotValidateOrPublish() = runBlocking {
        val download = temp.newFile("download.apk").apply { writeText("bytes") }
        val part = File(temp.root, "part")
        val ready = File(temp.root, "ready.apk")
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            prepareUpdateApk(download, part, ready) { fail("cancelled verifier"); 123L }
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(part.isFile) // The real transaction ran through copying to its cancellation fence.
        assertFalse(ready.exists())
    }

    @Test fun ownershipIsDurableBeforeExposureCallbackAndInstaller() = runBlocking {
        val (store, record) = owned()
        val ready = AppUpdateReadyApk(store.readyFile(record).absolutePath, 123L)
        val events = mutableListOf<String>()
        handoffUpdateApk(1L, ready, { store }, { exposed ->
            assertTrue(exposed.exposed)
            assertEquals(AppUpdateOwnershipPhase.EXPOSED, store.read(1L)!!.phase)
            events += "exposed"
        }) { file ->
            assertEquals(store.readyFile(record), file)
            assertEquals(AppUpdateOwnershipPhase.EXPOSED, store.read(1L)!!.phase)
            events += "installer"
        }
        assertEquals(listOf("exposed", "installer"), events)
    }

    @Test fun failedDurableWritePreventsBothExposureAndInstaller() = runBlocking {
        val (store, record) = owned()
        val readyFile = store.readyFile(record)
        File(readyFile.parentFile, "ownership-1.properties.tmp").mkdir()
        val ready = AppUpdateReadyApk(readyFile.absolutePath, 123L)
        val events = mutableListOf<String>()
        assertNotNull(runCatching {
            handoffUpdateApk(1L, ready, { store }, { events += "exposed" }) { events += "installer" }
        }.exceptionOrNull())
        assertTrue(events.isEmpty())
        assertEquals(AppUpdateOwnershipPhase.READY, store.read(1L)!!.phase)
        assertEquals("ready", readyFile.readText())
    }

    @Test fun missingOwnershipOrDifferentFileCannotReachInstaller() = runBlocking {
        val (store, record) = owned()
        val foreign = temp.newFile("foreign.apk").apply { writeText("foreign") }
        for ((id, path) in listOf(1L to foreign, 2L to store.readyFile(record))) {
            assertTrue(runCatching {
                handoffUpdateApk(id, AppUpdateReadyApk(path.absolutePath, 123L), { store },
                    { fail("must not expose unowned file") }) { fail("must not launch installer") }
            }.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(AppUpdateOwnershipPhase.READY, store.read(1L)!!.phase)
        assertEquals("foreign", foreign.readText())
    }

    private fun owned(): Pair<AppUpdateOwnershipStore, AppUpdateOwnershipRecord> {
        val store = AppUpdateOwnershipStore(temp.newFolder("staging"), temp.newFolder("downloads"))
        val record = AppUpdateOwnershipRecord(1L, "next", 5L, "download.apk", "ready.apk.part",
            "ready.apk", 123L, AppUpdateOwnershipPhase.READY)
        store.readyFile(record).writeText("ready")
        store.write(record)
        return store to record
    }
}
