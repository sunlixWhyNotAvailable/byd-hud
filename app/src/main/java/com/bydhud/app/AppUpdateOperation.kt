package com.bydhud.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal enum class AppUpdateOperationPhase {
    DOWNLOADING,
    PREPARING,
    INSTALLING,
    READY,
    FAILED
}

internal data class AppUpdateReadyApk(
    val path: String,
    val targetVersionCode: Long,
    val exposed: Boolean = false
)

internal data class AppUpdateOperationSnapshot(
    val id: Long,
    val update: AppUpdateManager.UpdateInfo,
    val phase: AppUpdateOperationPhase,
    val progress: String = "0%",
    val ready: AppUpdateReadyApk? = null,
    val error: String? = null
)

/** Injectable boundary around DownloadManager, APK validation, persistence, and installer launch. */
internal interface AppUpdateOperationDriver {
    suspend fun recover(): AppUpdateOperationSnapshot?
    suspend fun prepare(
        operationId: Long,
        update: AppUpdateManager.UpdateInfo,
        onPreparing: () -> Unit,
        onProgress: (String) -> Unit
    ): AppUpdateReadyApk

    suspend fun handoff(
        operationId: Long,
        ready: AppUpdateReadyApk,
        onExposed: (AppUpdateReadyApk) -> Unit
    )

    suspend fun cancelUnexposed(operationId: Long)
    fun recordCleanupFailure(operationId: Long, error: Throwable) = Unit
}

internal data class AppUpdateEnvironment<T>(val downloadRoot: File, val downloadService: T)

/** Defers nullable Android environment access until guarded updater I/O begins. */
internal class AppUpdateEnvironmentResolver<T>(
    private val downloadRoot: () -> File?,
    private val downloadService: () -> T?
) {
    fun resolve(): AppUpdateEnvironment<T> {
        val root = downloadRoot() ?: throw IllegalStateException("Update storage is unavailable")
        val service = downloadService() ?: throw IllegalStateException("Android download service is unavailable")
        return AppUpdateEnvironment(root, service)
    }
}

