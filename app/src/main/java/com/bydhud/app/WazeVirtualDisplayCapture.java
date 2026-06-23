package com.bydhud.app;

//uses virtual display capture so Waze visuals can be parsed without depending on screenshots.

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the WazeVirtualDisplayCapture module boundary so related behavior stays readable inside one unit.
final class WazeVirtualDisplayCapture {
    private static final String TAG = "BydHudWazeVD";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final String SESSION_DIR = "waze-virtual-display";
    private static final String SESSION_LOG = "session.jsonl";
    private static final int MAIN_DISPLAY_ID = 0;
    private static final int FALLBACK_VIRTUAL_DISPLAY_ID = 2;
    private static final String PRIMARY_VIRTUAL_DISPLAY_NAME = "fission_bg_XDJAScreenProjection";
    private static final String SHARED_VIRTUAL_DISPLAY_PREFIX =
            "shared_fission_bg_XDJAScreenProjection";
    private static final long CAPTURE_INTERVAL_MS = 1500L;
    private static final Pattern ROOT_TASK_PATTERN = Pattern.compile(
            ".*RootTask id=([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern ROOT_TASK_HASH_PATTERN = Pattern.compile(
            ".*RootTask\\{[^#]*#([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern TASK_HASH_PATTERN = Pattern.compile(
            ".*Task\\{[^#]*#([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern TASK_HASH_NO_DISPLAY_PATTERN = Pattern.compile(
            ".*Task\\{[^#]*#([0-9]+).*");
    private static final Pattern DISPLAY_SECTION_PATTERN = Pattern.compile(
            ".*Display #([0-9]+).*");
    private static final Pattern LOGICAL_DISPLAY_PATTERN = Pattern.compile(
            "\\s*Display ([0-9]+):.*");
    private static final Pattern DISPLAY_ID_PATTERN = Pattern.compile(".*mDisplayId=([0-9]+).*");
    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile(
            ".*Display(?:Info|DeviceInfo)\\{\"([^\"]+)\".*");
    private static final Pattern DISPLAY_INFO_DISPLAY_ID_PATTERN = Pattern.compile(
            ".*displayId ([0-9]+).*");
    private static final Pattern REMOTE_DASHBOARD_DISPLAY_BEFORE = Pattern.compile(
            "(?s).*mDisplayId=([0-9]+).{0,700}remote_dashboard.*");
    private static final Pattern REMOTE_DASHBOARD_DISPLAY_AFTER = Pattern.compile(
            "(?s).*remote_dashboard.{0,700}mDisplayId=([0-9]+).*");
    private static final SimpleDateFormat SESSION_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    //defines the Mode module boundary so related behavior stays readable inside one unit.
    enum Mode {
        LOG_ONLY
    }

    //defines the Listener module boundary so related behavior stays readable inside one unit.
    interface Listener {
        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        void onWazeVirtualDisplayChanged();
    }

