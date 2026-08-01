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

final class NavigatorPackageInstaller {
    private static final long INTERRUPTED_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final AtomicBoolean VERIFY_RUNNING = new AtomicBoolean();
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
        verifyInstallTargetUnchanged(context, prepared);
        File patched = new File(prepared.directory, "patched.apk");
        int sessionId = prepareSession(context, prepared.profile, patched);
        NavigatorPatchStore.setSessionId(context, sessionId);
        try {
            verifyInstallTargetUnchanged(context, prepared);
            NavHudLiveSender.get(context).stop(
                    prepared.profile.packageName, "navigator-patch", true);
            if (prepared.destructive && isInstalled(context, prepared.profile.packageName)) {
                verifyInstallTargetUnchanged(context, prepared);
                NavigatorPatchStore.expectCallback(context, OP_UNINSTALL);
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
            abandonPreparedSession(context);
            throw error;
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
            File source = NavigatorPatchStore.originalApk(context);
            if (!source.isFile()) throw new IOException("Recovery APK is missing");
            NavigatorPatchPipeline.ScanResult expected =
                    NavigatorPatchPipeline.verifyRecoverySource(context, profile, source);
            if (isInstalled(context, profile.packageName)) {
                NavigatorPatchPipeline.ScanResult installed =
                        NavigatorPatchPipeline.inspectInstalled(context, profile);
                if (sameArtifact(expected, installed)) {
                    completeRestore(context, profile, installed, "Original APK is already installed");
                    return;
                }
            }
            NavigatorPatchStore.beginRestoreTransaction(context, profile, expected);
            int sessionId = prepareSession(context, profile, source);
            NavigatorPatchStore.setSessionId(context, sessionId);
            NavHudLiveSender.get(context).stop(profile.packageName, "navigator-restore", true);
            if (isInstalled(context, profile.packageName)) {
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
        int sessionId = NavigatorPatchStore.sessionId(context);
        if (sessionId < 0) throw new IOException("Prepared installer session is missing");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            NavigatorPatchStore.expectCallback(context, operation);
            NavigatorPatchStore.transition(context, profile,
                    NavigatorPatchStore.COMMITTING, "Submitting installer session");
            session.commit(callback(context, profile, operation).getIntentSender());
            NavigatorPatchStore.transitionIfPhase(context, profile,
                    NavigatorPatchStore.COMMITTING, NavigatorPatchStore.INSTALL_REQUESTED,
                    "Waiting for install confirmation");
        }
    }

    static void verifyInstalledAsync(Context context, NavigatorPatchStore.Profile profile) {
        if (!VERIFY_RUNNING.compareAndSet(false, true)) return;
        NavigatorPatchStore.transition(
                context, profile, NavigatorPatchStore.OUTPUT_VERIFY, "Verifying installed APK");
        Thread worker = new Thread(() -> {
            try {
                if (!NavigatorSigningKey.installedUsesLocalKey(context, profile.packageName)) {
                    throw new IOException("Installed signer does not match local patcher key");
                }
                NavigatorPatchPipeline.ScanResult result =
                        NavigatorPatchPipeline.inspectInstalled(context, profile);
                verifyExpected(context, result, true);
                PackageInfo info = context.getPackageManager().getPackageInfo(
                        profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                File transaction = NavigatorPatchStore.transactionDirectory(context);
                NavigatorPatchStore.clearExternal(context, profile);
                NavigatorPatchStore.saveScan(context, result);
                NavigatorPatchStore.recordInstalledVerification(
                        context, profile, info.lastUpdateTime, info.getLongVersionCode(),
                        result.sha256, result.directState, result.optionalState);
                NavigatorPatchStore.transition(
                        context, profile, NavigatorPatchStore.VERIFIED, "Installed and verified");
                NavigatorPatchStore.clearTransactionMetadata(context);
                NavigatorPatchPipeline.deleteTree(transaction);
            } catch (Exception error) {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        error.getMessage());
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
                context, profile, NavigatorPatchStore.OUTPUT_VERIFY, "Verifying restored APK");
        Thread worker = new Thread(() -> {
            try {
                NavigatorPatchPipeline.ScanResult result =
                        NavigatorPatchPipeline.inspectInstalled(context, profile);
                verifyExpected(context, result, false);
                completeRestore(context, profile, result, "Original APK restored");
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
        NavigatorPatchStore.OperationSnapshot operation = NavigatorPatchStore.operation(context);
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
                || NavigatorPatchStore.RECOVERY_REQUIRED.equals(operation.phase)) {
            return;
        }
        boolean installed = isInstalled(context, profile.packageName);
        if (NavigatorPatchStore.OUTPUT_VERIFY.equals(operation.phase)
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
            if (!installed) {
                NavigatorPatchStore.transition(context, profile,
                        NavigatorPatchStore.RECOVERY_REQUIRED,
                        "Navigator was removed before the patch transaction completed");
            }
            return;
        }
        abandonPreparedSession(context);
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED,
                "Interrupted before the installed navigator was changed");
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
            File apk) throws Exception {
        if (!apk.isFile()) throw new IOException("APK is missing: " + apk);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(profile.packageName);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             FileInputStream input = new FileInputStream(apk);
             OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            session.fsync(output);
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
        String token = NavigatorPatchStore.transactionToken(context);
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
        int sessionId = NavigatorPatchStore.sessionId(context);
        if (sessionId < 0) return;
        try {
            context.getPackageManager().getPackageInstaller().abandonSession(sessionId);
        } catch (RuntimeException ignored) {
        }
        NavigatorPatchStore.setSessionId(context, -1);
    }

    private static void verifyExpected(Context context,
            NavigatorPatchPipeline.ScanResult actual, boolean includeComponents)
            throws IOException {
        if (!NavigatorPatchStore.expectedSha(context).equals(actual.sha256)
                || NavigatorPatchStore.expectedVersionCode(context) != actual.versionCode
                || !NavigatorPatchStore.expectedSigner(context).equals(actual.signerSha256)) {
            throw new IOException("Installed APK does not match the staged artifact");
        }
        if (includeComponents
                && (!NavigatorPatchStore.expectedDirect(context).equals(actual.directState)
                || !NavigatorPatchStore.expectedOptional(context).equals(actual.optionalState))) {
            throw new IOException("Installed patch components do not match staged output");
        }
    }

    private static boolean sameArtifact(NavigatorPatchPipeline.ScanResult first,
            NavigatorPatchPipeline.ScanResult second) {
        return first.sha256.equals(second.sha256)
                && first.versionCode == second.versionCode
                && first.signerSha256.equals(second.signerSha256);
    }

    private static void completeRestore(Context context, NavigatorPatchStore.Profile profile,
            NavigatorPatchPipeline.ScanResult result, String detail) {
        File transaction = NavigatorPatchStore.transactionDirectory(context);
        NavigatorPatchStore.clearExternal(context, profile);
        NavigatorPatchStore.saveScan(context, result);
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.IDLE, detail);
        NavigatorPatchStore.clearTransactionMetadata(context);
        NavigatorPatchPipeline.deleteTree(transaction);
    }

    private static boolean initialInstalledTargetUnchanged(Context context,
            NavigatorPatchStore.Profile profile) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            return NavigatorPatchStore.initialUpdateTime(context) == info.lastUpdateTime
                    && NavigatorPatchStore.initialVersionCode(context)
                    == info.getLongVersionCode()
                    && NavigatorPatchStore.initialSigner(context).equals(
                    NavigatorSigningKey.installedCertificateSha256(
                            context, profile.packageName));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void finishUnchangedFailure(Context context,
            NavigatorPatchStore.Profile profile, String detail) {
        File transaction = NavigatorPatchStore.transactionDirectory(context);
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED, detail);
        NavigatorPatchStore.clearTransactionMetadata(context);
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
                        context, prepared.profile.packageName))) {
            throw new IOException("Installed navigator changed after staging");
        }
    }
}
