package com.bydhud.app;

//checks visible app state so visual capture only runs when the target app is actually on screen.

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the NavAppDisplayController module boundary so related behavior stays readable inside one unit.
final class NavAppDisplayController {
    private static final String TAG = "BydHudNavAppDisplay";
    private static final String CHANNEL = "nav_app_display";
    private static final int MAIN_DISPLAY_ID = 0;
    private static final int FALLBACK_DASHBOARD_DISPLAY_ID = 2;
    private static final long DISPLAY_CONFIRM_TIMEOUT_MS = 4000L;
    private static final long DISPLAY_CONFIRM_INTERVAL_MS = 250L;
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
        void onNavAppDisplayChanged();
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

    private final Context context;
    private final Object lock = new Object();
    private final Map<String, NavAppDisplayState> states = new HashMap<>();
    private boolean moveInProgress;
    private String activeDashboardPackage = "";
    private Listener listener;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavAppDisplayController(Context context) {
        this.context = context.getApplicationContext();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void setListener(Listener listener) {
        synchronized (lock) {
            this.listener = listener;
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
        synchronized (lock) {
            return activeDashboardPackage;
        }
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
            return remember(new NavAppDisplayState(
                    normalized,
                    parsed.taskId,
                    parsed.displayId,
                    parsed.visible,
                    "display=" + parsed.displayId + " task=" + parsed.taskId));
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
        move(packageName, true, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void moveToMain(String packageName, String reason) {
        move(packageName, false, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void toggleDisplay(String packageName, String reason) {
        move(packageName, null, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void moveIndependentDashboardApp(String packageName, boolean toDashboard, String reason) {
        String normalized = normalizePackage(packageName);
        String label = toDashboard ? "independent_dashboard_on" : "independent_dashboard_off";
        if (!beginMove(normalized, label + " reason=" + safe(reason))) {
            log(normalized, label + " skipped already_running reason=" + safe(reason));
            return;
        }
        Thread worker = new Thread(
                () -> moveIndependentDashboardAppBlocking(normalized, toDashboard, reason),
                "BydHudIndependentDashboardDisplay");
        worker.start();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void move(String packageName, Boolean toDashboard, String reason) {
        String normalized = normalizePackage(packageName);
        String label = toDashboard == null
                ? "toggle_display"
                : (toDashboard ? "move_to_dashboard" : "move_to_main");
        if (!beginMove(normalized, label + " reason=" + safe(reason))) {
            log(normalized, label + " skipped already_running reason=" + safe(reason));
            return;
        }
        Thread worker = new Thread(
                () -> moveTask(normalized, toDashboard, reason),
                toDashboard == null
                        ? "BydHudNavAppDisplayToggle"
                        : (toDashboard
                                ? "BydHudNavAppDisplayDashboard"
                                : "BydHudNavAppDisplayMain"));
        worker.start();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void moveTask(String packageName, Boolean toDashboard, String reason) {
        String label = toDashboard == null
                ? "toggle_display"
                : (toDashboard ? "move_to_dashboard" : "move_to_main");
        try {
            if (packageName.isEmpty()) {
                remember(new NavAppDisplayState(
                        packageName,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        label + " failed: empty package"));
                return;
            }
            NavAppDisplayState current = checkDisplay(packageName, reason);
            if (current.taskId < 0) {
                remember(new NavAppDisplayState(
                        packageName,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        label + " failed: task missing"));
                return;
            }
            boolean targetDashboard = toDashboard == null
                    ? !current.isOnDashboardDisplay()
                    : toDashboard;
            label = targetDashboard ? "move_to_dashboard" : "move_to_main";
            if (targetDashboard) {
                ClusterProjectionService.startProjection(
                        context,
                        packageName,
                        safe(reason));
                remember(new NavAppDisplayState(
                        packageName,
                        current.taskId,
                        current.displayId,
                        current.visible,
                        label + " projection requested from=" + current.displayId));
                return;
            }
            ClusterProjectionService.returnToMain(
                    context,
                    packageName,
                    safe(reason));
            synchronized (lock) {
                if (packageName.equals(activeDashboardPackage)) {
                    activeDashboardPackage = "";
                }
            }
            remember(new NavAppDisplayState(
                    packageName,
                    current.taskId,
                    current.displayId,
                    current.visible,
                    label + " return requested from=" + current.displayId));
            return;
        } catch (SecurityException e) {
            remember(new NavAppDisplayState(
                    packageName,
                    -1,
                    NavAppDisplayState.DISPLAY_UNKNOWN,
                    false,
                    label + " failed: " + safe(e.getMessage())));
        } finally {
            endMove(packageName);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void moveIndependentDashboardAppBlocking(
            String packageName,
            boolean toDashboard,
            String reason) {
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
            if (!toDashboard) {
                ClusterProjectionService.returnToMain(
                        context,
                        packageName,
                        "independent-dashboard return-main " + safe(reason));
                NavAppDisplayState confirmed = waitForMainDisplay(
                        packageName,
                        "independent-return-confirm");
                synchronized (lock) {
                    if ((confirmed.taskId < 0 || confirmed.displayId == MAIN_DISPLAY_ID)
                            && packageName.equals(activeDashboardPackage)) {
                        activeDashboardPackage = "";
                    }
                }
                remember(new NavAppDisplayState(
                        packageName,
                        confirmed.taskId,
                        confirmed.displayId,
                        confirmed.visible,
                        confirmed.taskId < 0 || confirmed.displayId == MAIN_DISPLAY_ID
                                ? "independent dashboard returned to main"
                                : "independent dashboard return failed display="
                                        + confirmed.displayId));
                return;
            }

            NavAppDisplayState current = checkDisplay(packageName, "independent-dashboard-precheck");
            if (current.taskId < 0) {
                remember(new NavAppDisplayState(
                        packageName,
                        -1,
                        NavAppDisplayState.DISPLAY_UNKNOWN,
                        false,
                        "independent dashboard failed: task missing"));
                return;
            }
            if (!returnPreviousDashboardApp(packageName, reason)) {
                remember(new NavAppDisplayState(
                        packageName,
                        current.taskId,
                        current.displayId,
                        current.visible,
                        "independent dashboard blocked: previous app not on main"));
                return;
            }
            ClusterProjectionService.startProjection(context, packageName, safe(reason));
            NavAppDisplayState confirmed = waitForDashboardDisplay(
                    packageName,
                    "independent-dashboard-start");
            if (!confirmed.isOnDashboardDisplay()) {
                remember(new NavAppDisplayState(
                        packageName,
                        confirmed.taskId,
                        confirmed.displayId,
                        confirmed.visible,
                        "independent dashboard projection not confirmed"));
                return;
            }
            synchronized (lock) {
                activeDashboardPackage = packageName;
            }
            remember(new NavAppDisplayState(
                    packageName,
                    confirmed.taskId,
                    confirmed.displayId,
                    confirmed.visible,
                    "independent dashboard projection confirmed"));
        } catch (SecurityException e) {
            remember(new NavAppDisplayState(
                    packageName,
                    -1,
                    NavAppDisplayState.DISPLAY_UNKNOWN,
                    false,
                    "independent dashboard failed: " + safe(e.getMessage())));
        } finally {
            endMove(packageName);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private boolean returnPreviousDashboardApp(String nextPackageName, String reason) {
        String previous;
        synchronized (lock) {
            previous = activeDashboardPackage;
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
        boolean onMain = confirmed.taskId < 0 || confirmed.displayId == MAIN_DISPLAY_ID;
        if (onMain) {
            synchronized (lock) {
                if (previous.equals(activeDashboardPackage)) {
                    activeDashboardPackage = "";
                }
            }
            return true;
        }
        log(previous, "return_previous_dashboard_app failed display=" + confirmed.displayId
                + " next=" + nextPackageName);
        return false;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    NavAppDisplayState moveTaskToDisplayBlocking(
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private NavAppDisplayState waitForDashboardDisplay(String packageName, String reason) {
        NavAppDisplayState last = checkDisplay(packageName, reason + "-initial");
        long deadline = android.os.SystemClock.elapsedRealtime() + DISPLAY_CONFIRM_TIMEOUT_MS;
        while (!last.isOnDashboardDisplay()
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            sleepDisplayConfirmInterval();
            last = checkDisplay(packageName, reason);
        }
        return last;
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void endMove(String packageName) {
        synchronized (lock) {
            moveInProgress = false;
        }
        log(packageName, "move idle");
        notifyStatusChanged();
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
        synchronized (lock) {
            callback = listener;
        }
        if (callback != null) {
            callback.onNavAppDisplayChanged();
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
                NavAppDisplayState previous = taskFromBlock(
                        normalized,
                        currentTaskId,
                        currentDisplayId,
                        block);
                if (previous != null) {
                    return previous;
                }
                currentTaskId = header[0];
                currentDisplayId = header[1];
                block.setLength(0);
            }
            if (currentTaskId >= 0) {
                block.append(line).append('\n');
            } else if (containsPackage(line, normalized)) {
                int[] inline = parseInlineTaskValues(line, sectionDisplayId);
                if (inline != null) {
                    return new NavAppDisplayState(
                            normalized,
                            inline[0],
                            inline[1],
                            parseVisible(line),
                            "parsed");
                }
            }
        }
        return taskFromBlock(normalized, currentTaskId, currentDisplayId, block);
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
