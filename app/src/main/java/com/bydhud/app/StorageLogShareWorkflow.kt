package com.bydhud.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors

enum class StorageLogSharePhase {
    WAITING_FOR_WRITES, COPYING, ARCHIVING, WAITING_FOR_SHARE, READY, UPLOADING, SENT,
    FAILED, CANCELLING, CANCELLED
}

data class StorageLogShareSnapshot(
    val operationId: String,
    val phase: StorageLogSharePhase,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val endedAtElapsedMs: Long = 0L,
    val foundFiles: Int = 0,
    val knownBytes: Long = 0L,
    val inventoryComplete: Boolean = false,
    val currentFile: String = "",
    val toDeveloper: Boolean = false,
    val detail: String = "",
    val eventId: String = "",
    val dismissed: Boolean = false
)

internal fun storageLogShareElapsedSeconds(
    state: StorageLogShareSnapshot,
    nowElapsedMs: Long = SystemClock.elapsedRealtime()
): Long = ((if (state.endedAtElapsedMs > 0L) state.endedAtElapsedMs else nowElapsedMs) -
    state.startedAtElapsedMs).coerceAtLeast(0L) / 1000L

/** Process-owned selected-day archive/upload; Activities only observe and issue scoped actions. */
object StorageLogShareWorkflow {
    private class Control {
        @Volatile var cancelled = false
        @Volatile var worker: Thread? = null

        fun cancel() {
            cancelled = true
            worker?.interrupt()
        }
    }

