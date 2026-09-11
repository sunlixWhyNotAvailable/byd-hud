package com.bydhud.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.database.Cursor;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/** Owns the fixed navigator asset catalog, downloads, validation, and install handoff. */
final class NavigatorAssetManager {
    static final String DOWNLOADING = "DOWNLOADING";
    static final String VERIFYING = "VERIFYING";
    static final String NOT_DOWNLOADED = "NOT_DOWNLOADED";
    static final String READY = "READY";
    static final String INSTALL_REQUESTED = "INSTALL_REQUESTED";
    static final String UNINSTALL_REQUESTED = "UNINSTALL_REQUESTED";
    static final String INSTALLED = "INSTALLED";
    static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
    static final String ERROR = "ERROR";
    static final String ERROR_NETWORK = "NETWORK";
    static final String ERROR_STORAGE = "STORAGE";
    static final String ERROR_MISSING_OPERATION = "MISSING_OPERATION";
    static final String ERROR_INVALID_APK = "INVALID_APK";
    static final String ERROR_INTEGRITY = "INTEGRITY";
    static final String ERROR_SYSTEM = "SYSTEM";

    private static final String PREFS = "navigator_asset_manager";
    private static final long NO_DOWNLOAD = -1L;
    private static final String PHASE_NONE = "NONE";
    private static final String PHASE_INSTALL = "INSTALL";
    private static final String PHASE_UNINSTALL = "UNINSTALL";
    private static final String PHASE_RECOVERY = "RECOVERY";
    private static final long UNINSTALL_CANCEL_TIMEOUT_MS = 30_000L;
    private static final long ORPHAN_MIN_AGE_MS = 5 * 60_000L;
    private static final int MAX_ORPHAN_DELETIONS_PER_SWEEP = 4;
    private static final Set<String> ACTIVE_VALIDATIONS = new HashSet<>();
    private static final Set<String> ACTIVE_INSTALL_VERIFICATIONS = new HashSet<>();
    private static final Set<String> ACTIVE_TRANSACTION_DIRECTORIES = new HashSet<>();
    private static final AtomicBoolean ORPHAN_SWEEP_RUNNING = new AtomicBoolean(false);
    private static final Object TRANSACTION_LOCK = new Object();

    static final class Asset {
        final String id;
        final String englishLabel;
        final String ukrainianLabel;
        final String russianLabel;
        final String versionName;
        final long versionCode;
        final String packageName;
        final String signerSha256;
        final String sha256;
        final String url;
        final String fileName;
        final NavigatorPatchStore.Profile profile;

        Asset(String id, String englishLabel, String ukrainianLabel, String versionName,
                long versionCode, String packageName, String signerSha256, String sha256,
                String url, String fileName, NavigatorPatchStore.Profile profile) {
            this(id, englishLabel, ukrainianLabel, englishLabel, versionName, versionCode,
                    packageName, signerSha256, sha256, url, fileName, profile);
        }

        Asset(String id, String englishLabel, String ukrainianLabel, String russianLabel,
                String versionName, long versionCode, String packageName, String signerSha256,
                String sha256, String url, String fileName, NavigatorPatchStore.Profile profile) {
            this.id = id;
            this.englishLabel = englishLabel;
            this.ukrainianLabel = ukrainianLabel;
            this.russianLabel = russianLabel;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.packageName = packageName;
            this.signerSha256 = signerSha256;
            this.sha256 = sha256;
            this.url = url;
            this.fileName = fileName;
            this.profile = profile;
        }

        String label(boolean ukrainian) {
            return ukrainian ? ukrainianLabel : englishLabel;
        }

        String label(String language) {
            return "uk".equals(language) ? ukrainianLabel
                    : "ru".equals(language) ? russianLabel : englishLabel;
        }
    }

    static final class AssetSnapshot {
        private final Asset asset;
        public final String id;
        public final String label;
        public final String versionName;
        public final String packageName;
        public final String state;
        public final String progress;
        public final String error;
        public final String errorCategory;
        public final String errorDetail;
        public final boolean installed;
        public final boolean downloadReady;
        public final boolean downloadable;

        AssetSnapshot(Asset asset, boolean ukrainian, String state, String progress,
                String error, boolean installed) {
            this(asset, ukrainian, state, progress, error, installed, READY.equals(state));
        }

        AssetSnapshot(Asset asset, boolean ukrainian, String state, String progress,
                String error, boolean installed, boolean downloadReady) {
            this(asset, ukrainian ? "uk" : "en", state, progress, error, installed,
                    downloadReady);
        }

        AssetSnapshot(Asset asset, String language, String state, String progress,
                String error, boolean installed, boolean downloadReady) {
            this(asset, language, state, progress, error == null ? "" : error,
                    error == null || error.isEmpty() ? "" : ERROR_SYSTEM,
                    error == null ? "" : error, installed, downloadReady);
        }

        AssetSnapshot(Asset asset, String language, String state, String progress,
                String error, String errorCategory, String errorDetail,
                boolean installed, boolean downloadReady) {
            this.asset = asset;
            this.id = asset.id;
            this.label = asset.label(language);
            this.versionName = asset.versionName;
            this.packageName = asset.packageName;
            this.state = state;
            this.progress = progress;
            this.error = error == null ? "" : error;
            this.errorCategory = errorCategory == null ? "" : errorCategory;
            this.errorDetail = errorDetail == null ? "" : errorDetail;
            this.installed = installed;
            this.downloadReady = downloadReady;
            this.downloadable = NOT_DOWNLOADED.equals(state) || ERROR.equals(state)
                    || (!installed && !DOWNLOADING.equals(state)
                    && !INSTALL_REQUESTED.equals(state) && !UNINSTALL_REQUESTED.equals(state));
        }

        AssetSnapshot localized(boolean ukrainian) {
            return new AssetSnapshot(asset, ukrainian ? "uk" : "en", state, progress, error,
                    errorCategory, errorDetail, installed, downloadReady);
        }

