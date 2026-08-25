package com.bydhud.app;

//keeps dashboard ownership decisions in one pure helper so stale foreign displays do not drive UI.

//defines the DashboardProjectionPolicy module boundary so related behavior stays readable inside one unit.
final class DashboardProjectionPolicy {
    private static final String WAZE_PACKAGE = "com.waze";
    static final int MIN_WIDTH_PERCENT = 20;
    static final int MAX_WIDTH_PERCENT = 100;
    static final int DEFAULT_WIDTH_PERCENT = 100;
    static final int MIN_HEIGHT_PERCENT = 20;
    static final int MAX_HEIGHT_PERCENT = 100;
    static final int DEFAULT_HEIGHT_PERCENT = 100;
    static final int MIN_OFFSET_PERCENT = 0;
    static final int MAX_OFFSET_PERCENT = 100;
    static final int DEFAULT_OFFSET_PERCENT = 50;
    static final int MIN_SCALE_PERCENT = 50;
    static final int MAX_SCALE_PERCENT = 150;
    static final int DEFAULT_SCALE_PERCENT = 100;
    static final int VIRTUAL_WIDTH = 1920;
    static final int VIRTUAL_BASE_HEIGHT = 720;
    static final int VIRTUAL_DENSITY = 320;

    enum ObservedDisplay {
        UNKNOWN,
        MAIN,
        DASHBOARD,
        OTHER
    }

    private DashboardProjectionPolicy() {
    }

    static int clampHeightPercent(int percent) {
        return Math.max(MIN_HEIGHT_PERCENT, Math.min(MAX_HEIGHT_PERCENT, percent));
    }

    static int clampWidthPercent(int percent) {
        return Math.max(MIN_WIDTH_PERCENT, Math.min(MAX_WIDTH_PERCENT, percent));
    }

    static int clampOffsetPercent(int percent) {
        return Math.max(MIN_OFFSET_PERCENT, Math.min(MAX_OFFSET_PERCENT, percent));
    }

    static int clampScalePercent(int percent) {
        return Math.max(MIN_SCALE_PERCENT, Math.min(MAX_SCALE_PERCENT, percent));
    }

    static Profile defaultProfile() {
        return new Profile(
                DEFAULT_WIDTH_PERCENT,
                DEFAULT_HEIGHT_PERCENT,
                DEFAULT_OFFSET_PERCENT,
                DEFAULT_SCALE_PERCENT);
    }

    static Profile nativeProfileForMode(int dashboardMode, Profile profile) {
        if (HudPrefs.normalizeDashboardScreenMode(dashboardMode)
                == HudPrefs.DASHBOARD_MODE_NONE) {
            return defaultProfile();
        }
        return profile == null ? defaultProfile() : profile;
    }

    static Geometry geometryForProfile(Profile profile) {
        Profile effective = profile == null ? defaultProfile() : profile;
        int width = (VIRTUAL_WIDTH * effective.widthPercent) / 100;
        int height = (VIRTUAL_BASE_HEIGHT * effective.heightPercent) / 100;
        int left = ((VIRTUAL_WIDTH - width) * effective.offsetPercent) / 100;
        int bufferWidth = Math.max(1, (width * 100) / effective.scalePercent);
        int bufferHeight = Math.max(1, (height * 100) / effective.scalePercent);
        return new Geometry(
                width,
                height,
                left,
                (VIRTUAL_BASE_HEIGHT - height) / 2,
                bufferWidth,
                bufferHeight);
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

    static final class Geometry {
        final int width;
        final int height;
        final int left;
        final int density;
        final int top;
        final int bufferWidth;
        final int bufferHeight;

        Geometry(
                int width,
                int height,
                int left,
                int top,
                int bufferWidth,
                int bufferHeight) {
            this.width = width;
            this.height = height;
            this.left = left;
            this.density = VIRTUAL_DENSITY;
            this.top = top;
            this.bufferWidth = bufferWidth;
            this.bufferHeight = bufferHeight;
        }
    }

    static final class Profile {
        final int widthPercent;
        final int heightPercent;
        final int offsetPercent;
        final int scalePercent;

        Profile(int widthPercent, int heightPercent, int offsetPercent, int scalePercent) {
            this.widthPercent = clampWidthPercent(widthPercent);
            this.heightPercent = clampHeightPercent(heightPercent);
            this.offsetPercent = clampOffsetPercent(offsetPercent);
            this.scalePercent = clampScalePercent(scalePercent);
        }
    }
}
