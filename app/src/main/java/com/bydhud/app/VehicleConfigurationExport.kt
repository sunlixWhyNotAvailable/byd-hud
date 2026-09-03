package com.bydhud.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

enum class ConfigurationExportPhase {
    INVENTORY, DIAGNOSTICS, COPYING, ARCHIVING, VERIFYING, READY, UPLOADING,
    SENT, FAILED, CANCELLING, CANCELLED
}

data class ConfigurationExportSnapshot(
    val phase: ConfigurationExportPhase,
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
    val eventId: String = ""
)

/** One explicit export per process. Activities only observe it; no Activity is kept by the worker. */
object VehicleConfigurationExport {
    private val state = MutableStateFlow<ConfigurationExportSnapshot?>(null)
    val snapshot: StateFlow<ConfigurationExportSnapshot?> = state.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vehicle-configuration-export").apply { isDaemon = true }
    }
    private var active: VehicleConfigurationZip.Control? = null
    private var archive: File? = null

    @JvmStatic
    @Synchronized
    fun start(context: Context, toDeveloper: Boolean): Boolean {
        if (active != null || !MainActivity.claimShareOperation()) return false
        val control = VehicleConfigurationZip.Control()
        val app = context.applicationContext
        val started = System.nanoTime()
        active = control
        archive = null
        state.value = ConfigurationExportSnapshot(ConfigurationExportPhase.INVENTORY,
            toDeveloper = toDeveloper)
        try {
            worker.execute {
                control.worker = Thread.currentThread()
                try {
                    val result = VehicleConfigurationZip.createFull(app, control) {
                            phase, file, bytes, total, files, count, unavailable ->
                        synchronized(this) {
                            if (active === control && !control.isCancelled) {
                                state.value = ConfigurationExportSnapshot(
                                    phase = ConfigurationExportPhase.valueOf(phase),
                                    copiedBytes = bytes,
                                    totalBytes = total.takeIf { it >= 0 },
                                    copiedFiles = files,
                                    totalFiles = count.takeIf { it >= 0 },
                                    unavailableFiles = unavailable,
                                    currentFile = file,
                                    elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L,
                                    toDeveloper = toDeveloper
                                )
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
                    }
                    val uploadFile = synchronized(this) {
                        archive?.takeIf {
                            active === control && !control.isCancelled && toDeveloper &&
                                it.length() <= SentryLogUploader.MAX_ZIP_BYTES
                        }?.also {
                            state.value = state.value!!.copy(phase = ConfigurationExportPhase.UPLOADING)
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
                            }
                        }
                    }
                } catch (error: Exception) {
                    synchronized(this) {
                        if (active === control && !control.isCancelled) {
                            state.value = state.value?.copy(phase = ConfigurationExportPhase.FAILED,
                                detail = error.javaClass.simpleName)
                        }
                    }
                } finally {
                    synchronized(this) {
                        if (active === control) {
                            if (control.isCancelled && state.value != null) {
                                state.value = state.value!!.copy(phase = ConfigurationExportPhase.CANCELLED,
                                    archiveAvailable = false, currentFile = "", detail = "")
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
                detail = error.javaClass.simpleName)
            return false
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun cancel() {
        if (state.value?.phase == ConfigurationExportPhase.UPLOADING) return
        active?.let {
            state.value = state.value?.copy(phase = ConfigurationExportPhase.CANCELLING)
            it.cancel()
        }
    }

    @JvmStatic
    @Synchronized
    fun dismiss() {
        if (active != null) return
        state.value = null
        archive = null // Completed ZIP remains in the existing share cache for Android's recipient.
    }

    @JvmStatic
    @Synchronized
    fun shareReady() {
        if (active != null) return
        val file = archive ?: return
        if (!file.isFile) {
            state.value = state.value?.copy(phase = ConfigurationExportPhase.FAILED,
                archiveAvailable = false, detail = "Archive is missing")
            return
        }
        MainActivity.queueConfigurationShare(file)
        dismiss()
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        active?.cancel()
        state.value = null
        archive = null
    }
}
