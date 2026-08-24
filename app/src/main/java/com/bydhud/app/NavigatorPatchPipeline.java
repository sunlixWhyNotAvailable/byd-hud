package com.bydhud.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class NavigatorPatchPipeline {
    private static final ConcurrentHashMap<NavigatorPatchStore.Profile, Thread> ACTIVE =
            new ConcurrentHashMap<>();

    static final class OperationCancelledException extends IOException {
        OperationCancelledException() {
            super("Operation cancelled");
        }
    }
    private static final long MAX_SOURCE_APK_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_DEX_BYTES = 64L * 1024L * 1024L;
    private static final String GMAPS_PATCHABLE = "PATCHABLE_STOCK";
    private static final String GMAPS_DIRECT = "MESSENGER_BRIDGE_POC";
    private static final String GMAPS_DIRECT_UPGRADEABLE = "MESSENGER_BRIDGE_UPGRADEABLE";
    private static final String GMAPS_AUDIO = "NAVIGATION_AUDIO";
    private static final String GMAPS_GMS_CORE_ACTIVE = "ACTIVE";
    private static final String GMAPS_GMS_CORE_SUPPRESSED = "ALREADY_SUPPRESSED";
    private static final String GMAPS_GMS_CORE_UI_SUPPRESSED = "UI_SUPPRESSED";
    private static final String GMAPS_PIP_PATCHABLE = "PATCHABLE_STOCK";
    private static final String GMAPS_PIP_PATCHED = "PICTURE_IN_PICTURE_DISABLED";

    static final class ScanResult {
        final NavigatorPatchStore.Profile profile;
        final String sha256;
        final String versionName;
        final long versionCode;
        final String signerSha256;
        final String directState;
        final String gmsCoreState;
        final String optionalState;
        final String alertState;
        final String reason;

        ScanResult(NavigatorPatchStore.Profile profile, String sha256,
                String versionName, long versionCode, String signerSha256,
                String directState, String gmsCoreState, String optionalState,
                String alertState, String reason) {
            this.profile = profile;
            this.sha256 = sha256;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.directState = directState;
            this.gmsCoreState = gmsCoreState;
            this.optionalState = optionalState;
            this.alertState = alertState;
            this.reason = reason;
        }

        ScanResult(NavigatorPatchStore.Profile profile, String sha256,
                String versionName, long versionCode, String signerSha256,
                String directState, String optionalState, String alertState, String reason) {
            this(profile, sha256, versionName, versionCode, signerSha256, directState,
                    NavigatorPatchStore.NOT_CHECKED, optionalState, alertState, reason);
        }

    }

    static final class PreparedPatch {
        final NavigatorPatchStore.Profile profile;
        final ScanResult input;
        final ScanResult output;
        final File directory;
        final boolean destructive;
        final boolean optionalApplied;
        final long installedUpdateTime;
        final long installedVersionCode;
        final String installedSignerSha256;
        final String installedFingerprint;

        PreparedPatch(NavigatorPatchStore.Profile profile, ScanResult input, ScanResult output,
                File directory, boolean destructive, boolean optionalApplied,
                long installedUpdateTime, long installedVersionCode,
                String installedSignerSha256, String installedFingerprint) {
            this.profile = profile;
            this.input = input;
            this.output = output;
            this.directory = directory;
            this.destructive = destructive;
            this.optionalApplied = optionalApplied;
            this.installedUpdateTime = installedUpdateTime;
            this.installedVersionCode = installedVersionCode;
            this.installedSignerSha256 = installedSignerSha256;
            this.installedFingerprint = installedFingerprint;
        }
    }

    /** Result produced in the patcher process; it contains no store or installer state. */
    static final class WorkerPatchResult {
        final ScanResult input;
        final ScanResult output;
        final File transaction;
        final boolean optionalApplied;

        WorkerPatchResult(ScanResult input, ScanResult output, File transaction,
                boolean optionalApplied) {
            this.input = input;
            this.output = output;
            this.transaction = transaction;
            this.optionalApplied = optionalApplied;
        }
    }

    private static final class PatchOutcome {
        final boolean gmsCoreFailed;
        final boolean optionalFailed;
        final boolean auxiliaryFailed;

        PatchOutcome(boolean gmsCoreFailed, boolean optionalFailed, boolean auxiliaryFailed) {
            this.gmsCoreFailed = gmsCoreFailed;
            this.optionalFailed = optionalFailed;
            this.auxiliaryFailed = auxiliaryFailed;
        }
    }

    private NavigatorPatchPipeline() {
    }

    static NavigatorPatchStore.Profile profileFromId(String id) {
        return NavigatorPatchStore.Profile.fromId(id);
    }

    static ScanResult workerScan(Context context, String profileId,
            File selectedSource, File setDirectory) throws Exception {
        NavigatorPatchStore.Profile profile = profileFromId(profileId);
        if (profile == null) throw new IOException("Unknown patch profile");
        return workerScan(context, profile, selectedSource, setDirectory);
    }

    static WorkerPatchResult workerPrepare(Context context, String profileId,
            File selectedSource, File transaction, ScanResult expected) throws Exception {
        NavigatorPatchStore.Profile profile = profileFromId(profileId);
        if (profile == null) throw new IOException("Unknown patch profile");
        return workerPrepare(context, profile, selectedSource, transaction, expected);
    }

    static ScanResult workerInspectDirectory(Context context, String profileId,
            File directory) throws Exception {
        NavigatorPatchStore.Profile profile = profileFromId(profileId);
        if (profile == null) throw new IOException("Unknown patch profile");
        return workerInspectDirectory(context, profile, directory);
    }

    static Bundle workerBundle(ScanResult result) {
        Bundle data = new Bundle();
        data.putString("profile", result.profile.id);
        data.putString("sha256", result.sha256);
        data.putString("version_name", result.versionName);
        data.putLong("version_code", result.versionCode);
        data.putString("signer", result.signerSha256);
        data.putString("direct", result.directState);
        data.putString("gms_core", result.gmsCoreState);
        data.putString("optional", result.optionalState);
        data.putString("alert", result.alertState);
        data.putString("reason", result.reason);
        return data;
    }

    static ScanResult workerUnbundle(Bundle data) {
        if (data == null) return null;
        NavigatorPatchStore.Profile profile = profileFromId(data.getString("profile", ""));
        if (profile == null) throw new IllegalArgumentException("Unknown patch profile");
        return new ScanResult(profile, data.getString("sha256", ""),
                data.getString("version_name", ""), data.getLong("version_code", -1L),
                data.getString("signer", ""),
                data.getString("direct", NavigatorPatchStore.NOT_CHECKED),
                data.getString("gms_core", NavigatorPatchStore.NOT_CHECKED),
                data.getString("optional", NavigatorPatchStore.NOT_CHECKED),
                data.getString("alert", NavigatorPatchStore.NOT_CHECKED),
                data.getString("reason", ""));
    }

    /**
     * Stages a selected URI in the main process. The worker receives only this private file.
     */
    static File stageSelectedSource(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        String selected = NavigatorPatchStore.selectedUri(context, profile);
        if (selected == null || selected.isEmpty()) return null;
        String name = NavigatorPatchStore.selectedName(context, profile);
        File input = temporary(context, profile.id + "-worker-source-", suffix(name));
        try {
            copyUri(context, Uri.parse(selected), input);
            return input;
        } catch (Exception error) {
            input.delete();
            throw error;
        }
    }

    static File workerSetDirectory(Context context, NavigatorPatchStore.Profile profile,
            String kind) throws IOException {
        return temporaryDirectory(context, profile.id + "-worker-" + kind + "-");
    }

    static File workerTransaction(Context context, NavigatorPatchStore.Profile profile)
            throws IOException {
        File root = new File(context.getFilesDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create patcher root");
        }
        File transaction = new File(root, "tx-" + UUID.randomUUID());
        if (!transaction.mkdirs()) {
            throw new IOException("Cannot create transaction directory");
        }
        return transaction;
    }

    static ScanResult cachedScan(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        NavigatorPatchStore.ProfileSnapshot snapshot = NavigatorPatchStore.snapshot(
                context, profile);
        android.content.SharedPreferences prefs = NavigatorPatchStore.prefs(context);
        String sha = prefs.getString(profile.id + "_scan_sha", "");
        String version = prefs.getString(profile.id + "_scan_version", "");
        long versionCode = prefs.getLong(profile.id + "_scan_version_code", -1L);
        if (snapshot == null || sha.isEmpty() || versionCode < 0L
                || snapshot.directState == null || snapshot.directState.isEmpty()) {
            throw new IOException("Compatibility check is required before patching");
        }
        return new ScanResult(profile, sha, version, versionCode, snapshot.externalSource
                ? prefs.getString(profile.id + "_selected_signer", "")
                : NavigatorSigningKey.installedCertificateSha256(context, profile.packageName),
                snapshot.directState, snapshot.gmsCoreState, snapshot.optionalState,
                snapshot.alertState, snapshot.reason);
    }

    static ScanResult scanViaWorker(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        int previousPriority = lowerCurrentThreadPriority();
        File source = null;
        File output = null;
        boolean claimed = false;
        boolean registered = false;
        try {
            register(profile);
            registered = true;
            NavigatorPatchStore.claim(context, profile, NavigatorPatchStore.OP_CHECK,
                    NavigatorPatchStore.WAITING_FOR_PATCHER, "Waiting for patcher");
            claimed = true;
            String operation = NavigatorPatchStore.operation(context, profile).operationToken;
            source = stageSelectedSource(context, profile);
            output = workerSetDirectory(context, profile, "scan");
            ScanResult result = NavigatorPatchWorkerClient.scan(
                    context, profile, operation, source, output);
            checkCancelled(context, profile);
            if (!NavigatorPatchStore.completeScanUnlessCancelled(
                    context, result, NavigatorPatchStore.VERIFIED,
                    "Compatibility check completed")) {
                throw new OperationCancelledException();
            }
            return result;
        } catch (Exception error) {
            if (claimed) {
                if (NavigatorPatchStore.isCancellationRequested(context, profile)
                        || error instanceof OperationCancelledException
                        || Thread.currentThread().isInterrupted()) {
                    NavigatorPatchStore.markCancelled(context, profile, "Check cancelled");
                } else {
                    saveFailure(context, profile, error);
                }
            }
            throw error;
        } finally {
            deleteTree(source);
            deleteTree(output);
            if (registered) unregister(profile);
            restoreCurrentThreadPriority(previousPriority);
        }
    }

    static PreparedPatch prepareViaWorker(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        int previousPriority = lowerCurrentThreadPriority();
        File source = null;
        File transaction = null;
        boolean claimed = false;
        boolean registered = false;
        try {
            register(profile);
            registered = true;
            NavigatorPatchStore.claim(context, profile, NavigatorPatchStore.OP_PATCH,
                    NavigatorPatchStore.WAITING_FOR_PATCHER, "Waiting for patcher");
            claimed = true;
            ScanResult expected = cachedScan(context, profile);
            source = stageSelectedSource(context, profile);
            transaction = workerTransaction(context, profile);
            String operation = NavigatorPatchStore.operation(context, profile).operationToken;
            WorkerPatchResult result = NavigatorPatchWorkerClient.prepare(
                    context, profile, operation, source, transaction, expected);
            checkCancelled(context, profile);
            PackageInfo initialInstalled = installedInfo(context, profile.packageName);
            long initialUpdateTime = initialInstalled == null ? -1L : initialInstalled.lastUpdateTime;
            long initialVersionCode = initialInstalled == null
                    ? -1L : initialInstalled.getLongVersionCode();
            String initialSigner = initialInstalled == null ? ""
                    : NavigatorSigningKey.installedCertificateSha256(context, profile.packageName);
            String initialFingerprint = initialInstalled == null ? ""
                    : NavigatorPatchStore.selectedUri(context, profile).isEmpty()
                    ? result.input.sha256
                    : inspectInstalled(context, profile).sha256;
            assertInstalledTarget(context, profile, initialUpdateTime, initialVersionCode,
                    initialSigner, initialFingerprint);
            checkCancelled(context, profile);
            boolean destructive = initialInstalled != null
                    && !NavigatorSigningKey.installedUsesLocalKey(context, profile.packageName);
            if (!NavigatorPatchStore.setTransaction(context, profile, transaction, destructive,
                    result.output, initialUpdateTime, initialVersionCode, initialSigner,
                    initialFingerprint,
                    destructive ? "Replacement requires data removal" : "Ready to install")) {
                throw new OperationCancelledException();
            }
            transaction = null;
            return new PreparedPatch(profile, result.input, result.output, result.transaction,
                    destructive, result.optionalApplied, initialUpdateTime, initialVersionCode,
                    initialSigner, initialFingerprint);
        } catch (Exception error) {
            if (claimed) {
                if (NavigatorPatchStore.isCancellationRequested(context, profile)
                        || error instanceof OperationCancelledException
                        || Thread.currentThread().isInterrupted()) {
                    NavigatorPatchStore.markCancelled(context, profile,
                            "Patch preparation cancelled");
                } else {
                    NavigatorPatchStore.transition(context, profile,
                            NavigatorPatchStore.FAILED, error.getMessage());
                }
            }
            deleteTree(transaction);
            throw error;
        } finally {
            deleteTree(source);
            if (registered) unregister(profile);
            restoreCurrentThreadPriority(previousPriority);
        }
    }

    /** Heavy read-only work executed by NavigatorPatchWorkerService. */
    static ScanResult workerScan(Context context, NavigatorPatchStore.Profile profile,
            File selectedSource, File setDirectory) throws Exception {
        checkWorkerInterrupted();
        NavigatorApkSet.SetInfo set = selectedSource == null
                ? NavigatorApkSet.materializeInstalled(context, profile, setDirectory)
                : NavigatorApkSet.materializeSource(
                        context, profile, selectedSource, setDirectory);
        checkWorkerInterrupted();
        ScanResult result = inspectComponents(context, set, metadata(set, profile));
        checkWorkerInterrupted();
        return result;
    }

    static ScanResult workerInspectDirectory(Context context,
            NavigatorPatchStore.Profile profile, File directory) throws Exception {
        checkWorkerInterrupted();
        NavigatorApkSet.SetInfo set = NavigatorApkSet.readDirectory(context, profile, directory);
        ScanResult result = inspectComponents(context, set, metadata(set, profile));
        checkWorkerInterrupted();
        return result;
    }

    /** Heavy materialize/patch/sign/verify work executed by NavigatorPatchWorkerService. */
    static WorkerPatchResult workerPrepare(Context context, NavigatorPatchStore.Profile profile,
            File selectedSource, File transaction, ScanResult expected) throws Exception {
        checkWorkerInterrupted();
        File source = new File(transaction, "source-set");
        File patched = new File(transaction, "patched-set");
        NavigatorApkSet.SetInfo sourceSet = selectedSource == null
                ? NavigatorApkSet.materializeInstalled(context, profile, source)
                : NavigatorApkSet.materializeSource(context, profile, selectedSource, source);
        checkWorkerInterrupted();
        ensureWorkingSpace(context, sourceSet);
        ScanResult input = inspectComponents(context, sourceSet, metadata(sourceSet, profile));
        if (expected != null && !expected.sha256.equals(input.sha256)) {
            throw new IOException("APK changed after compatibility check");
        }
        if (!NavigatorPatchStore.isPatchEnabled(profile, input.directState, input.gmsCoreState,
                input.optionalState, input.alertState)) {
            throw new IOException("No compatible patch is available: " + input.reason);
        }
        checkWorkerInterrupted();
        PatchOutcome outcome = buildUnsignedSet(
                context, profile, sourceSet, patched, transaction, input, false);
        checkWorkerInterrupted();
        signSet(patched);
        checkWorkerInterrupted();
        NavigatorApkSet.SetInfo outputSet = NavigatorApkSet.readDirectory(context, profile, patched);
        ScanResult output = inspectComponents(context, outputSet, metadata(outputSet, profile));
        validateWorkerOutput(profile, input, output, outcome);
        if (outcome.optionalFailed) {
            output = copyStates(output, output.directState, output.gmsCoreState,
                    NavigatorPatchStore.FAILED, output.alertState,
                    "Optional patch attempt failed");
        }
        if (outcome.gmsCoreFailed) {
            output = copyStates(output, output.directState, NavigatorPatchStore.FAILED,
                    output.optionalState, output.alertState,
                    "GmsCore patch attempt failed");
        }
        if (outcome.auxiliaryFailed) {
            output = copyStates(output, output.directState, output.gmsCoreState,
                    output.optionalState, NavigatorPatchStore.FAILED,
                    "PiP patch attempt failed");
        }
        boolean optionalApplied = (NavigatorPatchStore.PATCHABLE.equals(input.gmsCoreState)
                && NavigatorPatchStore.PATCHED.equals(output.gmsCoreState))
                || (NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                && NavigatorPatchStore.PATCHED.equals(output.optionalState))
                || (NavigatorPatchStore.PATCHABLE.equals(input.alertState)
                && NavigatorPatchStore.PATCHED.equals(output.alertState));
        boolean directApplied = NavigatorPatchStore.PATCHABLE.equals(input.directState)
                && NavigatorPatchStore.PATCHED.equals(output.directState);
        if (!directApplied && !optionalApplied) {
            throw new IOException("No patch component was applied");
        }
        return new WorkerPatchResult(input, output, transaction, optionalApplied);
    }

    private static void checkWorkerInterrupted() throws OperationCancelledException {
        if (Thread.currentThread().isInterrupted()) throw new OperationCancelledException();
    }

    private static void validateWorkerOutput(NavigatorPatchStore.Profile profile,
            ScanResult input, ScanResult output, PatchOutcome outcome) throws IOException {
        if (profile == NavigatorPatchStore.Profile.WAZE
                && !NavigatorPatchStore.PATCHED.equals(output.directState)) {
            throw new IOException("Direct channel post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.WAZE
                && !NavigatorPatchStore.PATCHED.equals(output.gmsCoreState)) {
            throw new IOException("Waze lanes post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.GMAPS
                && NavigatorPatchStore.PATCHABLE.equals(input.gmsCoreState)
                && !outcome.gmsCoreFailed
                && !NavigatorPatchStore.PATCHED.equals(output.gmsCoreState)) {
            throw new IOException("Google Maps GmsCore post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.GMAPS
                && NavigatorPatchStore.PATCHABLE.equals(input.directState)
                && !NavigatorPatchStore.PATCHED.equals(output.directState)) {
            throw new IOException("Google Maps direct post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.GMAPS
                && NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                && !outcome.optionalFailed
                && !NavigatorPatchStore.PATCHED.equals(output.optionalState)) {
            throw new IOException("Google Maps audio post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.GMAPS
                && NavigatorPatchStore.PATCHABLE.equals(input.alertState)
                && !outcome.auxiliaryFailed
                && !NavigatorPatchStore.PATCHED.equals(output.alertState)) {
            throw new IOException("Google Maps PiP post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.WAZE
                && NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                && !outcome.optionalFailed
                && !NavigatorPatchStore.PATCHED.equals(output.optionalState)) {
            throw new IOException("Stable session post-verification failed");
        }
        if (profile == NavigatorPatchStore.Profile.WAZE
                && NavigatorPatchStore.PATCHABLE.equals(input.alertState)
                && !NavigatorPatchStore.PATCHED.equals(output.alertState)) {
            throw new IOException("Waze alert-hook post-verification failed");
        }
    }

    private static int lowerCurrentThreadPriority() {
        int tid = Process.myTid();
        int previous = Process.getThreadPriority(tid);
        if (previous != Process.THREAD_PRIORITY_BACKGROUND) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }
        return previous;
    }

    private static void restoreCurrentThreadPriority(int previous) {
        if (Process.getThreadPriority(Process.myTid()) != previous) {
            Process.setThreadPriority(previous);
        }
    }

    static boolean cancel(Context context, NavigatorPatchStore.Profile profile) {
        if (!NavigatorPatchStore.requestCancel(context, profile)) return false;
        Thread worker = ACTIVE.get(profile);
        if (worker != null) worker.interrupt();
        if (worker == null && NavigatorPatchStore.CANCEL_REQUESTED.equals(
                NavigatorPatchStore.operation(context, profile).phase)) {
            finishQueuedCancellation(context, profile, "Cancelled before installation");
        }
        return true;
    }

    static void finishQueuedCancellation(Context context,
            NavigatorPatchStore.Profile profile, String detail) {
        File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
        NavigatorPackageInstaller.abandonPreparedSession(context, profile);
        deleteTree(transaction);
        NavigatorPatchStore.clearTransactionMetadata(context, profile);
        NavigatorPatchStore.releaseInstall(context, profile);
        NavigatorPatchStore.markCancelled(context, profile, detail);
    }

    static boolean dismiss(Context context, NavigatorPatchStore.Profile profile) {
        return NavigatorPatchStore.dismiss(context, profile);
    }

    static PreparedPatch resumePrepared(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        int previousPriority = lowerCurrentThreadPriority();
        boolean registered = false;
        try {
            register(profile);
            registered = true;
            checkCancelled(context, profile);
            File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
            File patched = new File(transaction, "patched-set");
            if (!patched.isDirectory()) throw new IOException("Queued APK-set is missing");
            String operation = UUID.randomUUID().toString();
            ScanResult output = NavigatorPatchWorkerClient.inspectDirectory(
                    context, profile, operation, patched);
            checkCancelled(context, profile);
            if (!NavigatorPatchStore.expectedSha(context, profile).equals(output.sha256)
                    || NavigatorPatchStore.expectedVersionCode(context, profile)
                    != output.versionCode
                    || !NavigatorPatchStore.expectedSigner(context, profile).equals(
                    output.signerSha256)) {
                throw new IOException("Queued APK-set no longer matches verified output");
            }
            checkCancelled(context, profile);
            boolean optionalApplied = NavigatorPatchStore.PATCHED.equals(output.directState)
                    || NavigatorPatchStore.PATCHED.equals(output.gmsCoreState)
                    || NavigatorPatchStore.PATCHED.equals(output.optionalState)
                    || NavigatorPatchStore.PATCHED.equals(output.alertState);
            return new PreparedPatch(profile, output, output, transaction,
                    NavigatorPatchStore.operation(context, profile).destructive,
                    optionalApplied,
                    NavigatorPatchStore.initialUpdateTime(context, profile),
                    NavigatorPatchStore.initialVersionCode(context, profile),
                    NavigatorPatchStore.initialSigner(context, profile),
                    NavigatorPatchStore.initialFingerprint(context, profile));
        } catch (Exception error) {
            if (NavigatorPatchStore.isCancellationRequested(context, profile)
                    || error instanceof OperationCancelledException
                    || Thread.currentThread().isInterrupted()) {
                throw new OperationCancelledException();
            }
            throw error;
        } finally {
            if (registered) unregister(profile);
            restoreCurrentThreadPriority(previousPriority);
        }
    }

    private static void register(NavigatorPatchStore.Profile profile) throws IOException {
        Thread current = Thread.currentThread();
        Thread existing = ACTIVE.putIfAbsent(profile, current);
        if (existing != null && existing != current) {
            throw new IOException("Another operation for this navigator is active");
        }
    }

    private static void unregister(NavigatorPatchStore.Profile profile) {
        ACTIVE.remove(profile, Thread.currentThread());
    }

    static boolean hasActiveWorker(NavigatorPatchStore.Profile profile) {
        if (profile == null) return false;
        Thread worker = ACTIVE.get(profile);
        return worker != null && worker.isAlive();
    }

    private static void checkCancelled(Context context, NavigatorPatchStore.Profile profile)
            throws OperationCancelledException {
        if (NavigatorPatchStore.isCancellationRequested(context, profile)) {
            throw new OperationCancelledException();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new OperationCancelledException();
        }
    }

    static NavigatorPatchStore.Profile inspectSelectedPackage(
            Context context, NavigatorPatchStore.Profile expectedProfile,
            Uri uri, String displayName) throws Exception {
        if (expectedProfile == null) throw new IllegalArgumentException("Patch profile is required");
        NavigatorPatchStore.claim(
                context, expectedProfile, NavigatorPatchStore.OP_SELECT,
                NavigatorPatchStore.COPYING, displayName);
        File input = temporary(context, "selected-", suffix(displayName));
        File setDirectory = temporaryDirectory(context, "selected-set-");
        try {
            copyUri(context, uri, input);
            NavigatorApkSet.SetInfo set = NavigatorApkSet.materializeSource(
                    context, expectedProfile, input, setDirectory);
            NavigatorPatchStore.Profile profile = expectedProfile;
            PackageInfo installed = installedInfo(context, profile.packageName);
            if (installed != null && set.versionCode < installed.getLongVersionCode()) {
                throw new IOException("Selected APK-set is older than installed version");
            }
            context.getContentResolver().takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            NavigatorPatchStore.selectExternal(
                    context, profile, uri.toString(), displayName,
                    set.versionName, set.versionCode, set.fingerprint, set.signerSha256);
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.IDLE, "");
            return profile;
        } catch (Exception error) {
            NavigatorPatchStore.transition(
                    context, expectedProfile, NavigatorPatchStore.FAILED, error.getMessage());
            throw error;
        } finally {
            input.delete();
            deleteTree(setDirectory);
        }
    }

    static ScanResult scan(Context context, NavigatorPatchStore.Profile profile) throws Exception {
        return scanViaWorker(context, profile);
    }

    static PreparedPatch prepare(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        return prepareViaWorker(context, profile);
    }

    static ScanResult inspectInstalled(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        File setDirectory = temporaryDirectory(context, profile.id + "-installed-");
        try {
            return NavigatorPatchWorkerClient.inspectInstalled(
                    context, profile, UUID.randomUUID().toString(), setDirectory);
        } finally {
            deleteTree(setDirectory);
        }
    }

    static ScanResult verifyRecoverySource(Context context,
            NavigatorPatchStore.Profile profile, File source) throws Exception {
        ScanResult inspected = NavigatorPatchWorkerClient.inspectDirectory(
                context, profile, UUID.randomUUID().toString(), source);
        return new ScanResult(profile, inspected.sha256, inspected.versionName,
                inspected.versionCode, inspected.signerSha256,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED, "");
    }

    static void discardPrepared(Context context, PreparedPatch prepared, String reason) {
        if (prepared != null) deleteTree(prepared.directory);
        NavigatorPatchStore.clearTransactionMetadata(context);
        NavigatorPatchStore.transition(context,
                prepared == null ? null : prepared.profile,
                NavigatorPatchStore.FAILED, reason);
    }

    private static ScanResult metadata(NavigatorApkSet.SetInfo set,
            NavigatorPatchStore.Profile profile) {
        return new ScanResult(profile, set.fingerprint, set.versionName,
                set.versionCode, set.signerSha256,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED, "");
    }

    private static final class GmapsMemberInspection {
        final String direct;
        final String gmsCore;
        final String audio;
        final String pip;

        GmapsMemberInspection(String direct, String gmsCore, String audio, String pip) {
            this.direct = direct;
            this.gmsCore = gmsCore;
            this.audio = audio;
            this.pip = pip;
        }
    }

    private static GmapsMemberInspection inspectGmapsMember(
            NavigatorApkSet.Member member,
            GmapsDiagnosticPatcher.ComponentInspection inspection,
            String validatedProfile) throws IOException {
        if (!validatedProfile.equals(inspection.profile)) {
            throw new IOException("Ambiguous Google Maps target profiles");
        }
        return new GmapsMemberInspection(inspection.direct, inspection.gmsCore,
                inspection.audio, member.base ? inspection.pip : "UNSUPPORTED");
    }

    private static List<GmapsDiagnosticPatcher.ComponentInspection> inspectGmapsSet(
            NavigatorApkSet.SetInfo set) throws IOException {
        List<File> apks = new ArrayList<>();
        for (NavigatorApkSet.Member member : set.members) apks.add(member.file);
        return GmapsDiagnosticPatcher.inspectComponents(apks);
    }

    private static ScanResult inspectComponents(Context context,
        NavigatorApkSet.SetInfo set, ScanResult metadata) throws Exception {
        if (metadata.profile == NavigatorPatchStore.Profile.GMAPS) {
            List<GmapsDiagnosticPatcher.ComponentInspection> inspections = inspectGmapsSet(set);
            String validatedProfile = inspections.get(0).profile;
            String direct = "";
            String gmsCore = "";
            String audio = "";
            String pip = "";
            int directTargets = 0;
            int gmsCoreTargets = 0;
            int audioTargets = 0;
            int pipTargets = 0;
            for (int index = 0; index < set.members.size(); index++) {
                checkWorkerInterrupted();
                NavigatorApkSet.Member member = set.members.get(index);
                GmapsMemberInspection memberInspection = inspectGmapsMember(
                        member, inspections.get(index), validatedProfile);
                String memberDirect = memberInspection.direct;
                String memberGmsCore = memberInspection.gmsCore;
                String memberAudio = memberInspection.audio;
                String memberPip = memberInspection.pip;
                if (GMAPS_PATCHABLE.equals(memberDirect)
                        || GMAPS_DIRECT.equals(memberDirect)
                        || GMAPS_DIRECT_UPGRADEABLE.equals(memberDirect)) {
                    direct = memberDirect;
                    directTargets++;
                }
                if (GMAPS_GMS_CORE_ACTIVE.equals(memberGmsCore)
                        || GMAPS_GMS_CORE_SUPPRESSED.equals(memberGmsCore)
                        || GMAPS_GMS_CORE_UI_SUPPRESSED.equals(memberGmsCore)) {
                    gmsCore = memberGmsCore;
                    gmsCoreTargets++;
                }
                if (GMAPS_PATCHABLE.equals(memberAudio) || GMAPS_AUDIO.equals(memberAudio)) {
                    audio = memberAudio;
                    audioTargets++;
                }
                if (GMAPS_PIP_PATCHABLE.equals(memberPip)
                        || GMAPS_PIP_PATCHED.equals(memberPip)) {
                    pip = memberPip;
                    pipTargets++;
                }
            }
            String directState = directTargets == 1
                    && (GMAPS_PATCHABLE.equals(direct)
                    || GMAPS_DIRECT_UPGRADEABLE.equals(direct))
                    ? NavigatorPatchStore.PATCHABLE
                    : directTargets == 1 && GMAPS_DIRECT.equals(direct)
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.FAILED;
            String gmsCoreState = gmsCoreTargets == 1 && GMAPS_GMS_CORE_ACTIVE.equals(gmsCore)
                    ? NavigatorPatchStore.PATCHABLE
                    : gmsCoreTargets == 1 && (GMAPS_GMS_CORE_SUPPRESSED.equals(gmsCore)
                    || GMAPS_GMS_CORE_UI_SUPPRESSED.equals(gmsCore))
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.FAILED;
            String optionalState = audioTargets == 1 && GMAPS_PATCHABLE.equals(audio)
                    ? NavigatorPatchStore.PATCHABLE
                    : audioTargets == 1 && GMAPS_AUDIO.equals(audio)
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.FAILED;
            String alertState = pipTargets == 1 && GMAPS_PIP_PATCHABLE.equals(pip)
                    ? NavigatorPatchStore.PATCHABLE
                    : pipTargets == 1 && GMAPS_PIP_PATCHED.equals(pip)
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.FAILED;
            String reason = NavigatorPatchStore.FAILED.equals(directState)
                    && NavigatorPatchStore.FAILED.equals(gmsCoreState)
                    && NavigatorPatchStore.FAILED.equals(optionalState)
                    && NavigatorPatchStore.FAILED.equals(alertState)
                    ? "Google Maps patch anchors are incompatible" : "Compatible";
            return copyStates(metadata, directState, gmsCoreState, optionalState, alertState, reason);
        }
        int allowlistStock = 0;
        int allowlistPatched = 0;
        int laneStock = 0;
        int lanePatched = 0;
        int lifecycleTargets = 0;
        boolean lifecyclePatchable = false;
        boolean lifecyclePatched = false;
        int alertOwnerMembers = 0;
        boolean alertPatched = false;
        boolean alertCompatible = true;
        boolean alertHasStock = false;
        String reason = "Waze allowlist target missing";
        String laneReason = "Waze lane target missing";
        for (NavigatorApkSet.Member member : set.members) {
            checkWorkerInterrupted();
            WazeApkInspection inspection = inspectWaze(member.file);
            if (WazePatchEngine.PATCHABLE_STOCK.equals(inspection.allowlistClassification)) {
                allowlistStock++;
            } else if (WazePatchEngine.ALREADY_PATCHED.equals(inspection.allowlistClassification)) {
                allowlistPatched++;
            }
            if (WazePatchEngine.PATCHABLE_STOCK.equals(inspection.laneClassification)) {
                laneStock++;
            } else if (WazePatchEngine.ALREADY_PATCHED.equals(inspection.laneClassification)) {
                lanePatched++;
            }
            if (inspection.laneTargetCount > 0) laneReason = inspection.laneReason;
            lifecycleTargets += inspection.applicationTargetCount + inspection.routeTargetCount
                    + inspection.speedTargetCount + inspection.clusterEtaTargetCount;
            lifecyclePatchable |= inspection.lifecyclePatchable();
            lifecyclePatched |= inspection.lifecyclePatched();
            if (inspection.alertClassCount > 0 || inspection.alertOnceClassCount > 0) {
                alertOwnerMembers++;
            }
            alertPatched |= inspection.alertPatched();
            if (inspection.alertClassCount > 0 || inspection.alertOnceClassCount > 0) {
                alertCompatible &= inspection.alertCompatible();
                alertHasStock |= inspection.alertHasStock();
            }
            if (!inspection.reason.isEmpty()) reason = inspection.reason;
        }
        String directState = allowlistStock + allowlistPatched != 1
                ? NavigatorPatchStore.FAILED
                : allowlistPatched == 1
                ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.PATCHABLE;
        String laneState = laneStock + lanePatched != 1
                ? NavigatorPatchStore.FAILED
                : lanePatched == 1
                ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.PATCHABLE;
        String optional = lifecycleTargets == 0 ? NavigatorPatchStore.FAILED
                : lifecyclePatchable ? NavigatorPatchStore.PATCHABLE
                : lifecyclePatched ? NavigatorPatchStore.PATCHED
                : NavigatorPatchStore.FAILED;
        String alert = alertOwnerMembers != 1 || !alertCompatible ? NavigatorPatchStore.FAILED
                : alertHasStock ? NavigatorPatchStore.PATCHABLE
                : alertPatched ? NavigatorPatchStore.PATCHED
                : NavigatorPatchStore.FAILED;
        String compatibilityReason = NavigatorPatchStore.FAILED.equals(directState)
                ? reason
                : NavigatorPatchStore.FAILED.equals(laneState)
                ? laneReason
                : NavigatorPatchStore.FAILED.equals(optional)
                ? "Stable session anchors are incompatible"
                : NavigatorPatchStore.FAILED.equals(alert)
                ? "Waze alert anchors are incompatible" : "Compatible";
        return copyStates(metadata, directState, laneState,
                optional, alert, compatibilityReason);
    }

    private static PatchOutcome buildUnsignedSet(Context context, NavigatorPatchStore.Profile profile,
            NavigatorApkSet.SetInfo sourceSet, File outputDirectory, File transaction,
            ScanResult input) throws Exception {
        return buildUnsignedSet(context, profile, sourceSet, outputDirectory, transaction,
                input, true);
    }

    private static PatchOutcome buildUnsignedSet(Context context, NavigatorPatchStore.Profile profile,
            NavigatorApkSet.SetInfo sourceSet, File outputDirectory, File transaction,
            ScanResult input, boolean reportProgress) throws Exception {
        File sourceDirectory = sourceSet.members.get(0).file.getParentFile().getParentFile();
        copySetDirectory(sourceDirectory, outputDirectory);
        if (profile == NavigatorPatchStore.Profile.WAZE) {
            return patchWazeSet(context, sourceSet, outputDirectory, transaction, input,
                    reportProgress);
        }
        return patchGmapsSet(context, sourceSet, outputDirectory, transaction, input,
                reportProgress);
    }

    private static PatchOutcome patchGmapsSet(Context context, NavigatorApkSet.SetInfo sourceSet,
            File outputDirectory, File transaction, ScanResult input) throws Exception {
        return patchGmapsSet(context, sourceSet, outputDirectory, transaction, input, true);
    }

    private static PatchOutcome patchGmapsSet(Context context, NavigatorApkSet.SetInfo sourceSet,
            File outputDirectory, File transaction, ScanResult input,
            boolean reportProgress) throws Exception {
        List<GmapsDiagnosticPatcher.ComponentInspection> inspections =
                inspectGmapsSet(sourceSet);
        String validatedProfile = inspections.get(0).profile;
        File directMember = null;
        File gmsCoreMember = null;
        File audioMember = null;
        File pipMember = null;
        boolean gmsCoreFailed = false;
        boolean optionalFailed = false;
        boolean auxiliaryFailed = false;
        for (int index = 0; index < sourceSet.members.size(); index++) {
            checkWorkerInterrupted();
            NavigatorApkSet.Member member = sourceSet.members.get(index);
            GmapsMemberInspection memberInspection = inspectGmapsMember(
                    member, inspections.get(index), validatedProfile);
            String direct = memberInspection.direct;
            String gmsCore = memberInspection.gmsCore;
            String audio = memberInspection.audio;
            String pip = memberInspection.pip;
            if (GMAPS_PATCHABLE.equals(direct)
                    || GMAPS_DIRECT_UPGRADEABLE.equals(direct)) {
                if (directMember != null) throw new IOException("Multiple Google Maps direct targets");
                directMember = outputMember(outputDirectory, member.installName);
            } else if (GMAPS_DIRECT.equals(direct)) {
                if (directMember != null) throw new IOException("Multiple Google Maps direct targets");
            }
            if (GMAPS_GMS_CORE_ACTIVE.equals(gmsCore)) {
                if (gmsCoreMember != null) throw new IOException("Multiple Google Maps GmsCore targets");
                gmsCoreMember = outputMember(outputDirectory, member.installName);
            }
            if (GMAPS_PATCHABLE.equals(audio)) {
                if (audioMember != null) throw new IOException("Multiple Google Maps audio targets");
                audioMember = outputMember(outputDirectory, member.installName);
            }
            if (GMAPS_PIP_PATCHABLE.equals(pip)) {
                if (pipMember != null) throw new IOException("Multiple Google Maps PiP targets");
                pipMember = outputMember(outputDirectory, member.installName);
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.directState)) {
            if (directMember == null) throw new IOException("Google Maps direct target member missing");
            File rewritten = new File(transaction, "gmaps-direct-unsigned.apk");
            File loggerDex = new File(transaction, "gmaps-bridge.dex");
            File report = new File(transaction, "gmaps-direct-report.json");
            PatchPayloadDex.extract(
                    context, "Lcom/bydhud/gmapsdiag/NavInfoLogger", loggerDex);
            GmapsDiagnosticPatcher.patchDirect(directMember, rewritten, loggerDex, report);
            replaceFile(rewritten, directMember);
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.gmsCoreState)) {
            if (gmsCoreMember == null) {
                AppEventLogger.event(context,
                        "navigator_patch operation=patch profile=gmaps stage=gms_core"
                                + " code=GMS_CORE_INCOMPATIBLE");
                gmsCoreFailed = true;
            } else {
                try {
                    File gmsCore = new File(transaction, "gmaps-gms-core-unsigned.apk");
                    if (reportProgress) NavigatorPatchStore.transition(context,
                            NavigatorPatchStore.Profile.GMAPS,
                            NavigatorPatchStore.PATCHING, "Patching GmsCore");
                    GmapsDiagnosticPatcher.patchGmsCore(
                            gmsCoreMember, gmsCore,
                            new File(transaction, "gmaps-gms-core-report.json"));
                    replaceFile(gmsCore, gmsCoreMember);
                    GmapsDiagnosticPatcher.verifyGmsCore(gmsCoreMember);
                } catch (Exception gmsCoreError) {
                    AppEventLogger.event(context, "navigator_patch operation=patch profile=gmaps"
                            + " stage=gms_core code=GMS_CORE_PATCH_FAILED detail="
                            + clean(gmsCoreError.getMessage()));
                    gmsCoreFailed = true;
                }
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.optionalState)) {
            if (audioMember == null) {
                AppEventLogger.event(context,
                        "navigator_patch operation=patch profile=gmaps stage=optional code=OPTIONAL_INCOMPATIBLE");
                optionalFailed = true;
            } else {
                try {
                    File optional = new File(transaction, "gmaps-audio-unsigned.apk");
                    if (reportProgress) NavigatorPatchStore.transition(context,
                            NavigatorPatchStore.Profile.GMAPS,
                            NavigatorPatchStore.PATCHING, "Patching audio channel");
                    GmapsDiagnosticPatcher.patchNavigationAudio(
                            audioMember, optional, new File(transaction, "gmaps-audio-report.json"));
                    replaceFile(optional, audioMember);
                } catch (Exception optionalError) {
                    AppEventLogger.event(context, "navigator_patch operation=patch profile=gmaps"
                            + " stage=optional code=OPTIONAL_PATCH_FAILED detail="
                            + clean(optionalError.getMessage()));
                    optionalFailed = true;
                }
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.alertState)) {
            if (pipMember == null) {
                AppEventLogger.event(context,
                        "navigator_patch operation=patch profile=gmaps stage=pip code=PIP_INCOMPATIBLE");
                auxiliaryFailed = true;
            } else {
                try {
                    File pip = new File(transaction, "gmaps-pip-unsigned.apk");
                    if (reportProgress) NavigatorPatchStore.transition(context,
                            NavigatorPatchStore.Profile.GMAPS,
                            NavigatorPatchStore.PATCHING, "Patching PiP");
                    GmapsDiagnosticPatcher.patchPictureInPicture(
                            pipMember, pip, new File(transaction, "gmaps-pip-report.json"),
                            validatedProfile);
                    replaceFile(pip, pipMember);
                } catch (Exception pipError) {
                    AppEventLogger.event(context, "navigator_patch operation=patch profile=gmaps"
                            + " stage=pip code=PIP_PATCH_FAILED detail="
                            + clean(pipError.getMessage()));
                    auxiliaryFailed = true;
                }
            }
        }
        return new PatchOutcome(gmsCoreFailed, optionalFailed, auxiliaryFailed);
    }

    private static PatchOutcome patchWazeSet(Context context, NavigatorApkSet.SetInfo sourceSet,
            File outputDirectory, File transaction, ScanResult input) throws Exception {
        return patchWazeSet(context, sourceSet, outputDirectory, transaction, input, true);
    }

    private static PatchOutcome patchWazeSet(Context context, NavigatorApkSet.SetInfo sourceSet,
            File outputDirectory, File transaction, ScanResult input,
            boolean reportProgress) throws Exception {
        List<WazeApkInspection> inspections = new ArrayList<>();
        WazeApkInspection allowlist = null;
        WazeApkInspection lanes = null;
        int allowlistCount = 0;
        int laneCount = 0;
        for (NavigatorApkSet.Member member : sourceSet.members) {
            checkWorkerInterrupted();
            WazeApkInspection inspection = inspectWaze(member.file);
            inspections.add(inspection);
            if (inspection.allowlistTargetCount == 1) {
                allowlist = inspection;
                allowlistCount++;
            }
            if (inspection.laneTargetCount == 1) {
                lanes = inspection;
                laneCount++;
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.directState)) {
            if (allowlistCount != 1 || allowlist == null) {
                throw new IOException("Waze direct target member is ambiguous");
            }
            if (!WazePatchEngine.PATCHABLE_STOCK.equals(
                    allowlist.allowlistClassification)) {
                throw new IOException("Waze direct patch has no stock component");
            }
            File target = outputMember(outputDirectory, allowlist.fileName);
            File rewrittenDex = new File(transaction, "waze-direct.dex");
            WazePatchEngine.patchWazeAllowlist(
                    readEntry(target, allowlist.allowlistDex), rewrittenDex);
            File rewrittenApk = new File(transaction, "waze-direct-unsigned.apk");
            Map<String, File> replacements = new HashMap<>();
            replacements.put(allowlist.allowlistDex, rewrittenDex);
            repack(target, rewrittenApk, replacements, Collections.emptyMap());
            replaceFile(rewrittenApk, target);
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.gmsCoreState)) {
            if (laneCount != 1 || lanes == null) {
                throw new IOException("Waze lane target member is ambiguous");
            }
            if (!WazePatchEngine.PATCHABLE_STOCK.equals(lanes.laneClassification)) {
                throw new IOException("Waze lane patch has no stock component");
            }
            File target = outputMember(outputDirectory, lanes.fileName);
            File rewrittenDex = new File(transaction, "waze-lanes.dex");
            WazePatchEngine.patchLanes(readEntry(target, lanes.laneDex), rewrittenDex);
            File rewrittenApk = new File(transaction, "waze-lanes-unsigned.apk");
            Map<String, File> replacements = new HashMap<>();
            replacements.put(lanes.laneDex, rewrittenDex);
            repack(target, rewrittenApk, replacements, Collections.emptyMap());
            replaceFile(rewrittenApk, target);
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.optionalState)) {
            if (reportProgress) NavigatorPatchStore.transition(context,
                    NavigatorPatchStore.Profile.WAZE,
                    NavigatorPatchStore.PATCHING, "Patching stable session");
            WazeApkInspection lifecycleOwner = null;
            for (WazeApkInspection inspection : inspections) {
                if (inspection.applicationTargetCount > 0 || inspection.routeTargetCount > 0
                        || inspection.speedTargetCount > 0
                        || inspection.clusterEtaTargetCount > 0) {
                    if (lifecycleOwner != null) {
                        throw new IOException("Waze stable-session target member is ambiguous");
                    }
                    lifecycleOwner = inspection;
                }
            }
            if (lifecycleOwner == null || lifecycleOwner.routeTargetCount != 1
                    || lifecycleOwner.applicationTargetCount != 1
                    || lifecycleOwner.speedTargetCount != 1
                    || lifecycleOwner.clusterEtaTargetCount != 1) {
                throw new IOException("Waze stable-session target missing");
            }
            boolean addV2Bridge = lifecycleOwner.lifecycleCoreStock();
            File target = outputMember(outputDirectory, lifecycleOwner.fileName);
            Map<String, File> replacements = new HashMap<>();
            for (String dexEntry : lifecycleOwner.lifecycleDexEntries) {
                File rewritten = new File(transaction,
                        "lifecycle-" + lifecycleOwner.fileName + "-"
                                + dexEntry.replace('/', '_'));
                WazePatchEngine.patchLifecycle(readEntry(target, dexEntry), rewritten);
                replacements.put(dexEntry, rewritten);
            }
            File lifecycleApk = new File(transaction, "waze-lifecycle-unsigned.apk");
            repack(target, lifecycleApk, replacements, Collections.emptyMap());
            if (addV2Bridge) {
                File bridgeDex = new File(transaction, "waze-route-v2.dex");
                PatchPayloadDex.extract(
                        context, "Lcom/waze/bydhud/RouteStateBridgeV2", bridgeDex);
                File optionalApk = new File(transaction, "waze-optional-unsigned.apk");
                Map<String, File> additions = new HashMap<>();
                List<String> dexEntries = dexEntries(lifecycleApk);
                additions.put(nextDexEntry(dexEntries), bridgeDex);
                repack(lifecycleApk, optionalApk, Collections.emptyMap(), additions);
                replaceFile(optionalApk, target);
            } else {
                replaceFile(lifecycleApk, target);
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.alertState)) {
            if (reportProgress) NavigatorPatchStore.transition(context,
                    NavigatorPatchStore.Profile.WAZE,
                    NavigatorPatchStore.PATCHING, "Patching Waze alerts");
            WazeApkInspection alertOwner = null;
            for (WazeApkInspection inspection : inspections) {
                if (inspection.alertClassCount <= 0 && inspection.alertOnceClassCount <= 0) continue;
                if (alertOwner != null) {
                    throw new IOException("Waze alert-hook target member is ambiguous");
                }
                alertOwner = inspection;
            }
            if (alertOwner == null || !alertOwner.alertCompatible() || !alertOwner.alertHasStock()) {
                throw new IOException("Waze alert-hook target missing");
            }
            File target = outputMember(outputDirectory, alertOwner.fileName);
            String dexEntry = alertOwner.alertDexEntries.get(0);
            byte[] inputDex = readEntry(target, dexEntry);
            if (!WazePatchEngine.inspectAlertHook(inputDex).patchableComponents()) {
                throw new IOException("Waze alert-hook stock target missing");
            }
            File rewritten = new File(transaction, "waze-alert-hook.dex");
            WazePatchEngine.patchAlertHook(inputDex, rewritten);
            Map<String, File> replacements = new HashMap<>();
            replacements.put(dexEntry, rewritten);
            File alertApk = new File(transaction, "waze-alert-hook-unsigned.apk");
            repack(target, alertApk, replacements, Collections.emptyMap());
            replaceFile(alertApk, target);
        }
        return new PatchOutcome(false, false, false);
    }

    private static void signSet(File directory) throws Exception {
        List<File> members = new ArrayList<>();
        File memberDirectory = new File(directory, "members");
        File[] files = memberDirectory.listFiles((file, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("APK-set is empty");
        Collections.addAll(members, files);
        for (File input : members) {
            File signed = new File(input.getParentFile(), input.getName() + ".signed");
            sign(input, signed);
            replaceFile(signed, input);
        }
    }

    private static void copySetDirectory(File source, File target) throws IOException {
        deleteTree(target);
        if (!target.mkdirs()) throw new IOException("Cannot create patched APK-set");
        File sourceMembers = new File(source, "members");
        File targetMembers = new File(target, "members");
        if (!targetMembers.mkdirs()) throw new IOException("Cannot create patched APK members");
        File[] files = sourceMembers.listFiles((file, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("Source APK-set is empty");
        for (File file : files) {
            Files.copy(file.toPath(), new File(targetMembers, file.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File outputMember(File directory, String installName) throws IOException {
        File member = new File(new File(directory, "members"), installName);
        if (!member.isFile()) throw new IOException("Missing APK-set member: " + installName);
        return member;
    }

    private static void replaceFile(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static List<String> dexEntries(File apk) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes(\\d*)\\.dex")) result.add(name);
            }
        }
        return result;
    }

    private static String suffix(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apkm")) return ".apkm";
        if (lower.endsWith(".apks")) return ".apks";
        if (lower.endsWith(".xapk")) return ".xapk";
        return ".apk";
    }

    private static File temporaryDirectory(Context context, String prefix) throws IOException {
        File root = new File(context.getCacheDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create patch cache");
        File directory = new File(root, prefix + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot create temporary APK-set");
        return directory;
    }

    private static ScanResult copyStates(ScanResult source, String direct,
            String gmsCore, String optional, String alert, String reason) {
        return new ScanResult(source.profile, source.sha256, source.versionName,
                source.versionCode, source.signerSha256, direct, gmsCore, optional, alert, reason);
    }

    private static WazeApkInspection inspectWaze(File apk) throws IOException {
        WazeApkInspection result = new WazeApkInspection();
        result.fileName = apk.getName();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkWorkerInterrupted();
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                result.dexEntries.add(entry.getName());
                byte[] bytes = readEntry(zip, entry.getName());
                WazePatchEngine.CompositeInspection composite =
                        WazePatchEngine.inspectComposite(bytes);
                WazePatchEngine.WazeInspection allowlist = composite.allowlist;
                if (allowlist.targetCount > 0) {
                    result.allowlistTargetCount += allowlist.targetCount;
                    result.allowlistDex = entry.getName();
                    result.allowlistClassification = allowlist.classification;
                    result.reason = allowlist.reason;
                }
                WazePatchEngine.LaneInspection lane = composite.lane;
                if (lane.frameClassCount > 0 || lane.producerTargetCount > 0
                        || lane.adapterTargetCount > 0) {
                    result.laneTargetCount++;
                    result.laneDex = entry.getName();
                    result.laneClassification = lane.classification;
                    result.laneReason = lane.reason;
                }
                WazePatchEngine.LifecycleInspection lifecycle = composite.lifecycle;
                result.applicationTargetCount += lifecycle.applicationTargetCount;
                result.applicationHookCount += lifecycle.applicationHookCount;
                result.legacyApplicationHookCount += lifecycle.legacyApplicationHookCount;
                result.routeTargetCount += lifecycle.routeTargetCount;
                result.routeHookCount += lifecycle.routeHookCount;
                result.v1RouteHookCount += lifecycle.v1RouteHookCount;
                result.legacyRouteHookCount += lifecycle.legacyRouteHookCount;
                result.speedTargetCount += lifecycle.speedTargetCount;
                result.speedHookCount += lifecycle.speedHookCount;
                result.legacySpeedHookCount += lifecycle.legacySpeedHookCount;
                result.clusterEtaTargetCount += lifecycle.clusterEtaTargetCount;
                result.clusterEtaPatchCount += lifecycle.clusterEtaPatchCount;
                result.bridgeClassCount += lifecycle.bridgeClassCount;
                result.legacyBridgeClassCount += lifecycle.legacyBridgeClassCount;
                if (lifecycle.applicationTargetCount > 0
                        || lifecycle.routeTargetCount > 0
                        || lifecycle.speedTargetCount > 0
                        || lifecycle.clusterEtaTargetCount > 0) {
                    result.lifecycleDexEntries.add(entry.getName());
                }
                if (lifecycle.applicationTargetCount > 0) {
                    result.applicationGuard = lifecycle.applicationGuard;
                }
                if (lifecycle.routeTargetCount > 0) result.routeGuard = lifecycle.routeGuard;
                if (lifecycle.speedTargetCount > 0) result.speedGuard = lifecycle.speedGuard;
                if (lifecycle.clusterEtaTargetCount > 0) {
                    result.clusterEtaGuard = lifecycle.clusterEtaGuard;
                }
                WazePatchEngine.AlertInspection alert = composite.alert;
                result.alertClassCount += alert.classCount;
                result.alertFieldAnchorCount += alert.fieldAnchorCount;
                result.alertGuardFieldCount += alert.guardFieldCount;
                result.alertTargetMethodCount += alert.targetMethodCount;
                result.alertAnchorCount += alert.anchorCount;
                result.alertHookCallCount += alert.hookCallCount;
                result.alertHookAfterAnchorCount += alert.hookAfterAnchorCount;
                result.alertHelperMethodCount += alert.helperMethodCount;
                result.alertProducerCallCount += alert.producerCallCount;
                result.alertCollectorCallCount += alert.collectorCallCount;
                result.alertTripPublisherCallCount += alert.tripPublisherCallCount;
                result.alertGuardReadCount += alert.guardReadCount;
                result.alertGuardWriteCount += alert.guardWriteCount;
                result.alertLogMarkerCount += alert.logMarkerCount;
                result.alertOnceClassCount += alert.alertOnceClassCount;
                result.alertOnceTargetMethodCount += alert.alertOnceTargetMethodCount;
                result.alertOnceStateReadCount += alert.alertOnceStateReadCount;
                result.alertOnceModeReadCount += alert.alertOnceModeReadCount;
                result.alertOnceStateUpdateCount += alert.alertOnceStateUpdateCount;
                result.alertOnceNativeStartCount += alert.alertOnceNativeStartCount;
                result.alertOnceGuardCount += alert.alertOnceGuardCount;
                result.alertOnceUnchangedMethodCount += alert.alertOnceUnchangedMethodCount;
                result.alertOnceStockShapeCount += alert.alertOnceStockShapeCount;
                result.alertOncePatchedShapeCount += alert.alertOncePatchedShapeCount;
                if (alert.classCount > 0 || alert.alertOnceClassCount > 0) {
                    result.alertDexEntries.add(entry.getName());
                }
            }
        }
        if (result.allowlistTargetCount != 1) {
            result.allowlistClassification = WazePatchEngine.UNSUPPORTED;
            result.reason = "Waze allowlist target count=" + result.allowlistTargetCount;
            result.allowlistDex = "";
        }
        if (result.laneTargetCount != 1) {
            result.laneClassification = WazePatchEngine.UNSUPPORTED;
            result.laneReason = "Waze lane target count=" + result.laneTargetCount;
            result.laneDex = "";
        }
        return result;
    }

    private static void copyUri(Context context, Uri uri, File target) throws IOException {
        long declaredLength = -1L;
        try (AssetFileDescriptor descriptor =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) declaredLength = descriptor.getLength();
        }
        if (declaredLength >= 0L) rejectOversizedSource(declaredLength);
        ensureCopySpace(target, declaredLength);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Selected APK cannot be opened");
            byte[] buffer = new byte[128 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_SOURCE_APK_BYTES) {
                    throw new IOException("Selected APK exceeds 512 MiB limit");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (target.length() <= 0L) throw new IOException("Selected APK is empty");
    }

    private static void rejectOversizedSource(long length) throws IOException {
        if (length > MAX_SOURCE_APK_BYTES) {
            throw new IOException("APK exceeds 512 MiB limit");
        }
    }

    private static void ensureCopySpace(File target, long declaredLength) throws IOException {
        long expected = declaredLength < 0L ? MAX_SOURCE_APK_BYTES : declaredLength;
        long reserve = 32L * 1024L * 1024L;
        if (target.getParentFile().getUsableSpace() < expected + reserve) {
            throw new IOException("Insufficient storage to copy selected APK");
        }
    }

    private static ApkVerifier.Result verifySignature(File apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
        if (!result.isVerified()) {
            throw new IOException("APK signature verification failed: " + result.getErrors());
        }
        return result;
    }

    private static void sign(File input, File output) throws Exception {
        new ApkSigner.Builder(Collections.singletonList(
                NavigatorSigningKey.signerConfig()))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .build()
                .sign();
        ApkVerifier.Result result = verifySignature(output);
        String expected = NavigatorSigningKey.localCertificateSha256();
        String actual = NavigatorSigningKey.archiveCertificateSha256(
                result.getSignerCertificates());
        if (!expected.equals(actual)) throw new IOException("Output signer mismatch");
    }

    private static void repack(File input, File output, Map<String, File> replacements,
            Map<String, File> additions) throws IOException {
        Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (ZipArchive archive = new ZipArchive(output)) {
            for (String name : new ArrayList<>(archive.listEntries())) {
                String upper = name.toUpperCase(Locale.ROOT);
                if (replacements.containsKey(name)
                        || additions.containsKey(name)
                        || (upper.startsWith("META-INF/")
                        && (upper.endsWith(".RSA") || upper.endsWith(".DSA")
                        || upper.endsWith(".EC") || upper.endsWith(".SF")
                        || upper.endsWith("MANIFEST.MF")))) {
                    archive.delete(name);
                }
            }
            Map<String, File> changed = new HashMap<>(replacements);
            changed.putAll(additions);
            for (Map.Entry<String, File> entry : changed.entrySet()) {
                BytesSource source = new BytesSource(
                        entry.getValue(), entry.getKey(), Deflater.BEST_SPEED);
                source.align(4);
                archive.add(source);
            }
        }
    }

    private static byte[] readEntry(File apk, String name) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            return readEntry(zip, name);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        return readEntry(zip, name, MAX_DEX_BYTES);
    }

    private static byte[] readEntry(ZipFile zip, String name, long limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing APK entry " + name);
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IOException("APK entry exceeds limit: " + name);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String nextDexEntry(List<String> entries) {
        int maximum = 1;
        for (String entry : entries) {
            if ("classes.dex".equals(entry)) continue;
            String number = entry.substring("classes".length(), entry.length() - 4);
            maximum = Math.max(maximum, Integer.parseInt(number));
        }
        return "classes" + (maximum + 1) + ".dex";
    }

    private static void ensureWorkingSpace(Context context, long inputBytes) throws IOException {
        long required = inputBytes * 4L + 64L * 1024L * 1024L;
        long free = context.getFilesDir().getUsableSpace();
        if (free < required) {
            throw new IOException("Insufficient storage: free=" + free + ", required=" + required);
        }
    }

    private static void ensureWorkingSpace(Context context, NavigatorApkSet.SetInfo set)
            throws IOException {
        long total = 0L;
        for (NavigatorApkSet.Member member : set.members) total += member.file.length();
        ensureWorkingSpace(context, total);
    }

    private static File temporary(Context context, String prefix, String suffix)
            throws IOException {
        File root = new File(context.getCacheDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create patch cache");
        return File.createTempFile(prefix, suffix, root);
    }

    private static PackageInfo installedInfo(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static void assertInstalledTarget(Context context,
            NavigatorPatchStore.Profile profile, long updateTime,
            long versionCode, String signer, String fingerprint) throws Exception {
        PackageInfo current = installedInfo(context, profile.packageName);
        if (updateTime < 0L) {
            if (current != null) throw new IOException("Navigator was installed during patching");
            return;
        }
        if (current == null
                || current.lastUpdateTime != updateTime
                || current.getLongVersionCode() != versionCode
                || !signer.equals(NavigatorSigningKey.installedCertificateSha256(
                context, profile.packageName))) {
            throw new IOException("Installed navigator changed during patching");
        }
        if (!fingerprint.equals(
                inspectInstalled(context, profile).sha256)) {
            throw new IOException("Installed APK-set changed during patching");
        }
    }

    private static void saveFailure(Context context, NavigatorPatchStore.Profile profile,
            Exception error) {
        ScanResult failure = new ScanResult(profile, "", "", -1L, "",
                NavigatorPatchStore.FAILED, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED,
                clean(error.getMessage()));
        if (!NavigatorPatchStore.completeScanUnlessCancelled(
                context, failure, NavigatorPatchStore.FAILED, error.getMessage())) {
            NavigatorPatchStore.markCancelled(context, profile, "Check cancelled");
        }
    }

    private static void cleanupInactiveTransactions(File root, File active) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("tx-")
                    && (active == null || !child.equals(active))) deleteTree(child);
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) android.util.Log.w(
                "BYD_NAV_PATCH", "cleanup failed: " + file);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class WazeApkInspection {
        String fileName = "";
        final List<String> dexEntries = new ArrayList<>();
        final List<String> lifecycleDexEntries = new ArrayList<>();
        final List<String> alertDexEntries = new ArrayList<>();
        int allowlistTargetCount;
        String allowlistDex = "";
        String allowlistClassification = WazePatchEngine.UNSUPPORTED;
        String reason = "Waze allowlist target missing";
        int laneTargetCount;
        String laneDex = "";
        String laneClassification = WazePatchEngine.UNSUPPORTED;
        String laneReason = "Waze lane target missing";
        int applicationTargetCount;
        int applicationHookCount;
        int legacyApplicationHookCount;
        String applicationGuard = "not found";
        int routeTargetCount;
        int routeHookCount;
        int v1RouteHookCount;
        int legacyRouteHookCount;
        String routeGuard = "not found";
        int speedTargetCount;
        int speedHookCount;
        int legacySpeedHookCount;
        String speedGuard = "not found";
        int clusterEtaTargetCount;
        int clusterEtaPatchCount;
        String clusterEtaGuard = "not found";
        int bridgeClassCount;
        int legacyBridgeClassCount;
        int alertClassCount;
        int alertFieldAnchorCount;
        int alertGuardFieldCount;
        int alertTargetMethodCount;
        int alertAnchorCount;
        int alertHookCallCount;
        int alertHookAfterAnchorCount;
        int alertHelperMethodCount;
        int alertProducerCallCount;
        int alertCollectorCallCount;
        int alertTripPublisherCallCount;
        int alertGuardReadCount;
        int alertGuardWriteCount;
        int alertLogMarkerCount;
        int alertOnceClassCount;
        int alertOnceTargetMethodCount;
        int alertOnceStateReadCount;
        int alertOnceModeReadCount;
        int alertOnceStateUpdateCount;
        int alertOnceNativeStartCount;
        int alertOnceGuardCount;
        int alertOnceUnchangedMethodCount;
        int alertOnceStockShapeCount;
        int alertOncePatchedShapeCount;

        boolean lifecycleCoreStock() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && legacyApplicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && v1RouteHookCount == 0 && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 0
                    && legacySpeedHookCount == 0
                    && "ok".equals(speedGuard)
                    && bridgeClassCount == 0 && legacyBridgeClassCount == 0;
        }

        boolean lifecycleCoreV2Patched() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && legacyApplicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 1
                    && v1RouteHookCount == 0 && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 1
                    && legacySpeedHookCount == 0
                    && "ok".equals(speedGuard)
                    && bridgeClassCount == 1 && legacyBridgeClassCount == 0;
        }

        boolean lifecycleCoreV1Patched() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && legacyApplicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && v1RouteHookCount == 1 && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 1
                    && legacySpeedHookCount == 0
                    && "ok".equals(speedGuard)
                    && bridgeClassCount == 1 && legacyBridgeClassCount == 0;
        }

        boolean lifecycleCoreLegacyPatched() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && legacyApplicationHookCount == 1
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && legacyRouteHookCount == 1
                    && "ok".equals(routeGuard)
                    && speedTargetCount == 1 && speedHookCount == 0
                    && legacySpeedHookCount == 1
                    && "ok".equals(speedGuard)
                    && bridgeClassCount == 0 && legacyBridgeClassCount == 1;
        }

        boolean clusterEtaStock() {
            return clusterEtaTargetCount == 1 && clusterEtaPatchCount == 0
                    && "stock".equals(clusterEtaGuard);
        }

        boolean clusterEtaPatched() {
            return clusterEtaTargetCount == 1 && clusterEtaPatchCount == 1
                    && "patched".equals(clusterEtaGuard);
        }

        boolean lifecyclePatchable() {
            boolean coreCompatible = lifecycleCoreStock() || lifecycleCoreV2Patched();
            boolean etaCompatible = clusterEtaStock() || clusterEtaPatched();
            return coreCompatible && etaCompatible && !lifecyclePatched();
        }

        boolean lifecyclePatched() {
            return lifecycleCoreV2Patched() && clusterEtaPatched();
        }

        boolean alertStock() {
            return alertClassCount == 1 && alertFieldAnchorCount == 1
                    && alertGuardFieldCount == 0 && alertTargetMethodCount == 1
                    && alertAnchorCount == 1 && alertHookCallCount == 0
                    && alertHelperMethodCount == 0;
        }

        boolean alertPatched() {
            return alertClassCount == 1 && alertFieldAnchorCount == 1
                    && alertGuardFieldCount == 1 && alertTargetMethodCount == 1
                    && alertAnchorCount == 1 && alertHookCallCount == 1
                    && alertHookAfterAnchorCount == 1 && alertHelperMethodCount == 1
                    && alertProducerCallCount == 1 && alertCollectorCallCount == 1
                    && alertTripPublisherCallCount == 1
                    && alertGuardReadCount == 1 && alertGuardWriteCount == 1
                    && alertLogMarkerCount == 1;
        }

        boolean alertOnceStock() {
            return alertOnceClassCount == 1 && alertOnceTargetMethodCount == 1
                    && alertOnceUnchangedMethodCount == 2
                    && alertOnceStockShapeCount == 1
                    && alertOnceStateReadCount == 2 && alertOnceModeReadCount == 0
                    && alertOnceStateUpdateCount == 1 && alertOnceNativeStartCount == 1
                    && alertOnceGuardCount == 0;
        }

        boolean alertOncePatched() {
            return alertOnceClassCount == 1 && alertOnceTargetMethodCount == 1
                    && alertOnceUnchangedMethodCount == 2
                    && alertOncePatchedShapeCount == 1
                    && alertOnceStateReadCount == 3 && alertOnceModeReadCount == 1
                    && alertOnceStateUpdateCount == 1 && alertOnceNativeStartCount == 1
                    && alertOnceGuardCount == 2;
        }

        boolean alertCompatible() {
            return alertDexEntries.size() == 1
                    && alertClassCount == 1 && alertOnceClassCount == 1
                    && (alertStock() || alertPatched())
                    && (alertOnceStock() || alertOncePatched());
        }

        boolean alertHasStock() {
            return alertStock() || alertOnceStock();
        }

    }
}
