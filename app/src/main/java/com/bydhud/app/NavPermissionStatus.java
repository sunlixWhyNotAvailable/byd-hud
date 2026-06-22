package com.bydhud.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

final class NavPermissionStatus {
    final boolean notificationListenerEnabled;
    final boolean accessibilityServiceEnabled;
    final boolean accessibilityMasterEnabled;
    final boolean dashboardOverlayEnabled;
    final boolean storageReadEnabled;
    final boolean storageWriteEnabled;
    final String notificationListenersRaw;
    final String accessibilityServicesRaw;

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
                hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE),
                hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                notificationListeners,
                accessibilityServices);
    }

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

    boolean allGranted() {
        return notificationListenerEnabled
                && accessibilityServiceEnabled
                && accessibilityMasterEnabled
                && dashboardOverlayEnabled
                && storageReadEnabled
                && storageWriteEnabled;
    }

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

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
