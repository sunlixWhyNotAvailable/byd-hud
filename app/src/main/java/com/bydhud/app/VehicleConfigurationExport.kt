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
    INVENTORY, DIAGNOSTICS, COPYING, ARCHIVING, VERIFYING, WAITING_FOR_SHARE, READY, UPLOADING,
    SENT, FAILED, CANCELLING, CANCELLED
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
    val toDeveloper: Boolean = false,
    val detail: String = "",
    val eventId: String = "",
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
    private var archive: File? = null
    private var eventContext: Context? = null
    private var lastLoggedPhase = ""
    private var lastLoggedFile = ""
    private var lastLoggedBytes = 0L
    private var lastLoggedAtElapsedMs = 0L

    @JvmStatic
    @Synchronized
    fun start(context: Context, toDeveloper: Boolean): Boolean {
        if (active != null || !MainActivity.claimShareOperation()) return false
        val control = VehicleConfigurationZip.Control()
        val app = context.applicationContext
        val operationId = UUID.randomUUID().toString()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        val startedEpochMs = System.currentTimeMillis()
        StorageLogShareWorkflow.replaceCompletedForNewOperation()
        active = control
        archive = null
        eventContext = app
        lastLoggedPhase = ""
        lastLoggedFile = ""
        lastLoggedBytes = 0L
        lastLoggedAtElapsedMs = 0L
        state.value = ConfigurationExportSnapshot(
            operationId = operationId,
            phase = ConfigurationExportPhase.INVENTORY,
            startedAtEpochMs = startedEpochMs,
            startedAtElapsedMs = startedElapsedMs,
            toDeveloper = toDeveloper
        )
        logEvent("started destination=${if (toDeveloper) "sentry" else "android"}")
        try {
            worker.execute {
                control.worker = Thread.currentThread()
                try {
                    val result = VehicleConfigurationZip.createFull(app, control) {
                            phase, file, bytes, total, files, count, unavailable ->
                        synchronized(this) {
                            if (active === control && !control.isCancelled) {
                                val nextPhase = ConfigurationExportPhase.valueOf(phase)
                                val discovering = total < 0L || count < 0
                                val updated = state.value!!.copy(
                                    phase = nextPhase,
                                    foundFiles = if (discovering) files else maxOf(state.value!!.foundFiles, count),
                                    knownBytes = if (discovering) bytes else maxOf(state.value!!.knownBytes, total),
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
                            LogShareZip.deleteArtifact(result.file)
                            return@synchronized
                        }
                        archive = result.file.takeIf { result.ok }
                        state.value = state.value!!.copy(
                            phase = if (result.ok) ConfigurationExportPhase.READY else ConfigurationExportPhase.FAILED,
                            unavailableFiles = result.unavailableFiles,
                            archiveBytes = archive?.length() ?: 0,
                            archiveName = archive?.name.orEmpty(),
                            archiveAvailable = archive != null,
                            currentFile = "",
                            detail = if (result.ok) "" else result.detail
                        )
                        logProgress(state.value!!, force = true)
                    }
                    val uploadFile = synchronized(this) {
                        archive?.takeIf {
                            active === control && !control.isCancelled && toDeveloper &&
                                it.length() <= SentryLogUploader.MAX_ZIP_BYTES
                        }?.also {
                            state.value = state.value!!.copy(phase = ConfigurationExportPhase.UPLOADING)
                            logProgress(state.value!!, force = true)
                        }
                    }
                    if (uploadFile != null) {
                        control.check()
                        // Validate before the uploader: its legacy rejection path deletes its input.
                        val invalid = SentryLogUploader.validate(BuildConfig.SENTRY_DSN, uploadFile)
                        val sent = if (invalid.isEmpty()) SentryLogUploader.uploadConfiguration(app, uploadFile)
                            else SentryLogUploader.Result(false, "", invalid)
                        synchronized(this) {
                            if (active === control && !control.isCancelled) {
                                if (sent.ok) archive = null
                                state.value = state.value!!.copy(
                                    phase = if (sent.ok) ConfigurationExportPhase.SENT else ConfigurationExportPhase.FAILED,
                                    archiveAvailable = archive?.isFile == true,
                                    detail = sent.detail,
                                    eventId = sent.eventId
                                )
                                logProgress(state.value!!, force = true)
                            }
                        }
                    }
                } catch (error: Exception) {
                    synchronized(this) {
                        if (active === control && !control.isCancelled) {
                            state.value = state.value?.copy(phase = ConfigurationExportPhase.FAILED,
                                detail = error.javaClass.simpleName)
                            state.value?.let { logProgress(it, force = true) }
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
                            state.value?.let { logProgress(it, force = true) }
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
            state.value?.let { logProgress(it, force = true) }
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
                ConfigurationExportPhase.ARCHIVING,
                ConfigurationExportPhase.VERIFYING
            )) return
        active?.let {
            state.value = state.value?.copy(phase = ConfigurationExportPhase.CANCELLING)
            state.value?.let { snapshot -> logProgress(snapshot, force = true) }
            it.cancel()
        }
    }

    @JvmStatic
    @Synchronized
    fun dismiss() {
        val current = state.value ?: return
        if (active != null && current.phase != ConfigurationExportPhase.UPLOADING) return
        state.value = current.copy(dismissed = true)
        logEvent("dismissed phase=${current.phase}")
    }

    @JvmStatic
    @Synchronized
    fun shareReady() {
        if (active != null) return
        val file = archive ?: return
        val current = state.value ?: return
        if (current.phase == ConfigurationExportPhase.WAITING_FOR_SHARE) return
        if (!file.isFile) {
            archive = null
            state.value = current.copy(phase = ConfigurationExportPhase.FAILED,
                archiveAvailable = false, detail = "Archive is missing")
            logEvent("android_share_failed detail=Archive is missing")
            return
        }
        state.value = current.copy(
            phase = ConfigurationExportPhase.WAITING_FOR_SHARE,
            detail = "Waiting for Android share chooser",
            endedAtElapsedMs = 0L,
            elapsedSeconds = configurationExportElapsedSeconds(current.copy(endedAtElapsedMs = 0L))
        )
        MainActivity.queueConfigurationShare(file, current.operationId)
        logProgress(state.value!!, force = true)
    }

    @JvmStatic
    @Synchronized
    fun androidShareLaunched(operationId: String): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId) return false
        val ended = SystemClock.elapsedRealtime()
        state.value = current.copy(
            phase = ConfigurationExportPhase.READY,
            detail = "Android share chooser opened",
            endedAtElapsedMs = ended,
            elapsedSeconds = configurationExportElapsedSeconds(current.copy(endedAtElapsedMs = ended))
        )
        logProgress(state.value!!, force = true)
        return true
    }

    @JvmStatic
    @Synchronized
    fun androidShareFailed(operationId: String, detail: String, archiveMissing: Boolean): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId) return false
        if (archiveMissing) archive = null
        val ended = SystemClock.elapsedRealtime()
        state.value = current.copy(
            phase = ConfigurationExportPhase.FAILED,
            archiveAvailable = if (archiveMissing) false else current.archiveAvailable,
            detail = detail,
            endedAtElapsedMs = ended,
            elapsedSeconds = configurationExportElapsedSeconds(current.copy(endedAtElapsedMs = ended))
        )
        logProgress(state.value!!, force = true)
        return true
    }

    @JvmStatic
    @Synchronized
    fun replaceCompletedForNewOperation() {
        if (active != null) return
        state.value = null
        archive = null
        eventContext = null
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        active?.cancel()
        state.value = null
        archive = null
        eventContext = null
    }

    private fun logProgress(snapshot: ConfigurationExportSnapshot, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val phaseChanged = snapshot.phase.name != lastLoggedPhase
        val fileChanged = snapshot.currentFile.isNotEmpty() && snapshot.currentFile != lastLoggedFile
        val countersDue = snapshot.copiedBytes - lastLoggedBytes >= 4L * 1024L * 1024L ||
            now - lastLoggedAtElapsedMs >= 1_000L
        if (!force && !phaseChanged && !fileChanged && !countersDue) return
        lastLoggedPhase = snapshot.phase.name
        if (snapshot.currentFile.isNotEmpty()) lastLoggedFile = snapshot.currentFile
        lastLoggedBytes = snapshot.copiedBytes
        lastLoggedAtElapsedMs = now
        logEvent(
            "phase=${snapshot.phase} file=${safeEvent(snapshot.currentFile)} " +
                "found=${snapshot.foundFiles} known_bytes=${snapshot.knownBytes} " +
                "copied_files=${snapshot.copiedFiles} total_files=${snapshot.totalFiles ?: -1} " +
                "copied_bytes=${snapshot.copiedBytes} total_bytes=${snapshot.totalBytes ?: -1} " +
                "unavailable=${snapshot.unavailableFiles} duration_ms=" +
                ((if (snapshot.endedAtElapsedMs > 0L) snapshot.endedAtElapsedMs else now) -
                    snapshot.startedAtElapsedMs).coerceAtLeast(0L) +
                " detail=${safeEvent(snapshot.detail)} event_id=${safeEvent(snapshot.eventId)}"
        )
    }

    private fun logEvent(detail: String) {
        val snapshot = state.value ?: return
        eventContext?.let {
            AppEventLogger.event(it, "configuration_export operation_id=${snapshot.operationId} $detail")
        }
    }

    private fun safeEvent(value: String): String = value.replace('\n', ' ').replace('\r', ' ')
}
