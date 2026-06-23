package com.bydhud.app;

//keeps dashboard ownership decisions in one pure helper so stale foreign displays do not drive UI.

//defines the DashboardProjectionPolicy module boundary so related behavior stays readable inside one unit.
final class DashboardProjectionPolicy {
    private static final String WAZE_PACKAGE = "com.waze";

    private DashboardProjectionPolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isManagedDashboardPackage(String packageName, String activeDashboardPackage) {
        String normalized = normalizePackage(packageName);
        String active = normalizePackage(activeDashboardPackage);
        return !normalized.isEmpty() && normalized.equals(active);
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
