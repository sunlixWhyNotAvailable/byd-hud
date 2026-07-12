package com.bydhud.app;

//models install-time permission readiness so setup screens can show actionable status.

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.Settings;

//defines the NavPermissionStatus module boundary so related behavior stays readable inside one unit.
final class NavPermissionStatus {
    final boolean notificationListenerEnabled;
    final boolean accessibilityServiceEnabled;
    final boolean accessibilityMasterEnabled;
    final boolean dashboardOverlayEnabled;
    final boolean storageReadEnabled;
    final boolean storageWriteEnabled;
    final String notificationListenersRaw;
    final String accessibilityServicesRaw;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavPermissionStatus(
            boolean notificationListenerEnabled,
            boolean accessibilityServiceEnabled,
            boolean accessibilityMasterEnabled,
            boolean dashboardOverlayEnabled,
            boolean storageReadEnabled,
            boolean storageWriteEnabled,
            String notificationListenersRaw,
            String accessibilityServicesRaw) {
        this.notificationListenerEnabled = notificationListenerEnabled;
        this.accessibilityServiceEnabled = accessibilityServiceEnabled;
        this.accessibilityMasterEnabled = accessibilityMasterEnabled;
        this.dashboardOverlayEnabled = dashboardOverlayEnabled;
        this.storageReadEnabled = storageReadEnabled;
        this.storageWriteEnabled = storageWriteEnabled;
        this.notificationListenersRaw = notificationListenersRaw;
        this.accessibilityServicesRaw = accessibilityServicesRaw;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static NavPermissionStatus check(Context context) {
        String packageName = context.getPackageName();
        NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                packageName, "", "");
        String notificationListeners = Settings.Secure.getString(
                context.getContentResolver(),
                NavPermissionGrantPlan.NOTIFICATION_LISTENERS);
        String accessibilityServices = Settings.Secure.getString(
                context.getContentResolver(),
                NavPermissionGrantPlan.ACCESSIBILITY_SERVICES);
        int accessibilityEnabled = Settings.Secure.getInt(
                context.getContentResolver(),
                NavPermissionGrantPlan.ACCESSIBILITY_ENABLED,
                0);
        return new NavPermissionStatus(
                NavPermissionGrantPlan.containsService(
                        notificationListeners, plan.notificationService),
                NavPermissionGrantPlan.containsService(
                        accessibilityServices, plan.accessibilityService),
                accessibilityEnabled == 1,
                Settings.canDrawOverlays(context),
                hasStorageAccess(context, Manifest.permission.READ_EXTERNAL_STORAGE,
                        AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE),
                hasStorageAccess(context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE),
                notificationListeners,
                accessibilityServices);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static NavPermissionStatus forTest(
            boolean notificationListenerEnabled,
            boolean accessibilityServiceEnabled,
            boolean accessibilityMasterEnabled,
            boolean dashboardOverlayEnabled,
            String notificationListenersRaw,
            String accessibilityServicesRaw) {
        return new NavPermissionStatus(
                notificationListenerEnabled,
                accessibilityServiceEnabled,
                accessibilityMasterEnabled,
                dashboardOverlayEnabled,
                true,
                true,
                notificationListenersRaw,
                accessibilityServicesRaw);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    boolean allGranted() {
        return notificationListenerEnabled
                && accessibilityServiceEnabled
                && accessibilityMasterEnabled
                && dashboardOverlayEnabled
                && storageReadEnabled
                && storageWriteEnabled;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    String summary() {
        if (allGranted()) {
            return "nav permissions OK";
        }
        StringBuilder builder = new StringBuilder("missing:");
        if (!notificationListenerEnabled) {
            builder.append(" notification-listener");
        }
        if (!accessibilityServiceEnabled) {
            builder.append(" accessibility-service");
        }
        if (!accessibilityMasterEnabled) {
            builder.append(" accessibility-enabled");
        }
        if (!dashboardOverlayEnabled) {
            builder.append(" dashboard-overlay");
        }
        if (!storageReadEnabled) {
            builder.append(" storage-read");
        }
        if (!storageWriteEnabled) {
            builder.append(" storage-write");
        }
        return builder.toString();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasStorageAccess(Context context, String permission, String appOp) {
        if (!hasPermission(context, permission) || !Environment.isExternalStorageLegacy()) {
            return false;
        }
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);
        if (manager == null) {
            return false;
        }
        int mode = manager.unsafeCheckOpNoThrow(
                appOp, context.getApplicationInfo().uid, context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
    }
}
