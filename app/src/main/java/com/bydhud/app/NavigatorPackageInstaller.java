package com.bydhud.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NavigatorPackageInstaller {
    private static final long INTERRUPTED_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final AtomicBoolean VERIFY_RUNNING = new AtomicBoolean();
    private static final ExecutorService INSTALL_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread worker = new Thread(r, "NavigatorPatchDrain");
        worker.setPriority(Thread.MIN_PRIORITY);
        return worker;
    });
    static final String EXTRA_OPERATION = "navigator_patch_operation";
    static final String EXTRA_PROFILE = "navigator_patch_profile";
    static final String EXTRA_TOKEN = "navigator_patch_token";
    static final String OP_UNINSTALL = "uninstall_for_patch";
    static final String OP_INSTALL = "install_patched";
    static final String OP_UNINSTALL_RESTORE = "uninstall_for_restore";
    static final String OP_INSTALL_RESTORE = "install_original";

    private NavigatorPackageInstaller() {
    }

    static boolean canInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    static void begin(Context context, NavigatorPatchPipeline.PreparedPatch prepared)
            throws Exception {
        if (!canInstall(context)) throw new IOException("APK install permission is not granted");
        if (!NavigatorPatchStore.claimInstall(context, prepared.profile)) {
            NavigatorPatchStore.transition(context, prepared.profile,
                    NavigatorPatchStore.READY_TO_INSTALL,
                    "Queued behind another navigator install");
            return;
        }
        File patched;
        int sessionId;
        try {
            verifyInstallTargetUnchanged(context, prepared);
            patched = new File(prepared.directory, "patched-set");
            verifyPatchedSetUnchanged(context, prepared, patched);
            sessionId = prepareSession(context, prepared.profile, patched);
        } catch (Exception error) {
            NavigatorPatchStore.releaseInstall(context, prepared.profile);
            throw error;
        }
        NavigatorPatchStore.setSessionId(context, prepared.profile, sessionId);
        try {
            verifyPatchedSetUnchanged(context, prepared, patched);
            verifyInstallTargetUnchanged(context, prepared);
            NavHudLiveSender.get(context).stop(
                    prepared.profile.packageName, "navigator-patch", true);
            if (prepared.destructive && isInstalled(context, prepared.profile.packageName)) {
                verifyInstallTargetUnchanged(context, prepared);
                NavigatorPatchStore.expectCallback(context, prepared.profile, OP_UNINSTALL);
                NavigatorPatchStore.transition(context, prepared.profile,
                        NavigatorPatchStore.UNINSTALL_REQUESTED,
                        "Waiting for removal confirmation");
                context.getPackageManager().getPackageInstaller().uninstall(
                        prepared.profile.packageName,
                        callback(context, prepared.profile, OP_UNINSTALL).getIntentSender());
            } else {
                commitPreparedSession(context, prepared.profile, OP_INSTALL);
            }
        } catch (Exception error) {
            abandonPreparedSession(context, prepared.profile);
            NavigatorPatchStore.releaseInstall(context, prepared.profile);
            throw error;
        }
    }

    static void drainInstallQueue(Context context) {
        Context appContext = context.getApplicationContext();
        INSTALL_QUEUE.execute(() -> drainInstallQueueNow(appContext));
    }

    private static void drainInstallQueueNow(Context context) {
        if (!canInstall(context)) return;
        NavigatorPatchStore.OperationSnapshot global =
                NavigatorPatchStore.operation(context, null);
        if (NavigatorPatchStore.OP_RECOVERY.equals(global.kind)
                && (global.busy() || NavigatorPatchStore.RECOVERY_REQUIRED.equals(global.phase))) {
            return;
        }
        NavigatorPatchStore.Profile next = null;
        long nextReadyAt = Long.MAX_VALUE;
        for (NavigatorPatchStore.Profile profile : NavigatorPatchStore.Profile.values()) {
            NavigatorPatchStore.OperationSnapshot operation =
                    NavigatorPatchStore.operation(context, profile);
            if (NavigatorPatchStore.RECOVERY_REQUIRED.equals(operation.phase)) return;
            if (NavigatorPatchStore.READY_TO_INSTALL.equals(operation.phase)
                    && operation.readyAt > 0L && operation.readyAt < nextReadyAt) {
                next = profile;
                nextReadyAt = operation.readyAt;
            }
        }
        if (next == null) return;
        try {
            begin(context, NavigatorPatchPipeline.resumePrepared(context, next));
        } catch (Exception error) {
            NavigatorPatchStore.OperationSnapshot current =
                    NavigatorPatchStore.operation(context, next);
            if (NavigatorPatchStore.CANCELLED.equals(current.phase)) return;
            if (NavigatorPatchStore.isCancellationRequested(context, next)
                    || error instanceof NavigatorPatchPipeline.OperationCancelledException) {
                NavigatorPatchPipeline.finishQueuedCancellation(
                        context, next, "Queued installation cancelled");
                return;
            }
            NavigatorPatchStore.transition(context, next, NavigatorPatchStore.FAILED,
                    error.getMessage() == null ? "Queued install failed" : error.getMessage());
            NavigatorPatchStore.releaseInstall(context, next);
            MainActivity.publishSharedUiStateChange();
        }
    }

    static void beginRestore(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        NavigatorPatchStore.claimRecovery(
                context, profile, NavigatorPatchStore.VERIFYING, "Verifying recovery APK");
        try {
            if (!canInstall(context)) {
                throw new IOException("APK install permission is not granted");
            }
            File source = NavigatorPatchStore.originalSet(context);
            if (!source.isDirectory()) throw new IOException("Recovery APK-set is missing");
            NavigatorPatchPipeline.ScanResult expected =
                    NavigatorPatchPipeline.verifyRecoverySource(context, profile, source);
            String failedExpectedFingerprint = NavigatorPatchStore.expectedSha(context);
            PackageInfo initialInstalled = null;
            NavigatorPatchPipeline.ScanResult initialArtifact = null;
            String initialInstalledIdentity = "";
            if (isInstalled(context, profile.packageName)) {
                initialInstalledIdentity = NavigatorPatchStore.installedIdentity(context, profile);
                initialArtifact = NavigatorPatchPipeline.inspectInstalled(context, profile);
                if (sameArtifact(expected, initialArtifact)) {
                    completeRestore(context, profile, initialArtifact,
                            "Original APK is already installed", initialInstalledIdentity);
                    return;
                }
                if (failedExpectedFingerprint.isEmpty()
                        || !failedExpectedFingerprint.equals(initialArtifact.sha256)) {
                    throw new IOException(
                            "Installed navigator does not match the interrupted transaction");
                }
                initialInstalled = context.getPackageManager().getPackageInfo(
                        profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            }
            NavigatorPatchStore.beginRestoreTransaction(
                    context, profile, expected,
                    initialInstalled == null ? -1L : initialInstalled.lastUpdateTime,
                    initialInstalled == null ? -1L : initialInstalled.getLongVersionCode(),
                    initialArtifact == null ? "" : initialArtifact.signerSha256,
                    initialArtifact == null ? "" : initialArtifact.sha256);
            int sessionId = prepareSession(context, profile, source);
            NavigatorPatchStore.setSessionId(context, sessionId);
            NavHudLiveSender.get(context).stop(profile.packageName, "navigator-restore", true);
            if (isInstalled(context, profile.packageName)) {
                if (!initialInstalledTargetUnchanged(context, profile)) {
                    throw new IOException("Installed navigator changed during recovery staging");
                }
                NavigatorPatchStore.expectCallback(context, OP_UNINSTALL_RESTORE);
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.UNINSTALL_REQUESTED,
                        "Waiting to restore original APK");
                context.getPackageManager().getPackageInstaller().uninstall(
                        profile.packageName,
                        callback(context, profile, OP_UNINSTALL_RESTORE).getIntentSender());
            } else {
                commitPreparedSession(context, profile, OP_INSTALL_RESTORE);
            }
        } catch (Exception error) {
            abandonPreparedSession(context);
            NavigatorPatchStore.transition(context, profile,
                    NavigatorPatchStore.RECOVERY_REQUIRED, error.getMessage());
            throw error;
        }
    }

    static void commitPreparedSession(Context context, NavigatorPatchStore.Profile profile,
            String operation) throws Exception {
        int sessionId = NavigatorPatchStore.sessionId(context, profile);
        if (sessionId < 0) throw new IOException("Prepared installer session is missing");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            NavigatorPatchStore.expectCallback(context, profile, operation);
            NavigatorPatchStore.transition(context, profile,
                    NavigatorPatchStore.COMMITTING, "Submitting installer session");
            session.commit(callback(context, profile, operation).getIntentSender());
            NavigatorPatchStore.transitionIfPhase(context, profile,
                    NavigatorPatchStore.COMMITTING, NavigatorPatchStore.INSTALL_REQUESTED,
                    "Waiting for install confirmation");
        }
    }

    static void commitAfterUninstall(Context context, NavigatorPatchStore.Profile profile,
            String operation) throws Exception {
        if (isInstalled(context, profile.packageName)) {
            throw new IOException("Navigator was installed before the uninstall callback completed");
        }
        commitPreparedSession(context, profile, operation);
    }

    static void verifyInstalledAsync(Context context, NavigatorPatchStore.Profile profile) {
        if (!VERIFY_RUNNING.compareAndSet(false, true)) return;
        NavigatorPatchStore.transition(
                context, profile, NavigatorPatchStore.INSTALLED_VERIFY,
                "Verifying installed APK");
        Thread worker = new Thread(() -> {
            try {
                String installedIdentity = NavigatorPatchStore.installedIdentity(context, profile);
                if (!NavigatorSigningKey.installedUsesLocalKey(context, profile.packageName)) {
                    throw new IOException("Installed signer does not match local patcher key");
                }
                NavigatorPatchPipeline.ScanResult result =
                        NavigatorPatchPipeline.inspectInstalled(context, profile);
                verifyExpected(context, profile, result, true);
                NavigatorPatchPipeline.ScanResult visibleResult =
                        withExpectedOptionalFailure(context, profile, result);
                PackageInfo info = context.getPackageManager().getPackageInfo(
                        profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
                if (!installedIdentity.equals(
                        NavigatorPatchStore.installedIdentity(context, profile))) {
                    throw new IOException("Installed navigator changed during verification");
                }
                NavigatorPatchStore.clearExternal(context, profile);
                NavigatorPatchStore.saveScan(context, visibleResult);
                NavigatorPatchStore.recordInstalledVerification(
                        context, profile, info.lastUpdateTime, info.getLongVersionCode(),
                        visibleResult.sha256, visibleResult.directState,
                        visibleResult.gmsCoreState,
                        visibleResult.optionalState, visibleResult.alertState);
                NavigatorPatchStore.transition(
                        context, profile, NavigatorPatchStore.VERIFIED, "Installed and verified");
                NavigatorPatchStore.clearTransactionMetadata(context, profile);
                NavigatorPatchStore.releaseInstall(context, profile);
                NavigatorPatchPipeline.deleteTree(transaction);
                MainActivity.requestPatchUiStateRefresh(context, true, "patch-verified");
                drainInstallQueue(context);
            } catch (Exception error) {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        error.getMessage());
                NavigatorPatchStore.releaseInstall(context, profile);
                MainActivity.requestPatchUiStateRefresh(context, true, "patch-recovery");
                drainInstallQueue(context);
            } finally {
                VERIFY_RUNNING.set(false);
            }
        }, "NavigatorPatchVerify");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    static void verifyRestoredAsync(Context context, NavigatorPatchStore.Profile profile) {
        if (!VERIFY_RUNNING.compareAndSet(false, true)) return;
        NavigatorPatchStore.transition(
                context, profile, NavigatorPatchStore.INSTALLED_VERIFY,
                "Verifying restored APK");
        Thread worker = new Thread(() -> {
            try {
                String installedIdentity = NavigatorPatchStore.installedIdentity(context, profile);
                NavigatorPatchPipeline.ScanResult result =
                        NavigatorPatchPipeline.inspectInstalled(context, profile);
                verifyExpected(context, profile, result, false);
                completeRestore(context, profile, result, "Original APK restored",
                        installedIdentity);
            } catch (Exception error) {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED, error.getMessage());
            } finally {
                VERIFY_RUNNING.set(false);
            }
        }, "NavigatorRestoreVerify");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    static void reconcile(Context context) {
        reconcileGlobal(context);
        for (NavigatorPatchStore.Profile profile : NavigatorPatchStore.Profile.values()) {
            reconcileLocal(context, profile);
        }
        drainInstallQueue(context);
    }

    private static void reconcileGlobal(Context context) {
        NavigatorPatchStore.OperationSnapshot operation = NavigatorPatchStore.operation(context, null);
        NavigatorPatchStore.Profile profile = operation.profile;
        if (profile == null) {
            if (!NavigatorPatchStore.IDLE.equals(operation.phase)
                    && !NavigatorPatchStore.VERIFIED.equals(operation.phase)
                    && !NavigatorPatchStore.FAILED.equals(operation.phase)) {
                NavigatorPatchStore.transition(context, null, NavigatorPatchStore.FAILED,
                        "Interrupted before a navigator profile was selected");
            }
            return;
        }
        if (NavigatorPatchStore.IDLE.equals(operation.phase)
                || NavigatorPatchStore.VERIFIED.equals(operation.phase)
                || NavigatorPatchStore.FAILED.equals(operation.phase)
                || NavigatorPatchStore.CANCELLED.equals(operation.phase)
                || NavigatorPatchStore.RECOVERY_REQUIRED.equals(operation.phase)) {
            return;
        }
        boolean installed = isInstalled(context, profile.packageName);
        if ((NavigatorPatchStore.INSTALLED_VERIFY.equals(operation.phase)
                || NavigatorPatchStore.OUTPUT_VERIFY.equals(operation.phase))
                && !NavigatorPatchStore.expectedSha(context).isEmpty()) {
            if (!installed) {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        "Navigator is missing during installed APK verification");
            } else if (NavigatorPatchStore.expectedDirect(context).isEmpty()) {
                verifyRestoredAsync(context, profile);
            } else {
                verifyInstalledAsync(context, profile);
            }
            return;
        }
        long age = Math.max(0L, System.currentTimeMillis() - NavigatorPatchStore.stateAt(context));
        boolean sessionExists = installerSessionExists(
                context, NavigatorPatchStore.sessionId(context));
        if (NavigatorPatchStore.INSTALL_REQUESTED.equals(operation.phase)
                || NavigatorPatchStore.COMMITTING.equals(operation.phase)) {
            if (sessionExists && age < INTERRUPTED_TIMEOUT_MS) return;
            if (sessionExists) abandonPreparedSession(context);
            if (installed) {
                if (!NavigatorPatchStore.expectedDirect(context).isEmpty()
                        && initialInstalledTargetUnchanged(context, profile)) {
                    finishUnchangedFailure(context, profile,
                            "Install did not change the navigator");
                } else if (NavigatorPatchStore.expectedDirect(context).isEmpty()) {
                    verifyRestoredAsync(context, profile);
                } else {
                    verifyInstalledAsync(context, profile);
                }
            } else if (!NavigatorPatchStore.expectedDirect(context).isEmpty()
                    && NavigatorPatchStore.initialUpdateTime(context) < 0L) {
                finishUnchangedFailure(context, profile,
                        "Install did not create the navigator");
            } else {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        "Navigator is missing after interrupted install");
            }
            return;
        }
        if (NavigatorPatchStore.UNINSTALL_REQUESTED.equals(operation.phase)) {
            if (age < INTERRUPTED_TIMEOUT_MS) return;
            abandonPreparedSession(context);
            if (NavigatorPatchStore.OP_PATCH.equals(operation.kind)
                    && installed && initialInstalledTargetUnchanged(context, profile)) {
                finishUnchangedFailure(context, profile,
                        "Navigator removal did not complete");
            } else {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        installed
                                ? "Navigator changed during interrupted removal"
                                : "Navigator was removed before the transaction completed");
            }
            return;
        }
        abandonPreparedSession(context);
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED,
                "Interrupted before the installed navigator was changed");
    }

    private static void reconcileLocal(Context context, NavigatorPatchStore.Profile profile) {
        //A same-process worker owns this operation; only an absent worker is a restart orphan.
        if (NavigatorPatchPipeline.hasActiveWorker(profile)) return;
        NavigatorPatchStore.OperationSnapshot operation =
                NavigatorPatchStore.operation(context, profile);
        if (operation.kind.isEmpty() || operation.terminal()) return;
        if (NavigatorPatchStore.CANCEL_REQUESTED.equals(operation.phase)) {
            abandonPreparedSession(context, profile);
            File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
            NavigatorPatchStore.markCancelled(context, profile, "Cancelled after restart");
            NavigatorPatchPipeline.deleteTree(transaction);
            NavigatorPatchStore.clearTransactionMetadata(context, profile);
            NavigatorPatchStore.releaseInstall(context, profile);
            return;
        }
        if (NavigatorPatchStore.READY_TO_INSTALL.equals(operation.phase)) return;
        if (NavigatorPatchStore.isInstalledVerificationPhase(operation.phase)
                && !NavigatorPatchStore.expectedSha(context, profile).isEmpty()) {
            if (!isInstalled(context, profile.packageName)) {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        "Navigator is missing during installed APK verification");
            } else {
                verifyInstalledAsync(context, profile);
            }
            return;
        }
        if (NavigatorPatchStore.OUTPUT_VERIFY.equals(operation.phase)) {
            File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED,
                    "Interrupted during staged output verification");
            NavigatorPatchStore.clearTransactionMetadata(context, profile);
            NavigatorPatchPipeline.deleteTree(transaction);
            return;
        }
        long age = Math.max(0L, System.currentTimeMillis()
                - NavigatorPatchStore.stateAt(context, profile));
        boolean sessionExists = installerSessionExists(
                context, NavigatorPatchStore.sessionId(context, profile));
        boolean installPhase = NavigatorPatchStore.INSTALL_REQUESTED.equals(operation.phase)
                || NavigatorPatchStore.COMMITTING.equals(operation.phase)
                || NavigatorPatchStore.UNINSTALL_REQUESTED.equals(operation.phase);
        if (installPhase) {
            if (sessionExists && age < INTERRUPTED_TIMEOUT_MS) return;
            if (sessionExists) abandonPreparedSession(context, profile);
            boolean installed = isInstalled(context, profile.packageName);
            if (installed && initialInstalledTargetUnchanged(context, profile)) {
                finishUnchangedFailure(context, profile, "Install did not change the navigator");
            } else if (installed) {
                verifyInstalledAsync(context, profile);
            } else {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        "Navigator is missing after interrupted install");
            }
            return;
        }
        File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
        abandonPreparedSession(context, profile);
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED,
                "Interrupted before the installed navigator was changed");
        NavigatorPatchStore.clearTransactionMetadata(context, profile);
        NavigatorPatchPipeline.deleteTree(transaction);
    }

    static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static int prepareSession(Context context, NavigatorPatchStore.Profile profile,
            File setDirectory) throws Exception {
        if (!setDirectory.isDirectory()) throw new IOException("APK-set is missing: " + setDirectory);
        java.util.List<File> members = NavigatorApkSet.memberFiles(setDirectory);
        if (members.isEmpty()) throw new IOException("APK-set is empty");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(profile.packageName);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            byte[] buffer = new byte[128 * 1024];
            for (File member : members) {
                try (FileInputStream input = new FileInputStream(member);
                     OutputStream output = session.openWrite(member.getName(), 0, member.length())) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                    session.fsync(output);
                }
            }
            return sessionId;
        } catch (Throwable error) {
            try {
                installer.abandonSession(sessionId);
            } catch (RuntimeException ignored) {
            }
            throw error;
        }
    }

    private static PendingIntent callback(Context context,
            NavigatorPatchStore.Profile profile, String operation) {
        String token = NavigatorPatchStore.transactionToken(context, profile);
        Intent intent = new Intent(context, NavigatorPackageResultReceiver.class)
                .putExtra(EXTRA_OPERATION, operation)
                .putExtra(EXTRA_PROFILE, profile.id)
                .putExtra(EXTRA_TOKEN, token);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(
                context, (operation + profile.id + token).hashCode(), intent, flags);
    }

    private static boolean installerSessionExists(Context context, int sessionId) {
        if (sessionId < 0) return false;
        for (PackageInstaller.SessionInfo info
                : context.getPackageManager().getPackageInstaller().getMySessions()) {
            if (info.getSessionId() == sessionId) return true;
        }
        return false;
    }

    static void abandonPreparedSession(Context context) {
        abandonPreparedSession(context, null);
    }

    static void abandonPreparedSession(Context context, NavigatorPatchStore.Profile profile) {
        int sessionId = NavigatorPatchStore.sessionId(context, profile);
        if (sessionId < 0) return;
        try {
            context.getPackageManager().getPackageInstaller().abandonSession(sessionId);
        } catch (RuntimeException ignored) {
        }
        NavigatorPatchStore.setSessionId(context, profile, -1);
    }

    private static void verifyExpected(Context context, NavigatorPatchStore.Profile profile,
            NavigatorPatchPipeline.ScanResult actual, boolean includeComponents)
            throws IOException {
        if (!NavigatorPatchStore.expectedSha(context, profile).equals(actual.sha256)
                || NavigatorPatchStore.expectedVersionCode(context, profile) != actual.versionCode
                || !NavigatorPatchStore.expectedSigner(context, profile).equals(actual.signerSha256)) {
            throw new IOException("Installed APK does not match the staged artifact");
        }
        String expectedOptional = NavigatorPatchStore.expectedOptional(context, profile);
        String expectedGmsCore = NavigatorPatchStore.expectedGmsCore(context, profile);
        String expectedAlert = NavigatorPatchStore.expectedAlert(context, profile);
        boolean gmsCoreMatches = expectedGmsCore.equals(actual.gmsCoreState)
                || (NavigatorPatchStore.FAILED.equals(expectedGmsCore)
                && NavigatorPatchStore.PATCHABLE.equals(actual.gmsCoreState));
        boolean optionalMatches = expectedOptional.equals(actual.optionalState)
                || (NavigatorPatchStore.FAILED.equals(expectedOptional)
                && NavigatorPatchStore.PATCHABLE.equals(actual.optionalState));
        boolean auxiliaryMatches = expectedAlert.equals(actual.alertState)
                || (NavigatorPatchStore.FAILED.equals(expectedAlert)
                && NavigatorPatchStore.PATCHABLE.equals(actual.alertState));
        if (includeComponents
                && (!NavigatorPatchStore.expectedDirect(context, profile).equals(actual.directState)
                || !gmsCoreMatches
                || !optionalMatches || !auxiliaryMatches)) {
            throw new IOException("Installed patch components do not match staged output");
        }
    }

    private static NavigatorPatchPipeline.ScanResult withExpectedOptionalFailure(
            Context context, NavigatorPatchStore.Profile profile,
            NavigatorPatchPipeline.ScanResult actual) {
        boolean optionalFailed = NavigatorPatchStore.FAILED.equals(
                NavigatorPatchStore.expectedOptional(context, profile))
                && NavigatorPatchStore.PATCHABLE.equals(actual.optionalState);
        boolean gmsCoreFailed = NavigatorPatchStore.FAILED.equals(
                NavigatorPatchStore.expectedGmsCore(context, profile))
                && NavigatorPatchStore.PATCHABLE.equals(actual.gmsCoreState);
        boolean auxiliaryFailed = NavigatorPatchStore.FAILED.equals(
                NavigatorPatchStore.expectedAlert(context, profile))
                && NavigatorPatchStore.PATCHABLE.equals(actual.alertState);
        if (!gmsCoreFailed && !optionalFailed && !auxiliaryFailed) {
            return actual;
        }
        return new NavigatorPatchPipeline.ScanResult(
                actual.profile, actual.sha256, actual.versionName, actual.versionCode,
                actual.signerSha256, actual.directState,
                gmsCoreFailed ? NavigatorPatchStore.FAILED : actual.gmsCoreState,
                optionalFailed ? NavigatorPatchStore.FAILED : actual.optionalState,
                auxiliaryFailed ? NavigatorPatchStore.FAILED : actual.alertState,
                "Optional patch attempt failed");
    }

    private static boolean sameArtifact(NavigatorPatchPipeline.ScanResult first,
            NavigatorPatchPipeline.ScanResult second) {
        return first.sha256.equals(second.sha256)
                && first.versionCode == second.versionCode
                && first.signerSha256.equals(second.signerSha256);
    }

    private static void completeRestore(Context context, NavigatorPatchStore.Profile profile,
            NavigatorPatchPipeline.ScanResult result, String detail,
            String installedIdentity) throws Exception {
        File transaction = NavigatorPatchStore.transactionDirectory(context);
        String recoveryOwner = NavigatorPatchStore.recoveryOwner(context);
        String transactionName = transaction.getName();
        String expectedFingerprint = NavigatorPatchStore.expectedSha(context);
        if (!installedIdentity.equals(
                NavigatorPatchStore.installedIdentity(context, profile))) {
            throw new IOException("Installed navigator changed during recovery verification");
        }
        if (!NavigatorAssetManager.recordAuthoritativeRestoreVerified(
                context, profile, recoveryOwner, transactionName, expectedFingerprint)) {
            throw new IOException("Asset recovery completion receipt was rejected");
        }
        if (!installedIdentity.equals(
                NavigatorPatchStore.installedIdentity(context, profile))) {
            throw new IOException("Installed navigator changed during recovery verification");
        }
        NavigatorPatchStore.clearExternal(context, profile);
        NavigatorPatchStore.saveScan(context, result);
        NavigatorPatchStore.completeRestoreTransaction(context, profile, detail);
        if (!NavigatorAssetManager.finishAuthoritativeRestoreVerified(
                context, profile, recoveryOwner, transactionName, expectedFingerprint)) {
            AppEventLogger.event(context,
                    "navigator_asset recovery_finalize_pending owner=" + recoveryOwner);
            return;
        }
        NavigatorPatchPipeline.deleteTree(transaction);
    }

    private static boolean initialInstalledTargetUnchanged(Context context,
            NavigatorPatchStore.Profile profile) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            return NavigatorPatchStore.initialUpdateTime(context, profile) == info.lastUpdateTime
                    && NavigatorPatchStore.initialVersionCode(context, profile)
                    == info.getLongVersionCode()
                    && NavigatorPatchStore.initialSigner(context, profile).equals(
                    NavigatorSigningKey.installedCertificateSha256(
                        context, profile.packageName))
                    && NavigatorPatchStore.initialFingerprint(context, profile).equals(
                    NavigatorPatchPipeline.inspectInstalled(context, profile).sha256);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void finishUnchangedFailure(Context context,
            NavigatorPatchStore.Profile profile, String detail) {
        File transaction = NavigatorPatchStore.transactionDirectory(context, profile);
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED, detail);
        NavigatorPatchStore.clearTransactionMetadata(context, profile);
        NavigatorPatchStore.releaseInstall(context, profile);
        NavigatorPatchPipeline.deleteTree(transaction);
    }

    private static void verifyInstallTargetUnchanged(Context context,
            NavigatorPatchPipeline.PreparedPatch prepared) throws Exception {
        PackageInfo current;
        try {
            current = context.getPackageManager().getPackageInfo(
                    prepared.profile.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException ignored) {
            current = null;
        }
        if (prepared.installedUpdateTime < 0L) {
            if (current != null) throw new IOException("Navigator was installed after staging");
            return;
        }
        if (current == null
                || current.lastUpdateTime != prepared.installedUpdateTime
                || current.getLongVersionCode() != prepared.installedVersionCode
                || !prepared.installedSignerSha256.equals(
                NavigatorSigningKey.installedCertificateSha256(
                        context, prepared.profile.packageName))
                || !prepared.installedFingerprint.equals(
                NavigatorPatchPipeline.inspectInstalled(context, prepared.profile).sha256)) {
            throw new IOException("Installed navigator changed after staging");
        }
    }

    private static void verifyPatchedSetUnchanged(Context context,
            NavigatorPatchPipeline.PreparedPatch prepared, File patched) throws Exception {
        NavigatorApkSet.SetInfo actual = NavigatorApkSet.readDirectory(
                context, prepared.profile, patched);
        if (!matchesPreparedOutputForTest(
                prepared.output.sha256,
                prepared.output.versionName,
                prepared.output.versionCode,
                prepared.output.signerSha256,
                actual.fingerprint,
                actual.versionName,
                actual.versionCode,
                actual.signerSha256)) {
            throw new IOException("Staged patched APK-set changed before installation");
        }
    }

    static boolean matchesPreparedOutputForTest(
            String expectedFingerprint, String expectedVersionName,
            long expectedVersionCode, String expectedSigner,
            String actualFingerprint, String actualVersionName,
            long actualVersionCode, String actualSigner) {
        return safeEquals(expectedFingerprint, actualFingerprint)
                && safeEquals(expectedVersionName, actualVersionName)
                && expectedVersionCode == actualVersionCode
                && safeEquals(expectedSigner, actualSigner);
    }

    private static boolean safeEquals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
}
