package com.bydhud.app

//checks GitHub releases so the app can offer updates without baking release metadata into the UI.

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private val RELEASE_NOTES_MARKERS = setOf(
        RELEASE_NOTES_EN_OPEN,
        RELEASE_NOTES_EN_CLOSE,
        RELEASE_NOTES_UK_OPEN,
        RELEASE_NOTES_UK_CLOSE
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

    fun releaseNotesForLanguage(body: String, uaLanguage: Boolean): String {
        val requested = if (uaLanguage) {
            releaseNotesBlock(body, RELEASE_NOTES_UK_OPEN, RELEASE_NOTES_UK_CLOSE)
        } else {
            releaseNotesBlock(body, RELEASE_NOTES_EN_OPEN, RELEASE_NOTES_EN_CLOSE)
        }
        return requested
            ?: releaseNotesBlock(body, RELEASE_NOTES_EN_OPEN, RELEASE_NOTES_EN_CLOSE)
            ?: body
    }

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
        val dialogRequested: Boolean = false
    )

    private val session = UpdateSession(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        SystemClock::elapsedRealtime,
        ::fetchUpdate
    )

    val snapshot: StateFlow<Snapshot> = session.snapshot

    /** Called only by admitted runtime startup or a user-open entry, never by a heartbeat. */
    @JvmStatic
    fun onSessionEntry(context: Context) {
        val app = context.applicationContext
        session.enter(isAutoCheckEnabled(app), isBetaChannelEnabled(app))
    }

    @JvmStatic
    fun requestManualCheck(context: Context) {
        session.requestManual(isBetaChannelEnabled(context.applicationContext))
    }

    @JvmStatic
    fun dismissResult() = session.dismiss()

    @JvmStatic
    fun resetForShutdown() = session.reset()

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
        currentCoroutineContext().ensureActive()
        val release = if (betaChannel) {
            selectLatestRelease(fetchReleaseListJson())
        } else {
            selectStableRelease(fetchLatestReleaseJson())
        }
        currentCoroutineContext().ensureActive()
        val remoteVersion = parseGitTag(release.optString("tag_name", "")).androidName()
        if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
            return@withContext CheckResult.UpToDate
        }
        CheckResult.Available(
            UpdateInfo(
                version = remoteVersion,
                downloadUrl = findApkAssetUrl(release),
                releaseNotes = release.optString("body", "")
            )
        )
    }

    /** Small process-session controller; injected clock/delay/fetch make its lifecycle deterministic. */
    internal class UpdateSession(
        private val scope: CoroutineScope,
        private val elapsedMs: () -> Long,
        private val fetch: suspend (Boolean) -> CheckResult,
        private val waitBeforeCheck: suspend (Long) -> Unit = { delay(it) }
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
        private var scheduled: Job? = null
        private var active: Request? = null
        private var lastCompletedAt: Long? = null

        fun enter(automaticEnabled: Boolean, betaChannel: Boolean) = synchronized(lock) {
            changeChannelLocked(betaChannel)
            if (!automaticEnabled) {
                disableAutomaticLocked()
                return@synchronized
            }
            if (scheduled != null || active != null || state.value.dialogRequested) return@synchronized
            val completed = lastCompletedAt
            if (completed != null && elapsedMs() - completed < SESSION_REFRESH_AGE_MS) return@synchronized
            val ticket = ++generation
            val job = scope.launch(start = CoroutineStart.LAZY) {
                waitBeforeCheck(AUTO_CHECK_DELAY_MS)
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

        fun requestManual(betaChannel: Boolean) = synchronized(lock) {
            changeChannelLocked(betaChannel)
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

        fun dismiss() = synchronized(lock) {
            active?.dismissed = true
            state.value = state.value.copy(dialogRequested = false)
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
            state.value = Snapshot()
        }

        private fun cancelScheduledLocked() {
            if (scheduled == null) return
            ++generation
            scheduled?.cancel()
            scheduled = null
        }

        private fun disableAutomaticLocked() {
            cancelScheduledLocked()
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
                            lastCompletedAt = elapsedMs()
                            state.value = Snapshot(result, checking = false,
                                dialogRequested = !request.dismissed && (request.manual || result is CheckResult.Available))
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

    //keeps update I/O here so network, file, and installer failures are handled in one path.
    suspend fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "BYD-HUD-${update.version}.apk"
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        destination.parentFile?.mkdirs()
        if (destination.exists()) {
            destination.delete()
        }

        //download through Android DownloadManager so DiLink keeps a visible system-owned transfer.
        val request = DownloadManager.Request(Uri.parse(requireHttpsDownloadUrl(update.downloadUrl)))
            .setTitle("BYD HUD ${update.version}")
            .setDescription("BYD HUD update")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val downloadId = manager.enqueue(request)
        var installHandedOff = false
        try {
            emitProgress("0%", onProgress)
            pollDownload(manager, downloadId, onProgress)
            val staged = stageDownloadedApk(context, destination, fileName)

            //install downloaded APK through content URI; file:// is rejected on modern Android.
            withContext(Dispatchers.Main) {
                installDownloadedApk(context, staged)
                installHandedOff = true
            }
        } finally {
            if (!installHandedOff) {
                runCatching { manager.remove(downloadId) }
                runCatching { destination.delete() }
            }
        }
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

    //guard the installer handoff so Package Installer reads an app-private staged APK.
    private fun stageDownloadedApk(context: Context, downloaded: File, fileName: String): File {
        if (!downloaded.isFile) {
            throw IllegalStateException("Downloaded APK not found")
        }
        val updateDir = File(context.filesDir, "updates")
        updateDir.mkdirs()
        val staged = File(updateDir, fileName)
        if (staged.exists() && !staged.delete()) {
            throw IllegalStateException("Could not replace staged update APK")
        }
        downloaded.copyTo(staged, overwrite = true)
        downloaded.delete()
        return staged
    }

    //keeps update I/O here so network, file, and installer failures are handled in one path.
    private fun installDownloadedApk(context: Context, file: File) {
        if (!file.exists()) {
            throw IllegalStateException("Downloaded APK not found")
        }
        //guard downloaded APK identity before Package Installer sees the file.
        validateDownloadedApk(context, file)
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
    private fun validateDownloadedApk(context: Context, file: File) {
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
