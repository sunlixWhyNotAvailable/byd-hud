package com.bydhud.app

//checks GitHub releases so the app can offer updates without baking release metadata into the UI.

import android.app.DownloadManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

//defines AppUpdateManager UI/state support so Compose code can keep rendering intent explicit.
object AppUpdateManager {
    const val AUTO_CHECK_DELAY_MS = 30_000L
    private const val PREFS_NAME = "bydhud_update_prefs"
    private const val KEY_AUTO_CHECK = "auto_check_enabled"
    private const val KEY_BETA_CHANNEL = "beta_channel_enabled"
    internal const val SESSION_REFRESH_AGE_MS = 60 * 60 * 1000L
    private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
    private const val EXPECTED_PACKAGE_NAME = "com.bydhud.app"
    private const val RELEASE_API_HOST = "api.github.com"
    private const val APK_DOWNLOAD_HOST = "github.com"
    private const val RELEASE_PATH_MARKER = "/sunlixWhyNotAvailable/byd-hud/releases/download/"
    private const val RELEASE_NOTES_EN_OPEN = "<!-- bydhud:release-notes:en -->"
    private const val RELEASE_NOTES_EN_CLOSE = "<!-- /bydhud:release-notes:en -->"
    private const val RELEASE_NOTES_UK_OPEN = "<!-- bydhud:release-notes:uk -->"
    private const val RELEASE_NOTES_UK_CLOSE = "<!-- /bydhud:release-notes:uk -->"
    private const val RELEASE_NOTES_RU_OPEN = "<!-- bydhud:release-notes:ru -->"
    private const val RELEASE_NOTES_RU_CLOSE = "<!-- /bydhud:release-notes:ru -->"
    private val RELEASE_NOTES_MARKERS = setOf(
        RELEASE_NOTES_EN_OPEN,
        RELEASE_NOTES_EN_CLOSE,
        RELEASE_NOTES_UK_OPEN,
        RELEASE_NOTES_UK_CLOSE,
        RELEASE_NOTES_RU_OPEN,
        RELEASE_NOTES_RU_CLOSE
    )
    private val GIT_TAG_PATTERN = Regex("""^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.(0|[1-9]\d*))?$""")
    private val ANDROID_VERSION_PATTERN = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.(0|[1-9]\d*))?$""")

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val beta: Int?
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
            compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
            return when {
                beta == null && other.beta != null -> 1
                beta != null && other.beta == null -> -1
                else -> compareValues(beta ?: 0, other.beta ?: 0)
            }
        }

        fun androidName(): String {
            return "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")
        }
    }

    //defines UpdateInfo UI/state support so Compose code can keep rendering intent explicit.
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    fun releaseNotesForLanguage(body: String, languageCode: String): String {
        val requested = when (languageCode.lowercase()) {
            "uk", "ua" -> releaseNotesBlock(body, RELEASE_NOTES_UK_OPEN, RELEASE_NOTES_UK_CLOSE)
            "ru" -> releaseNotesBlock(body, RELEASE_NOTES_RU_OPEN, RELEASE_NOTES_RU_CLOSE)
            else -> releaseNotesBlock(body, RELEASE_NOTES_EN_OPEN, RELEASE_NOTES_EN_CLOSE)
        }
        return requested
            ?: releaseNotesBlock(body, RELEASE_NOTES_EN_OPEN, RELEASE_NOTES_EN_CLOSE)
            ?: body
    }

    fun releaseNotesForLanguage(body: String, uaLanguage: Boolean): String =
        releaseNotesForLanguage(body, if (uaLanguage) "uk" else "en")

    private fun releaseNotesBlock(body: String, open: String, close: String): String? {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').lines()
        val opens = lines.indices.filter { lines[it] == open }
        val closes = lines.indices.filter { lines[it] == close }
        if (opens.size != 1 || closes.size != 1 || closes[0] <= opens[0]) return null
        val content = lines.subList(opens[0] + 1, closes[0])
        if (content.any { it in RELEASE_NOTES_MARKERS }) return null
        return content.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    //defines CheckResult UI/state support so Compose code can keep rendering intent explicit.
    sealed class CheckResult {
        //defines UpToDate UI/state support so Compose code can keep rendering intent explicit.
        data object UpToDate : CheckResult()
        //defines Available UI/state support so Compose code can keep rendering intent explicit.
        data class Available(val info: UpdateInfo) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    data class Snapshot(
        val result: CheckResult? = null,
        val checking: Boolean = false,
        val dialogRequested: Boolean = false,
        val resultId: Long = 0L
    )

    private val session = UpdateSession(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        SystemClock::elapsedRealtime,
        ::fetchUpdate,
        event = { detail -> appContext?.let { AppEventLogger.event(it, "update_check $detail") } }
    )

    @Volatile private var appContext: Context? = null
    private val wakePolicy = UpdateWakePolicy()
    private var wakeReceiver: BroadcastReceiver? = null
    private val fetchMutex = Mutex()

    /** Register only after runtime/user admission, never on coordinator-only process startup. */
    @Synchronized private fun observeWake(app: Context) {
        if (wakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    wakePolicy.onSleep()
                    session.pauseForSleep()
                } else onRuntimeWake(context, intent.action ?: "")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        wakeReceiver = receiver
    }

    @JvmStatic
    fun onRuntimeWake(context: Context, action: String) {
        val app = context.applicationContext
        if (HudPrefs.isUserShutdownActive(app) || !HudPrefs.isBootEnabled(app)) return
        initialize(app)
        observeWake(app)
        if (!wakePolicy.onWake(action, SystemClock.elapsedRealtime())) return
        AppEventLogger.event(app, "update_check wake action=$action")
        session.wake(isAutoCheckEnabled(app), isBetaChannelEnabled(app))
    }

    val snapshot: StateFlow<Snapshot> = session.snapshot

    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var operationController: AppUpdateOperationController? = null

    internal val operationSnapshot: StateFlow<AppUpdateOperationSnapshot?>
        get() = checkNotNull(operationController) { "AppUpdateManager.initialize must run first" }.snapshot

    /** Main-process startup hook: recover only updater-owned work and never resume a download. */
    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (operationController != null) return
        synchronized(this) {
            if (operationController != null) return
            val app = context.applicationContext
            operationController = AppUpdateOperationController(
                operationScope,
                AndroidUpdateOperationDriver(app),
                AppUpdateOperationIds.processSeed()
            )
                .also { it.recover() }
        }
    }

    /** Called only by admitted runtime startup or a user-open entry, never by a heartbeat. */
    @JvmStatic
    fun onSessionEntry(context: Context) {
        val app = context.applicationContext
        initialize(app)
        observeWake(app)
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (wakePolicy.onEntry(SystemClock.elapsedRealtime(), interactive)) {
            session.wake(isAutoCheckEnabled(app), isBetaChannelEnabled(app))
        } else session.enter(isAutoCheckEnabled(app), isBetaChannelEnabled(app))
    }

    @JvmStatic
    fun requestManualCheck(context: Context) {
        initialize(context)
        session.requestManual(isBetaChannelEnabled(context.applicationContext), isAutoCheckEnabled(context))
    }

    @JvmStatic
    fun dismissResult() {
        operationController?.dismissTerminalFailure()
        val resultId = session.dismiss()
        if (resultId != 0L) UpdateHintManager.onResultInvalidated(resultId, "offer-dismissed")
    }

    /** Reopens only the retained offer represented by this process-local identity; never fetches. */
    @JvmStatic
    fun showRetainedOffer(resultId: Long): Boolean = session.showRetainedOffer(resultId)

    @JvmStatic
    fun resetForShutdown() {
        session.reset()
        synchronized(this) {
            wakeReceiver?.let { receiver -> appContext?.unregisterReceiver(receiver) }
            wakeReceiver = null
            wakePolicy.reset()
        }
        operationController?.shutdown()
    }

    @JvmStatic
    fun startDownload(context: Context, update: UpdateInfo): Long {
        initialize(context)
        return checkNotNull(operationController).start(update)
    }

    @JvmStatic
    fun retryReadyInstall(): Boolean = operationController?.retryInstall() == true

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    fun isAutoCheckEnabled(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, true)
    }

    //keeps this Compose helper focused so UI state changes remain easy to audit.
    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK, enabled)
            .apply()
        if (enabled) onSessionEntry(app) else session.disableAutomatic()
    }

    fun isBetaChannelEnabled(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_CHANNEL, false)
    }

    //Channel selection invalidates old work/results but never starts a request by itself.
    fun setBetaChannelEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BETA_CHANNEL, false) == enabled) {
            return
        }
        prefs.edit()
            .putBoolean(KEY_BETA_CHANNEL, enabled)
            .apply()
        session.changeChannel(enabled)
    }

    //No Context or UI is retained by the process-owned request.
    private suspend fun fetchUpdate(betaChannel: Boolean): CheckResult = withContext(Dispatchers.IO) {
        fetchMutex.withLock {
            currentCoroutineContext().ensureActive()
            val release = if (betaChannel) {
                selectLatestRelease(fetchReleaseListJson())
            } else {
                selectStableRelease(fetchLatestReleaseJson())
            }
            currentCoroutineContext().ensureActive()
            val remoteVersion = parseGitTag(release.optString("tag_name", "")).androidName()
            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                return@withLock CheckResult.UpToDate
            }
            CheckResult.Available(
                UpdateInfo(
                    version = remoteVersion,
                    downloadUrl = findApkAssetUrl(release),
                    releaseNotes = release.optString("body", "")
                )
            )
        }
    }

    /** Small process-session controller; injected clock/delay/fetch make its lifecycle deterministic. */
    internal class UpdateSession(
        private val scope: CoroutineScope,
        private val elapsedMs: () -> Long,
        private val fetch: suspend (Boolean) -> CheckResult,
        private val waitBeforeCheck: suspend (Long) -> Unit = { delay(it) },
        private val event: (String) -> Unit = {}
    ) {
        private class Request(val generation: Long, var manual: Boolean) {
            var dismissed = false
            var job: Job? = null
        }

        private val lock = Any()
        private val state = MutableStateFlow(Snapshot())
        val snapshot: StateFlow<Snapshot> = state.asStateFlow()
        private var channel: Boolean? = null
        private var generation = 0L
        private var nextResultId = 0L
        private var scheduled: Job? = null
        private var active: Request? = null
        private var lastCompletedAt: Long? = null
        private var automatic = false
        private var sleeping = false
        private var failures = 0

        fun enter(automaticEnabled: Boolean, betaChannel: Boolean) = synchronized(lock) {
            changeChannelLocked(betaChannel)
            automatic = automaticEnabled
            if (!automaticEnabled) {
                disableAutomaticLocked()
                return@synchronized
            }
            if (sleeping || scheduled != null || active != null) return@synchronized
            if (state.value.dialogRequested && state.value.result !is CheckResult.Error) return@synchronized
            val completed = lastCompletedAt
            if (completed != null && elapsedMs() - completed < SESSION_REFRESH_AGE_MS) return@synchronized
            scheduleLocked(AUTO_CHECK_DELAY_MS, "entry")
        }

        fun wake(automaticEnabled: Boolean, betaChannel: Boolean) = synchronized(lock) {
            changeChannelLocked(betaChannel)
            sleeping = false
            automatic = automaticEnabled
            failures = 0
            if (!automatic) {
                disableAutomaticLocked()
                return@synchronized
            }
            cancelScheduledLocked()
            // A user-owned request already in progress supplies this wake's result.
            if (active?.manual == true) return@synchronized
            cancelAutomaticRequestLocked()
            scheduleLocked(AUTO_CHECK_DELAY_MS, "wake")
        }

        fun pauseForSleep() = synchronized(lock) {
            sleeping = true
            cancelScheduledLocked()
            cancelAutomaticRequestLocked()
        }

        private fun scheduleLocked(waitMs: Long, reason: String) {
            val ticket = ++generation
            event("scheduled reason=$reason delayMs=$waitMs generation=$ticket")
            val job = scope.launch(start = CoroutineStart.LAZY) {
                waitBeforeCheck(waitMs)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (generation == ticket) {
                        scheduled = null
                        startRequestLocked(manual = false)
                    }
                }
            }
            scheduled = job
            job.start()
        }

        fun requestManual(betaChannel: Boolean, automaticEnabled: Boolean = automatic) = synchronized(lock) {
            changeChannelLocked(betaChannel)
            automatic = automaticEnabled
            cancelScheduledLocked()
            val current = active
            if (current != null) {
                current.manual = true
                current.dismissed = false
                state.value = state.value.copy(dialogRequested = true)
            } else {
                startRequestLocked(manual = true)
            }
        }

        fun dismiss(): Long = synchronized(lock) {
            active?.dismissed = true
            state.value = state.value.copy(dialogRequested = false)
            state.value.resultId
        }

        fun showRetainedOffer(resultId: Long): Boolean = synchronized(lock) {
            val current = state.value
            if (resultId <= 0L || current.resultId != resultId || current.result !is CheckResult.Available) {
                return@synchronized false
            }
            state.value = current.copy(dialogRequested = true)
            true
        }

        fun disableAutomatic() = synchronized(lock) { disableAutomaticLocked() }

        fun changeChannel(betaChannel: Boolean) = synchronized(lock) { changeChannelLocked(betaChannel) }

        fun reset() = synchronized(lock) {
            clearLocked()
            channel = null
        }

        private fun changeChannelLocked(betaChannel: Boolean) {
            if (channel == betaChannel) return
            clearLocked()
            channel = betaChannel
        }

        private fun clearLocked() {
            ++generation
            scheduled?.cancel()
            scheduled = null
            val previous = active
            active = null
            previous?.job?.cancel()
            lastCompletedAt = null
            failures = 0
            sleeping = false
            state.value = Snapshot()
        }

        private fun cancelScheduledLocked() {
            if (scheduled == null) return
            ++generation
            scheduled?.cancel()
            scheduled = null
        }

        private fun disableAutomaticLocked() {
            automatic = false
            failures = 0
            cancelScheduledLocked()
            cancelAutomaticRequestLocked()
        }

        private fun cancelAutomaticRequestLocked() {
            val current = active
            if (current != null && !current.manual) {
                ++generation
                active = null
                current.job?.cancel()
                state.value = state.value.copy(checking = false)
            }
        }

        private fun startRequestLocked(manual: Boolean) {
            val request = Request(++generation, manual)
            val requestChannel = checkNotNull(channel)
            event("started generation=${request.generation} manual=$manual beta=$requestChannel")
            active = request
            state.value = state.value.copy(checking = true, dialogRequested = manual)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = try {
                        fetch(requestChannel)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        CheckResult.Error(error.message ?: "Update check failed")
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (active === request && generation == request.generation) {
                            active = null
                            if (result !is CheckResult.Error) {
                                lastCompletedAt = elapsedMs()
                                failures = 0
                            } else lastCompletedAt = null
                            state.value = Snapshot(
                                result = result,
                                checking = false,
                                dialogRequested = !request.dismissed && (request.manual || result is CheckResult.Available),
                                resultId = ++nextResultId
                            )
                            event("completed generation=${request.generation} resultId=$nextResultId outcome=${result.javaClass.simpleName}")
                            if (result is CheckResult.Error && automatic && !sleeping) {
                                val retryMs = when (failures++) {
                                    0 -> 30_000L
                                    1 -> 60_000L
                                    2 -> 120_000L
                                    else -> { failures = 3; 300_000L }
                                }
                                scheduleLocked(retryMs, "retry")
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (active === request) {
                            active = null
                            state.value = state.value.copy(checking = false, dialogRequested = false)
                        }
                    }
                    throw cancelled
                }
            }
            request.job = job
            job.start()
        }
    }

    /** Android boundary; the controller above remains the sole operation/admission owner. */
    private class AndroidUpdateOperationDriver(private val context: Context) : AppUpdateOperationDriver {
        private val stagingRoot = File(context.filesDir, "updates")
        private val environment = AppUpdateEnvironmentResolver(
            downloadRoot = { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) },
            downloadService = { context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager }
        )

        override suspend fun recover(): AppUpdateOperationSnapshot? = withContext(Dispatchers.IO) {
            val environment = environment.resolve()
            val store = store(environment)
            val recovery = recovery(environment, store)
            val record = recovery.recover() ?: return@withContext null
            val readyFile = store.readyFile(record)
            AppUpdateOperationSnapshot(
                id = record.operationId,
                update = UpdateInfo(record.version, "", ""),
                phase = AppUpdateOperationPhase.READY,
                progress = "100%",
                ready = AppUpdateReadyApk(
                    path = readyFile.absolutePath,
                    targetVersionCode = record.targetVersionCode,
                    exposed = record.phase == AppUpdateOwnershipPhase.EXPOSED
                )
            )
        }

        override suspend fun prepare(
            operationId: Long,
            update: UpdateInfo,
            onPreparing: () -> Unit,
            onProgress: (String) -> Unit
        ): AppUpdateReadyApk = withContext(Dispatchers.IO) {
            val environment = environment.resolve()
            val manager = environment.downloadService
            val downloadRoot = environment.downloadRoot
            val store = store(environment)
            val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val baseName = "op-$operationId-BYD-HUD-$safeVersion.apk"
            var record = AppUpdateOwnershipRecord(
                operationId = operationId,
                version = update.version,
                downloadId = -1L,
                downloadName = baseName,
                partName = "$baseName.part",
                readyName = baseName,
                targetVersionCode = -1L,
                phase = AppUpdateOwnershipPhase.ACTIVE
            )
            stagingRoot.mkdirs()
            downloadRoot.mkdirs()
            store.write(record)
            val download = store.downloadFile(record)
            val part = store.partFile(record)
            val ready = store.readyFile(record)
            listOf(download, part, ready).forEach { if (it.exists() && !it.delete()) throw IllegalStateException("Could not clear owned update file") }

            val request = DownloadManager.Request(Uri.parse(requireHttpsDownloadUrl(update.downloadUrl)))
                .setTitle("BYD HUD ${update.version}")
                .setDescription("BYD HUD update")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, record.downloadName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            val downloadId = manager.enqueue(request)
            record = record.copy(downloadId = downloadId)
            store.write(record)
            onProgress("0%")
            pollDownload(manager, downloadId, onProgress)
            currentCoroutineContext().ensureActive()
            onPreparing()
            if (!download.isFile) throw IllegalStateException("Downloaded APK not found")
            FileInputStream(download).use { input ->
                FileOutputStream(part).use { output -> input.copyTo(output) }
            }
            currentCoroutineContext().ensureActive()
            val targetVersionCode = validateDownloadedApk(context, part)
            if (ready.exists() && !ready.delete()) throw IllegalStateException("Could not replace owned update APK")
            if (!part.renameTo(ready)) throw IllegalStateException("Could not publish update APK")
            record = record.copy(
                targetVersionCode = targetVersionCode,
                phase = AppUpdateOwnershipPhase.READY
            )
            store.write(record)
            removeDownload(manager, downloadId)
            download.delete()
            AppUpdateReadyApk(ready.absolutePath, targetVersionCode)
        }

        override suspend fun handoff(
            operationId: Long,
            ready: AppUpdateReadyApk,
            onExposed: (AppUpdateReadyApk) -> Unit
        ) {
            val file = File(ready.path)
            val exposed = withContext(Dispatchers.IO) {
                val store = store(environment.resolve())
                val record = store.read(operationId)
                    ?: throw IllegalStateException("Update ownership missing")
                if (!file.isFile || file.canonicalFile != store.readyFile(record).canonicalFile) {
                    throw IllegalStateException("Downloaded APK not found")
                }
                val marked = record.copy(phase = AppUpdateOwnershipPhase.EXPOSED)
                store.write(marked) //Durable before FileProvider creates or grants a URI.
                ready.copy(exposed = true)
            }
            onExposed(exposed)
            withContext(Dispatchers.Main) { launchInstaller(context, file) }
        }

        override suspend fun cancelUnexposed(operationId: Long) = withContext(Dispatchers.IO) {
            val environment = environment.resolve()
            recovery(environment, store(environment)).cancelUnexposed(operationId)
        }

        override fun recordCleanupFailure(operationId: Long, error: Throwable) {
            runCatching {
                AppEventLogger.event(
                    context,
                    "app_update cleanup_failed operation_id=$operationId error=${error.javaClass.simpleName}"
                )
            }
        }

        private fun store(environment: AppUpdateEnvironment<DownloadManager>) =
            AppUpdateOwnershipStore(stagingRoot, environment.downloadRoot)

        private fun recovery(
            environment: AppUpdateEnvironment<DownloadManager>,
            store: AppUpdateOwnershipStore
        ) = AppUpdateOwnershipRecovery(
            store,
            ::installedVersionCode
        ) { downloadId -> removeDownload(environment.downloadService, downloadId) }

        private fun removeDownload(manager: DownloadManager, downloadId: Long) {
            if (downloadId >= 0L) runCatching { manager.remove(downloadId) }
        }

        @Suppress("DEPRECATION")
        private fun installedVersionCode(): Long = runCatching {
            context.packageManager.getPackageInfo(EXPECTED_PACKAGE_NAME, 0).longVersionCode
        }.getOrDefault(-1L)
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    internal fun isNewerVersion(remote: String, local: String): Boolean {
        return parseAndroidVersion(remote) > parseAndroidVersion(local)
    }

    //keeps update I/O here so network, file, and installer failures are handled in one path.
    private fun fetchLatestReleaseJson(): JSONObject {
        return JSONObject(fetchReleaseJson(BuildConfig.UPDATE_RELEASE_API_URL))
    }

    private fun fetchReleaseListJson(): JSONArray {
        return JSONArray(fetchReleaseJson(BuildConfig.UPDATE_RELEASES_API_URL))
    }

    private fun fetchReleaseJson(url: String): String {
        val releaseUrl = requireReleaseApiUrl(url)
        val connection = (URL(releaseUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", BuildConfig.UPDATE_USER_AGENT)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API HTTP $code")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun selectStableRelease(release: JSONObject): JSONObject {
        val version = parseGitTag(release.optString("tag_name", ""))
        if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false) || version.beta != null) {
            throw IllegalStateException("GitHub latest release is not stable")
        }
        return release
    }

    private fun selectLatestRelease(releases: JSONArray): JSONObject {
        var selected: JSONObject? = null
        var selectedVersion: SemanticVersion? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false)) {
                continue
            }
            val version = parseGitTagOrNull(release.optString("tag_name", "")) ?: continue
            if (selectedVersion == null || version > selectedVersion) {
                selected = release
                selectedVersion = version
            }
        }
        return selected ?: throw IllegalStateException("GitHub releases have no supported tags")
    }

    //guard release metadata fetches so app updates only trust the configured GitHub API host.
    private fun requireReleaseApiUrl(url: String): String {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("https", ignoreCase = true)
            || !uri.host.equals(RELEASE_API_HOST, ignoreCase = true)
        ) {
            throw IllegalStateException("GitHub release API host is not allowed")
        }
        return url
    }

    //guard update downloads so a release asset cannot downgrade transport security.
    private fun findApkAssetUrl(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: throw IllegalStateException("GitHub release has no assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (name.lowercase(Locale.US).endsWith(".apk") && url.isNotBlank()) {
                return requireHttpsDownloadUrl(url)
            }
        }
        throw IllegalStateException("GitHub release has no APK asset")
    }

    //guard update downloads so only HTTPS GitHub asset URLs reach DownloadManager.
    private fun requireHttpsDownloadUrl(url: String): String {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IllegalStateException("GitHub APK asset must use HTTPS")
        }
        if (!uri.host.equals(APK_DOWNLOAD_HOST, ignoreCase = true)
            || !uri.encodedPath.orEmpty().contains(RELEASE_PATH_MARKER)
        ) {
            throw IllegalStateException("GitHub APK asset host is not allowed")
        }
        return url
    }

    //keeps this Compose helper focused so UI state changes remain easy to audit.
    private suspend fun pollDownload(
        manager: DownloadManager,
        downloadId: Long,
        onProgress: (String) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var finished = false
        while (!finished) {
            if (SystemClock.elapsedRealtime() - startedAt > DOWNLOAD_TIMEOUT_MS) {
                throw IllegalStateException("Download timed out")
            }
            val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                ?: throw IllegalStateException("Download row missing")
            cursor.use {
                if (it.moveToFirst()) {
                    finished = handleDownloadRow(it, onProgress)
                } else {
                    throw IllegalStateException("Download row missing")
                }
            }
            if (!finished) {
                delay(500L)
            }
        }
    }

    //handles this branch here so source-specific edge cases stay out of the main flow.
    private suspend fun handleDownloadRow(cursor: Cursor, onProgress: (String) -> Unit): Boolean {
        return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_RUNNING -> {
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                if (total > 0L) {
                    emitProgress("${(downloaded * 100L / total).toInt()}%", onProgress)
                }
                false
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                emitProgress("100%", onProgress)
                true
            }
            DownloadManager.STATUS_FAILED -> {
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                throw IllegalStateException("Download failed: $reason")
            }
            DownloadManager.STATUS_PAUSED -> {
                emitProgress("paused", onProgress)
                false
            }
            else -> false
        }
    }

    //keeps this Compose helper focused so UI state changes remain easy to audit.
    private suspend fun emitProgress(progress: String, onProgress: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            onProgress(progress)
        }
    }

    //keeps installer launch separate from preparation; validation and exposure already completed.
    private fun launchInstaller(context: Context, file: File) {
        if (!file.exists()) {
            throw IllegalStateException("Downloaded APK not found")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    //keeps update I/O here so network, file, and installer failures are handled in one path.
    @Suppress("DEPRECATION")
    private fun validateDownloadedApk(context: Context, file: File): Long {
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: throw IllegalStateException("Downloaded APK cannot be inspected")
        if (info.packageName != EXPECTED_PACKAGE_NAME) {
            throw IllegalStateException("Downloaded APK package mismatch: ${info.packageName}")
        }
        if (info.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            throw IllegalStateException("Downloaded APK is not newer")
        }
        if (!hasSameSigningCertificate(context, info)) {
            throw IllegalStateException("Downloaded APK signature mismatch")
        }
        return info.longVersionCode
    }

    //guard app updates so only APKs signed like the installed app are installable.
    @Suppress("DEPRECATION")
    private fun hasSameSigningCertificate(context: Context, archiveInfo: PackageInfo): Boolean {
        val installedInfo = context.packageManager.getPackageInfo(
            EXPECTED_PACKAGE_NAME,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val installedSigners = signingCertificateSet(installedInfo)
        val archiveSigners = signingCertificateSet(archiveInfo)
        return installedSigners.isNotEmpty() && installedSigners == archiveSigners
    }

    //normalize signer bytes so PackageManager Signature instances compare by content.
    private fun signingCertificateSet(info: PackageInfo): Set<List<Byte>> {
        val signingInfo = info.signingInfo ?: return emptySet()
        return signingInfo.apkContentsSigners
            .map { signature -> signature.toByteArray().toList() }
            .toSet()
    }

    private fun parseGitTag(value: String): SemanticVersion {
        return parseSemanticVersion(value.trim(), GIT_TAG_PATTERN)
            ?: throw IllegalStateException("Unsupported GitHub release tag: $value")
    }

    private fun parseGitTagOrNull(value: String): SemanticVersion? {
        return parseSemanticVersion(value.trim(), GIT_TAG_PATTERN)
    }

    private fun parseAndroidVersion(value: String): SemanticVersion {
        return parseSemanticVersion(value.trim(), ANDROID_VERSION_PATTERN)
            ?: throw IllegalArgumentException("Unsupported Android version name: $value")
    }

    private fun parseSemanticVersion(value: String, pattern: Regex): SemanticVersion? {
        val match = pattern.matchEntire(value) ?: return null
        val betaText = match.groupValues[4]
        return SemanticVersion(
            major = match.groupValues[1].toIntOrNull() ?: return null,
            minor = match.groupValues[2].toIntOrNull() ?: return null,
            patch = match.groupValues[3].toIntOrNull() ?: return null,
            beta = if (betaText.isEmpty()) null else betaText.toIntOrNull() ?: return null
        )
    }
}
