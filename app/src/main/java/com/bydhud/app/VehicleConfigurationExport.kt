package com.bydhud.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

enum class ConfigurationExportPhase {
    INVENTORY, DIAGNOSTICS, COPYING, ARCHIVING, WAITING_FOR_SHARE, READY,
    FAILED, CANCELLING, CANCELLED, EXPIRED
}

data class ConfigurationExportSnapshot(
    val operationId: String,
    val phase: ConfigurationExportPhase,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val endedAtElapsedMs: Long = 0,
    val foundFiles: Int = 0,
    val knownBytes: Long = 0,
    val inventoryComplete: Boolean = false,
    val copiedBytes: Long = 0,
    val totalBytes: Long? = null,
    val copiedFiles: Int = 0,
    val totalFiles: Int? = null,
    val unavailableFiles: Int = 0,
    val currentFile: String = "",
    val elapsedSeconds: Long = 0,
    val archiveBytes: Long = 0,
    val archiveName: String = "",
    val archiveAvailable: Boolean = false,
    val completedAtEpochMs: Long = 0,
    val expiresAtEpochMs: Long = 0,
    val volumeSizes: List<Long> = emptyList(),
    val detail: String = "",
    val dismissed: Boolean = false
)

internal fun configurationExportElapsedSeconds(
    state: ConfigurationExportSnapshot,
    nowElapsedMs: Long = SystemClock.elapsedRealtime()
): Long = ((if (state.endedAtElapsedMs > 0L) state.endedAtElapsedMs else nowElapsedMs) -
    state.startedAtElapsedMs).coerceAtLeast(0L) / 1000L

