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
            this.id = id;
            this.englishLabel = englishLabel;
            this.ukrainianLabel = ukrainianLabel;
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
        public final boolean installed;
        public final boolean downloadable;

        AssetSnapshot(Asset asset, boolean ukrainian, String state, String progress,
                String error, boolean installed) {
            this.asset = asset;
            this.id = asset.id;
            this.label = asset.label(ukrainian);
            this.versionName = asset.versionName;
            this.packageName = asset.packageName;
            this.state = state;
            this.progress = progress;
            this.error = error;
            this.installed = installed;
            this.downloadable = NOT_DOWNLOADED.equals(state) || ERROR.equals(state)
                    || (!installed && !DOWNLOADING.equals(state)
                    && !INSTALL_REQUESTED.equals(state) && !UNINSTALL_REQUESTED.equals(state));
        }

        AssetSnapshot localized(boolean ukrainian) {
            return new AssetSnapshot(asset, ukrainian, state, progress, error, installed);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof AssetSnapshot)) return false;
            AssetSnapshot other = (AssetSnapshot) value;
            return installed == other.installed && downloadable == other.downloadable
                    && Objects.equals(id, other.id)
                    && Objects.equals(label, other.label)
                    && Objects.equals(versionName, other.versionName)
                    && Objects.equals(packageName, other.packageName)
                    && Objects.equals(state, other.state)
                    && Objects.equals(progress, other.progress)
                    && Objects.equals(error, other.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, label, versionName, packageName, state, progress, error,
                    installed, downloadable);
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
        boolean installed = matchesInstalledCached(context, asset);
        String state = string(context, asset, "state", NOT_DOWNLOADED);
        if (installed) state = INSTALLED;
        String progress = string(context, asset, "progress", "0%");
        String error = string(context, asset, "error", "");
        if (DOWNLOADING.equals(state)) {
            progress = queryProgress(context, asset);
        } else if (VERIFYING.equals(state)) {
            validateDownloadedAsync(context, asset);
        }
        return new AssetSnapshot(asset, ukrainian, state, progress, error, installed);
    }

    static void startDownload(Context context, String assetId) throws IOException {
        Asset asset = require(assetId);
        DownloadManager manager = downloadManager(context);
        long previous = prefs(context).getLong(key(asset, "download_id"), NO_DOWNLOAD);
        if (previous != NO_DOWNLOAD) {
            try {
                manager.remove(previous);
            } catch (RuntimeException ignored) {
            }
        }
        File destination = downloadFile(context, asset);
        deleteFile(destination);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(asset.url))
                .setTitle(asset.englishLabel + " " + asset.versionName)
                .setDescription("BYD HUD navigator asset")
                .setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, asset.fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        long id;
        try {
            id = manager.enqueue(request);
        } catch (RuntimeException error) {
            fail(context, asset, "Download enqueue failed: " + error.getMessage());
            throw new IOException("Download enqueue failed", error);
        }
        prefs(context).edit()
                .putLong(key(asset, "download_id"), id)
                .putString(key(asset, "state"), DOWNLOADING)
                .putString(key(asset, "progress"), "0%")
                .putString(key(asset, "error"), "")
                .putString(key(asset, "phase"), PHASE_NONE)
                .commit();
        AppEventLogger.event(context, "navigator_asset download_start id=" + asset.id);
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
        if (installed != null && matchesInstalled(context, asset)) {
            setState(context, asset, INSTALLED, "100%", "");
            return;
        }
        boolean destructive = installed != null && requiresDestructiveConfirmation(context, assetId);
        if (destructive && !destructiveApproved) throw new DestructiveConfirmationRequired();
        File staged = stageForInstaller(context, asset, file);
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
                setState(context, asset, INSTALLED, "100%", "");
                clearTransaction(context, asset);
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
            prefs(context).edit().putString(key(asset, "phase"), PHASE_NONE).commit();
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
            if (READY.equals(state) && !downloadFile(context, asset).isFile()) {
                setState(context, asset, ERROR, "0%", "Downloaded navigator asset is missing");
            }
            return;
        }
        long id = prefs(context).getLong(key(asset, "download_id"), NO_DOWNLOAD);
        if (id == NO_DOWNLOAD) {
            fail(context, asset, "Download id is missing");
            return;
        }
        DownloadManager manager = downloadManager(context);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                fail(context, asset, "Download row is missing");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                setState(context, asset, VERIFYING, "100%", "");
                validateDownloadedAsync(context, asset);
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                fail(context, asset, "Download failed reason=" + reason);
            } else {
                String progress = progressFromCursor(cursor);
                prefs(context).edit().putString(key(asset, "progress"), progress).apply();
            }
        } catch (RuntimeException error) {
            fail(context, asset, "Download status read failed: " + error.getMessage());
        }
    }

    private static void validateDownloadedAsync(Context context, Asset asset) {
        synchronized (ACTIVE_VALIDATIONS) {
            if (!ACTIVE_VALIDATIONS.add(asset.id)) return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                File file = downloadFile(appContext, asset);
                validate(appContext, asset, file);
                setState(appContext, asset, READY, "100%", "");
                AppEventLogger.event(appContext,
                        "navigator_asset verified id=" + asset.id);
            } catch (Exception error) {
                deleteFile(downloadFile(appContext, asset));
                fail(appContext, asset, clean(error.getMessage()));
            } finally {
                synchronized (ACTIVE_VALIDATIONS) {
                    ACTIVE_VALIDATIONS.remove(asset.id);
                }
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
            setState(context, asset, READY, "100%", "");
            return file;
        } catch (Exception error) {
            deleteFile(file);
            fail(context, asset, clean(error.getMessage()));
            throw error;
        }
    }

    private static void validate(Context context, Asset asset, File file) throws Exception {
        if (!file.isFile()) throw new IOException("Downloaded navigator asset is missing");
        if (!isMonolithicApk(file)) {
            throw new IOException("Navigator asset must be a monolithic APK");
        }
        String actualSha = sha256(file);
        if (!asset.sha256.equals(actualSha)) {
            throw new IOException("Navigator asset SHA-256 mismatch expected=" + asset.sha256
                    + " actual=" + actualSha);
        }
        File validation = new File(new File(context.getCacheDir(), "navigator-assets"),
                "validate-" + asset.id + "-" + UUID.randomUUID());
        try {
            NavigatorApkSet.SetInfo set = NavigatorApkSet.materializeSource(
                    context, asset.profile, file, validation);
            if (set.members.size() != 1 || !asset.packageName.equals(set.packageName)
                    || !asset.versionName.equals(set.versionName)
                    || asset.versionCode != set.versionCode
                    || !asset.signerSha256.equals(set.signerSha256)) {
                throw new IOException("Navigator asset metadata mismatch");
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
                            clearTransaction(appContext, asset);
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
                .remove(key(asset, "installed_matches"));
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
            throws IOException {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create installer staging directory");
        }
        File staged = new File(directory, asset.fileName);
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

    private static File downloadFile(Context context, Asset asset) {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(directory == null ? context.getFilesDir() : directory, asset.fileName);
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
        prefs(context).edit()
                .putString(key(asset, "state"), state)
                .putString(key(asset, "progress"), progress == null ? "0%" : progress)
                .putString(key(asset, "error"), error == null ? "" : error)
                .commit();
    }

    private static void fail(Context context, Asset asset, String detail) {
        String safe = clean(detail);
        setState(context, asset, ERROR, "0%", safe);
        AppEventLogger.event(context, "navigator_asset error id=" + asset.id + " detail=" + safe);
    }

    private static void clearTransaction(Context context, Asset asset) {
        clearLocalTransaction(context, asset, true);
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
                    .remove(key(asset, "restore_verified_transaction"))
                    .remove(key(asset, "restore_verified_fingerprint"))
                    .putString(key(asset, "phase"), PHASE_NONE)
                    .commit();
        }
    }

    private static boolean isMonolithicApk(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("AndroidManifest.xml") != null;
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
