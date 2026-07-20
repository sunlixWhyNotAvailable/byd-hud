package com.bydhud.app;

//keeps dashboard ownership decisions in one pure helper so stale foreign displays do not drive UI.

//defines the DashboardProjectionPolicy module boundary so related behavior stays readable inside one unit.
final class DashboardProjectionPolicy {
    private static final String WAZE_PACKAGE = "com.waze";

    enum ObservedDisplay {
        UNKNOWN,
        MAIN,
        DASHBOARD,
        OTHER
    }

    private DashboardProjectionPolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isManagedDashboardPackage(String packageName, String activeDashboardPackage) {
        String normalized = normalizePackage(packageName);
        String active = normalizePackage(activeDashboardPackage);
        return !normalized.isEmpty() && normalized.equals(active);
    }

    //classifies an observed task against the exact live display owned for that package.
    static ObservedDisplay classifyObservedDisplay(
            String packageName,
            NavAppDisplayState state,
            String activeDashboardPackage,
            int activeDashboardDisplayId) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()
                || state == null
                || state.taskId < 0
                || state.displayId == NavAppDisplayState.DISPLAY_UNKNOWN
                || !normalized.equals(normalizePackage(state.packageName))) {
            return ObservedDisplay.UNKNOWN;
        }
        if (state.displayId == 0) {
            return ObservedDisplay.MAIN;
        }
        if (activeDashboardDisplayId > 0
                && state.displayId == activeDashboardDisplayId
                && isManagedDashboardPackage(normalized, activeDashboardPackage)) {
            return ObservedDisplay.DASHBOARD;
        }
        return ObservedDisplay.OTHER;
    }

    //requires both live ownership and the actual observed task display for UI decisions.
    static boolean isManagedDashboardPackage(
            String packageName,
            String activeDashboardPackage,
            NavAppDisplayState state,
            int activeDashboardDisplayId) {
        return classifyObservedDisplay(
                packageName,
                state,
                activeDashboardPackage,
                activeDashboardDisplayId) == ObservedDisplay.DASHBOARD;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldRestartWazeCropAfterDashboardProjection(
            String packageName,
            String activePackage,
            boolean onDashboardDisplay) {
        return onDashboardDisplay
                && WAZE_PACKAGE.equals(normalizePackage(packageName))
                && WAZE_PACKAGE.equals(normalizePackage(activePackage));
    }

    //normalizes values here so malformed app text cannot leak into dashboard decisions.
    private static String normalizePackage(String packageName) {
        return NavTextNormalizer.lower(packageName);
    }
}