    private val state = MutableStateFlow<StorageLogShareSnapshot?>(null)
    val snapshot: StateFlow<StorageLogShareSnapshot?> = state.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "storage-log-share").apply { isDaemon = true }
    }
    private var active: Control? = null
    private var eventContext: Context? = null

    @JvmStatic
    @Synchronized
    fun start(
        context: Context,
        days: List<String>,
        toDeveloper: Boolean,
        selectedFileCount: Int,
        selectedBytes: Long
    ): Boolean {
        if (active != null || !MainActivity.claimShareOperation()) return false
        val app = context.applicationContext
        val submittedDays = days.toList()
        val operationId = if (toDeveloper) SentryLogUploader.newUploadId()
            else UUID.randomUUID().toString()
        val control = Control()
        VehicleConfigurationExport.replaceCompletedForNewOperation()
        active = control
        eventContext = app
        state.value = StorageLogShareSnapshot(
            operationId = operationId,
            phase = StorageLogSharePhase.WAITING_FOR_WRITES,
            startedAtEpochMs = System.currentTimeMillis(),
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
            foundFiles = selectedFileCount.coerceAtLeast(0),
            knownBytes = selectedBytes.coerceAtLeast(0L),
            inventoryComplete = true,
            toDeveloper = toDeveloper
        )
        log("started destination=${if (toDeveloper) "sentry" else "android"} " +
            "days=${submittedDays.joinToString(",")}")
        try {
            worker.execute {
                control.worker = Thread.currentThread()
                try {
                    if (control.cancelled) return@execute
                    LogShareZip.attachProgressListener { phase ->
                        transition(control, when (phase) {
                            LogShareZip.Phase.WAITING_FOR_WRITES -> StorageLogSharePhase.WAITING_FOR_WRITES
                            LogShareZip.Phase.COPYING -> StorageLogSharePhase.COPYING
                            LogShareZip.Phase.ARCHIVING -> StorageLogSharePhase.ARCHIVING
                        })
                    }
                    val archive = LogShareZip.create(
                        app,
                        submittedDays,
                        if (toDeveloper) operationId else ""
                    )
                    if (control.cancelled || Thread.currentThread().isInterrupted) {
                        LogShareZip.deleteArtifact(archive.file)
                        return@execute
                    }
                    if (!archive.ok || archive.file == null) {
                        finish(control, StorageLogSharePhase.FAILED, archive.detail)
                        return@execute
                    }
                    if (!toDeveloper) {
                        if (!admit(control, StorageLogSharePhase.WAITING_FOR_SHARE,
                                "${archive.file.name} ${archive.detail}".trim(),
                                archive.file.absolutePath)) {
                            LogShareZip.deleteArtifact(archive.file)
                            return@execute
                        }
                        if (!queueStorageShareIfOwned(control, archive.file, submittedDays)) {
                            LogShareZip.deleteArtifact(archive.file)
                        }
                        return@execute
                    }

                    if (!admit(control, StorageLogSharePhase.UPLOADING,
                            "${archive.file.name} ${archive.detail}".trim(),
                            archive.file.absolutePath)) {
                        LogShareZip.deleteArtifact(archive.file)
                        return@execute
                    }
                    val upload = SentryLogUploader.upload(
                        app, archive.file, submittedDays, operationId)
                    if (upload.ok) publishCompletionIfOwned(control, submittedDays)
                    finish(
                        control,
                        if (upload.ok) StorageLogSharePhase.SENT else StorageLogSharePhase.FAILED,
                        upload.detail,
                        upload.eventId
                    )
                } catch (error: Exception) {
                    if (!control.cancelled) {
                        finish(control, StorageLogSharePhase.FAILED,
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    }
                } finally {
                    LogShareZip.clearProgressListener()
                    synchronized(this) {
                        if (active === control) {
                            val ended = SystemClock.elapsedRealtime()
                            val current = state.value
                            state.value = if (control.cancelled && current != null) {
                                current.copy(
                                    phase = StorageLogSharePhase.CANCELLED,
                                    endedAtElapsedMs = ended,
                                    detail = ""
                                )
                            } else if (current?.phase == StorageLogSharePhase.WAITING_FOR_SHARE) {
                                current
                            } else current?.copy(endedAtElapsedMs = ended)
                            log("${if (state.value?.phase == StorageLogSharePhase.WAITING_FOR_SHARE) "queued" else "terminal"} " +
                                "phase=${state.value?.phase} duration_ms=" +
                                (ended - (state.value?.startedAtElapsedMs ?: ended)).coerceAtLeast(0L) +
                                " detail=${safe(state.value?.detail.orEmpty())} " +
                                "event_id=${safe(state.value?.eventId.orEmpty())}")
                            active = null
                            MainActivity.releaseShareOperation()
                            MainActivity.refreshAfterStorageShare(app,
                                if (toDeveloper) "sentry-share" else "share")
                        }
                    }
                    control.worker = null
                    Thread.interrupted()
                }
            }
        } catch (error: RuntimeException) {
            active = null
            MainActivity.releaseShareOperation()
            val ended = SystemClock.elapsedRealtime()
            state.value = state.value?.copy(
                phase = StorageLogSharePhase.FAILED,
                endedAtElapsedMs = ended,
                detail = error.javaClass.simpleName
            )
            log("terminal phase=FAILED detail=${error.javaClass.simpleName}")
            return false
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun cancel() {
        val current = state.value ?: return
        if (current.phase !in setOf(
                StorageLogSharePhase.WAITING_FOR_WRITES,
                StorageLogSharePhase.COPYING,
                StorageLogSharePhase.ARCHIVING
            )) return
        active?.let {
            state.value = current.copy(phase = StorageLogSharePhase.CANCELLING)
            log("phase=CANCELLING")
            it.cancel()
        }
    }

    @JvmStatic
    @Synchronized
    fun dismiss() {
        val current = state.value ?: return
        if (active != null && current.phase != StorageLogSharePhase.UPLOADING &&
            current.phase != StorageLogSharePhase.WAITING_FOR_SHARE) return
        state.value = current.copy(dismissed = true)
        log("dismissed phase=${current.phase}")
    }

    @JvmStatic
    @Synchronized
    fun replaceCompletedForNewOperation() {
        if (active != null) return
        state.value = null
        eventContext = null
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        active?.cancel()
        state.value = null
        eventContext = null
    }

    @JvmStatic
    @Synchronized
    fun androidShareLaunched(operationId: String): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId) return false
        state.value = current.copy(
            phase = StorageLogSharePhase.READY,
            detail = "Android share chooser opened",
            endedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        log("terminal phase=READY result=android_share_launched duration_ms=" +
            (state.value!!.endedAtElapsedMs - current.startedAtElapsedMs).coerceAtLeast(0L))
        return true
    }

    @JvmStatic
    @Synchronized
    fun androidShareFailed(operationId: String, detail: String): Boolean {
        val current = state.value ?: return false
        if (current.operationId != operationId) return false
        state.value = current.copy(
            phase = StorageLogSharePhase.FAILED,
            detail = detail,
            endedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        log("terminal phase=FAILED result=android_share_failed duration_ms=" +
            (state.value!!.endedAtElapsedMs - current.startedAtElapsedMs).coerceAtLeast(0L) +
            " detail=${safe(detail)}")
        return true
    }

    @Synchronized
    private fun transition(control: Control, phase: StorageLogSharePhase, detail: String = "") {
        admit(control, phase, detail)
    }

    @Synchronized
    private fun admit(
        control: Control,
        phase: StorageLogSharePhase,
        detail: String = "",
        currentFile: String = ""
    ): Boolean {
        val current = state.value ?: return false
        if (active !== control || control.cancelled) return false
        state.value = current.copy(
            phase = phase,
            detail = detail,
            currentFile = currentFile.ifEmpty { current.currentFile }
        )
        log("phase=$phase detail=${safe(detail)}")
        return true
    }

    @Synchronized
    private fun finish(
        control: Control,
        phase: StorageLogSharePhase,
        detail: String,
        eventId: String = ""
    ) {
        val current = state.value ?: return
        if (active !== control || control.cancelled) return
        state.value = current.copy(phase = phase, detail = detail, eventId = eventId)
    }

    @Synchronized
    private fun queueStorageShareIfOwned(
        control: Control,
        file: java.io.File,
        submittedDays: List<String>
    ): Boolean {
        if (active !== control || control.cancelled) return false
        MainActivity.queueStorageShare(file, submittedDays, state.value!!.operationId)
        return true
    }

    @Synchronized
    private fun publishCompletionIfOwned(control: Control, submittedDays: List<String>): Boolean {
        if (active !== control || control.cancelled) return false
        MainActivity.publishStorageShareCompletion(submittedDays)
        return true
    }

    private fun log(detail: String) {
        val current = state.value ?: return
        eventContext?.let {
            AppEventLogger.event(it,
                "storage_log_share operation_id=${current.operationId} $detail")
        }
    }

    private fun safe(value: String): String = value.replace('\n', ' ').replace('\r', ' ')
}
