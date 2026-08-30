package com.bydhud.app;

//checks visible app state so visual capture only runs when the target app is actually on screen.

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the NavAppDisplayController module boundary so related behavior stays readable inside one unit.
final class NavAppDisplayController {
    private static final String TAG = "BydHudNavAppDisplay";
    private static final String CHANNEL = "nav_app_display";
    private static final String PREFS = "bydhud_dashboard_projection";
    private static final String KEY_ACTIVE_PACKAGE = "active_package";
    private static final String KEY_ACTIVE_MODE = "active_mode";
    private static final String KEY_ACTIVE_REASON = "active_reason";
    private static final String KEY_ACTIVE_UPDATED_MS = "active_updated_ms";
    private static final String KEY_PROJECTION_GENERATION = "projection_generation";
    private static final String KEY_AUTOCONTAINER_LEASE_PACKAGE =
            "autocontainer_lease_package";
    private static final String KEY_AUTOCONTAINER_LEASE_GENERATION =
            "autocontainer_lease_generation";
    private static final String KEY_WIDGET_AUTOCONTAINER_VALUE =
            "widget_autocontainer_value";
    private static final int MAIN_DISPLAY_ID = 0;
    private static final int FALLBACK_DASHBOARD_DISPLAY_ID = 2;
    private static final int AUTO_CONTAINER_PARTIAL = 17;
    private static final int AUTO_CONTAINER_FULLSCREEN = 16;
    private static final int AUTO_CONTAINER_RELEASE = 18;
    static final int WIDGET_MODE_IPC_OFF = 0;
    static final int WIDGET_MODE_TBT = 1;
    static final int WIDGET_MODE_MINI = 2;
    static final int WIDGET_MODE_FULL = 3;
    private static final long DISPLAY_CONFIRM_TIMEOUT_MS = 4000L;
    private static final long PROJECTED_DISPLAY_CONFIRM_TIMEOUT_MS = 10000L;
    private static final long DISPLAY_CONFIRM_INTERVAL_MS = 250L;
    private static final long WAZE_SURFACE_HANDOFF_TIMEOUT_MS = 5000L;
    private static final String PRIMARY_DASHBOARD_DISPLAY_NAME = "fission_bg_XDJAScreenProjection";
    private static final String SHARED_DASHBOARD_DISPLAY_PREFIX =
            "shared_fission_bg_XDJAScreenProjection";
    private static final Pattern LOGICAL_DISPLAY_PATTERN =
            Pattern.compile("\\s*Display ([0-9]+):.*");
    private static final Pattern DISPLAY_NAME_PATTERN =
            Pattern.compile(".*Display(?:Info|DeviceInfo)\\{\"([^\"]+)\".*");
    private static final Pattern DISPLAY_INFO_DISPLAY_ID_PATTERN =
            Pattern.compile(".*displayId ([0-9]+).*");
    private static final Pattern DISPLAY_SECTION_PATTERN =
            Pattern.compile(".*Display #([0-9]+).*");
    private static final Pattern DISPLAY_ID_PATTERN =
            Pattern.compile(".*mDisplayId=([0-9]+).*");
    private static final Pattern REMOTE_DASHBOARD_DISPLAY_BEFORE = Pattern.compile(
            "(?s).*mDisplayId=([0-9]+).{0,700}remote_dashboard.*");
    private static final Pattern REMOTE_DASHBOARD_DISPLAY_AFTER = Pattern.compile(
            "(?s).*remote_dashboard.{0,700}mDisplayId=([0-9]+).*");
    private static final Pattern ROOT_TASK_PATTERN =
            Pattern.compile(".*RootTask id=([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern ROOT_TASK_HASH_PATTERN =
            Pattern.compile(".*RootTask\\{[^#]*#([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern TASK_HASH_PATTERN =
            Pattern.compile(".*Task\\{[^#]*#([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern TASK_HASH_NO_DISPLAY_PATTERN =
            Pattern.compile(".*Task\\{[^#]*#([0-9]+).*");
    private static final Pattern TASK_ID_DISPLAY_PATTERN =
            Pattern.compile(".*taskId=([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern DISPLAY_ID_TASK_PATTERN =
            Pattern.compile(".*displayId=([0-9]+).*taskId=([0-9]+).*");
    private static final Pattern TASK_ID_COLON_PATTERN =
            Pattern.compile(".*taskId=([0-9]+):.*");
    private static final Pattern TASK_ID_PATTERN =
            Pattern.compile(".*taskId=([0-9]+).*");

    //defines the Listener module boundary so related behavior stays readable inside one unit.
    interface Listener {
        //keeps this step explicit so callers can rely on one documented behavior boundary.
        void onNavAppDisplayChanged(boolean moveInProgress);
    }

    private static NavAppDisplayController instance;

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static synchronized NavAppDisplayController get(Context context) {
        if (instance == null) {
            instance = new NavAppDisplayController(context.getApplicationContext());
        }
        return instance;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static NavAppDisplayState parseTaskForTest(String packageName, String dumpsys) {
        return parseTask(packageName, dumpsys);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static int parseDashboardDisplayIdForTest(String dumpsys) {
        return parseDashboardDisplayId(dumpsys);
    }

    //keeps compositor policy pure so tests cannot accidentally require a vehicle connection.
    static int autoContainerValueForTest(
            boolean toDashboard, int dashboardMode, boolean explicit) {
        if (!toDashboard || !explicit) return 0;
        return autoContainerValueForMode(dashboardMode);
    }

    static int widgetAutoContainerValueForTest(int mode) {
        switch (mode) {
            case WIDGET_MODE_IPC_OFF:
            case WIDGET_MODE_TBT:
                return AUTO_CONTAINER_RELEASE;
            case WIDGET_MODE_MINI:
                return AUTO_CONTAINER_PARTIAL;
            case WIDGET_MODE_FULL:
                return AUTO_CONTAINER_FULLSCREEN;
            default:
                return 0;
        }
    }

    static boolean widgetModeUsesTbtProtocolForTest(int mode) {
        return mode == WIDGET_MODE_TBT;
    }

    static boolean widgetModeUsesAutoContainerForTest(
            int mode, boolean releaseRequired) {
        switch (mode) {
            case WIDGET_MODE_IPC_OFF:
            case WIDGET_MODE_MINI:
            case WIDGET_MODE_FULL:
                return true;
            case WIDGET_MODE_TBT:
                return releaseRequired;
            default:
                return false;
        }
    }

    static boolean widgetTbtNeedsAutoContainerReleaseForTest(
            int lastWidgetAutoContainerValue, boolean hasProjectionLease) {
        return hasProjectionLease
                || lastWidgetAutoContainerValue == AUTO_CONTAINER_PARTIAL
                || lastWidgetAutoContainerValue == AUTO_CONTAINER_FULLSCREEN;
    }

    static int dashboardModeForWidgetForTest(int mode) {
        return mode == WIDGET_MODE_MINI
                ? HudPrefs.DASHBOARD_MODE_PARTIAL
                : mode == WIDGET_MODE_FULL
                        ? HudPrefs.DASHBOARD_MODE_FULL
                        : HudPrefs.DASHBOARD_MODE_NONE;
    }

    private static int autoContainerValueForMode(int dashboardMode) {
        switch (HudPrefs.normalizeDashboardScreenMode(dashboardMode)) {
            case HudPrefs.DASHBOARD_MODE_PARTIAL:
                return AUTO_CONTAINER_PARTIAL;
            case HudPrefs.DASHBOARD_MODE_FULL:
                return AUTO_CONTAINER_FULLSCREEN;
            default:
                return 0;
        }
    }

    static boolean isUserRequestedReturnForTest(String reason) {
        String normalized = safe(reason).toLowerCase(Locale.ROOT);
        return normalized.contains("ui-independent-dashboard-explicit")
                || normalized.contains("user-return")
                || normalized.contains("explicit-return");
    }

    private final Context context;
    private final Object lock = new Object();
    private final Map<String, NavAppDisplayState> states = new HashMap<>();
    private boolean moveInProgress;
    private String activeDashboardPackage = "";
    private String pendingAutoContainerLeaseTransferFrom = "";
    private long pendingAutoContainerLeaseTransferGeneration;
    private String pendingShutdownReturnPackage = "";
    private String pendingShutdownReturnReason = "";
    private long widgetOperationToken;
    private boolean widgetOperationActive;
    private boolean widgetOperationCancelled;
    private Listener listener;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavAppDisplayController(Context context) {
        this.context = context.getApplicationContext();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    boolean setListener(Listener listener) {
        synchronized (lock) {
            this.listener = listener;
            return moveInProgress;
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isMoveInProgress() {
        synchronized (lock) {
            return moveInProgress;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    String activeDashboardPackage() {
        String current = confirmedDashboardPackage();
        return observedDisplay(current) == DashboardProjectionPolicy.ObservedDisplay.DASHBOARD
                ? current
                : "";
    }

    //exposes live service ownership without treating persisted recovery intent as proof.
    String confirmedDashboardPackage() {
        String current;
        synchronized (lock) {
            current = activeDashboardPackage;
        }
        return !current.isEmpty() && ClusterProjectionService.isProjectedPackageCurrent(current)
                ? current
                : "";
    }

    //exposes the exact live display id used to classify observed task state.
    int confirmedDashboardDisplayId() {
        String current = confirmedDashboardPackage();
        return current.isEmpty()
                ? NavAppDisplayState.DISPLAY_UNKNOWN
                : ClusterProjectionService.projectedDisplayIdForPackage(current);
    }

    //classifies the last real task observation without treating persisted recovery intent as proof.
    DashboardProjectionPolicy.ObservedDisplay observedDisplay(String packageName) {
        String normalized = normalizePackage(packageName);
        String owner = confirmedDashboardPackage();
        int ownedDisplayId = owner.isEmpty()
                ? NavAppDisplayState.DISPLAY_UNKNOWN
                : ClusterProjectionService.projectedDisplayIdForPackage(owner);
        return DashboardProjectionPolicy.classifyObservedDisplay(
                normalized,
                lastState(normalized),
                owner,
                ownedDisplayId);
    }

    //persists dashboard ownership so sticky service restarts do not lose the target app.
    String persistedDashboardPackage() {
        return normalizePackage(dashboardPrefs().getString(KEY_ACTIVE_PACKAGE, ""));
    }

    //persists the exact mode paired with the dashboard package for sticky recovery.
    int persistedDashboardMode() {
        return HudPrefs.normalizeDashboardScreenMode(dashboardPrefs().getInt(
                KEY_ACTIVE_MODE,
                HudPrefs.dashboardScreenMode(context)));
    }

    //a real boot invalidates the old virtual display; update and process recovery do not.
    void clearStaleProjectionIntentForBoot(String reason) {
        synchronized (lock) {
            activeDashboardPackage = "";
        }
        clearDashboardProjection("boot:" + safe(reason));
        clearAutoContainerLease("boot:" + safe(reason));
        dashboardPrefs().edit()
                .remove(KEY_PROJECTION_GENERATION)
                .remove(KEY_WIDGET_AUTOCONTAINER_VALUE)
                .apply();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    NavAppDisplayState lastState(String packageName) {
        String normalized = normalizePackage(packageName);
        synchronized (lock) {
            NavAppDisplayState state = states.get(normalized);
            if (state != null) {
                return state;
            }
        }
        return new NavAppDisplayState(
                normalized,
                -1,
                NavAppDisplayState.DISPLAY_UNKNOWN,
                false,
                "unknown");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    NavAppDisplayState checkDisplay(String packageName, String reason) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            return remember(new NavAppDisplayState(
                    "",
                    -1,
                    NavAppDisplayState.DISPLAY_UNKNOWN,
                    false,
                    "empty package"));
        }
        try {
            LocalAdbBridge.ShellResult result = runCommand(
                    normalized,
                    "dumpsys activity activities",
                    "check_display reason=" + safe(reason));
            if (!result.success()) {
                return remember(new NavAppDisplayState(
                        normalized,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "check failed: " + result.shortDetail()));
            }
            NavAppDisplayState parsed = parseTask(normalized, result.output);
            if (parsed == null) {
                return remember(new NavAppDisplayState(
                        normalized,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "task missing"));
            }
            NavAppDisplayState observed = new NavAppDisplayState(
                    normalized,
                    parsed.taskId,
                    parsed.displayId,
                    parsed.visible,
                    "display=" + parsed.displayId + " task=" + parsed.taskId);
            reconcileConfirmedDashboardOwnership(
                    normalized,
                    observed,
                    persistedDashboardMode(),
                    "display-check");
            return remember(observed);
        } catch (IOException | SecurityException e) {
            return remember(new NavAppDisplayState(
                    normalized,
                    -1,
                    NavAppDisplayState.DISPLAY_UNKNOWN,
                    false,
                    "check failed: " + safe(e.getMessage())));
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void moveToDashboard(String packageName, String reason) {
        moveToDashboard(
                packageName,
                HudPrefs.dashboardScreenMode(context),
                reason);
    }

    //moves to an explicit dashboard layout; the worker rechecks task state before dispatching it.
    void moveToDashboard(String packageName, int dashboardMode, String reason) {
        moveIndependentDashboardApp(packageName, true, dashboardMode, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void moveToMain(String packageName, String reason) {
        moveIndependentDashboardApp(packageName, false, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void moveIndependentDashboardApp(String packageName, boolean toDashboard, String reason) {
        moveIndependentDashboardApp(
                packageName,
                toDashboard,
                HudPrefs.dashboardScreenMode(context),
                reason);
    }

    //accepts an explicit physical target and dashboard layout instead of deriving either by toggling.
    void moveIndependentDashboardApp(
            String packageName,
            boolean toDashboard,
            int dashboardMode,
            String reason) {
        moveIndependentDashboardApp(packageName, toDashboard, dashboardMode, reason, null);
    }

    //toggles a configured app only after a worker-side task/display recheck.
    void requestSteeringToggle(String packageName, String profile, String reason) {
        final String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            reportSteeringFailure(normalized, "selected package missing");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                if (HudPrefs.isUserShutdownActive(context)) {
                    reportSteeringFailure(normalized, "shutdown active");
                    return;
                }
                if (isMoveInProgress()) {
                    reportSteeringFailure(normalized, "display move busy");
                    return;
                }
                NavAppDisplayState current = checkDisplay(normalized, "steering-precheck");
                DashboardProjectionPolicy.ObservedDisplay observed = observedDisplay(normalized);
                if (current.taskId < 0
                        || observed == DashboardProjectionPolicy.ObservedDisplay.UNKNOWN
                        || observed == DashboardProjectionPolicy.ObservedDisplay.OTHER) {
                    reportSteeringFailure(normalized, "task/display state unknown");
                    return;
                }
                boolean toDashboard = observed == DashboardProjectionPolicy.ObservedDisplay.MAIN;
                int dashboardMode = SteeringTransferPolicy.resolveDashboardMode(
                        profile, HudPrefs.dashboardScreenMode(context));
                if (HudPrefs.isUserShutdownActive(context)) {
                    reportSteeringFailure(normalized, "shutdown active");
                    return;
                }
                moveIndependentDashboardApp(
                        normalized,
                        toDashboard,
                        dashboardMode,
                        "steering-key " + safe(reason),
                        error -> {
                            if (error != null && !error.isEmpty()) {
                                reportSteeringFailure(normalized, error);
                            }
                        });
            } catch (RuntimeException error) {
                reportSteeringFailure(
                        normalized, "runtime " + error.getClass().getSimpleName());
            }
        }, "BydHudSteeringTransfer");
        worker.setDaemon(true);
        worker.start();
    }

    private void moveIndependentDashboardApp(
            String packageName,
            boolean toDashboard,
            int dashboardMode,
            String reason,
            Consumer<String> completion) {
        int normalizedDashboardMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        String normalized = normalizePackage(packageName);
        String label = toDashboard ? "independent_dashboard_on" : "independent_dashboard_off";
        if (!beginMove(normalized, label + " reason=" + safe(reason))) {
            log(normalized, label + " skipped already_running reason=" + safe(reason));
            notifyMoveCompletion(completion, "display move busy");
            return;
        }
        Thread worker = new Thread(
                () -> moveIndependentDashboardAppBlocking(
                        normalized,
                        toDashboard,
                        normalizedDashboardMode,
                        reason,
                        completion),
                "BydHudIndependentDashboardDisplay");
        worker.start();
    }

    //runs explicit widget vehicle commands on the existing move gate, never on the UI thread.
    void requestWidgetMode(int mode, boolean applyProfile, Consumer<String> completion) {
        if (widgetAutoContainerValueForTest(mode) == 0) {
            notifyWidgetCompletionAsync(completion, "unsupported widget mode=" + mode);
            return;
        }
        final long token;
        if (!beginMove("", "widget_mode_request mode=" + mode)) {
            notifyWidgetCompletionAsync(completion, "widget command busy");
            return;
        }
        synchronized (lock) {
            token = ++widgetOperationToken;
            widgetOperationActive = true;
            widgetOperationCancelled = false;
        }
        Thread worker = new Thread(() -> runWidgetMode(
                token, mode, applyProfile, completion), "BydHudWidgetModeCommand");
        worker.setDaemon(true);
        worker.start();
    }

    //invalidates a queued widget operation before shutdown starts its explicit return.
    void cancelWidgetModeForShutdown() {
        synchronized (lock) {
            if (widgetOperationActive) {
                widgetOperationCancelled = true;
                log("", "widget_mode_cancelled reason=shutdown");
            }
        }
    }

    private void runWidgetMode(
            long token, int mode, boolean applyProfile, Consumer<String> completion) {
        String error = "";
        String owner = "";
        long ownerGeneration = 0L;
        try {
            owner = confirmedDashboardPackage();
            ownerGeneration = widgetProjectionGenerationForPackage(owner);
            if (!isWidgetOperationCurrent(token)) {
                error = widgetCancellationReason();
            } else {
                boolean releaseRequired = widgetTbtNeedsAutoContainerReleaseForTest(
                        persistedWidgetAutoContainerValue(),
                        hasWidgetAutoContainerLease(owner));
                String commandFailure = widgetModeUsesAutoContainerForTest(
                        mode, releaseRequired)
                        ? sendWidgetAutoContainer(
                                owner, widgetAutoContainerValueForTest(mode), token, mode)
                        : "";
                if (!commandFailure.isEmpty()) {
                    error = commandFailure;
                } else if (widgetModeUsesTbtProtocolForTest(mode)) {
                    error = sendWidgetTbtProtocolEdge(token);
                }
                if (error.isEmpty()
                        && applyProfile
                        && (mode == WIDGET_MODE_MINI || mode == WIDGET_MODE_FULL)
                        && !owner.isEmpty()) {
                    if (!isWidgetOperationCurrent(token)) {
                        error = widgetCancellationReason();
                    } else {
                        int dashboardMode = dashboardModeForWidgetForTest(mode);
                        String profileFailure =
                                ClusterProjectionService.applyDashboardProfileForWidget(
                                        context,
                                        owner,
                                        dashboardMode,
                                        "widget-mode=" + mode,
                                        ownerGeneration,
                                        () -> isWidgetOperationCurrent(token));
                        if (!profileFailure.isEmpty()) {
                            error = "partial profile failure: " + profileFailure;
                        } else if (!persistWidgetDashboardMode(
                                owner, dashboardMode, ownerGeneration)) {
                            error = "partial profile failure: stale projection owner";
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            error = "widget command failed: " + safe(e.getMessage());
        } finally {
            finishWidgetOperation(token);
            endMove("");
            notifyWidgetCompletion(completion, error);
        }
    }

    private String sendWidgetTbtProtocolEdge(long token) {
        String typeOneFailure = StockMapProtocol30011.dispatch(
                context,
                1,
                () -> isWidgetOperationCurrent(token));
        if (!typeOneFailure.isEmpty()) {
            return "TBT protocol type 1 failed: " + typeOneFailure;
        }
        String typeTwoFailure = StockMapProtocol30011.dispatch(
                context,
                2,
                () -> isWidgetOperationCurrent(token));
        return typeTwoFailure.isEmpty()
                ? ""
                : "TBT protocol type 2 failed: " + typeTwoFailure;
    }

    private boolean hasWidgetAutoContainerLease(String owner) {
        String normalizedOwner = normalizePackage(owner);
        if (normalizedOwner.isEmpty()) return false;
        SharedPreferences prefs = dashboardPrefs();
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        long leaseGeneration = prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L);
        return leaseGeneration > 0L
                && normalizedOwner.equals(leasePackage)
                && projectionGenerationForPackage(normalizedOwner) == leaseGeneration;
    }

    private int persistedWidgetAutoContainerValue() {
        int value = dashboardPrefs().getInt(KEY_WIDGET_AUTOCONTAINER_VALUE, 0);
        return value == AUTO_CONTAINER_PARTIAL || value == AUTO_CONTAINER_FULLSCREEN
                ? value
                : 0;
    }

    private void recordWidgetAutoContainerValue(int value) {
        SharedPreferences.Editor editor = dashboardPrefs().edit();
        if (value == AUTO_CONTAINER_PARTIAL || value == AUTO_CONTAINER_FULLSCREEN) {
            editor.putInt(KEY_WIDGET_AUTOCONTAINER_VALUE, value);
        } else if (value == AUTO_CONTAINER_RELEASE) {
            editor.remove(KEY_WIDGET_AUTOCONTAINER_VALUE);
        } else {
            return;
        }
        editor.apply();
    }

    private String sendWidgetAutoContainer(
            String owner, int value, long token, int mode) {
        if (!isWidgetOperationCurrent(token)) {
            return widgetCancellationReason();
        }
        String normalizedOwner = normalizePackage(owner);
        long leaseGeneration = projectionGenerationForPackage(normalizedOwner);
        if ((value == AUTO_CONTAINER_FULLSCREEN || value == AUTO_CONTAINER_PARTIAL)
                && !normalizedOwner.isEmpty()) {
            String leaseOwner = persistedAutoContainerLeasePackage();
            if (!leaseOwner.isEmpty() && !leaseOwner.equals(normalizedOwner)) {
                return "existing AutoContainer lease retained";
            }
        }
        try {
            LocalAdbBridge.ShellResult result = LocalAdbBridge.runAutoContainer(context, value);
            if (!result.success()) {
                return "widget AutoContainer " + value + " failed: " + result.shortDetail();
            }
            recordWidgetAutoContainerValue(value);
            log(normalizedOwner, "widget_autocontainer_sent mode=" + mode + " value=" + value);
            // A completed side effect still needs ownership bookkeeping if Shutdown
            // cancelled the following steps while the shell command was in flight.
            if (leaseGeneration > 0L
                    && projectionGenerationForPackage(normalizedOwner) == leaseGeneration) {
                if (mode == WIDGET_MODE_IPC_OFF || mode == WIDGET_MODE_TBT) {
                    clearAutoContainerLeaseIfExact(
                            normalizedOwner, leaseGeneration, "widget-release");
                } else if (mode == WIDGET_MODE_MINI || mode == WIDGET_MODE_FULL) {
                    acquireAutoContainerLeaseIfSucceeded(
                            normalizedOwner,
                            dashboardModeForWidgetForTest(mode),
                            "",
                            "widget-mode");
                }
            }
            if (!isWidgetOperationCurrent(token)) return widgetCancellationReason();
            return "";
        } catch (IOException | SecurityException e) {
            return "widget AutoContainer " + value + " failed: " + safe(e.getMessage());
        }
    }

    private boolean isWidgetOperationCurrent(long token) {
        synchronized (lock) {
            return widgetOperationActive
                    && !widgetOperationCancelled
                    && token == widgetOperationToken
                    && moveInProgress
                    && !HudPrefs.isUserShutdownActive(context);
        }
    }

    private String widgetCancellationReason() {
        return HudPrefs.isUserShutdownActive(context)
                ? "widget command cancelled: shutdown active"
                : "widget command cancelled: stale operation";
    }

    private void finishWidgetOperation(long token) {
        synchronized (lock) {
            if (widgetOperationActive && token == widgetOperationToken) {
                widgetOperationActive = false;
            }
        }
    }

    private void notifyWidgetCompletion(Consumer<String> completion, String error) {
        if (completion == null) return;
        try {
            completion.accept(error == null ? "" : error);
        } catch (RuntimeException e) {
            log("", "widget completion failed " + e.getClass().getSimpleName());
        }
    }

    private void notifyWidgetCompletionAsync(Consumer<String> completion, String error) {
        if (completion == null) return;
        Thread worker = new Thread(
                () -> notifyWidgetCompletion(completion, error),
                "BydHudWidgetModeCompletion");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean persistWidgetDashboardMode(
            String owner, int dashboardMode, long expectedProjectionGeneration) {
        String normalized = normalizePackage(owner);
        synchronized (lock) {
            if (normalized.isEmpty() || !normalized.equals(activeDashboardPackage)) {
                return false;
            }
        }
        if (ClusterProjectionService.projectedGenerationTokenForWidget(normalized)
                != expectedProjectionGeneration) {
            return false;
        }
        persistDashboardProjection(normalized, dashboardMode, "widget-profile");
        return true;
    }

    //returns the current dashboard-owned app to main before replacing or shutting down projection.
    void returnActiveDashboardToMain(String reason) {
        String active = activeDashboardPackage();
        if (active.isEmpty()) {
            active = persistedDashboardPackage();
        }
        if (active.isEmpty()) {
            active = persistedAutoContainerLeasePackage();
        }
        if (active.isEmpty()) {
            log("", "dashboard_return_main_failed package=missing reason=" + safe(reason));
            return;
        }
        if (isShutdownReturnReason(reason)) {
            synchronized (lock) {
                if (moveInProgress) {
                    pendingShutdownReturnPackage = active;
                    pendingShutdownReturnReason = safe(reason);
                    log(active, "dashboard_return_main_queued reason=" + safe(reason));
                    return;
                }
            }
        }
        moveIndependentDashboardApp(active, false, reason);
    }

    private static boolean isShutdownReturnReason(String reason) {
        return safe(reason).toLowerCase(Locale.ROOT).contains("shutdown");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void moveIndependentDashboardAppBlocking(
            String packageName,
            boolean toDashboard,
            int dashboardMode,
            String reason,
            Consumer<String> completion) {
        try {
            if (packageName.isEmpty()) {
                remember(new NavAppDisplayState(
                        packageName,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "independent dashboard failed: empty package"));
                return;
            }
            if (!preflightAuthorizedAdb(packageName, reason)) {
                remember(new NavAppDisplayState(
                        packageName,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "independent dashboard failed: authorized ADB unavailable"));
                return;
            }
            NavAppDisplayState current = checkDisplay(
                    packageName,
                    toDashboard
                            ? "independent-dashboard-precheck"
                            : "independent-return-precheck");
            if (current.taskId < 0) {
                remember(new NavAppDisplayState(
                        packageName,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "independent dashboard failed: task missing"));
                return;
            }
            if (!toDashboard) {
                long returnGeneration = projectionGenerationForPackage(packageName);
                if (current.displayId == MAIN_DISPLAY_ID
                        && !ClusterProjectionService.isProjectedPackageCurrent(packageName)) {
                    boolean surfaceReady = ensureWazeSurfaceOnDisplay(
                            packageName, MAIN_DISPLAY_ID,
                            "dashboard-already-main:" + safe(reason));
                    synchronized (lock) {
                        if (packageName.equals(activeDashboardPackage)) {
                            activeDashboardPackage = "";
                        }
                    }
                    clearDashboardProjection("independent-dashboard-already-main:" + safe(reason));
                    releaseAutoContainerLeaseIfRequested(
                            packageName, returnGeneration, true, reason);
                    requestTbtAfterReturnIfRequested(packageName, true, reason);
                    remember(new NavAppDisplayState(
                            packageName,
                            current.taskId,
                            current.displayId,
                            current.visible,
                            surfaceReady
                                    ? "independent dashboard already on main"
                                    : "independent dashboard already on main; surface handoff failed"));
                    return;
                }
                ClusterProjectionService.returnToMain(
                        context,
                        packageName,
                        "independent-dashboard return-main " + safe(reason));
                log(packageName, "dashboard_return_main_requested package=" + packageName
                        + " reason=" + safe(reason));
                NavAppDisplayState confirmed = waitForMainDisplay(
                        packageName,
                        "independent-return-confirm");
                boolean onMain = confirmed.taskId >= 0
                        && confirmed.displayId == MAIN_DISPLAY_ID;
                boolean surfaceReady = !onMain || ensureWazeSurfaceOnDisplay(
                        packageName, MAIN_DISPLAY_ID, "dashboard-return:" + safe(reason));
                synchronized (lock) {
                    if (onMain
                            && packageName.equals(activeDashboardPackage)) {
                        activeDashboardPackage = "";
                    }
                }
                boolean projectionReleased = false;
                if (onMain) {
                    clearDashboardProjection("independent-dashboard-return:" + safe(reason));
                    log(packageName, "dashboard_return_clear_after_confirm package=" + packageName);
                    projectionReleased = waitForProjectionRelease(
                            packageName, "independent-return-release");
                } else {
                    log(packageName, "dashboard_return_main_failed package=" + packageName
                            + " task=" + confirmed.taskId
                            + " display=" + confirmed.displayId
                            + " reason=" + safe(reason));
                }
                releaseAutoContainerLeaseIfRequested(
                        packageName, returnGeneration, projectionReleased, reason);
                requestTbtAfterReturnIfRequested(packageName, projectionReleased, reason);
                String returnStatus = onMain
                        ? surfaceReady
                                ? projectionReleased
                                        ? "independent dashboard returned to main"
                                        : "independent dashboard returned to main; projection release pending"
                                : "independent dashboard returned to main; surface handoff failed"
                        : "independent dashboard return failed display=" + confirmed.displayId;
                remember(new NavAppDisplayState(
                        packageName,
                        confirmed.taskId,
                        confirmed.displayId,
                        confirmed.visible,
                        returnStatus));
                return;
            }
            boolean alreadyProjected = isConfirmedProjectedDashboardDisplay(packageName, current);
            if (!alreadyProjected && !returnPreviousDashboardApp(packageName, reason)) {
                remember(new NavAppDisplayState(
                        packageName,
                        current.taskId,
                        current.displayId,
                        current.visible,
                        "independent dashboard blocked: previous app not on main"));
                return;
            }
            if (alreadyProjected) {
                ClusterProjectionService.startProjection(
                        context,
                        packageName,
                        dashboardMode,
                        "dashboard-existing:" + safe(reason));
                boolean surfaceReady = ensureWazeSurfaceOnDisplay(
                        packageName, current.displayId, "dashboard-existing:" + safe(reason));
                reconcileConfirmedDashboardOwnership(
                        packageName,
                        current,
                        dashboardMode,
                        "independent-dashboard-already-projected:" + safe(reason));
                String layoutFailure = sendAutoContainerIfRequested(
                        packageName,
                        autoContainerValueForMode(dashboardMode),
                        autoContainerValueForMode(dashboardMode) != 0,
                        "existing-dashboard");
                acquireAutoContainerLeaseIfSucceeded(
                        packageName, dashboardMode, layoutFailure, "existing-dashboard");
                remember(new NavAppDisplayState(
                        packageName,
                        current.taskId,
                        current.displayId,
                        current.visible,
                        autoContainerStatus(
                                !surfaceReady
                                        ? "independent dashboard projection retained; surface handoff failed"
                                        : "independent dashboard projection retained",
                                layoutFailure)));
                return;
            }
            ClusterProjectionService.startProjection(
                    context, packageName, dashboardMode, safe(reason));
            NavAppDisplayState confirmed = waitForProjectedDashboardDisplay(
                    packageName,
                    "independent-dashboard-start");
            if (!isConfirmedProjectedDashboardDisplay(packageName, confirmed)) {
                ClusterProjectionService.returnToMain(
                        context,
                        packageName,
                        "dashboard-confirmation-failed:" + safe(reason));
                clearDashboardProjection(
                        "dashboard-confirmation-failed:" + safe(reason));
                releaseAutoContainerLeaseAfterFailedSuccessor(
                        packageName, "dashboard-confirmation-failed:" + safe(reason));
                remember(new NavAppDisplayState(
                        packageName,
                        confirmed.taskId,
                        confirmed.displayId,
                        confirmed.visible,
                        "independent dashboard projection not confirmed"));
                return;
            }
            reconcileConfirmedDashboardOwnership(
                    packageName,
                    confirmed,
                    dashboardMode,
                    "independent-dashboard-confirmed:" + safe(reason));
            boolean surfaceReady = ensureWazeSurfaceOnDisplay(
                    packageName, confirmed.displayId, "dashboard-confirmed:" + safe(reason));
            String layoutFailure = sendAutoContainerIfRequested(
                    packageName,
                    autoContainerValueForMode(dashboardMode),
                    autoContainerValueForMode(dashboardMode) != 0,
                    "dashboard-confirmed");
            acquireAutoContainerLeaseIfSucceeded(
                    packageName, dashboardMode, layoutFailure, "dashboard-confirmed");
            remember(new NavAppDisplayState(
                    packageName,
                    confirmed.taskId,
                    confirmed.displayId,
                    confirmed.visible,
                    autoContainerStatus(
                            !surfaceReady
                                    ? "independent dashboard projection confirmed; surface handoff failed"
                                    : "independent dashboard projection confirmed",
                            layoutFailure)));
        } catch (SecurityException e) {
            remember(new NavAppDisplayState(
                    packageName,
                    -1,
                    NavAppDisplayState.DISPLAY_UNKNOWN,
                    false,
                    "independent dashboard failed: " + safe(e.getMessage())));
        } finally {
            String completionError = completionErrorForState(packageName);
            endMove(packageName);
            notifyMoveCompletion(completion, completionError);
        }
    }

    private String completionErrorForState(String packageName) {
        String status = lastState(packageName).status.toLowerCase(Locale.ROOT);
        return status.contains("failed") || status.contains("blocked")
                || status.contains("unknown") || status.contains("unavailable")
                || status.contains("not confirmed")
                ? lastState(packageName).status
                : "";
    }

    private void notifyMoveCompletion(Consumer<String> completion, String error) {
        if (completion == null) return;
        try {
            completion.accept(error == null ? "" : error);
        } catch (RuntimeException callbackError) {
            log("", "move completion callback failed "
                    + callbackError.getClass().getSimpleName());
        }
    }

    private void reportSteeringFailure(String packageName, String detail) {
        String safeDetail = safe(detail);
        log(packageName, "steering_transfer_failed detail=" + safeDetail);
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                context,
                HudPrefs.isUaLanguage(context)
                        ? "Не вдалося перенести застосунок"
                        : "Unable to transfer app",
                Toast.LENGTH_LONG).show());
    }

    //rejects unauthorised ADB before projection or task state can be changed.
    private boolean preflightAuthorizedAdb(String packageName, String reason) {
        try {
            LocalAdbBridge.ShellResult result = LocalAdbBridge.runRuntimeShellCommand(
                    context, "id");
            if (!result.success()) {
                log(packageName, "dashboard_preflight_adb_failed reason=" + safe(reason)
                        + " detail=" + result.shortDetail());
                return false;
            }
            return true;
        } catch (IOException | SecurityException e) {
            log(packageName, "dashboard_preflight_adb_rejected reason=" + safe(reason)
                    + " detail=" + safe(e.getMessage()));
            return false;
        }
    }

    //sends only explicit compositor transitions; ordinary task moves stay layout-neutral.
    private String sendAutoContainerIfRequested(
            String packageName, int value, boolean requested, String reason) {
        if (!requested || value == 0) {
            return "";
        }
        if (value == AUTO_CONTAINER_FULLSCREEN || value == AUTO_CONTAINER_PARTIAL) {
            String existingLease = persistedAutoContainerLeasePackage();
            String normalized = normalizePackage(packageName);
            if (!existingLease.isEmpty() && !existingLease.equals(normalized)) {
                log(normalized, "dashboard_autocontainer_skipped_existing_lease="
                        + existingLease + " reason=" + safe(reason));
                return "existing AutoContainer lease retained";
            }
        }
        try {
            LocalAdbBridge.ShellResult result = LocalAdbBridge.runAutoContainer(context, value);
            if (result.success()) {
                if (value == AUTO_CONTAINER_RELEASE) {
                    recordWidgetAutoContainerValue(value);
                }
                log(packageName, "dashboard_autocontainer_sent value=" + value
                        + " reason=" + safe(reason));
                return "";
            }
            String detail = result.shortDetail();
            log(packageName, "dashboard_autocontainer_failed value=" + value
                    + " reason=" + safe(reason) + " detail=" + detail);
            return "layout command failed: " + safe(detail);
        } catch (IOException | SecurityException e) {
            String detail = safe(e.getMessage());
            log(packageName, "dashboard_autocontainer_rejected value=" + value
                    + " reason=" + safe(reason) + " detail=" + detail);
            return "layout command failed: " + detail;
        }
    }

    private static String autoContainerStatus(String base, String failure) {
        String safeBase = base == null ? "" : base;
        return failure == null || failure.isEmpty()
                ? safeBase
                : safeBase + "; " + failure;
    }

    private void acquireAutoContainerLeaseIfSucceeded(
            String packageName, int dashboardMode, String layoutFailure, String reason) {
        if (autoContainerValueForMode(dashboardMode) == 0
                || (layoutFailure != null && !layoutFailure.isEmpty())) return;
        long generation = projectionGenerationForPackage(packageName);
        if (generation <= 0L) return;
        String normalized = normalizePackage(packageName);
        String existingLease = persistedAutoContainerLeasePackage();
        if (!existingLease.isEmpty() && !existingLease.equals(normalized)) {
            log(normalized, "dashboard_autocontainer_lease_acquire_skipped_existing="
                    + existingLease + " generation=" + generation
                    + " reason=" + safe(reason));
            return;
        }
        dashboardPrefs().edit()
                .putString(KEY_AUTOCONTAINER_LEASE_PACKAGE, normalized)
                .putLong(KEY_AUTOCONTAINER_LEASE_GENERATION, generation)
                .apply();
        log(packageName, "dashboard_autocontainer_lease_acquired generation="
                + generation + " reason=" + safe(reason));
    }

    private void releaseAutoContainerLeaseIfRequested(
            String packageName, long generation, boolean projectionReleased, String reason) {
        if (!projectionReleased || !isUserRequestedReturnForTest(reason)) return;
        releaseAutoContainerLease(packageName, generation, "return-release", reason);
    }

    private void releaseAutoContainerLease(
            String packageName, long generation, String operation, String reason) {
        String normalized = normalizePackage(packageName);
        SharedPreferences prefs = dashboardPrefs();
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        long leaseGeneration = prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L);
        if (!normalized.equals(leasePackage) || generation <= 0L || leaseGeneration != generation) {
            log(normalized, "dashboard_autocontainer_release_skipped leasePackage="
                    + leasePackage + " leaseGeneration=" + leaseGeneration
                    + " generation=" + generation + " reason=" + safe(reason));
            return;
        }
        String failure = sendAutoContainerIfRequested(
                normalized, AUTO_CONTAINER_RELEASE, true, operation);
        if (failure == null || failure.isEmpty()) {
            if (clearAutoContainerLeaseIfExact(
                    normalized, generation, operation + ":" + safe(reason))) {
                log(normalized, "dashboard_autocontainer_lease_released operation="
                        + operation + " generation=" + generation + " reason=" + safe(reason));
            }
        } else {
            log(normalized, "dashboard_autocontainer_lease_retained operation="
                    + operation + " generation=" + generation + " reason=" + safe(reason));
        }
    }

    private void releaseAutoContainerLeaseAfterFailedSuccessor(
            String successorPackage, String reason) {
        String previousPackage = normalizePackage(pendingAutoContainerLeaseTransferFrom);
        String successor = normalizePackage(successorPackage);
        SharedPreferences prefs = dashboardPrefs();
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        long leaseGeneration = prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L);
        long pendingGeneration = pendingAutoContainerLeaseTransferGeneration;
        if (previousPackage.isEmpty() || successor.isEmpty()
                || pendingGeneration <= 0L
                || !previousPackage.equals(leasePackage)
                || leaseGeneration != pendingGeneration
                || !isDirectNavigatorReplacement(previousPackage, successor)) {
            return;
        }
        NavAppDisplayState previous = waitForMainDisplay(
                previousPackage, "failed-successor-previous-main-confirm");
        NavAppDisplayState successorState = waitForMainDisplay(
                successor, "failed-successor-successor-main-confirm");
        boolean previousOnMain = isOnMainDisplay(previous);
        boolean successorOnMain = isOnMainDisplay(successorState);
        boolean noProjectionOwner = waitForProjectionRelease(
                successor,
                "failed-successor-projection-release");
        if (!shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                previousOnMain, successorOnMain, noProjectionOwner,
                previousPackage, successor,
                previousPackage, pendingGeneration,
                leasePackage, leaseGeneration)) {
            return;
        }
        releaseAutoContainerLease(
                previousPackage, pendingGeneration, "failed-successor-release", reason);
    }

    private boolean isOnMainDisplay(NavAppDisplayState state) {
        return state != null
                && state.taskId >= 0
                && state.displayId == MAIN_DISPLAY_ID;
    }

    private void clearAutoContainerLease(String reason) {
        String previous = normalizePackage(
                dashboardPrefs().getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        dashboardPrefs().edit()
                .remove(KEY_AUTOCONTAINER_LEASE_PACKAGE)
                .remove(KEY_AUTOCONTAINER_LEASE_GENERATION)
                .apply();
        if (!previous.isEmpty()) {
            log(previous, "dashboard_autocontainer_lease_clear reason=" + safe(reason));
        }
    }

    private boolean clearAutoContainerLeaseIfExact(
            String packageName, long generation, String reason) {
        String normalized = normalizePackage(packageName);
        SharedPreferences prefs = dashboardPrefs();
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        long leaseGeneration = prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L);
        if (!normalized.equals(leasePackage)
                || generation <= 0L
                || leaseGeneration != generation) {
            log(normalized, "dashboard_autocontainer_lease_clear_skipped"
                    + " leasePackage=" + leasePackage
                    + " leaseGeneration=" + leaseGeneration
                    + " package=" + normalized
                    + " generation=" + generation
                    + " reason=" + safe(reason));
            return false;
        }
        prefs.edit()
                .remove(KEY_AUTOCONTAINER_LEASE_PACKAGE)
                .remove(KEY_AUTOCONTAINER_LEASE_GENERATION)
                .apply();
        log(normalized, "dashboard_autocontainer_lease_clear reason=" + safe(reason));
        return true;
    }

    private String persistedAutoContainerLeasePackage() {
        return normalizePackage(
                dashboardPrefs().getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
    }

    private void requestTbtAfterReturnIfRequested(
            String packageName, boolean onMain, String reason) {
        if (!onMain || !isUserRequestedReturnForTest(reason)) return;
        String normalized = normalizePackage(packageName);
        if (!"com.waze".equals(normalized)
                && !GMapsDirectChannel.PACKAGE_NAME.equals(normalized)) return;
        NavHudLiveSender.get(context).onDashboardReturnConfirmed(normalized, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private boolean returnPreviousDashboardApp(String nextPackageName, String reason) {
        String previous = confirmedDashboardPackage();
        if (previous.isEmpty()) {
            String recoveryCandidate = persistedDashboardPackage();
            if (!recoveryCandidate.isEmpty() && !recoveryCandidate.equals(nextPackageName)) {
                checkDisplay(recoveryCandidate, "previous-dashboard-recovery-check");
                previous = confirmedDashboardPackage();
            }
        }
        if (previous.isEmpty() || previous.equals(nextPackageName)) {
            return true;
        }
        log(previous, "return_previous_dashboard_app next=" + nextPackageName
                + " reason=" + safe(reason));
        NavAppDisplayState returned = moveTaskToDisplayBlocking(
                previous,
                MAIN_DISPLAY_ID,
                "replaced-by-" + nextPackageName);
        NavAppDisplayState confirmed = returned.displayId == MAIN_DISPLAY_ID
                ? returned
                : waitForMainDisplay(previous, "return-previous-dashboard-confirm");
        boolean onMain = confirmed.taskId >= 0
                && confirmed.displayId == MAIN_DISPLAY_ID;
        if (onMain) {
            prepareAutoContainerLeaseTransfer(previous, nextPackageName);
            if (!ensureWazeSurfaceOnDisplay(
                    previous, MAIN_DISPLAY_ID,
                    "return-previous-dashboard:" + safe(reason))) {
                log(previous, "return_previous_dashboard_app surface_handoff_failed next="
                        + nextPackageName + " reason=" + safe(reason));
            }
            synchronized (lock) {
                if (previous.equals(activeDashboardPackage)) {
                    activeDashboardPackage = "";
                }
            }
            clearDashboardProjection("return-previous-dashboard:" + safe(reason));
            return true;
        }
        log(previous, "return_previous_dashboard_app failed task=" + confirmed.taskId
                + " display=" + confirmed.displayId
                + " next=" + nextPackageName);
        return false;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    // ponytail: one projection is active, so a global move lock is enough.
    synchronized NavAppDisplayState moveTaskToDisplayBlocking(
            String packageName,
            int targetDisplay,
            String reason) {
        String normalized = normalizePackage(packageName);
        String label = "move_to_display";
        try {
            if (normalized.isEmpty()) {
                return remember(new NavAppDisplayState(
                        normalized,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        label + " failed: empty package"));
            }
            if (!preflightAuthorizedAdb(normalized, reason)) {
                return remember(new NavAppDisplayState(
                        normalized,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        label + " failed: authorized ADB unavailable"));
            }
            NavAppDisplayState current = checkDisplay(normalized, reason);
            if (current.taskId < 0) {
                return remember(new NavAppDisplayState(
                        normalized,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        label + " failed: task missing"));
            }
            if (current.displayId == targetDisplay) {
                return remember(new NavAppDisplayState(
                        normalized,
                        current.taskId,
                        current.displayId,
                        current.visible,
                        label + " skipped: already on display " + targetDisplay));
            }
            LocalAdbBridge.ShellResult move = runCommand(
                    normalized,
                    "cmd activity display move-stack " + current.taskId + " " + targetDisplay,
                    label + " target=" + targetDisplay + " reason=" + safe(reason));
            if (!move.success()) {
                return remember(new NavAppDisplayState(
                        normalized,
                        current.taskId,
                        current.displayId,
                        current.visible,
                        label + " failed: " + move.shortDetail()));
            }
            NavAppDisplayState confirmed = checkDisplay(normalized, label + "-confirm");
            if (confirmed.displayId == targetDisplay) {
                return remember(new NavAppDisplayState(
                        normalized,
                        confirmed.taskId,
                        confirmed.displayId,
                        confirmed.visible,
                        label + " ok from=" + current.displayId + " to=" + targetDisplay));
            }
            return remember(new NavAppDisplayState(
                    normalized,
                    confirmed.taskId,
                    confirmed.displayId,
                    confirmed.visible,
                    label + " check failed target=" + targetDisplay));
        } catch (IOException | SecurityException e) {
            return remember(new NavAppDisplayState(
                    normalized,
                    -1,
                    NavAppDisplayState.DISPLAY_UNKNOWN,
                    false,
                    label + " failed: " + safe(e.getMessage())));
        }
    }

    private boolean ensureWazeSurfaceOnDisplay(
            String logicalPackage, int targetDisplay, String reason) {
        if (!"com.waze".equals(logicalPackage)) return true;
        boolean ready = NavHudLiveSender.get(context).ensureWazeSurfaceOnDisplayBlocking(
                targetDisplay, reason, WAZE_SURFACE_HANDOFF_TIMEOUT_MS);
        log(logicalPackage, "waze_surface_handoff target=" + targetDisplay
                + " actual=" + WazeSurfaceActivity.activeDisplayId()
                + " task=" + WazeSurfaceActivity.activeTaskId()
                + " ready=" + ready
                + " reason=" + safe(reason));
        return ready;
    }

    synchronized NavAppDisplayState moveTaskIdToDisplayBlocking(
            String logicalPackage,
            int taskId,
            int targetDisplay,
            String reason) {
        String normalized = normalizePackage(logicalPackage);
        String label = "move_task_to_display";
        try {
            if (normalized.isEmpty() || taskId < 0) {
                return new NavAppDisplayState(normalized, taskId,
                        NavAppDisplayState.DISPLAY_UNKNOWN, false,
                        label + " failed: invalid target");
            }
            if (!preflightAuthorizedAdb(normalized, reason)) {
                return new NavAppDisplayState(normalized, taskId,
                        NavAppDisplayState.DISPLAY_UNKNOWN, false,
                        label + " failed: authorized ADB unavailable");
            }
            NavAppDisplayState current = checkTaskId(normalized, taskId, reason);
            if (current == null) {
                return new NavAppDisplayState(normalized, taskId,
                        NavAppDisplayState.DISPLAY_UNKNOWN, false,
                        label + " failed: task missing");
            }
            if (current.displayId == targetDisplay) return current;
            LocalAdbBridge.ShellResult move = runCommand(
                    normalized,
                    "cmd activity display move-stack " + taskId + " " + targetDisplay,
                    label + " target=" + targetDisplay + " reason=" + safe(reason));
            if (!move.success()) {
                return new NavAppDisplayState(normalized, taskId, current.displayId,
                        current.visible, label + " failed: " + move.shortDetail());
            }
            NavAppDisplayState confirmed = checkTaskId(
                    normalized, taskId, label + "-confirm");
            return confirmed == null
                    ? new NavAppDisplayState(normalized, taskId,
                            NavAppDisplayState.DISPLAY_UNKNOWN, false,
                            label + " confirmation missing")
                    : confirmed;
        } catch (IOException | SecurityException error) {
            return new NavAppDisplayState(normalized, taskId,
                    NavAppDisplayState.DISPLAY_UNKNOWN, false,
                    label + " failed: " + safe(error.getMessage()));
        }
    }

    private NavAppDisplayState checkTaskId(
            String logicalPackage, int taskId, String reason) throws IOException {
        LocalAdbBridge.ShellResult result = runCommand(
                logicalPackage,
                "dumpsys activity activities",
                "check_task_id reason=" + safe(reason));
        return result.success() ? parseTaskId(logicalPackage, taskId, result.output) : null;
    }

    //waits for the app-owned virtual display, then moves the task there if Android created it late.
    private NavAppDisplayState waitForProjectedDashboardDisplay(String packageName, String reason) {
        NavAppDisplayState last = checkDisplay(packageName, reason + "-initial");
        long deadline = android.os.SystemClock.elapsedRealtime() + PROJECTED_DISPLAY_CONFIRM_TIMEOUT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            int projectedDisplayId = ClusterProjectionService.projectedDisplayIdForPackage(packageName);
            log(packageName, "dashboard_confirm_wait projectedDisplay=" + projectedDisplayId
                    + " actualDisplay=" + last.displayId
                    + " reason=" + safe(reason));
            if (projectedDisplayId > MAIN_DISPLAY_ID) {
                if (last.displayId == projectedDisplayId) {
                    log(packageName, "dashboard_confirmed_late package=" + packageName
                            + " display=" + last.displayId);
                    return last;
                }
                NavAppDisplayState moved = moveTaskToDisplayBlocking(
                        packageName,
                        projectedDisplayId,
                        reason + "-late-projected-display");
                if (moved.displayId == projectedDisplayId) {
                    log(packageName, "dashboard_confirmed_late package=" + packageName
                            + " display=" + moved.displayId);
                    return moved;
                }
                last = moved;
            }
            sleepDisplayConfirmInterval();
            last = checkDisplay(packageName, reason);
        }
        return last;
    }

    //requires confirmation against the app-owned virtual display so unrelated dashboard displays do not win.
    private boolean isConfirmedProjectedDashboardDisplay(String packageName, NavAppDisplayState state) {
        if (state == null) {
            return false;
        }
        int projectedDisplayId = ClusterProjectionService.projectedDisplayIdForPackage(packageName);
        return projectedDisplayId > MAIN_DISPLAY_ID && state.displayId == projectedDisplayId;
    }

    //promotes recovery intent to live ownership only after observing the exact app-owned display.
    private boolean reconcileConfirmedDashboardOwnership(
            String packageName,
            NavAppDisplayState state,
            int dashboardMode,
            String reason) {
        if (!isConfirmedProjectedDashboardDisplay(packageName, state)) {
            return false;
        }
        String normalized = normalizePackage(packageName);
        String previousPersistedPackage = persistedDashboardPackage();
        long generation = confirmProjectionGeneration(normalized, previousPersistedPackage);
        transferAutoContainerLeaseIfReplaced(normalized, generation);
        boolean ownershipChanged;
        synchronized (lock) {
            ownershipChanged = !normalized.equals(activeDashboardPackage);
            activeDashboardPackage = normalized;
        }
        int normalizedMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        if (!normalized.equals(previousPersistedPackage)
                || persistedDashboardMode() != normalizedMode) {
            persistDashboardProjection(normalized, normalizedMode, reason);
        }
        if (ownershipChanged) {
            log(normalized, "dashboard_live_owner_confirmed package=" + normalized
                    + " display=" + state.displayId + " generation=" + generation
                    + " reason=" + safe(reason));
        }
        return true;
    }

    private long confirmProjectionGeneration(String packageName, String previousPersistedPackage) {
        SharedPreferences prefs = dashboardPrefs();
        long current = prefs.getLong(KEY_PROJECTION_GENERATION, 0L);
        if (packageName.equals(previousPersistedPackage) && current > 0L) {
            return current;
        }
        long next = current == Long.MAX_VALUE ? 1L : current + 1L;
        prefs.edit().putLong(KEY_PROJECTION_GENERATION, next).apply();
        return next;
    }

    private long projectionGenerationForPackage(String packageName) {
        String normalized = normalizePackage(packageName);
        String persisted = persistedDashboardPackage();
        if (normalized.isEmpty()) return 0L;
        SharedPreferences prefs = dashboardPrefs();
        if (normalized.equals(persisted)) {
            return prefs.getLong(KEY_PROJECTION_GENERATION, 0L);
        }
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        return normalized.equals(leasePackage)
                ? prefs.getLong(KEY_PROJECTION_GENERATION,
                        prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L))
                : 0L;
    }

    private void transferAutoContainerLeaseIfReplaced(String packageName, long generation) {
        SharedPreferences prefs = dashboardPrefs();
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        long leaseGeneration = prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L);
        if (leasePackage.isEmpty() || leaseGeneration <= 0L
                || !leasePackage.equals(pendingAutoContainerLeaseTransferFrom)
                || leaseGeneration != pendingAutoContainerLeaseTransferGeneration
                || !isDirectNavigatorReplacement(leasePackage, packageName)) {
            return;
        }
        prefs.edit()
                .putString(KEY_AUTOCONTAINER_LEASE_PACKAGE, packageName)
                .putLong(KEY_AUTOCONTAINER_LEASE_GENERATION, generation)
                .apply();
        pendingAutoContainerLeaseTransferFrom = "";
        pendingAutoContainerLeaseTransferGeneration = 0L;
        log(packageName, "dashboard_autocontainer_lease_transferred from="
                + leasePackage + " to=" + packageName
                + " generation=" + generation);
    }

    private void prepareAutoContainerLeaseTransfer(
            String previousPackage, String nextPackage) {
        SharedPreferences prefs = dashboardPrefs();
        String leasePackage = normalizePackage(
                prefs.getString(KEY_AUTOCONTAINER_LEASE_PACKAGE, ""));
        long leaseGeneration = prefs.getLong(KEY_AUTOCONTAINER_LEASE_GENERATION, 0L);
        if (shouldPrepareAutoContainerLeaseTransfer(
                previousPackage, nextPackage, leasePackage, leaseGeneration)) {
            pendingAutoContainerLeaseTransferFrom = leasePackage;
            pendingAutoContainerLeaseTransferGeneration = leaseGeneration;
        }
    }

    static boolean shouldPrepareAutoContainerLeaseTransfer(
            String previousPackage, String nextPackage,
            String leasePackage, long leaseGeneration) {
        return leaseGeneration > 0L
                && previousPackage.equals(leasePackage)
                && isDirectNavigatorReplacement(previousPackage, nextPackage);
    }

    static boolean shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
            boolean previousOnMain, boolean successorOnMain, boolean noProjectionOwner,
            String previousPackage, String successorPackage,
            String pendingPackage, long pendingGeneration,
            String leasePackage, long leaseGeneration) {
        String previous = normalizePackage(previousPackage);
        String successor = normalizePackage(successorPackage);
        String pending = normalizePackage(pendingPackage);
        String lease = normalizePackage(leasePackage);
        return previousOnMain
                && successorOnMain
                && noProjectionOwner
                && pendingGeneration > 0L
                && pending.equals(previous)
                && lease.equals(pending)
                && leaseGeneration == pendingGeneration
                && isDirectNavigatorReplacement(previous, successor);
    }

    static boolean isDirectNavigatorReplacement(String previousPackage, String nextPackage) {
        if (previousPackage.equals(nextPackage)) return false;
        boolean previousSupported = "com.waze".equals(previousPackage)
                || GMapsDirectChannel.PACKAGE_NAME.equals(previousPackage);
        boolean nextSupported = "com.waze".equals(nextPackage)
                || GMapsDirectChannel.PACKAGE_NAME.equals(nextPackage);
        return previousSupported && nextSupported;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private NavAppDisplayState waitForMainDisplay(String packageName, String reason) {
        NavAppDisplayState last = checkDisplay(packageName, reason + "-initial");
        long deadline = android.os.SystemClock.elapsedRealtime() + DISPLAY_CONFIRM_TIMEOUT_MS;
        while (last.taskId >= 0
                && last.displayId != MAIN_DISPLAY_ID
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            sleepDisplayConfirmInterval();
            last = checkDisplay(packageName, reason);
        }
        return last;
    }

    private boolean waitForProjectionRelease(String packageName, String reason) {
        long deadline = android.os.SystemClock.elapsedRealtime() + DISPLAY_CONFIRM_TIMEOUT_MS;
        while (ClusterProjectionService.hasProjectionOwner()
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            sleepDisplayConfirmInterval();
        }
        boolean released = !ClusterProjectionService.hasProjectionOwner();
        log(packageName, "dashboard_projection_release_confirmed=" + released
                + " reason=" + safe(reason));
        return released;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void sleepDisplayConfirmInterval() {
        try {
            Thread.sleep(DISPLAY_CONFIRM_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private int resolveDashboardDisplay(String packageName) throws IOException {
        LocalAdbBridge.ShellResult displayDump = runCommand(
                packageName,
                "dumpsys display",
                "resolve_dashboard");
        if (displayDump.success()) {
            return parseDashboardDisplayId(displayDump.output);
        }
        return FALLBACK_DASHBOARD_DISPLAY_ID;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private LocalAdbBridge.ShellResult runCommand(
            String packageName,
            String command,
            String label) throws IOException {
        LocalAdbBridge.ShellResult result =
                LocalAdbBridge.runRuntimeShellCommand(context, command);
        log(packageName, label
                + " command=\"" + NavCaptureStore.esc(command) + "\""
                + " exit=" + result.exitCode
                + " output=\"" + NavCaptureStore.esc(shortOutput(result.output)) + "\"");
        return result;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private boolean beginMove(String packageName, String status) {
        synchronized (lock) {
            if (moveInProgress) {
                return false;
            }
            moveInProgress = true;
        }
        remember(new NavAppDisplayState(
                packageName,
                -1,
                NavAppDisplayState.DISPLAY_UNKNOWN,
                false,
                status == null ? "move running" : status));
        return true;
    }

    //keeps dashboard projection intent outside process memory for projection-service recovery.
    private void persistDashboardProjection(String packageName, int dashboardMode, String reason) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            return;
        }
        dashboardPrefs()
                .edit()
                .putString(KEY_ACTIVE_PACKAGE, normalized)
                .putInt(KEY_ACTIVE_MODE, HudPrefs.normalizeDashboardScreenMode(dashboardMode))
                .putString(KEY_ACTIVE_REASON, safe(reason))
                .putLong(KEY_ACTIVE_UPDATED_MS, System.currentTimeMillis())
                .apply();
        log(normalized, "dashboard_projection_persist package=" + normalized
                + " reason=" + safe(reason));
    }

    //clears dashboard projection intent when the app is intentionally returned to main.
    private void clearDashboardProjection(String reason) {
        String previous = persistedDashboardPackage();
        dashboardPrefs()
                .edit()
                .remove(KEY_ACTIVE_PACKAGE)
                .remove(KEY_ACTIVE_MODE)
                .putString(KEY_ACTIVE_REASON, safe(reason))
                .putLong(KEY_ACTIVE_UPDATED_MS, System.currentTimeMillis())
                .apply();
        if (!previous.isEmpty()) {
            log(previous, "dashboard_projection_persist_clear reason=" + safe(reason));
        }
    }

    //centralizes storage access so all dashboard ownership reads use the same preferences file.
    private SharedPreferences dashboardPrefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void endMove(String packageName) {
        String deferredReturnPackage;
        String deferredReturnReason;
        pendingAutoContainerLeaseTransferFrom = "";
        pendingAutoContainerLeaseTransferGeneration = 0L;
        synchronized (lock) {
            moveInProgress = false;
            deferredReturnPackage = pendingShutdownReturnPackage;
            deferredReturnReason = pendingShutdownReturnReason;
            pendingShutdownReturnPackage = "";
            pendingShutdownReturnReason = "";
        }
        log(packageName, "move idle");
        NavAccessibilityService.requestSteeringTaskCacheRefresh(context, "move-complete");
        notifyStatusChanged();
        if (!deferredReturnPackage.isEmpty()) {
            moveIndependentDashboardApp(
                    deferredReturnPackage,
                    false,
                    deferredReturnReason);
        }
    }

    private long widgetProjectionGenerationForPackage(String packageName) {
        String normalized = normalizePackage(packageName);
        return normalized.isEmpty()
                ? 0L
                : ClusterProjectionService.projectedGenerationTokenForWidget(normalized);
    }

    //renders this UI section here so screen structure stays traceable during preview and car testing.
    private NavAppDisplayState remember(NavAppDisplayState state) {
        NavAppDisplayState safeState = state == null
                ? new NavAppDisplayState(
                        "",
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "unknown")
                : state;
        synchronized (lock) {
            states.put(safeState.packageName, safeState);
        }
        log(safeState.packageName,
                "state package=" + safeState.packageName
                        + " task=" + safeState.taskId
                        + " display=" + safeState.displayId
                        + " visible=" + safeState.visible
                        + " status=" + safeState.status);
        notifyStatusChanged();
        return safeState;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void notifyStatusChanged() {
        Listener callback;
        boolean moving;
        synchronized (lock) {
            callback = listener;
            moving = moveInProgress;
        }
        if (callback != null) {
            callback.onNavAppDisplayChanged(moving);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void log(String packageName, String line) {
        String safePackage = normalizePackage(packageName);
        String safeLine = safe(line);
        Log.i(TAG, safeLine);
        AppEventLogger.event(context, CHANNEL + " " + safeLine);
        NavCaptureStore.rawEvent(context, CHANNEL, safePackage, safeLine);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static NavAppDisplayState parseTask(String packageName, String dumpsys) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty() || dumpsys == null || dumpsys.isEmpty()) {
            return null;
        }
        String[] lines = dumpsys.split("\\r?\\n");
        int sectionDisplayId = NavAppDisplayState.DISPLAY_UNKNOWN;
        int currentTaskId = -1;
        int currentDisplayId = NavAppDisplayState.DISPLAY_UNKNOWN;
        NavAppDisplayState selected = null;
        StringBuilder block = new StringBuilder();
        for (String line : lines) {
            Matcher displaySection = DISPLAY_SECTION_PATTERN.matcher(line);
            if (displaySection.matches()) {
                sectionDisplayId = parseInt(
                        displaySection.group(1),
                        NavAppDisplayState.DISPLAY_UNKNOWN);
            }
            int[] header = parseTaskHeader(line, sectionDisplayId);
            if (header != null) {
                selected = preferVisibleTask(selected, taskFromBlock(
                        normalized,
                        currentTaskId,
                        currentDisplayId,
                        block));
                currentTaskId = header[0];
                currentDisplayId = header[1];
                block.setLength(0);
            }
            if (currentTaskId >= 0) {
                block.append(line).append('\n');
            } else if (containsPackage(line, normalized)) {
                int[] inline = parseInlineTaskValues(line, sectionDisplayId);
                if (inline != null) {
                    selected = preferVisibleTask(selected, new NavAppDisplayState(
                            normalized,
                            inline[0],
                            inline[1],
                            parseVisible(line),
                            "parsed"));
                }
            }
        }
        return preferVisibleTask(
                selected,
                taskFromBlock(normalized, currentTaskId, currentDisplayId, block));
    }

    private static NavAppDisplayState parseTaskId(
            String logicalPackage, int targetTaskId, String dumpsys) {
        if (targetTaskId < 0 || dumpsys == null || dumpsys.isEmpty()) return null;
        String[] lines = dumpsys.split("\\r?\\n");
        int sectionDisplayId = NavAppDisplayState.DISPLAY_UNKNOWN;
        int currentTaskId = -1;
        int currentDisplayId = NavAppDisplayState.DISPLAY_UNKNOWN;
        StringBuilder block = new StringBuilder();
        for (String line : lines) {
            Matcher section = DISPLAY_SECTION_PATTERN.matcher(line);
            if (section.matches()) {
                sectionDisplayId = parseInt(
                        section.group(1), NavAppDisplayState.DISPLAY_UNKNOWN);
            }
            int[] header = parseTaskHeader(line, sectionDisplayId);
            if (header != null) {
                if (currentTaskId == targetTaskId) {
                    return new NavAppDisplayState(logicalPackage, targetTaskId,
                            currentDisplayId, parseVisible(block.toString()), "parsed-task-id");
                }
                currentTaskId = header[0];
                currentDisplayId = header[1];
                block.setLength(0);
            }
            if (currentTaskId >= 0) block.append(line).append('\n');
        }
        return currentTaskId == targetTaskId
                ? new NavAppDisplayState(logicalPackage, targetTaskId, currentDisplayId,
                        parseVisible(block.toString()), "parsed-task-id")
                : null;
    }

    private static NavAppDisplayState preferVisibleTask(
            NavAppDisplayState current,
            NavAppDisplayState candidate) {
        if (candidate == null) {
            return current;
        }
        return NavAppTaskScanner.shouldReplaceTaskSelection(
                current != null,
                current != null && current.visible,
                candidate.visible)
                ? candidate
                : current;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static int[] parseTaskHeader(String line, int fallbackDisplayId) {
        Matcher root = ROOT_TASK_PATTERN.matcher(line);
        if (!root.matches()) {
            root = ROOT_TASK_HASH_PATTERN.matcher(line);
        }
        if (!root.matches()) {
            root = TASK_HASH_PATTERN.matcher(line);
        }
        if (root.matches()) {
            return new int[]{
                    parseInt(root.group(1), -1),
                    parseInt(root.group(2), NavAppDisplayState.DISPLAY_UNKNOWN)
            };
        }
        root = TASK_HASH_NO_DISPLAY_PATTERN.matcher(line);
        if (root.matches()) {
            return new int[]{
                    parseInt(root.group(1), -1),
                    fallbackDisplayId
            };
        }
        return null;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static int[] parseInlineTaskValues(String line, int fallbackDisplayId) {
        Matcher taskDisplay = TASK_ID_DISPLAY_PATTERN.matcher(line);
        if (taskDisplay.matches()) {
            return new int[]{
                    parseInt(taskDisplay.group(1), -1),
                    parseInt(taskDisplay.group(2), NavAppDisplayState.DISPLAY_UNKNOWN)
            };
        }
        Matcher displayTask = DISPLAY_ID_TASK_PATTERN.matcher(line);
        if (displayTask.matches()) {
            return new int[]{
                    parseInt(displayTask.group(2), -1),
                    parseInt(displayTask.group(1), NavAppDisplayState.DISPLAY_UNKNOWN)
            };
        }
        Matcher taskColon = TASK_ID_COLON_PATTERN.matcher(line);
        if (taskColon.matches()) {
            return new int[]{
                    parseInt(taskColon.group(1), -1),
                    fallbackDisplayId
            };
        }
        Matcher taskOnly = TASK_ID_PATTERN.matcher(line);
        if (taskOnly.matches()) {
            return new int[]{
                    parseInt(taskOnly.group(1), -1),
                    fallbackDisplayId
            };
        }
        return null;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static NavAppDisplayState taskFromBlock(
            String packageName,
            int taskId,
            int displayId,
            StringBuilder block) {
        if (taskId < 0 || block == null) {
            return null;
        }
        String value = block.toString();
        if (!containsPackage(value, packageName)) {
            return null;
        }
        return new NavAppDisplayState(
                packageName,
                taskId,
                displayId,
                parseVisible(value),
                "parsed");
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static int parseDashboardDisplayId(String dumpsys) {
        String safe = dumpsys == null ? "" : dumpsys;
        int primary = findDisplayByName(safe, PRIMARY_DASHBOARD_DISPLAY_NAME, false);
        if (primary >= 0) {
            return primary;
        }
        int shared = findDisplayByName(safe, SHARED_DASHBOARD_DISPLAY_PREFIX, true);
        if (shared >= 0) {
            return shared;
        }
        Matcher before = REMOTE_DASHBOARD_DISPLAY_BEFORE.matcher(safe);
        if (before.matches()) {
            return parseInt(before.group(1), FALLBACK_DASHBOARD_DISPLAY_ID);
        }
        Matcher after = REMOTE_DASHBOARD_DISPLAY_AFTER.matcher(safe);
        if (after.matches()) {
            return parseInt(after.group(1), FALLBACK_DASHBOARD_DISPLAY_ID);
        }
        String[] lines = safe.split("\\r?\\n");
        int lastDisplayId = NavAppDisplayState.DISPLAY_UNKNOWN;
        for (String line : lines) {
            Matcher display = DISPLAY_ID_PATTERN.matcher(line);
            if (display.matches()) {
                lastDisplayId = parseInt(display.group(1), NavAppDisplayState.DISPLAY_UNKNOWN);
            }
            if (line.contains("remote_dashboard") && lastDisplayId >= 0) {
                return lastDisplayId;
            }
        }
        return FALLBACK_DASHBOARD_DISPLAY_ID;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static int findDisplayByName(String dumpsys, String name, boolean prefix) {
        String[] lines = dumpsys.split("\\r?\\n");
        int currentDisplayId = NavAppDisplayState.DISPLAY_UNKNOWN;
        for (String line : lines) {
            Matcher logicalDisplay = LOGICAL_DISPLAY_PATTERN.matcher(line);
            if (logicalDisplay.matches()) {
                currentDisplayId = parseInt(
                        logicalDisplay.group(1),
                        NavAppDisplayState.DISPLAY_UNKNOWN);
            }
            Matcher displayId = DISPLAY_ID_PATTERN.matcher(line);
            if (displayId.matches()) {
                currentDisplayId = parseInt(
                        displayId.group(1),
                        currentDisplayId);
            }
            Matcher displayName = DISPLAY_NAME_PATTERN.matcher(line);
            if (!displayName.matches()) {
                continue;
            }
            String foundName = displayName.group(1);
            boolean matched = prefix ? foundName.startsWith(name) : foundName.equals(name);
            if (!matched) {
                continue;
            }
            Matcher infoDisplayId = DISPLAY_INFO_DISPLAY_ID_PATTERN.matcher(line);
            if (infoDisplayId.matches()) {
                return parseInt(infoDisplayId.group(1), currentDisplayId);
            }
            if (currentDisplayId >= 0) {
                return currentDisplayId;
            }
        }
        return NavAppDisplayState.DISPLAY_UNKNOWN;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean containsPackage(String text, String packageName) {
        if (text == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        Pattern packagePattern = Pattern.compile(
                "(^|[^A-Za-z0-9_.$])"
                        + Pattern.quote(packageName)
                        + "($|[^A-Za-z0-9_.$])");
        return packagePattern.matcher(text).find();
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static boolean parseVisible(String text) {
        String value = text == null ? "" : text;
        if (value.contains("visible=false")) {
            return false;
        }
        if (value.contains("isVisible=false")) {
            return false;
        }
        return true;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String shortOutput(String value) {
        String safe = safe(value).replace('\n', ' ').replace('\r', ' ');
        if (safe.length() > 180) {
            return safe.substring(0, 180) + "...";
        }
        return safe;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
