package com.bydhud.app;

final class NavRuntimePermissionStatus {
    final NavPermissionStatus settings;
    final boolean notificationListenerConnected;
    final boolean accessibilityServiceConnected;
    final boolean accessibilityServiceCrashed;
    final String notificationDetail;
    final String accessibilityDetail;

    private NavRuntimePermissionStatus(
            NavPermissionStatus settings,
            boolean notificationListenerConnected,
            boolean accessibilityServiceConnected,
            boolean accessibilityServiceCrashed,
            String notificationDetail,
            String accessibilityDetail) {
        this.settings = settings;
        this.notificationListenerConnected = notificationListenerConnected;
        this.accessibilityServiceConnected = accessibilityServiceConnected;
        this.accessibilityServiceCrashed = accessibilityServiceCrashed;
        this.notificationDetail = safe(notificationDetail);
        this.accessibilityDetail = safe(accessibilityDetail);
    }

    static NavRuntimePermissionStatus check(android.content.Context context) {
        NavPermissionStatus settings = NavPermissionStatus.check(context);
        return new NavRuntimePermissionStatus(
                settings,
                NavNotificationListenerService.isConnectedForRuntimeCheck(),
                NavAccessibilityService.isConnectedForRuntimeCheck(),
                NavAccessibilityService.isCrashedForRuntimeCheck(),
                NavNotificationListenerService.runtimeDetailForRuntimeCheck(),
                NavAccessibilityService.runtimeDetailForRuntimeCheck());
    }

    static NavRuntimePermissionStatus fromSettingsForTest(
            NavPermissionStatus settings,
            boolean notificationListenerConnected,
            boolean accessibilityServiceConnected,
            boolean accessibilityServiceCrashed,
            String notificationDetail,
            String accessibilityDetail) {
        return new NavRuntimePermissionStatus(
                settings,
                notificationListenerConnected,
                accessibilityServiceConnected,
                accessibilityServiceCrashed,
                notificationDetail,
                accessibilityDetail);
    }

    boolean settingsGranted() {
        return settings != null && settings.allGranted();
    }

    boolean readyForCapture() {
        return settingsGranted()
                && notificationListenerConnected
                && accessibilityServiceConnected
                && !accessibilityServiceCrashed;
    }

    boolean needsAdbGrant() {
        return settings == null || !settings.allGranted();
    }

    String uiSummary(boolean autoGrantAttempted, String adbKeyFingerprint) {
        StringBuilder builder = new StringBuilder();
        if (!needsAdbGrant()) {
            builder.append("Permissions: OK");
            if (readyForCapture()) {
                builder.append("\nCapture services: OK");
            } else {
                builder.append("\nCapture services: reconnecting");
            }
            return builder.toString();
        }
        builder.append("Permissions: missing");
        appendMissingPermission(builder, "notification listener",
                settings != null && settings.notificationListenerEnabled);
        appendMissingPermission(builder, "accessibility",
                settings != null
                        && settings.accessibilityServiceEnabled
                        && settings.accessibilityMasterEnabled);
        appendMissingPermission(builder, "dashboard overlay",
                settings != null && settings.dashboardOverlayEnabled);
        appendMissingPermission(builder, "storage read",
                settings != null && settings.storageReadEnabled);
        appendMissingPermission(builder, "storage write",
                settings != null && settings.storageWriteEnabled);
        builder.append(autoGrantAttempted
                ? "\nADB: not granted"
                : "\nADB: auto-grant pending");
        return builder.toString();
    }

    String summary() {
        if (readyForCapture()) {
            return "nav capture runtime OK";
        }
        StringBuilder builder = new StringBuilder("not ready:");
        if (settings == null || !settings.notificationListenerEnabled) {
            builder.append(" notification-setting");
        }
        if (!notificationListenerConnected) {
            builder.append(" notification-listener");
        }
        if (settings == null || !settings.accessibilityServiceEnabled) {
            builder.append(" accessibility-setting");
        }
        if (settings == null || !settings.accessibilityMasterEnabled) {
            builder.append(" accessibility-master");
        }
        if (!accessibilityServiceConnected) {
            builder.append(" accessibility-service");
        }
        if (accessibilityServiceCrashed) {
            builder.append(" accessibility-crashed");
        }
        if (settings == null || !settings.dashboardOverlayEnabled) {
            builder.append(" dashboard-overlay");
        }
        if (settings == null || !settings.storageReadEnabled) {
            builder.append(" storage-read");
        }
        if (settings == null || !settings.storageWriteEnabled) {
            builder.append(" storage-write");
        }
        return builder.toString();
    }

    String rows() {
        StringBuilder builder = new StringBuilder();
        append(builder, "Notification listener",
                settings != null && settings.notificationListenerEnabled,
                "enabled",
                "disabled");
        append(builder, "Notification runtime",
                notificationListenerConnected,
                "connected",
                "disconnected");
        append(builder, "Accessibility service",
                settings != null && settings.accessibilityServiceEnabled,
                "enabled",
                "disabled");
        append(builder, "Accessibility master",
                settings != null && settings.accessibilityMasterEnabled,
                "enabled",
                "disabled");
        append(builder, "Accessibility runtime",
                accessibilityServiceConnected && !accessibilityServiceCrashed,
                "connected",
                accessibilityServiceCrashed ? "crashed" : "disconnected");
        append(builder, "Dashboard overlay",
                settings != null && settings.dashboardOverlayEnabled,
                "enabled",
                "disabled");
        append(builder, "Storage read",
                settings != null && settings.storageReadEnabled,
                "enabled",
                "disabled");
        append(builder, "Storage write",
                settings != null && settings.storageWriteEnabled,
                "enabled",
                "disabled");
        if (!notificationDetail.isEmpty()) {
            builder.append("\nnotification detail: ").append(notificationDetail);
        }
        if (!accessibilityDetail.isEmpty()) {
            builder.append("\naccessibility detail: ").append(accessibilityDetail);
        }
        return builder.toString();
    }

    private static void append(StringBuilder builder, String label, boolean ok,
            String okText, String badText) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(ok ? okText : badText);
    }

    private static void appendMissingPermission(StringBuilder builder, String label, boolean granted) {
        if (granted) {
            return;
        }
        builder.append(' ').append(label);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