/** One explicit export per process. Activities only observe it; no Activity is kept by the worker. */
object VehicleConfigurationExport {
    private val state = MutableStateFlow<ConfigurationExportSnapshot?>(null)
    val snapshot: StateFlow<ConfigurationExportSnapshot?> = state.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vehicle-configuration-export").apply { isDaemon = true }
    }
    private var active: VehicleConfigurationZip.Control? = null
    private var archives: List<File> = emptyList()
    private var eventContext: Context? = null
    private val loggedPhases = mutableSetOf<String>()

    @JvmStatic
    @Synchronized
    fun start(context: Context): Boolean {
        if (active != null || !MainActivity.claimShareOperation()) return false
        val control = VehicleConfigurationZip.Control()
        val app = context.applicationContext
        val operationId = UUID.randomUUID().toString()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        val startedEpochMs = System.currentTimeMillis()
        StorageLogShareWorkflow.replaceCompletedForNewOperation()
        active = control
        archives = emptyList()
        eventContext = app
        loggedPhases.clear()
        state.value = ConfigurationExportSnapshot(
            operationId = operationId,
            phase = ConfigurationExportPhase.INVENTORY,
            startedAtEpochMs = startedEpochMs,
            startedAtElapsedMs = startedElapsedMs
        )
        logEvent("started")
        try {
            worker.execute {
                control.worker = Thread.currentThread()
                try {
                    ConfigurationExportArtifacts.checkBeforeExport(app)
                    control.check()
                    val result = VehicleConfigurationZip.createFull(app, control) {
                            phase, file, bytes, total, files, count, unavailable ->
                        synchronized(this) {
                            if (active === control && !control.isCancelled) {
                                val nextPhase = ConfigurationExportPhase.valueOf(phase)
                                val discovering = total < 0L || count < 0
                                val updated = state.value!!.copy(
                                    phase = nextPhase,
                                    foundFiles = if (discovering) files else count,
                                    knownBytes = if (discovering) bytes else total,
                                    inventoryComplete = !discovering,
                                    copiedBytes = if (discovering) state.value!!.copiedBytes else bytes,
                                    totalBytes = total.takeIf { it >= 0 },
                                    copiedFiles = if (discovering) state.value!!.copiedFiles else files,
                                    totalFiles = count.takeIf { it >= 0 },
                                    unavailableFiles = unavailable,
                                    currentFile = file,
                                    elapsedSeconds = configurationExportElapsedSeconds(state.value!!)
                                )
                                state.value = updated
                                logProgress(updated)
                            }
                        }
                    }
                    synchronized(this) {
                        if (active !== control || control.isCancelled) {
                            result.volumes.forEach(LogShareZip::deleteArtifact)
                            return@synchronized
                        }
                        archives = if (result.ok) result.volumes else emptyList()
                        state.value = state.value!!.copy(
                            phase = if (result.ok) ConfigurationExportPhase.READY else ConfigurationExportPhase.FAILED,
                            unavailableFiles = result.unavailableFiles,
                            archiveBytes = archives.sumOf { it.length() },
                            archiveName = archives.firstOrNull()?.name.orEmpty(),
                            archiveAvailable = archives.isNotEmpty(),
                            completedAtEpochMs = result.completedAtMs,
                            expiresAtEpochMs = if (result.ok) result.completedAtMs + ConfigurationExportArtifacts.RETENTION_MS else 0,
                            volumeSizes = archives.map { it.length() },
                            currentFile = "",
                            detail = if (result.ok) "" else result.detail
                        )
                        logProgress(state.value!!)
                    }
                } catch (error: Exception) {
                    synchronized(this) {
                        if (active === control && !control.isCancelled) {
                            state.value = state.value?.copy(phase = ConfigurationExportPhase.FAILED,
                                detail = error.javaClass.simpleName)
                            state.value?.let { logProgress(it) }
                        }
                    }
                } finally {
                    synchronized(this) {
                        if (active === control) {
                            val ended = SystemClock.elapsedRealtime()
                            if (control.isCancelled && state.value != null) {
                                state.value = state.value!!.copy(phase = ConfigurationExportPhase.CANCELLED,
                                    archiveAvailable = false, currentFile = "", detail = "",
                                    endedAtElapsedMs = ended,
                                    elapsedSeconds = configurationExportElapsedSeconds(
                                        state.value!!.copy(endedAtElapsedMs = ended)))
                            } else if (state.value != null) {
                                state.value = state.value!!.copy(
                                    endedAtElapsedMs = ended,
                                    elapsedSeconds = configurationExportElapsedSeconds(
                                        state.value!!.copy(endedAtElapsedMs = ended)))
                            }
                            state.value?.let {
                                logProgress(it)
                                logEvent("finished phase=${it.phase} duration_s=${it.elapsedSeconds}")
                            }
                            active = null
                        }
                        MainActivity.releaseShareOperation()
                    }
                    control.close()
                    Thread.interrupted()
                }
            }
        } catch (error: RuntimeException) {
            active = null
            MainActivity.releaseShareOperation()
            state.value = state.value!!.copy(phase = ConfigurationExportPhase.FAILED,
                detail = error.javaClass.simpleName,
                endedAtElapsedMs = SystemClock.elapsedRealtime())
            state.value?.let { logProgress(it) }
            return false
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun cancel() {
        val phase = state.value?.phase ?: return
        if (phase !in setOf(
                ConfigurationExportPhase.INVENTORY,
                ConfigurationExportPhase.DIAGNOSTICS,
                ConfigurationExportPhase.COPYING,
                ConfigurationExportPhase.ARCHIVING
            )) return
        active?.let {
            state.value = state.value?.copy(phase = ConfigurationExportPhase.CANCELLING)
            state.value?.let { snapshot -> logProgress(snapshot) }
            it.cancel()
        }
    }

    @JvmStatic
    @Synchronized
    fun dismiss() {
        val current = state.value ?: return
        if (active != null) return
        state.value = current.copy(dismissed = true)
        logEvent("dismissed phase=${current.phase}")
    }

    @JvmStatic
    @Synchronized
    fun artifactsChecked(nowEpochMs: Long) {
        val current = state.value ?: return
        if (current.expiresAtEpochMs <= 0 || nowEpochMs < current.expiresAtEpochMs) return
        if (current.phase == ConfigurationExportPhase.EXPIRED) return
        archives = emptyList()
        state.value = current.copy(phase = ConfigurationExportPhase.EXPIRED, archiveAvailable = false)
        logEvent("expired expiresAtMs=${current.expiresAtEpochMs}")
    }

    @JvmStatic
    @Synchronized
    fun canShare(operationId: String): Boolean {
        artifactsChecked(System.currentTimeMillis())
        return state.value?.let { it.operationId == operationId && it.archiveAvailable } == true
    }

    @JvmStatic
    @Synchronized
    fun shareReady() {
        if (active != null) return
        artifactsChecked(System.currentTimeMillis())
        val current = state.value ?: return
        if (!current.archiveAvailable || current.phase == ConfigurationExportPhase.WAITING_FOR_SHARE) return
        state.value = current.copy(phase = ConfigurationExportPhase.WAITING_FOR_SHARE, detail = "")
        // No expiry renewal and no inferred delivery acknowledgement.
        MainActivity.queueConfigurationShare(archives, current.operationId)
        logEvent("share_attempt volumes=${archives.size} expiresAtMs=${current.expiresAtEpochMs}")
    }

    @JvmStatic
    @Synchronized
    fun androidShareLaunched(operationId: String): Boolean {
        if (!canShare(operationId)) return false
        state.value = state.value!!.copy(phase = ConfigurationExportPhase.READY, detail = "")
        logEvent("share_chooser_opened")
        return true
    }

    @JvmStatic
    @Synchronized
    fun androidShareFailed(operationId: String, detail: String, archiveMissing: Boolean): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId) return false
        if (current.phase == ConfigurationExportPhase.EXPIRED) return false
        if (archiveMissing) archives = emptyList()
        state.value = current.copy(
            phase = ConfigurationExportPhase.FAILED,
            archiveAvailable = !archiveMissing && current.archiveAvailable,
            detail = detail
        )
        logEvent("share_failed missing=$archiveMissing")
        return true
    }

    @JvmStatic
    @Synchronized
    fun replaceCompletedForNewOperation() {
        if (active != null) return
        state.value = null
        archives = emptyList()
        eventContext = null
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        active?.cancel()
        state.value = null
        archives = emptyList()
        eventContext = null
    }

    private fun logProgress(snapshot: ConfigurationExportSnapshot) {
        // COPYING/ARCHIVING alternate per file: log each phase once, never per file or chunk.
        if (!loggedPhases.add(snapshot.phase.name)) return
        logEvent("phase=${snapshot.phase} inventory=${snapshot.foundFiles} collected=${snapshot.copiedFiles} " +
            "unavailable=${snapshot.unavailableFiles} volumes=${snapshot.volumeSizes.size} bytes=${snapshot.archiveBytes} " +
            "createdAtMs=${snapshot.completedAtEpochMs} expiresAtMs=${snapshot.expiresAtEpochMs}")
    }

    private fun logEvent(detail: String) {
        val snapshot = state.value ?: return
        eventContext?.let {
            AppEventLogger.event(it, "configuration_export operation_id=${snapshot.operationId} $detail")
        }
    }

}