/** One process owner for admission and publication of a self-update operation. */
internal class AppUpdateOperationController(
    private val scope: CoroutineScope,
    private val driver: AppUpdateOperationDriver,
    initialOperationId: Long = 0L
) {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow<AppUpdateOperationSnapshot?>(null)
    val snapshot: StateFlow<AppUpdateOperationSnapshot?> = mutableSnapshot.asStateFlow()
    private var generation = 0L
    private var nextOperationId = initialOperationId
    private var job: Job? = null
    private var recovering = false
    private var pendingStart: Pair<Long, AppUpdateManager.UpdateInfo>? = null

    fun recover(): Job {
        val ticket = synchronized(lock) {
            if (recovering) return job ?: scope.launch { }
            recovering = true
            ++generation
        }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val recovered = try {
                driver.recover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    if (generation != ticket || !recovering) return@synchronized
                    recovering = false
                    val pending = pendingStart
                    pendingStart = null
                    if (pending != null) {
                        mutableSnapshot.value = AppUpdateOperationSnapshot(
                            id = pending.first,
                            update = pending.second,
                            phase = AppUpdateOperationPhase.FAILED,
                            error = error.message ?: "Update recovery failed"
                        )
                    }
                }
                return@launch
            }
            synchronized(lock) {
                if (generation != ticket || !recovering) return@synchronized
                recovering = false
                if (recovered != null) {
                    nextOperationId = maxOf(nextOperationId, recovered.id)
                    mutableSnapshot.value = recovered
                    pendingStart = null
                } else {
                    val pending = pendingStart
                    pendingStart = null
                    if (pending != null) startLocked(pending.second, pending.first)
                }
            }
        }
        synchronized(lock) { job = launched }
        launched.start()
        return launched
    }

    fun start(update: AppUpdateManager.UpdateInfo): Long = synchronized(lock) {
        val existing = mutableSnapshot.value
        if (existing != null) {
            val active = existing.phase == AppUpdateOperationPhase.DOWNLOADING ||
                existing.phase == AppUpdateOperationPhase.PREPARING ||
                existing.phase == AppUpdateOperationPhase.INSTALLING
            if (active || (existing.phase == AppUpdateOperationPhase.READY && existing.update.version == update.version)) {
                return@synchronized existing.id
            }
        }
        pendingStart?.let { return@synchronized it.first }
        val operationId = ++nextOperationId
        if (recovering) {
            pendingStart = operationId to update
            return@synchronized operationId
        }
        startLocked(update, operationId)
    }

    private fun startLocked(update: AppUpdateManager.UpdateInfo, operationId: Long): Long {
        val ticket = ++generation
        mutableSnapshot.value = AppUpdateOperationSnapshot(
            id = operationId,
            update = update,
            phase = AppUpdateOperationPhase.DOWNLOADING
        )
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val ready = driver.prepare(
                    operationId,
                    update,
                    onPreparing = { publish(ticket, operationId) { it.copy(phase = AppUpdateOperationPhase.PREPARING) } },
                    onProgress = { progress -> publish(ticket, operationId) { it.copy(progress = progress) } }
                )
                currentCoroutineContext().ensureActive()
                if (!publish(ticket, operationId) {
                        it.copy(phase = AppUpdateOperationPhase.INSTALLING, progress = "100%", ready = ready)
                    }
                ) return@launch
                handoff(ticket, operationId, ready)
            } catch (cancelled: CancellationException) {
                runCatching { driver.cancelUnexposed(operationId) }
                throw cancelled
            } catch (error: Exception) {
                val cleanupError = runCatching { driver.cancelUnexposed(operationId) }.exceptionOrNull()
                publish(ticket, operationId) {
                    val primary = error.message ?: "Update failed"
                    val message = cleanupError?.message?.let { "$primary; cleanup failed: $it" } ?: primary
                    it.copy(phase = AppUpdateOperationPhase.FAILED, error = message)
                }
            }
        }
        job = launched
        launched.start()
        return operationId
    }

    /** A READY operation is handed to Android again only after a fresh explicit press. */
    fun retryInstall(): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value ?: return@synchronized false
        val ready = current.ready ?: return@synchronized false
        if (current.phase != AppUpdateOperationPhase.READY) return@synchronized false
        val ticket = generation
        mutableSnapshot.value = current.copy(phase = AppUpdateOperationPhase.INSTALLING, error = null)
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                handoff(ticket, current.id, ready)
            } catch (cancelled: CancellationException) {
                runCatching { driver.cancelUnexposed(current.id) }
                throw cancelled
            }
        }
        job = launched
        launched.start()
        true
    }

    fun dismissTerminalFailure() = synchronized(lock) {
        if (mutableSnapshot.value?.phase == AppUpdateOperationPhase.FAILED) {
            mutableSnapshot.value = null
        }
    }

    fun shutdown() {
        val operationId = synchronized(lock) {
            ++generation
            recovering = false
            pendingStart = null
            job?.cancel()
            job = null
            val current = mutableSnapshot.value
            mutableSnapshot.value = null
            current?.id
        }
        if (operationId != null) scope.launch {
            try {
                driver.cancelUnexposed(operationId)
            } catch (error: Throwable) {
                runCatching { driver.recordCleanupFailure(operationId, error) }
            }
        }
    }

    private suspend fun handoff(ticket: Long, operationId: Long, ready: AppUpdateReadyApk) {
        try {
            driver.handoff(operationId, ready) { exposed ->
                publish(ticket, operationId) { it.copy(ready = exposed) }
            }
            publish(ticket, operationId) { it.copy(phase = AppUpdateOperationPhase.READY, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publish(ticket, operationId) {
                it.copy(phase = AppUpdateOperationPhase.READY, error = error.message ?: "Installer unavailable")
            }
        }
    }

    private fun publish(
        ticket: Long,
        operationId: Long,
        transform: (AppUpdateOperationSnapshot) -> AppUpdateOperationSnapshot
    ): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (generation != ticket || current?.id != operationId) return@synchronized false
        mutableSnapshot.value = transform(current)
        true
    }
}

internal object AppUpdateOperationIds {
    fun processSeed(): Long = System.currentTimeMillis().coerceAtLeast(0L)
}

internal enum class AppUpdateOwnershipPhase { ACTIVE, READY, EXPOSED }

internal data class AppUpdateOwnershipRecord(
    val operationId: Long,
    val version: String,
    val downloadId: Long,
    val downloadName: String,
    val partName: String,
    val readyName: String,
    val targetVersionCode: Long,
    val phase: AppUpdateOwnershipPhase
)

