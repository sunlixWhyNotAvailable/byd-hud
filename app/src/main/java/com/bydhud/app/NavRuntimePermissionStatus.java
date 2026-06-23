package com.bydhud.app;

//summarizes runtime grants so diagnostics can show which capability is missing.

final class NavRuntimePermissionStatus {
    final NavPermissionStatus settings;
    final boolean notificationListenerConnected;
    final boolean accessibilityServiceConnected;
    final boolean accessibilityServiceCrashed;
    final String notificationDetail;
    final String accessibilityDetail;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
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

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    boolean settingsGranted() {
        return settings != null && settings.allGranted();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    boolean readyForCapture() {
        return settingsGranted()
                && notificationListenerConnected
                && accessibilityServiceConnected
                && !accessibilityServiceCrashed;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    boolean needsAdbGrant() {
        return settings == null || !settings.allGranted();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void append(StringBuilder builder, String label, boolean ok,
            String okText, String badText) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(ok ? okText : badText);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void appendMissingPermission(StringBuilder builder, String label, boolean granted) {
        if (granted) {
            return;
        }
        builder.append(' ').append(label);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
