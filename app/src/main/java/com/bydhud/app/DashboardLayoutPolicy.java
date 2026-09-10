package com.bydhud.app;

/** Pure command ordering for native MINI/FULL and TBT layout transitions. */
final class DashboardLayoutPolicy {
    static final int AUTOCONTAINER_MINI = 17;
    static final int AUTOCONTAINER_RELEASE = 18;
    static final int PROTOCOL_NATIVE = 1;
    static final int PROTOCOL_TBT = 2;
    static final int PROTOCOL_FULL = 4;

    static final int OWNERSHIP_NONE = 0;
    static final int OWNERSHIP_MINI = 1;
    static final int OWNERSHIP_LEGACY = 2;

    private DashboardLayoutPolicy() {
    }

    static boolean supportsDashboardMode(int dashboardMode) {
        int mode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        return mode == HudPrefs.DASHBOARD_MODE_PARTIAL
                || mode == HudPrefs.DASHBOARD_MODE_FULL;
    }

    static boolean usesFullProtocol(int dashboardMode) {
        return HudPrefs.normalizeDashboardScreenMode(dashboardMode)
                == HudPrefs.DASHBOARD_MODE_FULL;
    }

    static boolean shouldReleaseBeforeDashboard(int dashboardMode, int ownership) {
        if (ownership == OWNERSHIP_NONE) return false;
        int mode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        return mode == HudPrefs.DASHBOARD_MODE_FULL
                || (mode == HudPrefs.DASHBOARD_MODE_PARTIAL
                        && ownership == OWNERSHIP_LEGACY);
    }

    static int ownershipKind(int lastSuccessfulAutoContainerValue, boolean hasLease) {
        if (lastSuccessfulAutoContainerValue == AUTOCONTAINER_MINI) return OWNERSHIP_MINI;
        if (lastSuccessfulAutoContainerValue != 0 || hasLease) return OWNERSHIP_LEGACY;
        return OWNERSHIP_NONE;
    }

    static boolean shouldRetainMiniLeaseForReplacement(int nextMode, int ownership) {
        return ownership == OWNERSHIP_MINI
                && HudPrefs.normalizeDashboardScreenMode(nextMode)
                        == HudPrefs.DASHBOARD_MODE_PARTIAL;
    }

    static boolean shouldReleaseBeforeWidget(int widgetMode, int ownership) {
        if (ownership == OWNERSHIP_NONE) return false;
        return widgetMode != NavAppDisplayController.WIDGET_MODE_MINI
                || ownership == OWNERSHIP_LEGACY;
    }

    static int ipcOffCommand(int ownership) {
        return ownership == OWNERSHIP_NONE
                ? PROTOCOL_NATIVE
                : AUTOCONTAINER_RELEASE;
    }
}
