package com.bydhud.app;

//keeps dashboard ownership decisions in one pure helper so stale foreign displays do not drive UI.

//defines the DashboardProjectionPolicy module boundary so related behavior stays readable inside one unit.
final class DashboardProjectionPolicy {
    private static volatile Host host;
    private static boolean initialized;
    private static volatile boolean hostChecked;
    static final int MIN_WIDTH_PERCENT = 20;
    static final int MAX_WIDTH_PERCENT = 100;
    static final int DEFAULT_WIDTH_PERCENT = 100;
    static final int DEFAULT_PARTIAL_WIDTH_PERCENT = 30;
    static final int DEFAULT_FULL_WIDTH_PERCENT = 100;
    static final int MIN_HEIGHT_PERCENT = 20;
    static final int MAX_HEIGHT_PERCENT = 100;
    static final int DEFAULT_HEIGHT_PERCENT = 100;
    static final int DEFAULT_PARTIAL_HEIGHT_PERCENT = 75;
    static final int DEFAULT_FULL_HEIGHT_PERCENT = 75;
    static final int MIN_OFFSET_PERCENT = 0;
    static final int MAX_OFFSET_PERCENT = 100;
    static final int DEFAULT_OFFSET_PERCENT = 50;
    static final int DEFAULT_PARTIAL_OFFSET_PERCENT = 99;
    static final int DEFAULT_FULL_OFFSET_PERCENT = 50;
    static final int MIN_SCALE_PERCENT = 20;
    static final int MAX_SCALE_PERCENT = 150;
    static final int DEFAULT_SCALE_PERCENT = 100;
    static final int DEFAULT_PARTIAL_SCALE_PERCENT = 50;
    static final int DEFAULT_FULL_SCALE_PERCENT = 100;
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

    static synchronized void initialize(android.content.Context context) {
        if (initialized) return;
        initialized = true;
        android.content.Context app = context.getApplicationContext();
        try {
            org.json.JSONObject saved = new org.json.JSONObject(HudPrefs.dashboardHostGeometry(app));
            int width = saved.getInt("width"), height = saved.getInt("height");
            if (width > 0 && height > 0) host = new Host(saved.getString("identity"), width, height);
        } catch (org.json.JSONException ignored) { }
        android.hardware.display.DisplayManager manager = app.getSystemService(
                android.hardware.display.DisplayManager.class);
        if (manager == null) return;
        android.hardware.display.DisplayManager.DisplayListener listener =
                new android.hardware.display.DisplayManager.DisplayListener() {
            private void check() {
                ShellRuntimeSession.afterPersistence(() -> {
                    if (!hostChecked && checkHost(app, manager)) manager.unregisterDisplayListener(this);
                });
            }
            @Override public void onDisplayAdded(int id) { check(); }
            @Override public void onDisplayChanged(int id) { check(); }
            @Override public void onDisplayRemoved(int id) { }
        };
        manager.registerDisplayListener(listener, new android.os.Handler(android.os.Looper.getMainLooper()));
        if (checkHost(app, manager)) manager.unregisterDisplayListener(listener);
    }

    private static boolean checkHost(android.content.Context app,
            android.hardware.display.DisplayManager manager) {
        android.view.Display target = ClusterProjectionService.chooseClusterDisplay(manager.getDisplays());
        if (target == null || target.getDisplayId() == android.view.Display.DEFAULT_DISPLAY
                || !target.isValid() || ClusterProjectionService.isVirtualProjectionDisplayName(target.getName())) {
            return false;
        }
        android.graphics.Point size = new android.graphics.Point();
        target.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) return false;
        Host next = new Host(target.getName(), size.x, size.y);
        try {
            String value = new org.json.JSONObject().put("identity", next.identity)
                    .put("width", next.width).put("height", next.height).toString();
            if (!HudPrefs.setDashboardHostGeometry(app, value)) {
                AppEventLogger.event(app, "dashboard_host_geometry persistence_failed " + value);
                return false;
            }
            host = next;
            hostChecked = true;
            AppEventLogger.event(app, "dashboard_host_geometry verified " + value);
            return true;
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    static boolean matchesHost(android.view.Display display) {
        Host current = host;
        return current != null && display != null && display.getDisplayId() != 0
                && current.identity.equals(display.getName());
    }

    static org.json.JSONObject hostSnapshot() {
        Host current = host;
        return current == null ? ShanghaiJson.object("status", "unavailable") : ShanghaiJson.object(
                "identity", current.identity, "width", current.width, "height", current.height,
                "verifiedThisSession", hostChecked);
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

    static Profile partialDefaultProfile() {
        return new Profile(
                DEFAULT_PARTIAL_WIDTH_PERCENT,
                DEFAULT_PARTIAL_HEIGHT_PERCENT,
                DEFAULT_PARTIAL_OFFSET_PERCENT,
                DEFAULT_PARTIAL_SCALE_PERCENT);
    }

    static Profile fullDefaultProfile() {
        return new Profile(
                DEFAULT_FULL_WIDTH_PERCENT,
                DEFAULT_FULL_HEIGHT_PERCENT,
                DEFAULT_FULL_OFFSET_PERCENT,
                DEFAULT_FULL_SCALE_PERCENT);
    }

    static Profile nativeProfileForMode(int dashboardMode, Profile profile) {
        if (HudPrefs.normalizeDashboardScreenMode(dashboardMode)
                == HudPrefs.DASHBOARD_MODE_NONE) {
            return defaultProfile();
        }
        return profile == null ? defaultProfile() : profile;
    }

    static Geometry geometryForProfile(Profile profile) {
        Host current = host;
        return geometryForProfile(profile, current == null ? VIRTUAL_WIDTH : current.width,
                current == null ? VIRTUAL_BASE_HEIGHT : current.height);
    }

    static Geometry geometryForProfile(Profile profile, int hostWidth, int hostHeight) {
        if (hostWidth <= 0 || hostHeight <= 0) throw new IllegalArgumentException("Invalid dashboard area");
        Profile effective = profile == null ? defaultProfile() : profile;
        int width = (hostWidth * effective.widthPercent) / 100;
        int height = (hostHeight * effective.heightPercent) / 100;
        int left = ((hostWidth - width) * effective.offsetPercent) / 100;
        int bufferWidth = Math.max(1, (width * 100) / effective.scalePercent);
        int bufferHeight = Math.max(1, (height * 100) / effective.scalePercent);
        return new Geometry(
                width,
                height,
                left,
                (hostHeight - height) / 2,
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

    private static final class Host {
        final String identity;
        final int width, height;
        Host(String identity, int width, int height) {
            this.identity = identity;
            this.width = width;
            this.height = height;
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
