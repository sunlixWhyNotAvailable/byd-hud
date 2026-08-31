package com.bydhud.app;

/** Pure key-event and profile rules used by the accessibility runtime. */
final class SteeringTransferPolicy {
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;

    private SteeringTransferPolicy() {
    }

    static boolean isFirstDown(int action, int repeatCount) {
        return action == ACTION_DOWN && repeatCount == 0;
    }

    static boolean isMappedKey(int keyCode, int configuredKeyCode) {
        return configuredKeyCode >= 0 && keyCode == configuredKeyCode;
    }

    static boolean shouldStartTransfer(int action, int repeatCount, boolean keyActive) {
        return isFirstDown(action, repeatCount) && !keyActive;
    }

    static boolean isRequestCurrent(
            boolean serviceActive,
            boolean shutdown,
            long requestRuntimeGeneration,
            long runtimeGeneration,
            long requestBindingRevision,
            long bindingRevision) {
        return serviceActive
                && !shutdown
                && requestRuntimeGeneration == runtimeGeneration
                && requestBindingRevision == bindingRevision;
    }

    static boolean canToggleTask(NavAppDisplayState task,
            DashboardProjectionPolicy.ObservedDisplay display) {
        return task != null && task.taskId >= 0
                && (display == DashboardProjectionPolicy.ObservedDisplay.MAIN
                || display == DashboardProjectionPolicy.ObservedDisplay.DASHBOARD);
    }

    static int resolveDashboardMode(String profile, int selectedMode) {
        if (SteeringTransferPreferences.PROFILE_PARTIAL.equals(profile)) {
            return HudPrefs.DASHBOARD_MODE_PARTIAL;
        }
        if (SteeringTransferPreferences.PROFILE_FULL.equals(profile)) {
            return HudPrefs.DASHBOARD_MODE_FULL;
        }
        return HudPrefs.normalizeDashboardScreenMode(selectedMode);
    }
}
