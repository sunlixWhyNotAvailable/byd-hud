package com.bydhud.app;

/** Pure command ordering for dashboard format-method and TBT transitions. */
final class DashboardLayoutPolicy {
    static final int AUTOCONTAINER_FULL = 16;
    static final int AUTOCONTAINER_MINI = 17;
    static final int AUTOCONTAINER_RELEASE = 18;
    static final int PROTOCOL_NATIVE = 1;
    static final int PROTOCOL_TBT = 2;
    static final int PROTOCOL_PARTIAL = 3;
    static final int PROTOCOL_FULL = 4;

    static final int OWNERSHIP_NONE = 0;
    static final int OWNERSHIP_AUTOCONTAINER = 1;

    private DashboardLayoutPolicy() {
    }

    static boolean supportsDashboardMode(int dashboardMode) {
        int mode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        return mode == HudPrefs.DASHBOARD_MODE_PARTIAL
                || mode == HudPrefs.DASHBOARD_MODE_FULL;
    }

    static int layoutCommand(int dashboardMode, int formatMethod) {
        int mode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        boolean alternative = HudPrefs.normalizeDashboardFormatMethod(mode, formatMethod)
                == HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE;
        if (mode == HudPrefs.DASHBOARD_MODE_FULL) {
            return alternative ? AUTOCONTAINER_FULL : PROTOCOL_FULL;
        }
        if (mode == HudPrefs.DASHBOARD_MODE_PARTIAL) {
            return alternative ? AUTOCONTAINER_MINI : PROTOCOL_PARTIAL;
        }
        return 0;
    }

    static boolean isAutoContainerCommand(int command) {
        return command == AUTOCONTAINER_FULL || command == AUTOCONTAINER_MINI;
    }

    static boolean shouldReleaseBeforeDashboard(int command, int ownership) {
        return ownership != OWNERSHIP_NONE && !isAutoContainerCommand(command);
    }

    static int ownershipKind(int lastSuccessfulAutoContainerValue, boolean hasLease) {
        return lastSuccessfulAutoContainerValue != 0 || hasLease
                ? OWNERSHIP_AUTOCONTAINER : OWNERSHIP_NONE;
    }

    static boolean shouldRetainLeaseForReplacement(int nextCommand, int ownership) {
        return ownership != OWNERSHIP_NONE && isAutoContainerCommand(nextCommand);
    }

    static boolean shouldReleaseBeforeWidget(int widgetMode, int command, int ownership) {
        if (ownership == OWNERSHIP_NONE) return false;
        return (widgetMode != NavAppDisplayController.WIDGET_MODE_MINI
                && widgetMode != NavAppDisplayController.WIDGET_MODE_FULL)
                || !isAutoContainerCommand(command);
    }

    static int ipcOffCommand(int ownership) {
        return ownership == OWNERSHIP_NONE
                ? PROTOCOL_NATIVE
                : AUTOCONTAINER_RELEASE;
    }
}
