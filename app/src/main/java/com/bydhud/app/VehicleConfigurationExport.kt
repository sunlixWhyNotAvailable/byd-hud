package com.bydhud.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

enum class ConfigurationExportPhase {
    INVENTORY, DIAGNOSTICS, COPYING, ARCHIVING, WAITING_FOR_SHARE, READY,
    FAILED, CANCELLING, CANCELLED, EXPIRED,
    WAITING_FOR_DESTINATION, SAVING_DOCUMENT, SAVE_RESULT
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
    val dismissed: Boolean = false,
    val destination: ArchiveExportDestination = ArchiveExportDestination.SHARE,
    val savedBytes: Long = 0L,
    val saveTotalBytes: Long = 0L,
    val saveStartedAtElapsedMs: Long = 0L,
    val saveSucceeded: Boolean? = null,
    val saveCancelled: Boolean = false,
    val autoOpenDestination: Boolean = false
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
    @JvmOverloads
    fun start(context: Context, restoredId: String? = null): Boolean =
        startInternal(context, ArchiveExportDestination.SHARE, autoOpen = false, restoredId = restoredId)

    @JvmStatic
    @Synchronized
    fun startWithDestination(context: Context, destination: ArchiveExportDestination): Boolean {
        if (destination == ArchiveExportDestination.DEVELOPER) return false
        return startInternal(context, destination, autoOpen = true, restoredId = null)
    }

