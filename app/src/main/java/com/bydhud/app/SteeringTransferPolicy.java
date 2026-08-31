package com.bydhud.app;

import java.util.Collections;

/** Pure key-event and profile rules used by the accessibility runtime. */
final class SteeringTransferPolicy {
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;

    enum TaskScanAction { PUBLISH, CLEAR, KEEP }

    private SteeringTransferPolicy() {
    }

    static boolean isFirstDown(int action, int repeatCount) {
        return action == ACTION_DOWN && repeatCount == 0;
    }

    static boolean isMappedKey(int keyCode, int configuredKeyCode) {
        return configuredKeyCode >= 0 && keyCode == configuredKeyCode;
    }

    static boolean hasFreshTaskEvidence(
            boolean authoritative,
            long acceptedElapsedMs,
            long nowElapsedMs,
            long maxAgeMs) {
        return authoritative
                && acceptedElapsedMs > 0L
                && nowElapsedMs >= acceptedElapsedMs
                && nowElapsedMs - acceptedElapsedMs <= maxAgeMs;
    }

    static boolean canPublishTaskEvidence(
            boolean serviceActive,
            boolean shutdown,
            boolean authoritative,
            long scanEpoch,
            long currentEpoch) {
        return serviceActive
                && !shutdown
                && authoritative
                && scanEpoch == currentEpoch;
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

    static boolean isSelectedMove(boolean moving, String movingPackage, String selectedPackage) {
        return moving && selectedPackage != null && !selectedPackage.isEmpty()
                && selectedPackage.equals(movingPackage);
    }

    static TaskScanAction taskScanAction(boolean serviceActive, boolean shutdown,
            boolean scanBusy, boolean authoritative, long scanEpoch, long currentEpoch,
            boolean selectedMoveInProgress) {
        if (scanEpoch != currentEpoch) return TaskScanAction.KEEP;
        if (!serviceActive || shutdown) return TaskScanAction.CLEAR;
        if (scanBusy || selectedMoveInProgress) return TaskScanAction.KEEP;
        return authoritative ? TaskScanAction.PUBLISH : TaskScanAction.CLEAR;
    }

    static NavAppTaskScanner.Snapshot confirmedTaskSnapshot(NavAppDisplayState state, long nowMs) {
        if (state == null || state.packageName.isEmpty() || state.taskId < 0 || state.displayId < 0) {
            return null;
        }
        NavAppTaskScanner.Row row = new NavAppTaskScanner.Row(
                state.packageName, "", 0, false, true, state.taskId, state.displayId, state.visible);
        //Only this task was confirmed; other Apps-screen rows must not inherit its freshness.
        return new NavAppTaskScanner.Snapshot(Collections.singletonList(row), nowMs, "", "task", "ok");
    }

    static boolean canAdmitMappedPress(
            int keyCode,
            int configuredKeyCode,
            boolean cacheAuthoritative,
            boolean targetTaskPresent,
            boolean shutdown) {
        return !shutdown
                && cacheAuthoritative
                && targetTaskPresent
                && isMappedKey(keyCode, configuredKeyCode);
    }
}
