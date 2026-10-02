package com.bydhud.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

enum class StorageLogSharePhase {
    WAITING_FOR_WRITES, COPYING, ARCHIVING, OVERSIZED, WAITING_FOR_SHARE, READY,
    UPLOADING, SENT, FAILED, CANCELLING, CANCELLED
}

data class StorageLogShareSnapshot(
    val operationId: String,
    val phase: StorageLogSharePhase,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val endedAtElapsedMs: Long = 0L,
    val foundFiles: Int = 0,
    val knownBytes: Long = 0L,
    val archiveBytes: Long = 0L,
    val inventoryComplete: Boolean = false,
    val currentFile: String = "",
    val selectedDays: List<String> = emptyList(),
    val selectionRevision: Int = 0,
    val toDeveloper: Boolean = false,
    val detail: String = "",
    val eventId: String = "",
    val reportTitle: String = "",
    val dismissed: Boolean = false,
    val collectionOutcome: String = "",
    val patchReportsIncomplete: Boolean = false
)

internal fun storageLogShareElapsedSeconds(
    state: StorageLogShareSnapshot,
    nowElapsedMs: Long = SystemClock.elapsedRealtime()
): Long = ((if (state.endedAtElapsedMs > 0L) state.endedAtElapsedMs else nowElapsedMs) -
    state.startedAtElapsedMs).coerceAtLeast(0L) / 1000L

internal fun storageLogShareTerminal(
    state: StorageLogShareSnapshot,
    phase: StorageLogSharePhase,
    detail: String,
    eventId: String,
    endedAtElapsedMs: Long
): StorageLogShareSnapshot = state.copy(
    phase = phase,
    detail = detail,
    eventId = eventId,
    endedAtElapsedMs = endedAtElapsedMs
)

internal fun updateStorageLogShareOperation(
    snapshots: List<StorageLogShareSnapshot>,
    operationId: String,
    transform: (StorageLogShareSnapshot) -> StorageLogShareSnapshot
): Pair<List<StorageLogShareSnapshot>, StorageLogShareSnapshot?> {
    val index = snapshots.indexOfFirst { it.operationId == operationId }
    if (index < 0) return snapshots to null
    val changed = transform(snapshots[index])
    return snapshots.toMutableList().apply { this[index] = changed } to changed
}

internal class StorageLogShareControl(val operationId: String) {
    @Volatile var cancelled = false
    @Volatile var worker: Thread? = null
    @Volatile var archive: File? = null

    fun cancelWorker() {
        cancelled = true
        worker?.interrupt()
    }
}

internal fun storageLogShareUploadMayPublish(
    control: StorageLogShareControl,
    submittedGeneration: Long,
    currentGeneration: Long,
    registeredControl: StorageLogShareControl?
): Boolean = !control.cancelled && submittedGeneration == currentGeneration &&
    registeredControl === control

/** Process-owned selected-day archive/uploads; Activities observe and issue ID-scoped actions. */
object StorageLogShareWorkflow {
    const val SENTRY_ZIP_LIMIT_BYTES = 39_000_000L

