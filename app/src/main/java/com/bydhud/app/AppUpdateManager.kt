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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_AUTO_CHECK_READY_AT_MS = "auto_check_ready_at_ms"
    private const val CHECK_THROTTLE_MS = 10 * 60 * 1000L
    private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
    private const val EXPECTED_PACKAGE_NAME = "com.bydhud.app"
    private const val RELEASE_API_HOST = "api.github.com"
    private const val APK_DOWNLOAD_HOST = "github.com"
    private const val RELEASE_PATH_MARKER = "/sunlixWhyNotAvailable/byd-hud/releases/download/"
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

    //defines CheckResult UI/state support so Compose code can keep rendering intent explicit.
    sealed class CheckResult {
        //defines UpToDate UI/state support so Compose code can keep rendering intent explicit.
        data object UpToDate : CheckResult()
        //defines Available UI/state support so Compose code can keep rendering intent explicit.
        data class Available(val info: UpdateInfo) : CheckResult()
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    fun isAutoCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, true)
    }

    //keeps this Compose helper focused so UI state changes remain easy to audit.
    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK, enabled)
            .also { editor ->
                if (!enabled) {
                    editor.remove(KEY_AUTO_CHECK_READY_AT_MS)
                }
            }
            .apply()
    }

    fun isBetaChannelEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_CHANNEL, false)
    }

    //changing channels invalidates only the throttle; the caller decides when to check again.
    fun setBetaChannelEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BETA_CHANNEL, false) == enabled) {
            return
        }
        prefs.edit()
            .putBoolean(KEY_BETA_CHANNEL, enabled)
            .remove(KEY_LAST_CHECK_MS)
            .apply()
    }

    @JvmStatic
    //starts or schedules work here so lifecycle recovery follows one controlled path.
    fun armAutoCheckTimer(context: Context, delayMs: Long) {
        armAutoCheckTimer(context, delayMs, System.currentTimeMillis())
    }

    @JvmStatic
    //starts or schedules work here so lifecycle recovery follows one controlled path.
    fun armAutoCheckTimer(context: Context, delayMs: Long, nowMs: Long) {
        if (!isAutoCheckEnabled(context)) {
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val readyAt = prefs.getLong(KEY_AUTO_CHECK_READY_AT_MS, 0L)
        if (readyAt > 0L) {
            return
        }
        prefs.edit()
            .putLong(KEY_AUTO_CHECK_READY_AT_MS, nowMs + delayMs.coerceAtLeast(0L))
            .apply()
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    fun autoCheckDelayRemainingMs(context: Context): Long? {
        return autoCheckDelayRemainingMs(context, System.currentTimeMillis())
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    internal fun autoCheckDelayRemainingMs(context: Context, nowMs: Long): Long? {
        if (!isAutoCheckEnabled(context)) {
            return null
        }
        val readyAt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_AUTO_CHECK_READY_AT_MS, 0L)
        if (readyAt <= 0L) {
            return null
        }
        return (readyAt - nowMs).coerceAtLeast(0L)
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    fun consumeAutoCheckReady(context: Context): Boolean {
        return consumeAutoCheckReady(context, System.currentTimeMillis())
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    internal fun consumeAutoCheckReady(context: Context, nowMs: Long): Boolean {
        if (!isAutoCheckEnabled(context)) {
            return false
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val readyAt = prefs.getLong(KEY_AUTO_CHECK_READY_AT_MS, 0L)
        if (readyAt <= 0L || nowMs < readyAt) {
            return false
        }
        prefs.edit().remove(KEY_AUTO_CHECK_READY_AT_MS).apply()
        return true
    }

    //keeps this Compose helper focused so UI state changes remain easy to audit.
    suspend fun checkForUpdate(context: Context, forceCheck: Boolean): CheckResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        if (!forceCheck && now - lastCheck < CHECK_THROTTLE_MS) {
            return@withContext CheckResult.UpToDate
        }
        prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()

        val release = if (isBetaChannelEnabled(context)) {
            selectLatestRelease(fetchReleaseListJson())
        } else {
            selectStableRelease(fetchLatestReleaseJson())
        }
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
        emitProgress("0%", onProgress)
        pollDownload(manager, downloadId, onProgress)
        val staged = stageDownloadedApk(context, destination, fileName)

        //install downloaded APK through content URI; file:// is rejected on modern Android.
        withContext(Dispatchers.Main) {
            installDownloadedApk(context, staged)
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
        val startedAt = System.currentTimeMillis()
        var finished = false
        while (!finished) {
            if (System.currentTimeMillis() - startedAt > DOWNLOAD_TIMEOUT_MS) {
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
