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