/** Durable, path-confined ownership record used only to recover files created by this updater. */
internal class AppUpdateOwnershipStore(
    private val stagingRoot: File,
    private val downloadRoot: File
) {
    @Synchronized fun write(record: AppUpdateOwnershipRecord) {
        stagingRoot.mkdirs()
        val recordFile = recordFile(record.operationId)
        val values = Properties().apply {
            setProperty("operationId", record.operationId.toString())
            setProperty("version", record.version)
            setProperty("downloadId", record.downloadId.toString())
            setProperty("downloadName", record.downloadName)
            setProperty("partName", record.partName)
            setProperty("readyName", record.readyName)
            setProperty("targetVersionCode", record.targetVersionCode.toString())
            setProperty("phase", record.phase.name)
        }
        val temporary = File(recordFile.parentFile, "${recordFile.name}.tmp")
        FileOutputStream(temporary).use {
            values.store(it, null)
            it.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                recordFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), recordFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized fun read(operationId: Long): AppUpdateOwnershipRecord? {
        val recordFile = recordFile(operationId)
        if (!recordFile.isFile) return null
        return runCatching {
            val values = Properties()
            FileInputStream(recordFile).use(values::load)
            AppUpdateOwnershipRecord(
                operationId = values.requiredLong("operationId"),
                version = values.required("version"),
                downloadId = values.requiredLong("downloadId"),
                downloadName = safeName(values.required("downloadName")),
                partName = safeName(values.required("partName")),
                readyName = safeName(values.required("readyName")),
                targetVersionCode = values.requiredLong("targetVersionCode"),
                phase = AppUpdateOwnershipPhase.valueOf(values.required("phase"))
            )
        }.getOrElse {
            recordFile.delete()
            null
        }
    }

    @Synchronized fun readAll(): List<AppUpdateOwnershipRecord> {
        return stagingRoot.listFiles().orEmpty()
            .mapNotNull { file -> RECORD_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull() }
            .distinct()
            .mapNotNull(::read)
    }

    @Synchronized fun clear(operationId: Long) {
        val recordFile = recordFile(operationId)
        recordFile.delete()
        File(recordFile.parentFile, "${recordFile.name}.tmp").delete()
    }

    fun downloadFile(record: AppUpdateOwnershipRecord) = ownedFile(downloadRoot, record.downloadName)
    fun partFile(record: AppUpdateOwnershipRecord) = ownedFile(stagingRoot, record.partName)
    fun readyFile(record: AppUpdateOwnershipRecord) = ownedFile(stagingRoot, record.readyName)

    private fun ownedFile(root: File, name: String): File {
        val safe = safeName(name)
        val file = File(root, safe)
        check(file.canonicalFile.parentFile == root.canonicalFile) { "Update path escaped its owner" }
        return file
    }

    private fun safeName(value: String): String {
        require(value.isNotBlank() && value == File(value).name && value != "." && value != "..") {
            "Unsafe update ownership path"
        }
        return value
    }

    private fun Properties.required(key: String): String = getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Missing update ownership $key")
    private fun Properties.requiredLong(key: String): Long = required(key).toLong()

    private fun recordFile(operationId: Long): File {
        require(operationId >= 0L) { "Invalid update operation identity" }
        return File(stagingRoot, "ownership-$operationId.properties")
    }

    private companion object {
        val RECORD_PATTERN = Regex("^ownership-(\\d+)\\.properties$")
    }
}

/** Deterministic recovery policy separated from Android so file ownership is exercised in JVM tests. */
internal class AppUpdateOwnershipRecovery(
    private val store: AppUpdateOwnershipStore,
    private val installedVersionCode: () -> Long,
    private val removeDownload: (Long) -> Unit
) {
    fun recover(): AppUpdateOwnershipRecord? {
        val installed = installedVersionCode()
        return store.readAll().mapNotNull { record ->
            if (record.phase == AppUpdateOwnershipPhase.ACTIVE ||
                installed >= record.targetVersionCode ||
                !store.readyFile(record).isFile
            ) {
                cleanup(record)
                null
            } else {
                store.downloadFile(record).delete()
                store.partFile(record).delete()
                remove(record.downloadId)
                record
            }
        }.maxByOrNull { it.operationId }
    }

    fun cancelUnexposed(operationId: Long) {
        val record = store.read(operationId) ?: return
        if (record.phase != AppUpdateOwnershipPhase.EXPOSED) cleanup(record)
    }

    private fun cleanup(record: AppUpdateOwnershipRecord) {
        remove(record.downloadId)
        store.downloadFile(record).delete()
        store.partFile(record).delete()
        store.readyFile(record).delete()
        store.clear(record.operationId)
    }

    private fun remove(downloadId: Long) {
        if (downloadId >= 0L) removeDownload(downloadId)
    }
}