        AssetSnapshot localized(String language) {
            return new AssetSnapshot(asset, language, state, progress, error,
                    errorCategory, errorDetail, installed, downloadReady);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof AssetSnapshot)) return false;
            AssetSnapshot other = (AssetSnapshot) value;
            return installed == other.installed && downloadReady == other.downloadReady
                    && downloadable == other.downloadable
                    && Objects.equals(id, other.id)
                    && Objects.equals(label, other.label)
                    && Objects.equals(versionName, other.versionName)
                    && Objects.equals(packageName, other.packageName)
                    && Objects.equals(state, other.state)
                    && Objects.equals(progress, other.progress)
                    && Objects.equals(error, other.error)
                    && Objects.equals(errorCategory, other.errorCategory)
                    && Objects.equals(errorDetail, other.errorDetail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, label, versionName, packageName, state, progress, error,
                    errorCategory, errorDetail, installed, downloadReady, downloadable);
        }
    }

    static final class DestructiveConfirmationRequired extends IOException {
        DestructiveConfirmationRequired() {
            super("Navigator replacement requires destructive confirmation");
        }
    }

    private static final List<Asset> ASSETS;

    static {
        List<Asset> assets = new ArrayList<>();
        assets.add(new Asset(
                "waze-stock-4.95.0.3",
                "Waze original version",
                "Waze оригінальна версія",
                "Waze оригинальная версия",
                "4.95.0.3",
                1023098L,
                "com.waze",
                NavigatorAssetSignerCatalog.WAZE_STOCK_SIGNER,
                "D7C8C50780AB3E8F7A63F96F9B4F3D426C8DBD04B3C87FE5095D213375A5B6A6",
                "https://github.com/sunlixWhyNotAvailable/byd-hud/releases/download/"
                        + "navigator-assets-v1/waze-4.95.0.3-stock-direct.apk",
                "waze-4.95.0.3-stock-direct.apk",
                NavigatorPatchStore.Profile.WAZE));
        assets.add(new Asset(
                "waze-direct-5.20.0.1",
                "Waze patched version",
                "Waze патчена версія",
                "Waze патченная версия",
                "5.20.0.1",
                1030706L,
                "com.waze",
                NavigatorAssetSignerCatalog.WAZE_PROJECT_SIGNER,
                "5B34D0BF24A28FC3ACCF483E821070357206C88199A2AC56AD48D1D4AC03E424",
                "https://github.com/sunlixWhyNotAvailable/byd-hud/releases/download/"
                        + "navigator-assets-v1/waze-5.20.0.1-direct.apk",
                "waze-5.20.0.1-direct.apk",
                NavigatorPatchStore.Profile.WAZE));
        assets.add(new Asset(
                "gmaps-direct-26.30.09.950492155",
                "Google Maps ReVanced patched version",
                "Google Maps ReVanced патчена версія",
                "Google Maps ReVanced патченная версия",
                "26.30.09.950492155",
                1068694917L,
                "app.revanced.android.apps.maps",
                NavigatorAssetSignerCatalog.GMAPS_PROJECT_SIGNER,
                "86EB9A465897F1C771E29C9250330862E0C1F777B93D21A743BF43791C0B2FA3",
                "https://github.com/sunlixWhyNotAvailable/byd-hud/releases/download/"
                        + "navigator-assets-v1/google-maps-revanced-26.30.09.950492155-direct.apk",
                "google-maps-revanced-26.30.09.950492155-direct.apk",
                NavigatorPatchStore.Profile.GMAPS));
        ASSETS = Collections.unmodifiableList(assets);
    }

    private NavigatorAssetManager() {
    }

    static List<Asset> catalog() {
        return ASSETS;
    }

    static List<AssetSnapshot> snapshots(Context context, boolean ukrainian) {
        List<AssetSnapshot> result = new ArrayList<>();
        for (Asset asset : ASSETS) result.add(snapshot(context, asset, ukrainian));
        return Collections.unmodifiableList(result);
    }

    static AssetSnapshot snapshot(Context context, Asset asset, boolean ukrainian) {
        reconcileCatalogRevision(context, asset);
        reconcileDownload(context, asset);
        reconcileDownloadPresence(context, asset);
        boolean installed = matchesInstalledCached(context, asset);
        boolean downloadReady = prefs(context).getBoolean(key(asset, "download_ready"), false);
        String state = resolvedSnapshotState(
                installed,
                downloadReady,
                string(context, asset, "state", NOT_DOWNLOADED));
        String progress = string(context, asset, "progress", "0%");
        String error = string(context, asset, "error", "");
        String errorCategory = string(context, asset, "error_category", "");
        String errorDetail = string(context, asset, "error_detail", error);
        if (DOWNLOADING.equals(state)) {
            progress = queryProgress(context, asset);
        } else if (VERIFYING.equals(state)) {
            validateDownloadedAsync(context, asset);
        }
        return new AssetSnapshot(asset, ukrainian ? "uk" : "en", state, progress, error,
                errorCategory, errorDetail, installed, downloadReady);
    }

    private static final class ValidationTicket {
        final long generation;
        final long downloadId;
        final String fileName;

        ValidationTicket(long generation, long downloadId, String fileName) {
            this.generation = generation;
            this.downloadId = downloadId;
            this.fileName = fileName;
        }
    }

    private static final class AssetFailure extends IOException {
        final String category;

        AssetFailure(String category, String detail) {
            super(detail);
            this.category = category;
        }

        AssetFailure(String category, String detail, Throwable cause) {
            super(detail, cause);
            this.category = category;
        }
    }

    static String resolvedSnapshotStateForTest(
            boolean installed, boolean downloadReady, String persistedState) {
        return resolvedSnapshotState(installed, downloadReady, persistedState);
    }

    private static String resolvedSnapshotState(
            boolean installed, boolean downloadReady, String persistedState) {
        String state = persistedState == null ? NOT_DOWNLOADED : persistedState;
        if (DOWNLOADING.equals(state) || VERIFYING.equals(state)
                || INSTALL_REQUESTED.equals(state) || UNINSTALL_REQUESTED.equals(state)
                || RECOVERY_REQUIRED.equals(state)) {
            return state;
        }
        if (installed) return INSTALLED;
        if (ERROR.equals(state)) return ERROR;
        return downloadReady ? READY : NOT_DOWNLOADED;
    }

    static void startDownload(Context context, String assetId) throws IOException {
        Asset asset = require(assetId);
        NavigatorDownloadAttemptGuard.serialized(TRANSACTION_LOCK, () -> {
            startDownloadLocked(context, asset);
            return null;
        });
    }

    private static void startDownloadLocked(Context context, Asset asset) throws IOException {
        ValidationTicket previousTicket = validationTicket(context, asset);
        long generation = prefs(context).getLong(key(asset, "attempt_generation"), 0L) + 1L;
        File stable = downloadFile(context, asset);
        File destination = new File(stable.getParentFile(),
                NavigatorDownloadAttemptGuard.newAttemptFileName(asset.fileName, generation));
        DownloadManager manager;
        try {
            manager = downloadManager(context);
        } catch (RuntimeException error) {
            failDownloadStart(context, asset, previousTicket, generation, destination,
                    ERROR_SYSTEM,
                    "DownloadManager unavailable: " + error.getMessage());
            throw new IOException("DownloadManager unavailable", error);
        }
        long previous = prefs(context).getLong(key(asset, "download_id"), NO_DOWNLOAD);
        if (previous != NO_DOWNLOAD) {
            try {
                manager.remove(previous);
            } catch (RuntimeException ignored) {
            }
        }
        deleteFile(destination);
        prefs(context).edit()
                .remove(key(asset, "download_id"))
                .putLong(key(asset, "attempt_generation"), generation)
                .putString(key(asset, "attempt_file"), destination.getName())
                .putString(key(asset, "state"), DOWNLOADING)
                .putString(key(asset, "progress"), "0%")
                .putString(key(asset, "error"), "")
                .putString(key(asset, "error_category"), "")
                .putString(key(asset, "error_detail"), "")
                .putBoolean(key(asset, "download_ready"), false)
                .putString(key(asset, "phase"), PHASE_NONE)
                .commit();
        deleteStaleCandidate(context, asset, candidateFile(context, asset, previousTicket));
        long id;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(asset.url))
                    .setTitle(asset.englishLabel + " " + asset.versionName)
                    .setDescription("BYD HUD navigator asset")
                    .setDestinationInExternalFilesDir(
                            context, Environment.DIRECTORY_DOWNLOADS, destination.getName())
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            id = manager.enqueue(request);
        } catch (RuntimeException error) {
            failDownloadStart(context, asset, previousTicket, generation, destination,
                    ERROR_SYSTEM,
                    "Download enqueue failed: " + error.getMessage());
            throw new IOException("Download enqueue failed", error);
        }
        prefs(context).edit().putLong(key(asset, "download_id"), id).commit();
        AppEventLogger.event(context, "navigator_asset download_start id=" + asset.id
                + " generation=" + generation + " download=" + id);
    }

    static boolean requiresDestructiveConfirmation(Context context, String assetId)
            throws Exception {
        Asset asset = require(assetId);
        File file = requireValidDownload(context, asset);
        PackageInfo installed = installedInfo(context, asset.packageName);
        if (installed == null || matchesInstalled(context, asset)) return false;
        String signer = installedSigner(context, asset.packageName);
        return !asset.signerSha256.equals(signer)
                || installed.getLongVersionCode() > asset.versionCode;
    }

    static void install(Context context, String assetId, boolean destructiveApproved)
            throws Exception {
        Asset asset = require(assetId);
        File file = requireValidDownload(context, asset);
        PackageInfo installed = installedInfo(context, asset.packageName);
        boolean destructive = installed != null && requiresDestructiveConfirmation(context, assetId);
        if (destructive && !destructiveApproved) throw new DestructiveConfirmationRequired();
        File staged;
        synchronized (TRANSACTION_LOCK) {
            if (!destructive) {
                String before = installedInfo(context, asset.packageName) == null
                        ? "absent" : installedIdentity(context, asset);
                if (!prefs(context).edit().putString(
                        key(asset, "install_previous_identity"), before).commit()) {
                    throw new IOException("Cannot retain navigator install identity");
                }
            }
            staged = stageForInstaller(context, asset, file);
            if (!asset.sha256.equals(sha256(staged))) {
                deleteFile(staged);
                throw new IOException("Staged navigator asset SHA-256 mismatch");
            }
            if (!destructive) {
                setState(context, asset, INSTALL_REQUESTED, "100%", "");
                prefs(context).edit().putString(key(asset, "phase"), PHASE_INSTALL).commit();
                launchInstall(context, staged);
                AppEventLogger.event(context, "navigator_asset install_requested id=" + asset.id
                        + " destructive=false");
                return;
            }
        }

        String previousTransactionName = string(context, asset, "transaction", "");
        File previousTransaction = transaction(context, asset);
        String installedIdentity = NavigatorPatchStore.installedIdentity(
                context, asset.profile);
        File transaction;
        synchronized (ACTIVE_TRANSACTION_DIRECTORIES) {
            transaction = createTransaction(context, asset);
            ACTIVE_TRANSACTION_DIRECTORIES.add(transaction.getName());
        }
        boolean retained = false;
        try {
            File sourceSet = new File(transaction, "source-set");
            NavigatorApkSet.materializeInstalled(context, asset.profile, sourceSet);
            NavigatorApkSet.SetInfo backup = NavigatorApkSet.readDirectory(
                    context, asset.profile, sourceSet);
            PackageInfo current = installedInfo(context, asset.packageName);
            if (current == null
                    || current.getLongVersionCode() != backup.versionCode
                    || !installedSigner(context, asset.packageName).equals(backup.signerSha256)
                    || !installedIdentity.equals(NavigatorPatchStore.installedIdentity(
                        context, asset.profile))) {
                throw new IOException("Installed navigator changed during replacement preparation");
            }
            synchronized (TRANSACTION_LOCK) {
                if (!previousTransactionName.equals(
                        string(context, asset, "transaction", ""))) {
                    throw new IOException("Navigator recovery transaction changed");
                }
                boolean switched = prefs(context).edit()
                        .putString(key(asset, "transaction"), transaction.getName())
                        .putString(key(asset, "backup_version"), backup.versionName)
                        .putLong(key(asset, "backup_code"), backup.versionCode)
                        .putString(key(asset, "backup_signer"), backup.signerSha256)
                        .putString(key(asset, "backup_fingerprint"), backup.fingerprint)
                        .putString(key(asset, "phase"), PHASE_UNINSTALL)
                        .putLong(key(asset, "uninstall_requested_ms"), System.currentTimeMillis())
                        .commit();
                if (!switched) {
                    throw new IOException("Cannot retain navigator recovery transaction");
                }
                retained = true;
                if (previousTransaction != null
                        && !previousTransaction.equals(transaction)) {
                    deleteTree(previousTransaction);
                }
            }
        } finally {
            if (!retained) deleteTree(transaction);
            synchronized (ACTIVE_TRANSACTION_DIRECTORIES) {
                ACTIVE_TRANSACTION_DIRECTORIES.remove(transaction.getName());
            }
        }
        setState(context, asset, UNINSTALL_REQUESTED, "100%", "");
        launchUninstall(context, asset.packageName);
        AppEventLogger.event(context, "navigator_asset uninstall_requested id=" + asset.id);
    }

    static void restore(Context context, String assetId) throws Exception {
        Asset asset = require(assetId);
        String transactionName = string(context, asset, "transaction", "");
        if (transactionName.isEmpty()) throw new IOException("Navigator asset backup is missing");
        File transaction = new File(new File(context.getFilesDir(), "navigator-patcher"), transactionName);
        File sourceSet = new File(transaction, "source-set");
        if (!sourceSet.isDirectory()) throw new IOException("Navigator asset backup is missing");
        NavigatorApkSet.SetInfo backup = NavigatorApkSet.readDirectory(
                context, asset.profile, sourceSet);
        PackageInfo current = installedInfo(context, asset.packageName);
        NavigatorPatchPipeline.ScanResult expected = new NavigatorPatchPipeline.ScanResult(
                asset.profile, backup.fingerprint, backup.versionName, backup.versionCode,
                backup.signerSha256, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED, "");
        NavigatorPatchStore.claimAssetRecoveryTransaction(
                context, asset.profile, asset.id, transaction, expected,
                current == null ? -1L : current.lastUpdateTime,
                current == null ? -1L : current.getLongVersionCode(),
                current == null ? "" : installedSigner(context, asset.packageName),
                current == null ? "" : currentArtifactFingerprint(context, asset.profile),
                "Navigator asset rollback");
        setState(context, asset, RECOVERY_REQUIRED, "0%", "");
        prefs(context).edit()
                .putString(key(asset, "backup_fingerprint"), backup.fingerprint)
                .putString(key(asset, "phase"), PHASE_RECOVERY)
                .commit();
        NavigatorPackageInstaller.beginRestore(context, asset.profile);
        AppEventLogger.event(context, "navigator_asset restore_requested id=" + asset.id);
    }

    static boolean recordAuthoritativeRestoreVerified(
            Context context, NavigatorPatchStore.Profile profile,
            String recoveryOwner, String transactionName, String expectedFingerprint) {
        if (recoveryOwner == null || recoveryOwner.isEmpty()) return true;
        Asset matched = findAsset(recoveryOwner);
        if (matched == null || matched.profile != profile) return false;
        synchronized (TRANSACTION_LOCK) {
            if (!PHASE_RECOVERY.equals(string(context, matched, "phase", PHASE_NONE))
                    || !transactionName.equals(string(context, matched, "transaction", ""))
                    || !expectedFingerprint.equals(
                        string(context, matched, "backup_fingerprint", ""))) {
                AppEventLogger.event(context,
                        "navigator_asset recovery_callback_mismatch id=" + matched.id);
                return false;
            }
            String recordedTransaction = string(
                    context, matched, "restore_verified_transaction", "");
            String recordedFingerprint = string(
                    context, matched, "restore_verified_fingerprint", "");
            if (!recordedTransaction.isEmpty()
                    && (!transactionName.equals(recordedTransaction)
                    || !expectedFingerprint.equals(recordedFingerprint))) {
                return false;
            }
            return prefs(context).edit()
                    .putString(key(matched, "restore_verified_transaction"), transactionName)
                    .putString(key(matched, "restore_verified_fingerprint"), expectedFingerprint)
                    .commit();
        }
    }

    static boolean finishAuthoritativeRestoreVerified(
            Context context, NavigatorPatchStore.Profile profile,
            String recoveryOwner, String transactionName, String expectedFingerprint) {
        if (recoveryOwner == null || recoveryOwner.isEmpty()) return true;
        Asset matched = findAsset(recoveryOwner);
        if (matched == null || matched.profile != profile) return false;
        synchronized (TRANSACTION_LOCK) {
            if (!transactionName.equals(string(context, matched, "transaction", ""))
                    || !expectedFingerprint.equals(
                        string(context, matched, "backup_fingerprint", ""))
                    || !transactionName.equals(string(
                        context, matched, "restore_verified_transaction", ""))
                    || !expectedFingerprint.equals(string(
                        context, matched, "restore_verified_fingerprint", ""))) {
                return false;
            }
            deleteFile(new File(new File(context.getFilesDir(), "updates"), matched.fileName));
            boolean downloaded = downloadFile(context, matched).isFile();
            boolean committed = prefs(context).edit()
                    .remove(key(matched, "transaction"))
                    .remove(key(matched, "backup_version"))
                    .remove(key(matched, "backup_code"))
                    .remove(key(matched, "backup_signer"))
                    .remove(key(matched, "backup_fingerprint"))
                    .remove(key(matched, "uninstall_requested_ms"))
                    .remove(key(matched, "restore_verified_transaction"))
                    .remove(key(matched, "restore_verified_fingerprint"))
                    .putString(key(matched, "phase"), PHASE_NONE)
                    .putString(key(matched, "state"), downloaded ? READY : NOT_DOWNLOADED)
                    .putString(key(matched, "progress"), downloaded ? "100%" : "0%")
                    .putString(key(matched, "error"), "")
                    .commit();
            if (!committed) return false;
            AppEventLogger.event(context,
                    "navigator_asset recovery_completed id=" + matched.id);
            return true;
        }
    }

    static void reconcile(Context context) {
        scheduleOrphanCleanup(context);
        for (Asset asset : ASSETS) {
            reconcileCatalogRevision(context, asset);
            reconcileDownload(context, asset);
            reconcileInstall(context, asset);
        }
    }

    static void refreshInstalledMatches(Context context) {
        for (Asset asset : ASSETS) {
            matchesInstalled(context, asset);
        }
    }

    private static void reconcileInstall(Context context, Asset asset) {
        String phase = string(context, asset, "phase", PHASE_NONE);
        NavigatorPatchStore.OperationSnapshot globalOperation =
                NavigatorPatchStore.operation(context);
        if (!PHASE_RECOVERY.equals(phase)
                && globalOperation.profile == asset.profile
                && (globalOperation.busy()
                || NavigatorPatchStore.RECOVERY_REQUIRED.equals(globalOperation.phase))
                && globalRecoveryOwns(
                context, asset, string(context, asset, "transaction", ""),
                string(context, asset, "backup_fingerprint", ""))) {
            phase = PHASE_RECOVERY;
            prefs(context).edit().putString(key(asset, "phase"), phase).commit();
        }
        if (PHASE_NONE.equals(phase)) return;
        PackageInfo installed = installedInfo(context, asset.packageName);
        if (!PHASE_RECOVERY.equals(phase)
                && installedMetadataMatches(context, asset, installed)) {
            Boolean cachedMatch = cachedInstalledMatch(context, asset, installed);
            if (Boolean.TRUE.equals(cachedMatch)) {
                synchronized (TRANSACTION_LOCK) {
                    setState(context, asset, INSTALLED, "100%", "");
                    if (installResultChanged(context, asset)) clearTransaction(context, asset);
                }
                return;
            }
            if (cachedMatch == null) {
                verifyTargetInstalledAsync(context, asset, phase);
                return;
            }
        }
        if (PHASE_UNINSTALL.equals(phase)) {
            if (installed != null) {
                long requestedAt = prefs(context).getLong(
                        key(asset, "uninstall_requested_ms"), 0L);
                if (requestedAt > 0L
                        && System.currentTimeMillis() - requestedAt >= UNINSTALL_CANCEL_TIMEOUT_MS) {
                    prefs(context).edit()
                            .putString(key(asset, "phase"), PHASE_NONE)
                            .remove(key(asset, "uninstall_requested_ms"))
                            .commit();
                    setState(context, asset, READY, "100%", "");
                    AppEventLogger.event(context,
                            "navigator_asset uninstall_cancelled id=" + asset.id);
                }
                return;
            }
            File staged = new File(new File(context.getFilesDir(), "updates"), asset.fileName);
            if (!staged.isFile()) {
                fail(context, asset, "Staged navigator asset is missing after uninstall");
                return;
            }
            try {
                launchInstall(context, staged);
                prefs(context).edit().putString(key(asset, "phase"), PHASE_INSTALL).commit();
                setState(context, asset, INSTALL_REQUESTED, "100%", "");
            } catch (RuntimeException error) {
                fail(context, asset, "Install handoff failed: " + error.getMessage());
            }
            return;
        }
        if (PHASE_INSTALL.equals(phase)) {
            String currentState = string(context, asset, "state", INSTALL_REQUESTED);
            if (ERROR.equals(currentState) || RECOVERY_REQUIRED.equals(currentState)) return;
            if (!string(context, asset, "backup_signer", "").isEmpty()) {
                if (installed == null) {
                    setState(context, asset, RECOVERY_REQUIRED, "0%",
                            "Navigator is missing after replacement");
                } else if (matchesBackupMetadata(context, asset, installed)) {
                    verifyBackupInstalledAsync(context, asset, phase);
                } else {
                    setState(context, asset, RECOVERY_REQUIRED, "0%",
                            "Navigator replacement verification failed");
                }
                return;
            }
            setState(context, asset, READY, "100%", "");
            // Cancellation leaves Install usable, but keep the receipt for a later package update.
            return;
        }
        if (PHASE_RECOVERY.equals(phase)) {
            String transactionName = string(context, asset, "transaction", "");
            String expectedFingerprint = string(context, asset, "backup_fingerprint", "");
            if (hasAuthoritativeRestoreReceipt(
                    context, asset, transactionName, expectedFingerprint)) {
                if (globalRecoveryOwns(
                        context, asset, transactionName, expectedFingerprint)) {
                    setState(context, asset,
                            globalOperation.busy() ? INSTALL_REQUESTED : RECOVERY_REQUIRED,
                            globalOperation.busy() ? "100%" : "0%", globalOperation.detail);
                } else if (!finishAuthoritativeRestoreVerified(
                        context, asset.profile, asset.id,
                        transactionName, expectedFingerprint)) {
                    setState(context, asset, RECOVERY_REQUIRED, "0%",
                            "Authoritative recovery completion is pending");
                }
                return;
            }
            if (globalOperation.profile == asset.profile && globalOperation.busy()) {
                setState(context, asset, INSTALL_REQUESTED, "100%", "");
            } else if (globalOperation.profile == asset.profile
                    && NavigatorPatchStore.RECOVERY_REQUIRED.equals(globalOperation.phase)) {
                setState(context, asset, RECOVERY_REQUIRED, "0%", globalOperation.detail);
            } else {
                setState(context, asset, RECOVERY_REQUIRED, "0%",
                        "Awaiting authoritative recovery confirmation");
            }
        }
    }

    private static boolean hasAuthoritativeRestoreReceipt(
            Context context, Asset asset, String transactionName, String expectedFingerprint) {
        return !transactionName.isEmpty() && !expectedFingerprint.isEmpty()
                && transactionName.equals(string(
                    context, asset, "restore_verified_transaction", ""))
                && expectedFingerprint.equals(string(
                    context, asset, "restore_verified_fingerprint", ""));
    }

    private static boolean globalRecoveryOwns(
            Context context, Asset asset, String transactionName, String expectedFingerprint) {
        return asset.id.equals(NavigatorPatchStore.recoveryOwner(context))
                && transactionName.equals(
                    NavigatorPatchStore.transactionDirectory(context).getName())
                && expectedFingerprint.equals(NavigatorPatchStore.expectedSha(context));
    }

    private static void reconcileDownload(Context context, Asset asset) {
        String state = string(context, asset, "state", NOT_DOWNLOADED);
        if (!DOWNLOADING.equals(state)) {
            if (VERIFYING.equals(state)) validateDownloadedAsync(context, asset);
            return;
        }
        ValidationTicket ticket = validationTicket(context, asset);
        long id = ticket.downloadId;
        if (id == NO_DOWNLOAD) {
            id = recoverReservedDownloadId(context, asset, ticket);
            if (id == NO_DOWNLOAD) {
                failActiveDownload(context, asset, ticket, ERROR_MISSING_OPERATION,
                        "Download id is missing");
                return;
            }
            ticket = new ValidationTicket(ticket.generation, id, ticket.fileName);
        }
        try (Cursor cursor = downloadManager(context).query(
                new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                failActiveDownload(context, asset, ticket, ERROR_MISSING_OPERATION,
                        "Download row is missing id=" + id);
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                synchronized (TRANSACTION_LOCK) {
                    if (!ownsActiveDownload(context, asset, ticket)) return;
                    setState(context, asset, VERIFYING, "100%", "");
                    validateDownloadedAsync(context, asset);
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                failActiveDownload(context, asset, ticket,
                        downloadFailureCategoryForTest(reason),
                        "Download failed reason=" + reason);
            } else if (isActiveDownloadManagerStatusForTest(status)) {
                String progress = progressFromCursor(cursor);
                synchronized (TRANSACTION_LOCK) {
                    if (ownsActiveDownload(context, asset, ticket)) {
                        prefs(context).edit().putString(key(asset, "progress"), progress).apply();
                    }
                }
            } else {
                failActiveDownload(context, asset, ticket, ERROR_SYSTEM,
                        "Download returned unknown status=" + status);
            }
        } catch (RuntimeException error) {
            failActiveDownload(context, asset, ticket, ERROR_SYSTEM,
                    "Download status read failed: " + error.getMessage());
        }
    }

    private static void reconcileDownloadPresence(Context context, Asset asset) {
        synchronized (TRANSACTION_LOCK) {
            File file = downloadFile(context, asset);
            boolean recorded = prefs(context).getBoolean(key(asset, "download_ready"), false);
            String state = string(context, asset, "state", NOT_DOWNLOADED);
            if (DOWNLOADING.equals(state) || VERIFYING.equals(state) || ERROR.equals(state)) return;
            if (recorded && !file.isFile()) {
                prefs(context).edit()
                        .putBoolean(key(asset, "download_ready"), false)
                        .putString(key(asset, "state"), NOT_DOWNLOADED)
                        .putString(key(asset, "progress"), "0%")
                        .putString(key(asset, "error"), "")
                        .putString(key(asset, "error_category"), "")
                        .putString(key(asset, "error_detail"), "")
                        .commit();
                AppEventLogger.event(context,
                        "navigator_asset retained_apk_missing id=" + asset.id);
            } else if (NavigatorDownloadAttemptGuard.shouldRediscover(state, recorded, file)) {
                validateDownloadedAsync(context, asset);
            }
        }
    }

    private static void validateDownloadedAsync(Context context, Asset asset) {
        ValidationTicket ticket = validationTicket(context, asset);
        synchronized (ACTIVE_VALIDATIONS) {
            if (!ACTIVE_VALIDATIONS.add(asset.id)) return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            File stable = downloadFile(appContext, asset);
            File candidate = NavigatorDownloadAttemptGuard.validationCandidate(
                    string(appContext, asset, "state", NOT_DOWNLOADED),
                    candidateFile(appContext, asset, ticket), stable);
            try {
                validate(appContext, asset, candidate);
                synchronized (TRANSACTION_LOCK) {
                    if (!settleValidatedAttempt(candidate, stable,
                            ownsValidation(appContext, asset, ticket))) {
                        AppEventLogger.event(appContext, "navigator_asset validation_stale id="
                                + asset.id + " generation=" + ticket.generation);
                        return;
                    }
                    prefs(appContext).edit()
                            .remove(key(asset, "download_id"))
                            .remove(key(asset, "attempt_file"))
                            .putBoolean(key(asset, "download_ready"), true)
                            .putString(key(asset, "state"), READY)
                            .putString(key(asset, "progress"), "100%")
                            .putString(key(asset, "error"), "")
                            .putString(key(asset, "error_category"), "")
                            .putString(key(asset, "error_detail"), "")
                            .commit();
                    AppEventLogger.event(appContext, "navigator_asset verified id=" + asset.id
                            + " generation=" + ticket.generation);
                }
            } catch (Exception error) {
                synchronized (TRANSACTION_LOCK) {
                    if (ownsValidation(appContext, asset, ticket)) {
                        deleteFile(candidate);
                        failDownload(appContext, asset, failureCategory(error),
                                clean(error.getMessage()));
                    } else {
                        try {
                            NavigatorDownloadAttemptGuard.settleValidated(candidate,
                                    downloadFile(appContext, asset), false);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } finally {
                synchronized (ACTIVE_VALIDATIONS) {
                    ACTIVE_VALIDATIONS.remove(asset.id);
                }
                MainActivity.requestNavigatorAssetCompletionRefresh(appContext);
            }
        }, "navigator-asset-verify-" + asset.id).start();
    }

    private static String queryProgress(Context context, Asset asset) {
        long id = prefs(context).getLong(key(asset, "download_id"), NO_DOWNLOAD);
        if (id == NO_DOWNLOAD) return "0%";
        try (Cursor cursor = downloadManager(context).query(
                new DownloadManager.Query().setFilterById(id))) {
            return cursor != null && cursor.moveToFirst() ? progressFromCursor(cursor)
                    : string(context, asset, "progress", "0%");
        } catch (RuntimeException ignored) {
            return string(context, asset, "progress", "0%");
        }
    }

    private static String progressFromCursor(Cursor cursor) {
        long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        if (total > 0L) return Math.max(0L, Math.min(100L, downloaded * 100L / total)) + "%";
        return "0%";
    }

    private static File requireValidDownload(Context context, Asset asset) throws Exception {
        File file = downloadFile(context, asset);
        try {
            validate(context, asset, file);
            setDownloadReady(context, asset, true);
            setState(context, asset, READY, "100%", "");
            return file;
        } catch (Exception error) {
            setDownloadReady(context, asset, false);
            deleteFile(file);
            fail(context, asset, clean(error.getMessage()));
            throw error;
        }
    }

    private static void validate(Context context, Asset asset, File file) throws Exception {
        if (!file.isFile()) {
            throw new AssetFailure(ERROR_MISSING_OPERATION,
                    "Downloaded navigator asset is missing");
        }
        try {
            if (!isMonolithicApk(file)) {
                throw new AssetFailure(ERROR_INVALID_APK,
                        "Navigator asset must be a monolithic APK");
            }
        } catch (AssetFailure error) {
            throw error;
        } catch (IOException error) {
            throw new AssetFailure(ERROR_INVALID_APK, clean(error.getMessage()), error);
        }
        String actualSha;
        try {
            actualSha = sha256(file);
        } catch (IOException error) {
            throw new AssetFailure(ERROR_STORAGE,
                    "Navigator asset could not be read: " + clean(error.getMessage()), error);
        }
        if (!asset.sha256.equals(actualSha)) {
            throw new AssetFailure(ERROR_INTEGRITY,
                    "Navigator asset SHA-256 mismatch expected=" + asset.sha256
                            + " actual=" + actualSha);
        }
        File validation = new File(new File(context.getCacheDir(), "navigator-assets"),
                "validate-" + asset.id + "-" + UUID.randomUUID());
        try {
            NavigatorApkSet.SetInfo set;
            try {
                set = NavigatorApkSet.materializeSource(
                        context, asset.profile, file, validation);
            } catch (NavigatorApkSet.StorageException error) {
                throw new AssetFailure(materializationFailureCategoryForTest(error),
                        "Navigator asset could not be staged: " + clean(error.getMessage()),
                        error);
            } catch (Exception error) {
                throw new AssetFailure(ERROR_INVALID_APK,
                        "Navigator asset package could not be read: " + clean(error.getMessage()),
                        error);
            }
            if (set.members.size() != 1 || !asset.packageName.equals(set.packageName)
                    || !asset.versionName.equals(set.versionName)
                    || asset.versionCode != set.versionCode
                    || !asset.signerSha256.equals(set.signerSha256)) {
                throw new AssetFailure(ERROR_INTEGRITY, "Navigator asset metadata mismatch");
            }
        } finally {
            deleteTree(validation);
        }
    }

    private static boolean matchesInstalledCached(Context context, Asset asset) {
        PackageInfo info = installedInfo(context, asset.packageName);
        return Boolean.TRUE.equals(cachedInstalledMatch(context, asset, info));
    }

    private static Boolean cachedInstalledMatch(
            Context context, Asset asset, PackageInfo info) {
        if (!installedMetadataMatches(context, asset, info)) return Boolean.FALSE;
        try {
            String identity = installedIdentity(context, asset);
            if (!identity.equals(string(context, asset, "installed_identity", ""))
                    || !prefs(context).contains(key(asset, "installed_matches"))) {
                return null;
            }
            return prefs(context).getBoolean(key(asset, "installed_matches"), false);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matchesInstalled(Context context, Asset asset) {
        PackageInfo info = installedInfo(context, asset.packageName);
        if (!installedMetadataMatches(context, asset, info)) return false;
        try {
            String identity = installedIdentity(context, asset);
            if (identity.equals(string(context, asset, "installed_identity", ""))
                    && prefs(context).contains(key(asset, "installed_matches"))) {
                return prefs(context).getBoolean(key(asset, "installed_matches"), false);
            }
            String sourceDir = info.applicationInfo == null ? "" : info.applicationInfo.sourceDir;
            boolean matches = !sourceDir.isEmpty()
                    && asset.sha256.equals(sha256(new File(sourceDir)));
            if (!identity.equals(installedIdentity(context, asset))) return false;
            prefs(context).edit()
                    .putString(key(asset, "installed_identity"), identity)
                    .putBoolean(key(asset, "installed_matches"), matches)
                    .commit();
            return matches;
        } catch (Exception error) {
            AppEventLogger.event(context, "navigator_asset installed_hash_failed id="
                    + asset.id + " error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean installedMetadataMatches(Context context, Asset asset,
            PackageInfo info) {
        if (info == null || !asset.versionName.equals(info.versionName)
                || asset.versionCode != info.getLongVersionCode()) return false;
        try {
            return asset.signerSha256.equals(installedSigner(context, asset.packageName));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String installedIdentity(Context context, Asset asset) throws Exception {
        return asset.sha256 + "|"
                + NavigatorPatchStore.installedIdentity(context, asset.profile);
    }

    private static boolean matchesBackupMetadata(Context context, Asset asset,
            PackageInfo installed) {
        if (installed == null
                || installed.getLongVersionCode()
                != prefs(context).getLong(key(asset, "backup_code"), -1L)
                || !string(context, asset, "backup_version", "").equals(installed.versionName)) {
            return false;
        }
        try {
            return string(context, asset, "backup_signer", "")
                    .equals(installedSigner(context, asset.packageName));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void verifyTargetInstalledAsync(
            Context context, Asset asset, String expectedPhase) {
        String transactionName = string(context, asset, "transaction", "");
        synchronized (ACTIVE_INSTALL_VERIFICATIONS) {
            if (!ACTIVE_INSTALL_VERIFICATIONS.add(asset.id)) return;
        }
        setState(context, asset, INSTALL_REQUESTED, "100%", "");
        Context appContext = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            boolean reconcileStale = false;
            try {
                PackageInfo installed = installedInfo(appContext, asset.packageName);
                if (!installedMetadataMatches(appContext, asset, installed)) {
                    throw new IOException("Installed navigator metadata changed during verification");
                }
                String installedIdentity = installedIdentity(appContext, asset);
                String sourceDir = installed.applicationInfo == null
                        ? "" : installed.applicationInfo.sourceDir;
                boolean verified = !sourceDir.isEmpty()
                        && asset.sha256.equals(sha256(new File(sourceDir)));
                synchronized (TRANSACTION_LOCK) {
                    boolean stale = !expectedPhase.equals(
                            string(appContext, asset, "phase", PHASE_NONE))
                            || !transactionName.equals(
                            string(appContext, asset, "transaction", ""));
                    if (stale) {
                        reconcileStale = true;
                    } else {
                        boolean unchanged = installedMetadataMatches(appContext, asset,
                                installedInfo(appContext, asset.packageName))
                                && installedIdentity.equals(installedIdentity(appContext, asset));
                        if (!unchanged) {
                            throw new IOException(
                                    "Installed navigator changed during verification");
                        }
                        prefs(appContext).edit()
                                .putString(key(asset, "installed_identity"), installedIdentity)
                                .putBoolean(key(asset, "installed_matches"), verified)
                                .commit();
                        if (verified) {
                            setState(appContext, asset, INSTALLED, "100%", "");
                            if (installResultChanged(appContext, asset)) {
                                clearTransaction(appContext, asset);
                            }
                        } else {
                            boolean destructive = !string(
                                    appContext, asset, "backup_signer", "").isEmpty();
                            setState(appContext, asset,
                                    destructive ? RECOVERY_REQUIRED : ERROR,
                                    "0%", "Installed navigator does not match catalog asset");
                        }
                    }
                }
            } catch (Exception error) {
                synchronized (TRANSACTION_LOCK) {
                    if (expectedPhase.equals(
                            string(appContext, asset, "phase", PHASE_NONE))
                            && transactionName.equals(
                            string(appContext, asset, "transaction", ""))) {
                        setState(appContext, asset,
                                string(appContext, asset, "backup_signer", "").isEmpty()
                                        ? ERROR : RECOVERY_REQUIRED,
                                "0%", clean(error.getMessage()));
                    }
                }
            } finally {
                synchronized (ACTIVE_INSTALL_VERIFICATIONS) {
                    ACTIVE_INSTALL_VERIFICATIONS.remove(asset.id);
                }
                if (reconcileStale) reconcileInstall(appContext, asset);
            }
        }, "navigator-asset-target-verify-" + asset.id);
        try {
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        } catch (RuntimeException error) {
            synchronized (ACTIVE_INSTALL_VERIFICATIONS) {
                ACTIVE_INSTALL_VERIFICATIONS.remove(asset.id);
            }
            setState(appContext, asset,
                    string(appContext, asset, "backup_signer", "").isEmpty()
                            ? ERROR : RECOVERY_REQUIRED,
                    "0%", "Navigator install verification could not start");
        }
    }

    private static void verifyBackupInstalledAsync(
            Context context, Asset asset, String expectedPhase) {
        String transactionName = string(context, asset, "transaction", "");
        String expectedFingerprint = string(context, asset, "backup_fingerprint", "");
        if (transactionName.isEmpty() || expectedFingerprint.isEmpty()) {
            setState(context, asset, RECOVERY_REQUIRED, "0%",
                    "Navigator recovery fingerprint is missing");
            return;
        }
        synchronized (ACTIVE_INSTALL_VERIFICATIONS) {
            if (!ACTIVE_INSTALL_VERIFICATIONS.add(asset.id)) return;
        }
        setState(context, asset, INSTALL_REQUESTED, "100%", "");
        Context appContext = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            boolean verified = false;
            boolean stale = false;
            String installedIdentity = "";
            String failure = "Navigator recovery verification failed";
            try {
                try {
                    PackageInfo installed = installedInfo(appContext, asset.packageName);
                    installedIdentity = NavigatorPatchStore.installedIdentity(
                            appContext, asset.profile);
                    verified = matchesBackupMetadata(appContext, asset, installed)
                            && expectedFingerprint.equals(
                            currentArtifactFingerprint(appContext, asset.profile));
                } catch (Exception error) {
                    failure = clean(error.getMessage());
                    if (failure.isEmpty()) {
                        failure = "Navigator recovery verification failed";
                    }
                }
                synchronized (TRANSACTION_LOCK) {
                    stale = !expectedPhase.equals(
                            string(appContext, asset, "phase", PHASE_NONE))
                            || !transactionName.equals(
                            string(appContext, asset, "transaction", ""))
                            || !expectedFingerprint.equals(
                            string(appContext, asset, "backup_fingerprint", ""));
                    try {
                        if (!installedIdentity.equals(NavigatorPatchStore.installedIdentity(
                                appContext, asset.profile))) {
                            verified = false;
                            failure = "Installed navigator changed during recovery verification";
                        }
                    } catch (Exception error) {
                        verified = false;
                        failure = "Installed navigator changed during recovery verification";
                    }
                    if (!stale) {
                        if (verified) {
                            boolean downloaded = downloadFile(appContext, asset).isFile();
                            clearTransaction(appContext, asset);
                            setState(appContext, asset, downloaded ? READY : NOT_DOWNLOADED,
                                    downloaded ? "100%" : "0%", "");
                            AppEventLogger.event(appContext,
                                    "navigator_asset recovery_completed id=" + asset.id);
                        } else {
                            setState(appContext, asset, RECOVERY_REQUIRED, "0%", failure);
                        }
                    }
                }
            } finally {
                synchronized (ACTIVE_INSTALL_VERIFICATIONS) {
                    ACTIVE_INSTALL_VERIFICATIONS.remove(asset.id);
                }
                if (stale) {
                    reconcileInstall(appContext, asset);
                }
            }
        }, "navigator-asset-recovery-verify-" + asset.id);
        try {
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        } catch (RuntimeException error) {
            synchronized (ACTIVE_INSTALL_VERIFICATIONS) {
                ACTIVE_INSTALL_VERIFICATIONS.remove(asset.id);
            }
            setState(appContext, asset, RECOVERY_REQUIRED, "0%",
                    "Navigator recovery verification could not start");
        }
    }

    private static void scheduleOrphanCleanup(Context context) {
        if (!ORPHAN_SWEEP_RUNNING.compareAndSet(false, true)) return;
        Context appContext = context.getApplicationContext();
        File root;
        Set<String> protectedNames;
        try {
            root = new File(appContext.getFilesDir(), "navigator-patcher").getCanonicalFile();
            protectedNames = protectedTransactionNames(appContext, root);
        } catch (IOException error) {
            ORPHAN_SWEEP_RUNNING.set(false);
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                File[] children = root.listFiles();
                if (children == null) return;
                int inspected = 0;
                long now = System.currentTimeMillis();
                for (File child : children) {
                    if (inspected >= MAX_ORPHAN_DELETIONS_PER_SWEEP) break;
                    if (!child.isDirectory()
                            || !child.getName().startsWith("asset-")
                            || now - child.lastModified() < ORPHAN_MIN_AGE_MS) {
                        continue;
                    }
                    inspected++;
                    Set<String> currentProtected = protectedTransactionNames(appContext, root);
                    if (protectedNames.contains(child.getName())
                            || currentProtected.contains(child.getName())) {
                        continue;
                    }
                    try {
                        if (!root.equals(child.getCanonicalFile().getParentFile())) continue;
                        deleteTreeUnderRoot(root, child);
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                ORPHAN_SWEEP_RUNNING.set(false);
            }
        }, "navigator-asset-orphan-sweep");
        try {
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        } catch (RuntimeException error) {
            ORPHAN_SWEEP_RUNNING.set(false);
        }
    }

    private static Set<String> protectedTransactionNames(Context context, File canonicalRoot) {
        Set<String> result = new HashSet<>();
        for (Asset asset : ASSETS) {
            String transactionName = string(context, asset, "transaction", "");
            if (!transactionName.isEmpty()) result.add(transactionName);
        }
        synchronized (ACTIVE_TRANSACTION_DIRECTORIES) {
            result.addAll(ACTIVE_TRANSACTION_DIRECTORIES);
        }
        try {
            File global = NavigatorPatchStore.transactionDirectory(context).getCanonicalFile();
            if (canonicalRoot.equals(global.getParentFile())) result.add(global.getName());
        } catch (IOException ignored) {
        }
        return result;
    }

    private static void deleteTreeUnderRoot(File root, File file) throws IOException {
        File canonical = file.getCanonicalFile();
        String prefix = root.getPath() + File.separator;
        if (!canonical.getPath().startsWith(prefix)) {
            file.delete();
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTreeUnderRoot(root, child);
        }
        file.delete();
    }

    private static void reconcileCatalogRevision(Context context, Asset asset) {
        String previous = string(context, asset, "catalog_sha", "");
        if (asset.sha256.equals(previous)) return;
        android.content.SharedPreferences.Editor editor = prefs(context).edit()
                .putString(key(asset, "catalog_sha"), asset.sha256)
                .remove(key(asset, "installed_identity"))
                .remove(key(asset, "installed_matches"))
                .putBoolean(key(asset, "download_ready"), false);
        if (PHASE_NONE.equals(string(context, asset, "phase", PHASE_NONE))) {
            editor.putString(key(asset, "state"), NOT_DOWNLOADED)
                    .putString(key(asset, "progress"), "0%")
                    .putString(key(asset, "error"), "");
        }
        editor.commit();
    }

    private static PackageInfo installedInfo(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static String installedSigner(Context context, String packageName) throws Exception {
        return NavigatorSigningKey.installedCertificateSha256(context, packageName);
    }

    private static String currentArtifactFingerprint(Context context,
            NavigatorPatchStore.Profile profile) throws Exception {
        File directory = new File(context.getCacheDir(), "navigator-assets-current-"
                + UUID.randomUUID());
        try {
            return NavigatorApkSet.materializeInstalled(context, profile, directory).fingerprint;
        } finally {
            deleteTree(directory);
        }
    }

    private static File transaction(Context context, Asset asset) {
        String name = string(context, asset, "transaction", "");
        return name.isEmpty()
                ? null
                : new File(new File(context.getFilesDir(), "navigator-patcher"), name);
    }

    private static File createTransaction(Context context, Asset asset) throws IOException {
        File root = new File(context.getFilesDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create patcher root");
        File transaction = new File(root, "asset-" + asset.id + "-" + UUID.randomUUID());
        if (!transaction.mkdirs()) throw new IOException("Cannot create asset transaction");
        return transaction;
    }

    private static File stageForInstaller(Context context, Asset asset, File source)
            throws Exception {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create installer staging directory");
        }
        File staged = new File(directory, asset.fileName);
        // A previous installer may still hold this URI: do not truncate identical bytes.
        if (staged.isFile() && asset.sha256.equals(sha256(staged))) return staged;
        copyFile(source, staged);
        return staged;
    }

    private static void launchInstall(Context context, File staged) {
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", staged);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(android.content.ClipData.newRawUri(staged.getName(), uri));
        context.startActivity(intent);
    }

    private static void launchUninstall(Context context, String packageName) {
        Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static DownloadManager downloadManager(Context context) throws IllegalStateException {
        DownloadManager manager = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("DownloadManager is unavailable");
        return manager;
    }

    private static Asset require(String id) throws IOException {
        Asset asset = findAsset(id);
        if (asset != null) return asset;
        throw new IOException("Unknown navigator asset: " + id);
    }

    private static Asset findAsset(String id) {
        for (Asset asset : ASSETS) if (asset.id.equals(id)) return asset;
        return null;
    }

    static boolean isCatalogPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        for (Asset asset : ASSETS) {
            if (asset.packageName.equals(packageName)) return true;
        }
        return false;
    }

    static void onCatalogPackageChanged(Context context, String packageName) {
        if (!isCatalogPackage(packageName)) return;
        MainActivity.requestPatchUiStateRefresh(
                context.getApplicationContext(), true, "navigator-package-change");
    }

    private static File downloadFile(Context context, Asset asset) {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(directory == null ? context.getFilesDir() : directory, asset.fileName);
    }

    private static ValidationTicket validationTicket(Context context, Asset asset) {
        long generation = prefs(context).getLong(key(asset, "attempt_generation"), 0L);
        long downloadId = prefs(context).getLong(key(asset, "download_id"), NO_DOWNLOAD);
        String fileName = string(context, asset, "attempt_file", asset.fileName);
        return new ValidationTicket(generation, downloadId, fileName);
    }

    private static File candidateFile(Context context, Asset asset, ValidationTicket ticket) {
        File stable = downloadFile(context, asset);
        return new File(stable.getParentFile(), ticket.fileName);
    }

    private static long recoverReservedDownloadId(
            Context context, Asset asset, ValidationTicket reservation) {
        File expected = candidateFile(context, asset, reservation);
        try (Cursor cursor = downloadManager(context).query(new DownloadManager.Query())) {
            if (cursor == null) return NO_DOWNLOAD;
            int idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
            int uriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);
            // DownloadManager exposes its underlying provider columns to Query results. The
            // destination hint is populated before COLUMN_LOCAL_URI becomes available.
            int hintColumn = cursor.getColumnIndex("hint");
            while (cursor.moveToNext()) {
                String localUri = cursor.getString(uriColumn);
                String hint = hintColumn < 0 ? null : cursor.getString(hintColumn);
                if (!matchesReservedDestination(expected, localUri, hint)) continue;
                long recovered = cursor.getLong(idColumn);
                synchronized (TRANSACTION_LOCK) {
                    if (!ownsValidation(context, asset, reservation)) return NO_DOWNLOAD;
                    prefs(context).edit().putLong(key(asset, "download_id"), recovered).commit();
                }
                AppEventLogger.event(context, "navigator_asset download_recovered id="
                        + asset.id + " generation=" + reservation.generation
                        + " download=" + recovered);
                return recovered;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return NO_DOWNLOAD;
    }

    static boolean matchesReservedDestinationForTest(
            File expected, String localUri, String hint) throws IOException {
        return matchesReservedDestination(expected, localUri, hint);
    }

    private static boolean matchesReservedDestination(
            File expected, String localUri, String hint) throws IOException {
        File canonicalExpected = expected.getCanonicalFile();
        for (String value : new String[]{localUri, hint}) {
            if (value == null || value.isEmpty()) continue;
            String path = value;
            if (value.startsWith("file:")) {
                try {
                    path = new File(java.net.URI.create(value)).getPath();
                } catch (IllegalArgumentException error) {
                    path = null;
                }
            } else if (value.contains("://")) {
                path = null;
            }
            if (path != null && canonicalExpected.equals(new File(path).getCanonicalFile())) {
                return true;
            }
        }
        return false;
    }

    private static boolean ownsValidation(
            Context context, Asset asset, ValidationTicket expected) {
        ValidationTicket current = validationTicket(context, asset);
        return ownsAttemptForTest(expected.generation, expected.downloadId, expected.fileName,
                current.generation, current.downloadId, current.fileName);
    }

    private static boolean ownsActiveDownload(
            Context context, Asset asset, ValidationTicket expected) {
        return DOWNLOADING.equals(string(context, asset, "state", NOT_DOWNLOADED))
                && ownsValidation(context, asset, expected);
    }

    private static void failActiveDownload(Context context, Asset asset,
            ValidationTicket ticket, String category, String detail) {
        synchronized (TRANSACTION_LOCK) {
            if (ownsActiveDownload(context, asset, ticket)) {
                try {
                    NavigatorDownloadAttemptGuard.discardFailed(
                            candidateFile(context, asset, ticket));
                } catch (IOException cleanupError) {
                    detail = clean(detail) + "; cleanup failed: "
                            + clean(cleanupError.getMessage());
                }
                failDownload(context, asset, category, detail);
            }
        }
    }

    static boolean ownsAttemptForTest(long expectedGeneration, long expectedDownloadId,
            String expectedFileName, long currentGeneration, long currentDownloadId,
            String currentFileName) {
        return expectedGeneration == currentGeneration && expectedDownloadId == currentDownloadId
                && Objects.equals(expectedFileName, currentFileName);
    }

    static boolean isActiveDownloadManagerStatusForTest(int status) {
        return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED;
    }

    private static void deleteStaleCandidate(Context context, Asset asset, File candidate) {
        try {
            NavigatorDownloadAttemptGuard.settleValidated(
                    candidate, downloadFile(context, asset), false);
        } catch (IOException ignored) {
        }
    }

    private static boolean settleValidatedAttempt(
            File candidate, File stable, boolean ownsAttempt) throws IOException {
        try {
            return NavigatorDownloadAttemptGuard.settleValidated(candidate, stable, ownsAttempt);
        } catch (IOException error) {
            throw new AssetFailure(ERROR_STORAGE,
                    "Navigator cache promotion failed: " + clean(error.getMessage()), error);
        }
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(Asset asset, String suffix) {
        return asset.id + "_" + suffix;
    }

    private static String string(Context context, Asset asset, String suffix, String fallback) {
        return prefs(context).getString(key(asset, suffix), fallback);
    }

    private static void setState(Context context, Asset asset, String state, String progress,
            String error) {
        String detail = error == null ? "" : error;
        prefs(context).edit()
                .putString(key(asset, "state"), state)
                .putString(key(asset, "progress"), progress == null ? "0%" : progress)
                .putString(key(asset, "error"), detail)
                .putString(key(asset, "error_category"), detail.isEmpty() ? "" : ERROR_SYSTEM)
                .putString(key(asset, "error_detail"), detail)
                .commit();
    }

    private static void setDownloadReady(Context context, Asset asset, boolean ready) {
        prefs(context).edit().putBoolean(key(asset, "download_ready"), ready).commit();
    }

    private static void fail(Context context, Asset asset, String detail) {
        failDownload(context, asset, ERROR_SYSTEM, detail);
    }

    private static void failDownload(
            Context context, Asset asset, String category, String detail) {
        String safe = clean(detail);
        prefs(context).edit()
                .putBoolean(key(asset, "download_ready"), false)
                .putString(key(asset, "state"), ERROR)
                .putString(key(asset, "progress"), "0%")
                .putString(key(asset, "error"), safe)
                .putString(key(asset, "error_category"), category)
                .putString(key(asset, "error_detail"), safe)
                .commit();
        AppEventLogger.event(context, "navigator_asset error id=" + asset.id
                + " category=" + category + " detail=" + safe);
        MainActivity.requestNavigatorAssetCompletionRefresh(context.getApplicationContext());
    }

    private static void failDownloadStart(Context context, Asset asset,
            ValidationTicket previousTicket, long generation, File destination,
            String category, String detail) {
        synchronized (TRANSACTION_LOCK) {
            prefs(context).edit()
                    .remove(key(asset, "download_id"))
                    .putLong(key(asset, "attempt_generation"), generation)
                    .putString(key(asset, "attempt_file"), destination.getName())
                    .commit();
            deleteStaleCandidate(context, asset, candidateFile(context, asset, previousTicket));
            try {
                NavigatorDownloadAttemptGuard.discardFailed(destination);
            } catch (IOException cleanupError) {
                detail = clean(detail) + "; cleanup failed: "
                        + clean(cleanupError.getMessage());
            }
            failDownload(context, asset, category, detail);
        }
    }

    private static String failureCategory(Exception error) {
        return error instanceof AssetFailure ? ((AssetFailure) error).category : ERROR_SYSTEM;
    }

    static String materializationFailureCategoryForTest(Exception error) {
        return error instanceof NavigatorApkSet.StorageException
                ? ERROR_STORAGE : ERROR_INVALID_APK;
    }

    static String downloadFailureCategoryForTest(int reason) {
        return reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                || reason == DownloadManager.ERROR_DEVICE_NOT_FOUND
                || reason == DownloadManager.ERROR_FILE_ERROR
                || reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS
                ? ERROR_STORAGE
                : reason == DownloadManager.ERROR_CANNOT_RESUME
                || reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS
                || reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE
                || reason == DownloadManager.ERROR_HTTP_DATA_ERROR
                || reason >= 400 && reason <= 599
                ? ERROR_NETWORK : ERROR_SYSTEM;
    }

    private static void clearTransaction(Context context, Asset asset) {
        clearLocalTransaction(context, asset, true);
    }

    private static boolean installResultChanged(Context context, Asset asset) {
        if (!PHASE_INSTALL.equals(string(context, asset, "phase", PHASE_NONE))) return true;
        // Destructive replacement already requires removal of the original package.
        if (!string(context, asset, "backup_signer", "").isEmpty()) return true;
        try {
            return installIdentityChanged(string(context, asset, "install_previous_identity", ""),
                    installedIdentity(context, asset));
        } catch (Exception unavailable) {
            return false;
        }
    }

    static boolean installIdentityChanged(String before, String current) {
        return before != null && !before.isEmpty() && current != null
                && !current.isEmpty() && !before.equals(current);
    }

    private static void clearLocalTransaction(
            Context context, Asset asset, boolean deleteBackup) {
        synchronized (TRANSACTION_LOCK) {
            File transaction = transaction(context, asset);
            if (deleteBackup && transaction != null) {
                deleteTree(transaction);
            }
            deleteFile(new File(new File(context.getFilesDir(), "updates"), asset.fileName));
            prefs(context).edit()
                    .remove(key(asset, "transaction"))
                    .remove(key(asset, "backup_version"))
                    .remove(key(asset, "backup_code"))
                    .remove(key(asset, "backup_signer"))
                    .remove(key(asset, "backup_fingerprint"))
                    .remove(key(asset, "uninstall_requested_ms"))
                    .remove(key(asset, "install_previous_identity"))
                    .remove(key(asset, "restore_verified_transaction"))
                    .remove(key(asset, "restore_verified_fingerprint"))
                    .putString(key(asset, "phase"), PHASE_NONE)
                    .commit();
        }
    }

    private static boolean isMonolithicApk(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("AndroidManifest.xml") != null;
        } catch (FileNotFoundException error) {
            throw new AssetFailure(ERROR_STORAGE,
                    "Downloaded file could not be opened", error);
        } catch (java.util.zip.ZipException error) {
            throw new IOException("Downloaded file is not a valid APK", error);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02X", value));
        }
        return result.toString();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) return "Unknown navigator asset error";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
