package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class NavigatorPackageResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // PackageInstaller delivers this callback through a restricted receiver context.
        // Normalize once before any continuation can reach the isolated worker service.
        Context appContext = context.getApplicationContext();
        String operation = intent.getStringExtra(NavigatorPackageInstaller.EXTRA_OPERATION);
        String token = intent.getStringExtra(NavigatorPackageInstaller.EXTRA_TOKEN);
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(
                intent.getStringExtra(NavigatorPackageInstaller.EXTRA_PROFILE));
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Intent confirmation = status == PackageInstaller.STATUS_PENDING_USER_ACTION
                ? intent.getParcelableExtra(Intent.EXTRA_INTENT) : null;
        boolean terminal = status != PackageInstaller.STATUS_PENDING_USER_ACTION
                || confirmation == null;
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (!NavigatorPatchStore.acceptCallback(
                appContext, profile, token, operation, sessionId, terminal)) {
            AppEventLogger.event(appContext, "navigator_patch stale_callback operation=" + operation);
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(confirmation);
            } else {
                fail(appContext, profile, operation, "Installer confirmation is missing");
            }
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS || operation == null) {
            fail(appContext, profile, operation,
                    message == null ? "Package operation failed" : message);
            return;
        }
        try {
            if (NavigatorPackageInstaller.OP_UNINSTALL.equals(operation)) {
                NavigatorPackageInstaller.commitAfterUninstall(
                        appContext, profile, NavigatorPackageInstaller.OP_INSTALL);
            } else if (NavigatorPackageInstaller.OP_UNINSTALL_RESTORE.equals(operation)) {
                NavigatorPackageInstaller.commitAfterUninstall(
                        appContext, profile, NavigatorPackageInstaller.OP_INSTALL_RESTORE);
            } else if (NavigatorPackageInstaller.OP_INSTALL.equals(operation)) {
                NavigatorPackageInstaller.verifyInstalledAsync(
                        appContext, profile);
            } else if (NavigatorPackageInstaller.OP_INSTALL_RESTORE.equals(operation)) {
                NavigatorPackageInstaller.verifyRestoredAsync(appContext, profile);
            }
        } catch (Exception error) {
            fail(appContext, profile, operation, error.getMessage());
        }
    }

    private static void fail(Context context, NavigatorPatchStore.Profile profile,
            String operation, String message) {
        if (profile == null) return;
        if (NavigatorPackageInstaller.OP_UNINSTALL.equals(operation)
                || NavigatorPackageInstaller.OP_UNINSTALL_RESTORE.equals(operation)) {
            NavigatorPackageInstaller.abandonPreparedSession(context, profile);
        }
        boolean restore = NavigatorPackageInstaller.OP_UNINSTALL_RESTORE.equals(operation)
                || NavigatorPackageInstaller.OP_INSTALL_RESTORE.equals(operation);
        NavigatorPatchStore.transition(context, profile,
                restore || NavigatorPatchStore.requiresRecovery(context, profile)
                        ? NavigatorPatchStore.RECOVERY_REQUIRED
                        : NavigatorPatchStore.FAILED,
                message == null ? "Package operation failed" : message);
        NavigatorPatchStore.releaseInstall(context, profile);
        MainActivity.requestPatchUiStateRefresh(context, true, "patch-failed");
        NavigatorPackageInstaller.drainInstallQueue(context);
    }
}