    private static WazeVirtualDisplayCapture instance;

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static synchronized WazeVirtualDisplayCapture get(Context context) {
        if (instance == null) {
            instance = new WazeVirtualDisplayCapture(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Object lock = new Object();
    private int generation;
    private boolean active;
    private Mode activeMode = Mode.LOG_ONLY;
    private String status = "stopped";
    private String sessionName = "";
    private File sessionDir;
    private String sessionShellDir = "";
    private boolean sessionShellWritable;
    private int screenshotIndex;
    private int lastKnownDisplayId = MAIN_DISPLAY_ID;
    private boolean moveInProgress;
    private Listener listener;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private WazeVirtualDisplayCapture(Context context) {
        this.context = context.getApplicationContext();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    void setListener(Listener listener) {
        synchronized (lock) {
            this.listener = listener;
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    void start(Mode mode, String reason) {
        final int workerGeneration;
        final File workerDir;
        final String workerSessionName;
        synchronized (lock) {
            generation++;
            workerGeneration = generation;
            active = true;
            activeMode = mode == null ? Mode.LOG_ONLY : mode;
            sessionName = timestampForFile();
            NavigationLogStorage.SessionPath path =
                    NavigationLogStorage.navCaptureSessionDir(context, SESSION_DIR, sessionName);
            sessionDir = path.localDir;
            sessionShellDir = path.shellDir;
            sessionShellWritable = path.shellWritable;
            if (!sessionDir.exists() && !sessionDir.mkdirs()) {
                Log.w(TAG, "mkdir failed: " + sessionDir.getAbsolutePath());
            }
            workerDir = sessionDir;
            workerSessionName = sessionName;
            screenshotIndex = 0;
            status = "armed " + activeMode + " session=" + sessionName;
        }
        log(workerDir, "start mode=" + mode + " reason=" + safe(reason)
                + " localDir=" + workerDir.getAbsolutePath()
                + " shellDir=" + safe(sessionShellDir)
                + " shellWritable=" + sessionShellWritable);
        Thread worker = new Thread(
                () -> runCaptureLoop(workerGeneration, workerDir, workerSessionName),
                "BydHudWazeVirtualDisplay");
        worker.start();
        notifyStatusChanged();
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stop(String reason) {
        final int restoreGeneration;
        synchronized (lock) {
            if (!active) {
                status = "stopped";
                return;
            }
            active = false;
            generation++;
            restoreGeneration = generation;
            status = "stopping reason=" + safe(reason);
        }
        File dir = currentSessionDir();
        log(dir, "stop requested reason=" + safe(reason));
        Thread worker = new Thread(
                () -> restoreWazeToMain(restoreGeneration, dir, reason),
                "BydHudWazeVirtualDisplayStop");
        worker.start();
        notifyStatusChanged();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isRunning() {
        synchronized (lock) {
            return active;
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isLogOnlyForPackage(String packageName) {
        synchronized (lock) {
            return active
                    && activeMode == Mode.LOG_ONLY
                    && WAZE_PACKAGE.equals(normalizePackage(packageName));
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isMoveInProgress() {
        synchronized (lock) {
            return moveInProgress;
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isKnownOnVirtualDisplay() {
        synchronized (lock) {
            return lastKnownDisplayId > MAIN_DISPLAY_ID;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    void moveToVirtualDisplay(String reason) {
        ensureSessionForManualMove(reason);
        runDisplayMove(true, reason);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    void moveToMainDisplay(String reason) {
        runDisplayMove(false, reason);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    int findWazeTaskDisplayId() {
        try {
            WazeTask task = findWazeTask(currentSessionDir());
            if (task == null) {
                setLastKnownDisplayId(MAIN_DISPLAY_ID);
                setStatus("Waze display check: task not found");
                return -1;
            }
            setLastKnownDisplayId(task.displayId);
            setStatus("Waze display check: display=" + task.displayId);
            return task.displayId;
        } catch (IOException | SecurityException e) {
            setStatus("Waze display check failed: " + safe(e.getMessage()));
            return -1;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    String statusText() {
        synchronized (lock) {
            String dir = sessionDir == null ? "" : sessionDir.getAbsolutePath();
            return status
                    + "\nWaze display: " + lastKnownDisplayId
                    + "\nWaze move: " + (moveInProgress ? "running" : "idle")
                    + (dir.isEmpty() ? "" : "\nWaze VD dir: " + dir);
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void runCaptureLoop(int workerGeneration, File dir, String workerSessionName) {
        int targetDisplay = FALLBACK_VIRTUAL_DISPLAY_ID;
        try {
            LocalAdbBridge.ShellResult displayDump =
                    runCommand("dumpsys display", dir, "display_dump");
            if (displayDump.success()) {
                targetDisplay = parseVirtualDisplayId(displayDump.output);
            }
            setStatus("running targetDisplay=" + targetDisplay
                    + " session=" + workerSessionName);
            while (isActiveGeneration(workerGeneration)) {
                if (!currentSessionShellWritable() || currentSessionShellDir().isEmpty()) {
                    setStatus("capture unavailable: shell path not writable");
                    log(dir, "screencap skipped shell_path_unavailable");
                    sleepQuietly(CAPTURE_INTERVAL_MS);
                    continue;
                }
                WazeTask task = findWazeTask(dir);
                if (task == null) {
                    setLastKnownDisplayId(MAIN_DISPLAY_ID);
                    setStatus("armed; waiting for open Waze task; targetDisplay=" + targetDisplay);
                    log(dir, "waze_task missing");
                    sleepQuietly(CAPTURE_INTERVAL_MS);
                    continue;
                }
                setLastKnownDisplayId(task.displayId);
                if (task.displayId != targetDisplay) {
                    setStatus("armed; Waze on display " + task.displayId
                            + "; use Waze virtual display button");
                    log(dir, "waze_task not_virtual task=" + task.rootTaskId
                            + " display=" + task.displayId
                            + " targetDisplay=" + targetDisplay);
                    sleepQuietly(CAPTURE_INTERVAL_MS);
                    continue;
                }
                captureScreenshot(targetDisplay, dir, workerSessionName);
                sleepQuietly(CAPTURE_INTERVAL_MS);
            }
        } catch (IOException | SecurityException e) {
            setStatus("failed: " + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            log(dir, "fatal " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
        } finally {
            log(dir, "loop exit active=" + isActiveGeneration(workerGeneration));
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private WazeTask findWazeTask(File dir) throws IOException {
        LocalAdbBridge.ShellResult result =
                runCommand("dumpsys activity activities", dir, "activity_dump");
        if (!result.success()) {
            log(dir, "activity_dump failed " + result.shortDetail());
            return null;
        }
        return parseWazeTask(result.output);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void restoreWazeToMain(int restoreGeneration, File dir, String reason) {
        try {
            if (!shouldRestoreForGeneration(restoreGeneration)) {
                log(dir, "restore skipped stale_generation reason=" + safe(reason));
                return;
            }
            WazeTask task = findWazeTask(dir);
            if (task == null) {
                setLastKnownDisplayId(MAIN_DISPLAY_ID);
                setStatus("stopped; Waze task not found");
                log(dir, "restore skipped missing_task reason=" + safe(reason));
                return;
            }
            setLastKnownDisplayId(task.displayId);
            if (task.displayId == MAIN_DISPLAY_ID) {
                setStatus("stopped; Waze already on display 0");
                log(dir, "restore skipped already_main task=" + task.rootTaskId);
                return;
            }
            if (!shouldRestoreForGeneration(restoreGeneration)) {
                log(dir, "restore skipped restarted_before_move reason=" + safe(reason));
                return;
            }
            LocalAdbBridge.ShellResult result =
                    moveStack(task.rootTaskId, MAIN_DISPLAY_ID, dir, "restore_main");
            if (!result.success()) {
                setStatus("stopped; restore failed: " + result.shortDetail());
                return;
            }
            WazeTask confirmed = findWazeTask(dir);
            if (confirmed != null) {
                setLastKnownDisplayId(confirmed.displayId);
            }
            setStatus(confirmed != null && confirmed.displayId == MAIN_DISPLAY_ID
                    ? "stopped; Waze returned to display 0"
                    : "stopped; restore check failed");
        } catch (IOException | SecurityException e) {
            setStatus("stopped; restore failed: " + safe(e.getMessage()));
            log(dir, "restore fatal " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void ensureSessionForManualMove(String reason) {
        synchronized (lock) {
            if (active) {
                return;
            }
        }
        start(Mode.LOG_ONLY, "manual-virtual-display-" + safe(reason));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void runDisplayMove(boolean toVirtual, String reason) {
        final File dir = currentSessionDir();
        final String label = toVirtual ? "move_to_virtual" : "move_to_main";
        if (!beginMove(label + " reason=" + safe(reason))) {
            log(dir, label + " skipped already_running reason=" + safe(reason));
            return;
        }
        Thread worker = new Thread(
                () -> moveWazeTask(toVirtual, dir, reason),
                toVirtual ? "BydHudWazeVirtualDisplayMove"
                        : "BydHudWazeVirtualDisplayReturn");
        worker.start();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void moveWazeTask(boolean toVirtual, File dir, String reason) {
        String label = toVirtual ? "move_to_virtual" : "move_to_main";
        try {
            int targetDisplay = toVirtual ? resolveTargetDisplay(dir) : MAIN_DISPLAY_ID;
            WazeTask task = findWazeTask(dir);
            if (task == null) {
                setLastKnownDisplayId(MAIN_DISPLAY_ID);
                setStatus(label + " failed: Waze task not found");
                log(dir, label + " failed missing_task reason=" + safe(reason));
                return;
            }
            setLastKnownDisplayId(task.displayId);
            if (task.displayId == targetDisplay) {
                setStatus(toVirtual
                        ? "Waze already on virtual display " + targetDisplay
                        : "Waze already on display 0");
                log(dir, label + " skipped already_target task=" + task.rootTaskId
                        + " display=" + task.displayId);
                return;
            }
            LocalAdbBridge.ShellResult move =
                    moveStack(task.rootTaskId, targetDisplay, dir, label);
            if (!move.success()) {
                setStatus(label + " failed: " + move.shortDetail());
                return;
            }
            WazeTask confirmed = findWazeTask(dir);
            if (confirmed != null) {
                setLastKnownDisplayId(confirmed.displayId);
            }
            if (confirmed != null && confirmed.displayId == targetDisplay) {
                setStatus(toVirtual
                        ? "Waze on virtual display " + targetDisplay
                        : "Waze returned to display 0");
                log(dir, label + " ok task=" + task.rootTaskId
                        + " fromDisplay=" + task.displayId
                        + " toDisplay=" + targetDisplay);
            } else {
                setStatus(label + " check failed: display="
                        + (confirmed == null ? "missing" : String.valueOf(confirmed.displayId)));
                log(dir, label + " check_failed targetDisplay=" + targetDisplay);
            }
        } catch (IOException | SecurityException e) {
            setStatus(label + " failed: " + safe(e.getMessage()));
            log(dir, label + " fatal " + e.getClass().getSimpleName()
                    + " " + safe(e.getMessage()));
        } finally {
            endMove();
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private int resolveTargetDisplay(File dir) throws IOException {
        LocalAdbBridge.ShellResult displayDump =
                runCommand("dumpsys display", dir, "display_dump");
        if (displayDump.success()) {
            return parseVirtualDisplayId(displayDump.output);
        }
        return FALLBACK_VIRTUAL_DISPLAY_ID;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private LocalAdbBridge.ShellResult moveStack(
            int taskId,
            int displayId,
            File dir,
            String label) throws IOException {
        return runCommand(
                "cmd activity display move-stack " + taskId + " " + displayId,
                dir,
                label);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void captureScreenshot(int displayId, File dir, String workerSessionName)
            throws IOException {
        int index;
        synchronized (lock) {
            screenshotIndex++;
            index = screenshotIndex;
        }
        String fileName = String.format(Locale.US, "screen_%04d.png", index);
        String path = currentSessionShellDir() + "/" + fileName;
        LocalAdbBridge.ShellResult result = runCommand(
                "screencap -d " + displayId + " -p " + path,
                dir,
                "screencap");
        if (result.success()) {
            setStatus("capturing display=" + displayId
                    + " file=" + fileName
                    + " session=" + workerSessionName);
            log(dir, "screencap ok display=" + displayId + " path=" + path);
            NavigationLogStorage.enforceNavCaptureRetention(context, SESSION_DIR, workerSessionName, fileName);
        } else {
            setStatus("screencap failed: " + result.shortDetail());
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private LocalAdbBridge.ShellResult runCommand(String command, File dir, String label)
            throws IOException {
        LocalAdbBridge.ShellResult result =
                LocalAdbBridge.runRuntimeShellCommand(context, command);
        log(dir, "adb " + label
                + " command=\"" + NavCaptureStore.esc(command) + "\""
                + " exit=" + result.exitCode
                + " output=\"" + NavCaptureStore.esc(shortOutput(result.output)) + "\"");
        return result;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void setStatus(String value) {
        synchronized (lock) {
            status = value == null ? "" : value;
        }
        AppEventLogger.event(context, "waze_virtual_display " + status);
        notifyStatusChanged();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void setLastKnownDisplayId(int displayId) {
        synchronized (lock) {
            lastKnownDisplayId = displayId;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private boolean beginMove(String value) {
        synchronized (lock) {
            if (moveInProgress) {
                status = "move already running";
                notifyStatusChanged();
                return false;
            }
            moveInProgress = true;
            status = value == null ? "move running" : value;
        }
        notifyStatusChanged();
        return true;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void endMove() {
        synchronized (lock) {
            moveInProgress = false;
        }
        notifyStatusChanged();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void notifyStatusChanged() {
        Listener callback;
        synchronized (lock) {
            callback = listener;
        }
        if (callback != null) {
            callback.onWazeVirtualDisplayChanged();
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void log(File dir, String line) {
        String safeLine = safe(line);
        Log.i(TAG, safeLine);
        AppEventLogger.event(context, "waze_virtual_display " + safeLine);
        NavCaptureStore.rawEvent(context, "waze_virtual_display", WAZE_PACKAGE, safeLine);
        appendSessionLine(dir, "{"
                + "\"t\":" + SystemClock.elapsedRealtime()
                + ",\"event\":\"" + NavCaptureStore.esc(safeLine) + "\""
                + "}");
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private void appendSessionLine(File dir, String line) {
        if (dir == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, SESSION_LOG);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "session append failed: " + file.getAbsolutePath(), e);
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean isActiveGeneration(int workerGeneration) {
        synchronized (lock) {
            return active && generation == workerGeneration;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private File currentSessionDir() {
        synchronized (lock) {
            return sessionDir;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private String currentSessionShellDir() {
        synchronized (lock) {
            return sessionShellDir;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private boolean currentSessionShellWritable() {
        synchronized (lock) {
            return sessionShellWritable;
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean shouldRestoreForGeneration(int restoreGeneration) {
        synchronized (lock) {
            return !active && generation == restoreGeneration;
        }
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static WazeTask parseWazeTaskForTest(String dumpsys) {
        return parseWazeTask(dumpsys);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static int parseVirtualDisplayIdForTest(String dumpsys) {
        return parseVirtualDisplayId(dumpsys);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static WazeTask parseWazeTask(String dumpsys) {
        if (dumpsys == null || dumpsys.isEmpty()) {
            return null;
        }
        String[] lines = dumpsys.split("\\r?\\n");
        int currentRootTaskId = -1;
        int currentDisplayId = -1;
        int sectionDisplayId = -1;
        StringBuilder block = new StringBuilder();
        for (String line : lines) {
            Matcher displaySection = DISPLAY_SECTION_PATTERN.matcher(line);
            if (displaySection.matches()) {
                sectionDisplayId = parseInt(displaySection.group(1), -1);
            }
            int[] rootValues = parseTaskHeader(line, sectionDisplayId);
            if (rootValues != null) {
                WazeTask fromPrevious = taskFromBlock(currentRootTaskId, currentDisplayId, block);
                if (fromPrevious != null) {
                    return fromPrevious;
                }
                currentRootTaskId = rootValues[0];
                currentDisplayId = rootValues[1];
                block.setLength(0);
            }
            if (currentRootTaskId >= 0) {
                block.append(line).append('\n');
            }
        }
        return taskFromBlock(currentRootTaskId, currentDisplayId, block);
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
                    parseInt(root.group(2), -1)
            };
        }
        root = TASK_HASH_NO_DISPLAY_PATTERN.matcher(line);
        if (!root.matches()) {
            return null;
        }
        int displayId = fallbackDisplayId < 0 ? MAIN_DISPLAY_ID : fallbackDisplayId;
        return new int[]{
                parseInt(root.group(1), -1),
                displayId
        };
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static WazeTask taskFromBlock(int rootTaskId, int displayId, StringBuilder block) {
        if (rootTaskId < 0 || block == null) {
            return null;
        }
        String value = block.toString();
        if (value.contains("com.waze/")
                || value.contains("packageName=com.waze")
                || value.contains(":com.waze ")
                || value.contains(":com.waze\n")
                || value.endsWith(":com.waze")) {
            return new WazeTask(rootTaskId, displayId < 0 ? MAIN_DISPLAY_ID : displayId);
        }
        return null;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static int parseVirtualDisplayId(String dumpsys) {
        String safe = dumpsys == null ? "" : dumpsys;
        int primary = findDisplayByName(safe, PRIMARY_VIRTUAL_DISPLAY_NAME, false);
        if (primary >= 0) {
            return primary;
        }
        int shared = findDisplayByName(safe, SHARED_VIRTUAL_DISPLAY_PREFIX, true);
        if (shared >= 0) {
            return shared;
        }
        Matcher before = REMOTE_DASHBOARD_DISPLAY_BEFORE.matcher(safe);
        if (before.matches()) {
            return parseInt(before.group(1), FALLBACK_VIRTUAL_DISPLAY_ID);
        }
        Matcher after = REMOTE_DASHBOARD_DISPLAY_AFTER.matcher(safe);
        if (after.matches()) {
            return parseInt(after.group(1), FALLBACK_VIRTUAL_DISPLAY_ID);
        }
        String[] lines = safe.split("\\r?\\n");
        int lastDisplayId = -1;
        for (String line : lines) {
            Matcher display = DISPLAY_ID_PATTERN.matcher(line);
            if (display.matches()) {
                lastDisplayId = parseInt(display.group(1), -1);
            }
            if (line.contains("remote_dashboard") && lastDisplayId >= 0) {
                return lastDisplayId;
            }
        }
        return FALLBACK_VIRTUAL_DISPLAY_ID;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int findDisplayByName(String dumpsys, String name, boolean prefix) {
        String[] lines = dumpsys.split("\\r?\\n");
        int currentDisplayId = -1;
        for (String line : lines) {
            Matcher logicalDisplay = LOGICAL_DISPLAY_PATTERN.matcher(line);
            if (logicalDisplay.matches()) {
                currentDisplayId = parseInt(logicalDisplay.group(1), -1);
            }
            Matcher displayId = DISPLAY_ID_PATTERN.matcher(line);
            if (displayId.matches()) {
                currentDisplayId = parseInt(displayId.group(1), currentDisplayId);
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
        return -1;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String timestampForFile() {
        synchronized (SESSION_FORMAT) {
            return SESSION_FORMAT.format(new Date());
        }
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
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

    //defines the WazeTask module boundary so related behavior stays readable inside one unit.
    static final class WazeTask {
        final int rootTaskId;
        final int displayId;

        WazeTask(int rootTaskId, int displayId) {
            this.rootTaskId = rootTaskId;
            this.displayId = displayId;
        }
    }
}