    private val state = MutableStateFlow<List<StorageLogShareSnapshot>>(emptyList())
    val snapshots: StateFlow<List<StorageLogShareSnapshot>> = state.asStateFlow()
    private val latestState = MutableStateFlow<StorageLogShareSnapshot?>(null)
    /** Compatibility view for callers which only support one regular share operation. */
    val snapshot: StateFlow<StorageLogShareSnapshot?> = latestState.asStateFlow()
    private val preparationWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "storage-log-prepare").apply { isDaemon = true }
    }
    private val uploadWorkers = Executors.newCachedThreadPool { task ->
        Thread(task, "sentry-log-upload").apply { isDaemon = true }
    }
    @Volatile private var activePreparation: StorageLogShareControl? = null
    private val restorationWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "share-reattach") }
    private val activeUploads = mutableMapOf<String, StorageLogShareControl>()
    private var generation = 0L
    private var eventContext: Context? = null
    private val reports = mutableMapOf<String, SentryLogReport>()

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun start(
        context: Context,
        days: List<String>,
        toDeveloper: Boolean,
        selectedFileCount: Int,
        selectedBytes: Long,
        report: SentryLogReport? = null,
        selectionRevision: Int = 0,
        restoredId: String? = null
    ): Boolean {
        if (toDeveloper && report == null) return false
        if (activePreparation != null || !MainActivity.claimShareOperation()) return false
        val app = context.applicationContext
        val submittedDays = days.toList()
        val restored = restoredId?.let { ShellRuntimeSession.prefs(app).getString("share_work_" + it, "") }
            ?.takeIf { it.isNotEmpty() }?.let { org.json.JSONObject(it) }
        val operationId = restoredId ?: if (toDeveloper) SentryLogUploader.newUploadId()
            else UUID.randomUUID().toString()
        val control = StorageLogShareControl(operationId)
        VehicleConfigurationExport.replaceCompletedForNewOperation()
        removeCompletedRegularShares()
        activePreparation = control
        eventContext = app
        if (report != null) reports[operationId] = report
        addSnapshot(StorageLogShareSnapshot(
            operationId = operationId,
            phase = restored?.optString("phase")?.let { StorageLogSharePhase.valueOf(it) } ?: StorageLogSharePhase.WAITING_FOR_WRITES,
            startedAtEpochMs = restored?.optLong("started") ?: System.currentTimeMillis(),
            startedAtElapsedMs = restored?.optLong("elapsed") ?: SystemClock.elapsedRealtime(),
            foundFiles = selectedFileCount.coerceAtLeast(0),
            knownBytes = selectedBytes.coerceAtLeast(0L),
            inventoryComplete = true,
            currentFile = restored?.optString("file").orEmpty(),
            archiveBytes = restored?.optLong("archiveBytes") ?: 0L,
            selectedDays = submittedDays,
            selectionRevision = selectionRevision,
            toDeveloper = toDeveloper,
            reportTitle = if (toDeveloper) report!!.title else ""
        ))
        log(operationId, "started destination=${if (toDeveloper) "sentry" else "android"} " +
            "days=${submittedDays.joinToString(",")}")
        return try {
            preparationWorker.execute {
                control.worker = Thread.currentThread()
                prepare(app, submittedDays, toDeveloper, report, control)
            }
            true
        } catch (error: RuntimeException) {
            finish(operationId, StorageLogSharePhase.FAILED, error.javaClass.simpleName)
            releasePreparation(control)
            false
        }
    }

    private fun prepare(
        app: Context,
        submittedDays: List<String>,
        toDeveloper: Boolean,
        report: SentryLogReport?,
        control: StorageLogShareControl
    ) {
        try {
            if (control.cancelled) return
            LogShareZip.attachProgressListener { phase ->
                transition(control, when (phase) {
                    LogShareZip.Phase.WAITING_FOR_WRITES -> StorageLogSharePhase.WAITING_FOR_WRITES
                    LogShareZip.Phase.COPYING -> StorageLogSharePhase.COPYING
                    LogShareZip.Phase.ARCHIVING -> StorageLogSharePhase.ARCHIVING
                })
            }
            val restored = find(control.operationId)
            if (restored?.phase == StorageLogSharePhase.CANCELLING) {
                control.cancelled = true
                if (ShellWorkClient.hasRequest(app, "log", control.operationId)) {
                    val job = ShellWorkClient.job(app, "log", control.operationId)
                    ShellWorkClient.cancel(job)
                    ShellWorkClient.await(app, job, null)
                }
                return
            }
            if (restored?.phase == StorageLogSharePhase.WAITING_FOR_SHARE && restored.currentFile.isNotEmpty()) {
                val file = File(restored.currentFile)
                if (!file.isFile) throw java.io.IOException("Retained archive is missing")
                control.archive = file
                MainActivity.queueStorageShare(file, submittedDays, control.operationId)
                return
            }
            if (toDeveloper && ShellWorkClient.hasRequest(app, "upload", control.operationId)) {
                val job = ShellWorkClient.job(app, "upload", control.operationId)
                val input = ShellWorkFiles.read(File(job, "request.json")).getJSONObject("input")
                val result = ShellWorkExports.upload(app, File(input.getString("archive")),
                    submittedDays, control.operationId, report!!)
                finish(control.operationId, if (result.ok) StorageLogSharePhase.SENT else StorageLogSharePhase.FAILED,
                    result.detail, result.eventId)
                return
            }
            val archive = LogShareZip.createIndependent(
                app, submittedDays, if (toDeveloper) control.operationId else "", control.operationId)
            update(control.operationId) { it.copy(
                collectionOutcome = archive.outcome.name,
                patchReportsIncomplete = archive.patchReportsIncomplete
            ) }
            control.archive = archive.file
            if (control.cancelled || Thread.currentThread().isInterrupted) {
                LogShareZip.deleteArtifact(archive.file)
                return
            }
            if (!archive.ok || archive.file == null) {
                finish(control.operationId, StorageLogSharePhase.FAILED, archive.detail)
                return
            }
            val detail = "${archive.file.name} ${archive.detail}".trim()
            if (!toDeveloper) {
                if (!transition(control, StorageLogSharePhase.WAITING_FOR_SHARE, detail,
                        archive.file.absolutePath, archive.file.length())) {
                    LogShareZip.deleteArtifact(archive.file)
                    return
                }
                MainActivity.queueStorageShare(archive.file, submittedDays, control.operationId)
                return
            }
            if (archive.file.length() >= SENTRY_ZIP_LIMIT_BYTES) {
                transition(control, StorageLogSharePhase.OVERSIZED, detail,
                    archive.file.absolutePath, archive.file.length())
                return
            }
            if (!transition(control, StorageLogSharePhase.UPLOADING, detail,
                    archive.file.absolutePath, archive.file.length())) {
                LogShareZip.deleteArtifact(archive.file)
                return
            }
            val uploadControl = StorageLogShareControl(control.operationId).apply {
                this.archive = archive.file
            }
            val uploadGeneration = registerUploadIfPreparationOwned(control, uploadControl)
            if (uploadGeneration == null) {
                LogShareZip.deleteArtifact(archive.file)
                return
            }
            try {
                uploadWorkers.execute {
                    uploadControl.worker = Thread.currentThread()
                    try {
                        if (uploadControl.cancelled) {
                            LogShareZip.deleteArtifact(archive.file)
                            return@execute
                        }
                        val upload = ShellWorkExports.upload(
                            app, archive.file, submittedDays, control.operationId, report!!)
                        publishUploadResultIfOwned(
                            uploadControl, uploadGeneration, upload, app)
                    } catch (error: Exception) {
                        publishUploadResultIfOwned(uploadControl, uploadGeneration,
                            SentryLogUploader.Result(false, "", error.toString()), app)
                    } finally {
                        synchronized(this) {
                            if (activeUploads[uploadControl.operationId] === uploadControl) {
                                activeUploads.remove(uploadControl.operationId)
                            }
                        }
                        uploadControl.worker = null
                        Thread.interrupted()
                    }
                }
            } catch (error: RuntimeException) {
                LogShareZip.deleteArtifact(archive.file)
                publishUploadResultIfOwned(
                    uploadControl,
                    uploadGeneration,
                    SentryLogUploader.Result(false, "", error.javaClass.simpleName),
                    app
                )
                synchronized(this) {
                    if (activeUploads[uploadControl.operationId] === uploadControl) {
                        activeUploads.remove(uploadControl.operationId)
                    }
                }
            }
        } catch (error: Exception) {
            if (!control.cancelled) {
                LogShareZip.deleteArtifact(control.archive)
                finish(control.operationId, StorageLogSharePhase.FAILED,
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        } finally {
            LogShareZip.clearProgressListener()
            control.worker = null
            Thread.interrupted()
            if (control.cancelled) {
                completeCancellation(control)
            } else if (find(control.operationId)?.phase != StorageLogSharePhase.OVERSIZED) {
                releasePreparation(control)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun cancel(operationId: String): Boolean {
        val current = find(operationId) ?: return false
        if (current.phase !in setOf(
                StorageLogSharePhase.WAITING_FOR_WRITES,
                StorageLogSharePhase.COPYING,
                StorageLogSharePhase.ARCHIVING,
                StorageLogSharePhase.OVERSIZED
            )) return false
        val control = activePreparation ?: return false
        if (control.operationId != operationId) return false
        if (current.phase == StorageLogSharePhase.OVERSIZED) {
            control.cancelled = true
            completeCancellation(control)
        } else {
            update(operationId) { it.copy(phase = StorageLogSharePhase.CANCELLING) }
            log(operationId, "phase=CANCELLING")
            control.cancelWorker()
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun cancel() {
        latestState.value?.let { cancel(it.operationId) }
    }

    @JvmStatic
    @Synchronized
    fun shareOversized(operationId: String): Boolean {
        val current = find(operationId) ?: return false
        val control = activePreparation ?: return false
        val archive = control.archive ?: return false
        if (control.operationId != operationId || current.phase != StorageLogSharePhase.OVERSIZED ||
            !archive.isFile) return false
        update(operationId) { it.copy(phase = StorageLogSharePhase.WAITING_FOR_SHARE) }
        MainActivity.queueStorageShare(archive, current.selectedDays, operationId)
        releasePreparation(control)
        return true
    }

    @JvmStatic
    @Synchronized
    fun dismiss(operationId: String): Boolean {
        val current = find(operationId) ?: return false
        if (current.phase in setOf(
                StorageLogSharePhase.WAITING_FOR_WRITES,
                StorageLogSharePhase.COPYING,
                StorageLogSharePhase.ARCHIVING,
                StorageLogSharePhase.CANCELLING,
                StorageLogSharePhase.OVERSIZED,
                StorageLogSharePhase.WAITING_FOR_SHARE
            )) return false
        update(operationId) { it.copy(dismissed = true) }
        log(operationId, "dismissed phase=${current.phase}")
        return true
    }

    @JvmStatic
    @Synchronized
    fun dismiss() {
        latestState.value?.let { dismiss(it.operationId) }
    }

    @JvmStatic
    @Synchronized
    fun replaceCompletedForNewOperation() {
        removeCompletedRegularShares()
    }

    @JvmStatic
    fun shutdown() {
        shutdownLocked()
    }

    @Synchronized
    private fun shutdownLocked() {
        generation += 1L
        activePreparation?.let {
            it.cancelWorker()
            releasePreparation(it)
        }
        activeUploads.values.toList().forEach {
            it.cancelWorker()
        }
        activeUploads.clear()
        eventContext?.let { app ->
            val prefs = ShellRuntimeSession.prefs(app)
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("share_work_") }.forEach { editor.remove(it) }
            editor.commit()
        }
        reports.clear()
        state.value = emptyList()
        latestState.value = null
        eventContext = null
    }

    @JvmStatic
    @Synchronized
    fun androidShareLaunched(operationId: String): Boolean {
        val current = find(operationId) ?: return false
        if (current.phase != StorageLogSharePhase.WAITING_FOR_SHARE) return false
        update(operationId) {
            it.copy(
                phase = StorageLogSharePhase.READY,
                detail = "android_share_chooser_opened",
                endedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }
        log(operationId, "terminal phase=READY result=android_share_launched")
        // Oversized Sentry shares are completed from their ID-scoped snapshot so an older
        // chooser cannot clear a newer selection through the legacy global share event.
        return !current.toDeveloper
    }

    @JvmStatic
    @Synchronized
    fun androidShareFailed(operationId: String, detail: String): Boolean {
        val current = find(operationId) ?: return false
        if (current.phase != StorageLogSharePhase.WAITING_FOR_SHARE) return false
        LogShareZip.deleteArtifact(current.currentFile.takeIf { it.isNotEmpty() }?.let(::File))
        finish(operationId, StorageLogSharePhase.FAILED, detail)
        return true
    }

    @Synchronized
    private fun transition(
        control: StorageLogShareControl,
        phase: StorageLogSharePhase,
        detail: String = "",
        currentFile: String = "",
        archiveBytes: Long = 0L
    ): Boolean {
        if (activePreparation !== control || control.cancelled) return false
        val changed = update(control.operationId) {
            it.copy(
                phase = phase,
                detail = detail,
                currentFile = currentFile.ifEmpty { it.currentFile },
                archiveBytes = if (archiveBytes > 0L) archiveBytes else it.archiveBytes
            )
        } ?: return false
        log(control.operationId, "phase=$phase archive_bytes=${changed.archiveBytes}")
        return true
    }

    @Synchronized
    private fun finish(
        operationId: String,
        phase: StorageLogSharePhase,
        detail: String,
        eventId: String = ""
    ): Boolean {
        val ended = SystemClock.elapsedRealtime()
        val changed = update(operationId) {
            storageLogShareTerminal(it, phase, detail, eventId, ended)
        } ?: return false
        log(operationId, "terminal phase=$phase duration_ms=" +
            (ended - changed.startedAtElapsedMs).coerceAtLeast(0L) +
            " detail=${safe(detail)} event_id=${safe(eventId)}")
        return true
    }

    @Synchronized
    private fun releasePreparation(control: StorageLogShareControl) {
        if (activePreparation !== control) return
        activePreparation = null
        MainActivity.releaseShareOperation()
    }

    @Synchronized
    private fun completeCancellation(control: StorageLogShareControl) {
        val current = find(control.operationId)
        if (current != null && current.phase != StorageLogSharePhase.CANCELLED) {
            LogShareZip.deleteArtifact(control.archive)
            finish(control.operationId, StorageLogSharePhase.CANCELLED, "")
            eventContext?.let { MainActivity.refreshAfterStorageShare(it, "share-cancel") }
        }
        releasePreparation(control)
    }

    @Synchronized
    private fun addSnapshot(snapshot: StorageLogShareSnapshot) {
        state.value = state.value.filterNot { it.operationId == snapshot.operationId } + snapshot
        latestState.value = snapshot
        persist(snapshot)
    }

    @Synchronized
    private fun registerUploadIfPreparationOwned(
        preparation: StorageLogShareControl,
        upload: StorageLogShareControl
    ): Long? {
        if (activePreparation !== preparation || preparation.cancelled) return null
        activeUploads[preparation.operationId] = upload
        return generation
    }

    @Synchronized
    private fun publishUploadResultIfOwned(
        control: StorageLogShareControl,
        submittedGeneration: Long,
        upload: SentryLogUploader.Result,
        app: Context
    ): Boolean {
        if (!storageLogShareUploadMayPublish(
                control, submittedGeneration, generation, activeUploads[control.operationId])) {
            return false
        }
        if (!finish(
                control.operationId,
                if (upload.ok) StorageLogSharePhase.SENT else StorageLogSharePhase.FAILED,
                upload.detail,
                upload.eventId
            )) return false
        MainActivity.refreshAfterStorageShare(app, "sentry-share")
        return true
    }

    @Synchronized
    private fun update(
        operationId: String,
        transform: (StorageLogShareSnapshot) -> StorageLogShareSnapshot
    ): StorageLogShareSnapshot? {
        val (updated, changed) = updateStorageLogShareOperation(
            state.value, operationId, transform)
        if (changed == null) return null
        state.value = updated
        if (latestState.value?.operationId == operationId) latestState.value = changed
        persist(changed)
        return changed
    }

    @Synchronized
    private fun find(operationId: String): StorageLogShareSnapshot? =
        state.value.firstOrNull { it.operationId == operationId }

    @Synchronized
    private fun removeCompletedRegularShares() {
        val before = state.value
        state.value = state.value.filter { snapshot ->
            snapshot.toDeveloper || snapshot.phase in setOf(
                StorageLogSharePhase.WAITING_FOR_WRITES,
                StorageLogSharePhase.COPYING,
                StorageLogSharePhase.ARCHIVING,
                StorageLogSharePhase.WAITING_FOR_SHARE
            )
        }
        for (old in before) if (state.value.none { it.operationId == old.operationId })
            eventContext?.let { ShellRuntimeSession.prefs(it).edit().remove("share_work_" + old.operationId).commit() }
        latestState.value = state.value.lastOrNull()
    }

    @JvmStatic
    fun restore(context: Context) {
        eventContext = context.applicationContext
        val saved = ShellRuntimeSession.prefs(context).all.filterKeys { it.startsWith("share_work_") }
        for ((_, raw) in saved) try {
            val value = org.json.JSONObject(raw.toString())
            val phase = StorageLogSharePhase.valueOf(value.getString("phase"))
            val id = value.getString("id")
            val days = ShellWorkEntryPoint.strings(value.getJSONArray("days"))
            val developer = value.optBoolean("developer")
            val report = if (developer) SentryLogReport(value.optString("comment"), value.optString("title")) else null
            if (phase in setOf(StorageLogSharePhase.WAITING_FOR_WRITES, StorageLogSharePhase.COPYING,
                    StorageLogSharePhase.ARCHIVING, StorageLogSharePhase.UPLOADING, StorageLogSharePhase.CANCELLING,
                    StorageLogSharePhase.OVERSIZED, StorageLogSharePhase.WAITING_FOR_SHARE)) {
                if (report != null) reports[id] = report
                addSnapshot(StorageLogShareSnapshot(id, phase, value.getLong("started"), value.getLong("elapsed"),
                    selectedDays = days, toDeveloper = developer, currentFile = value.optString("file"),
                    archiveBytes = value.optLong("archiveBytes"), reportTitle = value.optString("title"),
                    foundFiles = value.optInt("count"), knownBytes = value.optLong("bytes"),
                    selectionRevision = value.optInt("revision"), inventoryComplete = true))
                // A single preparation queue resumes the same retained jobs, never a new upload.
                restorationWorker.execute {
                    while (!HudPrefs.isUserShutdownActive(context)) {
                        if (start(context, days, developer, value.optInt("count"), value.optLong("bytes"),
                                report, value.optInt("revision"), id)) break
                        Thread.sleep(100)
                    }
                }
            } else {
                val snapshot = StorageLogShareSnapshot(id, phase, value.getLong("started"), value.getLong("elapsed"),
                    value.optLong("ended"), selectedDays = days, toDeveloper = developer,
                    currentFile = value.optString("file"), archiveBytes = value.optLong("archiveBytes"),
                    detail = value.optString("detail"), eventId = value.optString("event"),
                    dismissed = value.optBoolean("dismissed"), reportTitle = value.optString("title"),
                    selectionRevision = value.optInt("revision"), foundFiles = value.optInt("count"),
                    knownBytes = value.optLong("bytes"), inventoryComplete = true)
                if (report != null) reports[id] = report
                addSnapshot(snapshot)
            }
        } catch (error: Exception) { AppEventLogger.event(context, "log_share_restore_failed ${error.javaClass.simpleName}") }
    }

    private fun persist(current: StorageLogShareSnapshot) {
        val app = eventContext ?: return
        val value = org.json.JSONObject().put("id", current.operationId).put("phase", current.phase.name)
            .put("days", org.json.JSONArray(current.selectedDays)).put("developer", current.toDeveloper)
            .put("title", current.reportTitle).put("comment", reports[current.operationId]?.comment.orEmpty())
            .put("started", current.startedAtEpochMs).put("elapsed", current.startedAtElapsedMs)
            .put("ended", current.endedAtElapsedMs).put("count", current.foundFiles).put("bytes", current.knownBytes)
            .put("revision", current.selectionRevision).put("file", current.currentFile)
            .put("archiveBytes", current.archiveBytes).put("detail", current.detail).put("event", current.eventId)
            .put("dismissed", current.dismissed)
        ShellRuntimeSession.prefs(app).edit().putString("share_work_" + current.operationId, value.toString()).commit()
    }

    private fun log(operationId: String, detail: String) {
        eventContext?.let {
            AppEventLogger.event(it,
                "storage_log_share operation_id=$operationId ${safe(detail).take(240)}")
        }
    }

    private fun safe(value: String): String = value.replace('\n', ' ').replace('\r', ' ')
}