    @Synchronized
    private fun startInternal(
        context: Context,
        destination: ArchiveExportDestination,
        autoOpen: Boolean,
        restoredId: String?
    ): Boolean {
        val current = state.value
        if (active != null || current?.phase in setOf(ConfigurationExportPhase.WAITING_FOR_DESTINATION,
                ConfigurationExportPhase.SAVING_DOCUMENT) || !MainActivity.claimShareOperation()) return false
        val control = VehicleConfigurationZip.Control()
        val app = context.applicationContext
        val operationId = restoredId ?: UUID.randomUUID().toString()
        val restored = if (restoredId == null) null else ShellRuntimeSession.prefs(app)
            .getString("configuration_work", "")?.takeIf { it.isNotEmpty() }?.let { org.json.JSONObject(it) }
        val startedElapsedMs = restored?.optLong("elapsed") ?: SystemClock.elapsedRealtime()
        val startedEpochMs = restored?.optLong("started") ?: System.currentTimeMillis()
        val savedDestination = restored?.optString("destination")?.let {
            runCatching { ArchiveExportDestination.valueOf(it) }.getOrNull()
        } ?: destination
        val shouldAutoOpen = restored?.optBoolean("autoOpenDestination", false) ?: autoOpen
        StorageLogShareWorkflow.replaceCompletedForNewOperation()
        active = control
        archives = emptyList()
        eventContext = app
        loggedPhases.clear()
        state.value = ConfigurationExportSnapshot(
            operationId = operationId,
            phase = restored?.optString("phase")?.let { ConfigurationExportPhase.valueOf(it) } ?: ConfigurationExportPhase.INVENTORY,
            startedAtEpochMs = startedEpochMs,
            startedAtElapsedMs = startedElapsedMs,
            destination = savedDestination,
            autoOpenDestination = shouldAutoOpen
        )
        persist()
        logEvent("started")
        try {
            worker.execute {
                control.worker = Thread.currentThread()
                if (restored?.optString("phase") == ConfigurationExportPhase.CANCELLING.name) {
                    runCatching { ShellWorkClient.cancel(app,
                            ShellWorkClient.job(app, "configuration", operationId)) }
                    control.cancel()
                }
                try {
                    ConfigurationExportArtifacts.checkBeforeExport(app)
                    control.check()
                    val result = ShellWorkExports.configuration(app, operationId, control) {
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
                        ConfigurationExportArtifacts.checkAsync(app)
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
                                detail = failureDetail(error))
                            logFailure(error)
                            state.value?.let { logProgress(it) }
                        }
                    }
                } finally {
                    synchronized(this) {
                        var openDestinationId: String? = null
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
                                if (!control.isCancelled && it.phase == ConfigurationExportPhase.READY &&
                                    it.autoOpenDestination) openDestinationId = it.operationId
                            }
                            active = null
                        }
                        MainActivity.releaseShareOperation()
                        openDestinationId?.let(::openSelectedDestination)
                    }
                    control.close()
                    Thread.interrupted()
                }
            }
        } catch (error: RuntimeException) {
            active = null
            MainActivity.releaseShareOperation()
            state.value = state.value!!.copy(phase = ConfigurationExportPhase.FAILED,
                detail = failureDetail(error),
                endedAtElapsedMs = SystemClock.elapsedRealtime())
            logFailure(error)
            state.value?.let { logProgress(it) }
            return false
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun cancel() {
        val current = state.value
        if (current?.phase == ConfigurationExportPhase.SAVING_DOCUMENT) {
            eventContext?.let { ArchiveDocumentSave.cancel(it, current.operationId) }
            return
        }
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
    fun saveAsReady(operationId: String): Boolean {
        artifactsChecked(System.currentTimeMillis())
        val current = state.value ?: return false
        if (current.operationId == operationId &&
            current.phase == ConfigurationExportPhase.WAITING_FOR_DESTINATION &&
            current.destination == ArchiveExportDestination.SAVE_AS) {
            val queued = MainActivity.queueArchiveDocumentSave(operationId, true,
                current.archiveName.removeSuffix(".001"))
            if (!queued) saveAsFailed(operationId, "Unable to restore destination picker")
            return queued
        }
        if (active != null || current.operationId != operationId || !current.archiveAvailable ||
            current.phase !in setOf(ConfigurationExportPhase.READY, ConfigurationExportPhase.SAVE_RESULT,
                ConfigurationExportPhase.FAILED)) return false
        val displayName = current.archiveName.removeSuffix(".001")
        state.value = current.copy(
            phase = ConfigurationExportPhase.WAITING_FOR_DESTINATION,
            destination = ArchiveExportDestination.SAVE_AS,
            autoOpenDestination = false,
            savedBytes = 0L,
            saveTotalBytes = 0L,
            saveStartedAtElapsedMs = 0L,
            saveSucceeded = null,
            saveCancelled = false,
            detail = ""
        )
        logEvent("save_as_picker_requested expiresAtMs=${current.expiresAtEpochMs}")
        val queued = MainActivity.queueArchiveDocumentSave(operationId, true, displayName)
        if (!queued) saveAsFailed(operationId, "Unable to open destination picker")
        return queued
    }

    @JvmStatic
    @Synchronized
    fun saveAsDestination(operationId: String, uri: Uri): Boolean {
        artifactsChecked(System.currentTimeMillis())
        val current = state.value ?: return false
        if (current.operationId != operationId || current.phase != ConfigurationExportPhase.WAITING_FOR_DESTINATION ||
            current.destination != ArchiveExportDestination.SAVE_AS || !current.archiveAvailable ||
            archives.isEmpty() || archives.any { !it.isFile }) return false
        val app = eventContext ?: run {
            saveAsFailed(operationId, "Workflow context unavailable")
            return false
        }
        val displayName = current.archiveName.removeSuffix(".001")
        state.value = current.copy(
            phase = ConfigurationExportPhase.SAVING_DOCUMENT,
            autoOpenDestination = false,
            savedBytes = 0L,
            saveTotalBytes = archives.sumOf { it.length() },
            saveStartedAtElapsedMs = 0L,
            saveSucceeded = null,
            saveCancelled = false,
            detail = ""
        )
        // Persist the accepted destination attempt before the controller can open the document.
        persist()
        val accepted = runCatching {
            ArchiveDocumentSave.request(app, operationId, uri, true, archives.toList(),
                displayName, current.expiresAtEpochMs)
        }.getOrDefault(false)
        if (!accepted) saveAsFailed(operationId, "Unable to start document copy")
        return accepted
    }

    @JvmStatic
    @Synchronized
    fun saveAsCancelled(operationId: String) {
        artifactsChecked(System.currentTimeMillis())
        val current = state.value ?: return
        if (current.operationId != operationId || current.phase != ConfigurationExportPhase.WAITING_FOR_DESTINATION) return
        state.value = current.copy(phase = ConfigurationExportPhase.READY, autoOpenDestination = false,
            savedBytes = 0L, saveTotalBytes = 0L, saveStartedAtElapsedMs = 0L,
            saveSucceeded = null, saveCancelled = false, detail = "")
        logEvent("save_as_picker_cancelled")
    }

    @JvmStatic
    @Synchronized
    fun saveAsFailed(operationId: String, detail: String): Boolean {
        artifactsChecked(System.currentTimeMillis())
        val current = state.value ?: return false
        if (current.operationId != operationId || !canFinishDocumentSave(current)) return false
        finishDocumentSave(current, success = false, cancelled = false, detail = detail)
        return true
    }

    @JvmStatic
    @Synchronized
    fun saveAsFailed(operationId: String, attemptToken: String, detail: String): Boolean {
        if (!ArchiveDocumentSave.isLatestAttempt(operationId, attemptToken)) return false
        return saveAsFailed(operationId, detail)
    }

    @JvmStatic
    @Synchronized
    private fun saveAsCleanupWarning(operationId: String, detail: String): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId || current.destination != ArchiveExportDestination.SAVE_AS ||
            detail.isBlank()) return false
        if (current.phase in setOf(ConfigurationExportPhase.WAITING_FOR_DESTINATION,
                ConfigurationExportPhase.SAVING_DOCUMENT, ConfigurationExportPhase.EXPIRED)) {
            finishDocumentSave(current, success = false, cancelled = false, detail = detail)
            return !current.dismissed
        }
        if (current.phase == ConfigurationExportPhase.SAVE_RESULT) {
            if (current.detail != detail) {
                state.value = current.copy(detail = detail)
                logEvent("document_save_cleanup_warning")
            }
            return !current.dismissed
        }
        return false
    }

    @JvmStatic
    @Synchronized
    fun saveAsCleanupWarning(operationId: String, attemptToken: String, detail: String): Boolean {
        if (!ArchiveDocumentSave.isLatestAttempt(operationId, attemptToken)) return false
        return saveAsCleanupWarning(operationId, detail)
    }

    @JvmStatic
    @Synchronized
    fun documentSaveStarted(operationId: String, totalBytes: Long) {
        val current = state.value ?: return
        if (current.operationId != operationId || current.phase != ConfigurationExportPhase.SAVING_DOCUMENT) return
        if (current.saveStartedAtElapsedMs > 0L) return
        state.value = current.copy(saveTotalBytes = totalBytes.coerceAtLeast(0L),
            saveStartedAtElapsedMs = SystemClock.elapsedRealtime())
        logEvent("document_save_started bytes=${totalBytes.coerceAtLeast(0L)}")
    }

    @JvmStatic
    @Synchronized
    fun documentSaveProgress(operationId: String, copiedBytes: Long, totalBytes: Long) {
        val current = state.value ?: return
        if (current.operationId != operationId || current.phase != ConfigurationExportPhase.SAVING_DOCUMENT) return
        state.value = current.copy(
            savedBytes = copiedBytes.coerceAtLeast(0L),
            saveTotalBytes = totalBytes.coerceAtLeast(0L)
        )
    }

    @JvmStatic
    @Synchronized
    fun documentSaveFinished(operationId: String, success: Boolean, cancelled: Boolean, detail: String) {
        val current = state.value ?: return
        if (current.operationId != operationId || !canFinishDocumentSave(current)) return
        finishDocumentSave(current, success, cancelled, detail)
    }

    private fun canFinishDocumentSave(current: ConfigurationExportSnapshot): Boolean =
        current.phase in setOf(ConfigurationExportPhase.WAITING_FOR_DESTINATION,
            ConfigurationExportPhase.SAVING_DOCUMENT) ||
            // Expiry can follow output close before the terminal callback arrives.
            (current.phase == ConfigurationExportPhase.EXPIRED &&
                current.destination == ArchiveExportDestination.SAVE_AS &&
                (current.saveStartedAtElapsedMs > 0L || current.saveTotalBytes > 0L ||
                    ArchiveDocumentSave.wasInterrupted(current.operationId)))

    private fun finishDocumentSave(
        current: ConfigurationExportSnapshot,
        success: Boolean,
        cancelled: Boolean,
        detail: String
    ) {
        val expired = current.expiresAtEpochMs > 0 && System.currentTimeMillis() >= current.expiresAtEpochMs
        if (expired) archives = emptyList()
        state.value = current.copy(
            phase = ConfigurationExportPhase.SAVE_RESULT,
            archiveAvailable = !expired && current.archiveAvailable && archives.all { it.isFile },
            savedBytes = if (success) current.saveTotalBytes else current.savedBytes,
            saveSucceeded = success,
            saveCancelled = cancelled,
            autoOpenDestination = false,
            detail = detail,
            endedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        val updated = state.value!!
        val duration = if (updated.saveStartedAtElapsedMs > 0L)
            ((updated.endedAtElapsedMs - updated.saveStartedAtElapsedMs).coerceAtLeast(0L) / 1000L) else 0L
        logEvent("document_save_result success=$success cancelled=$cancelled " +
            "bytes=${updated.savedBytes}/${updated.saveTotalBytes} duration_s=$duration")
    }

    @Synchronized
    private fun finishRestoredPickerInterruption(operationId: String, detail: String) {
        val current = state.value ?: return
        if (current.operationId == operationId &&
            current.destination == ArchiveExportDestination.SAVE_AS && current.phase in setOf(
                ConfigurationExportPhase.WAITING_FOR_DESTINATION,
                ConfigurationExportPhase.EXPIRED
            )) {
            finishDocumentSave(current, success = false, cancelled = false, detail = detail)
        }
    }

    @JvmStatic
    @Synchronized
    fun dismiss() {
        val current = state.value ?: return
        if (active != null || current.phase in setOf(ConfigurationExportPhase.WAITING_FOR_DESTINATION,
                ConfigurationExportPhase.SAVING_DOCUMENT)) return
        state.value = current.copy(dismissed = true)
        logEvent("dismissed phase=${current.phase}")
    }

    @JvmStatic
    @Synchronized
    fun artifactsChecked(nowEpochMs: Long) {
        val current = state.value ?: return
        if (current.expiresAtEpochMs <= 0 || nowEpochMs < current.expiresAtEpochMs) return
        if (current.phase == ConfigurationExportPhase.EXPIRED) return
        if (current.phase == ConfigurationExportPhase.SAVING_DOCUMENT &&
            ArchiveDocumentSave.isCopying(current.operationId)) return
        archives = emptyList()
        state.value = if (current.phase == ConfigurationExportPhase.SAVE_RESULT) {
            current.copy(archiveAvailable = false)
        } else current.copy(phase = ConfigurationExportPhase.EXPIRED, archiveAvailable = false)
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
        if (!current.archiveAvailable || current.phase in setOf(ConfigurationExportPhase.WAITING_FOR_SHARE,
                ConfigurationExportPhase.WAITING_FOR_DESTINATION, ConfigurationExportPhase.SAVING_DOCUMENT)) return
        state.value = current.copy(phase = ConfigurationExportPhase.WAITING_FOR_SHARE,
            destination = ArchiveExportDestination.SHARE, autoOpenDestination = false, detail = "")
        // No expiry renewal and no inferred delivery acknowledgement.
        MainActivity.queueConfigurationShare(archives, current.operationId)
        logEvent("share_attempt volumes=${archives.size} expiresAtMs=${current.expiresAtEpochMs}")
    }

    @Synchronized
    private fun openSelectedDestination(operationId: String) {
        val current = state.value ?: return
        if (current.operationId != operationId || !current.autoOpenDestination || !current.archiveAvailable) return
        when (current.destination) {
            ArchiveExportDestination.SHARE -> shareReady()
            ArchiveExportDestination.SAVE_AS -> saveAsReady(operationId)
            ArchiveExportDestination.DEVELOPER -> return
        }
    }

    @JvmStatic
    @Synchronized
    fun androidShareLaunched(operationId: String): Boolean {
        if (!canShare(operationId) || state.value?.phase != ConfigurationExportPhase.WAITING_FOR_SHARE) return false
        state.value = state.value!!.copy(phase = ConfigurationExportPhase.READY, detail = "")
        logEvent("share_chooser_opened")
        return true
    }

    @JvmStatic
    @Synchronized
    fun androidShareFailed(operationId: String, detail: String, archiveMissing: Boolean): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId || current.phase != ConfigurationExportPhase.WAITING_FOR_SHARE) return false
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
        val current = state.value
        if (active != null || current?.phase in setOf(ConfigurationExportPhase.WAITING_FOR_DESTINATION,
                ConfigurationExportPhase.SAVING_DOCUMENT)) return
        eventContext?.let { ShellRuntimeSession.prefs(it).edit().remove("configuration_work").commit() }
        state.value = null
        archives = emptyList()
        eventContext = null
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        val current = state.value
        if (current?.phase == ConfigurationExportPhase.SAVING_DOCUMENT) {
            eventContext?.let { ArchiveDocumentSave.cancel(it, current.operationId) }
        }
        eventContext?.let { ShellRuntimeSession.prefs(it).edit().remove("configuration_work").commit() }
        active?.cancel()
        state.value = null
        archives = emptyList()
        eventContext = null
    }

    @JvmStatic
    fun restore(context: Context) {
        val saved = ShellRuntimeSession.prefs(context).getString("configuration_work", "") ?: ""
        if (saved.isEmpty()) return
        try {
            val value = org.json.JSONObject(saved)
            val phase = ConfigurationExportPhase.valueOf(value.getString("phase"))
            if (phase in setOf(ConfigurationExportPhase.INVENTORY, ConfigurationExportPhase.DIAGNOSTICS,
                    ConfigurationExportPhase.COPYING, ConfigurationExportPhase.ARCHIVING, ConfigurationExportPhase.CANCELLING)) {
                start(context, value.getString("id"))
            } else {
                eventContext = context.applicationContext
                archives = ShellWorkEntryPoint.strings(value.getJSONArray("archives")).map(::File)
                val destination = value.optString("destination").let {
                    runCatching { ArchiveExportDestination.valueOf(it) }.getOrNull()
                } ?: ArchiveExportDestination.SHARE
                state.value = ConfigurationExportSnapshot(value.getString("id"), phase,
                    value.getLong("started"), value.getLong("elapsed"), value.optLong("ended"),
                    archiveAvailable = value.optBoolean("available") && archives.all { it.isFile },
                    archiveName = archives.firstOrNull()?.name.orEmpty(),
                    archiveBytes = archives.sumOf { it.length() }, volumeSizes = archives.map { it.length() },
                    completedAtEpochMs = value.optLong("completed"), expiresAtEpochMs = value.optLong("expires"),
                    detail = value.optString("detail"), dismissed = value.optBoolean("dismissed"),
                    destination = destination, savedBytes = value.optLong("savedBytes"),
                    saveTotalBytes = value.optLong("saveTotalBytes"),
                    saveStartedAtElapsedMs = value.optLong("saveStartedAtElapsed"),
                    saveSucceeded = if (value.has("saveSucceeded")) value.optBoolean("saveSucceeded") else null,
                    saveCancelled = value.optBoolean("saveCancelled"),
                    autoOpenDestination = value.optBoolean("autoOpenDestination", false))
                var restored = state.value
                if (restored?.phase == ConfigurationExportPhase.SAVING_DOCUMENT) {
                    if (ArchiveDocumentSave.wasSaved(restored.operationId)) {
                        documentSaveFinished(restored.operationId, success = true, cancelled = false, detail = "")
                    } else if (ArchiveDocumentSave.wasInterrupted(restored.operationId) ||
                        (!ArchiveDocumentSave.isCopying(restored.operationId) &&
                            !ArchiveDocumentSave.isRecovering(restored.operationId))) {
                        saveAsFailed(restored.operationId, ArchiveDocumentSave.recoveryDetail(restored.operationId))
                    }
                } else if (restored?.phase == ConfigurationExportPhase.WAITING_FOR_DESTINATION &&
                    ArchiveDocumentSave.wasInterrupted(restored.operationId)) {
                    finishRestoredPickerInterruption(restored.operationId,
                        ArchiveDocumentSave.recoveryDetail(restored.operationId))
                }
                artifactsChecked(System.currentTimeMillis())
                restored = state.value
                if (restored?.phase == ConfigurationExportPhase.WAITING_FOR_DESTINATION) {
                    saveAsReady(restored.operationId)
                } else if (restored?.phase == ConfigurationExportPhase.READY && restored.autoOpenDestination) {
                    openSelectedDestination(restored.operationId)
                }
            }
        } catch (error: Exception) { AppEventLogger.event(context, "configuration_restore_failed ${error.javaClass.simpleName}") }
    }

    private fun persist() {
        val app = eventContext ?: return
        val current = state.value ?: return
        val value = org.json.JSONObject().put("id", current.operationId).put("phase", current.phase.name)
            .put("started", current.startedAtEpochMs).put("elapsed", current.startedAtElapsedMs)
            .put("ended", current.endedAtElapsedMs).put("available", current.archiveAvailable)
            .put("completed", current.completedAtEpochMs).put("expires", current.expiresAtEpochMs)
            .put("detail", current.detail).put("dismissed", current.dismissed)
            .put("destination", current.destination.name).put("savedBytes", current.savedBytes)
            .put("saveTotalBytes", current.saveTotalBytes).put("saveStartedAtElapsed", current.saveStartedAtElapsedMs)
            .put("saveSucceeded", current.saveSucceeded)
            .put("saveCancelled", current.saveCancelled).put("autoOpenDestination", current.autoOpenDestination)
            .put("archives", org.json.JSONArray(archives.map { it.path }))
        ShellRuntimeSession.prefs(app).edit().putString("configuration_work", value.toString()).commit()
    }

    private fun failureDetail(error: Exception): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1024)

    private fun logFailure(error: Exception) {
        val detail = android.util.Log.getStackTraceString(error).take(2048)
            .replace('\n', ' ').replace('\r', ' ')
        logEvent("failure=$detail")
    }

    private fun logProgress(snapshot: ConfigurationExportSnapshot) {
        // COPYING/ARCHIVING alternate per file: log each phase once, never per file or chunk.
        if (!loggedPhases.add(snapshot.phase.name)) return
        logEvent("phase=${snapshot.phase} inventory=${snapshot.foundFiles} collected=${snapshot.copiedFiles} " +
            "unavailable=${snapshot.unavailableFiles} volumes=${snapshot.volumeSizes.size} bytes=${snapshot.archiveBytes} " +
            "createdAtMs=${snapshot.completedAtEpochMs} expiresAtMs=${snapshot.expiresAtEpochMs}")
    }

    private fun logEvent(detail: String) {
        persist()
        val snapshot = state.value ?: return
        eventContext?.let {
            AppEventLogger.event(it, "configuration_export operation_id=${snapshot.operationId} $detail")
        }
    }

}
