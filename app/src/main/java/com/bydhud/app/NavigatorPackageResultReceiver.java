package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class NavigatorPackageResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
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
                context, profile, token, operation, sessionId, terminal)) {
            AppEventLogger.event(context, "navigator_patch stale_callback operation=" + operation);
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            } else {
                fail(context, profile, operation, "Installer confirmation is missing");
            }
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS || operation == null) {
            fail(context, profile, operation,
                    message == null ? "Package operation failed" : message);
            return;
        }
        try {
            if (NavigatorPackageInstaller.OP_UNINSTALL.equals(operation)) {
                NavigatorPackageInstaller.commitAfterUninstall(
                        context, profile, NavigatorPackageInstaller.OP_INSTALL);
            } else if (NavigatorPackageInstaller.OP_UNINSTALL_RESTORE.equals(operation)) {
                NavigatorPackageInstaller.commitAfterUninstall(
                        context, profile, NavigatorPackageInstaller.OP_INSTALL_RESTORE);
            } else if (NavigatorPackageInstaller.OP_INSTALL.equals(operation)) {
                NavigatorPackageInstaller.verifyInstalledAsync(
                        context, profile);
            } else if (NavigatorPackageInstaller.OP_INSTALL_RESTORE.equals(operation)) {
                NavigatorPackageInstaller.verifyRestoredAsync(context, profile);
            }
        } catch (Exception error) {
            fail(context, profile, operation, error.getMessage());
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
        boolean destructive = NavigatorPatchStore.operation(context, profile).destructive;
        boolean missing = !NavigatorPackageInstaller.isInstalled(
                context, profile.packageName);
        NavigatorPatchStore.transition(context, profile,
                restore || (destructive && missing)
                        ? NavigatorPatchStore.RECOVERY_REQUIRED
                        : NavigatorPatchStore.FAILED,
                message == null ? "Package operation failed" : message);
        NavigatorPatchStore.releaseInstall(context, profile);
        MainActivity.requestPatchUiStateRefresh(context, true, "patch-failed");
        NavigatorPackageInstaller.drainInstallQueue(context);
    }
}
