package com.bydhud.app;

//parses activity tasks so runtime can detect whether navigation apps are visible or backgrounded.

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the NavAppTaskScanner module boundary so related behavior stays readable inside one unit.
final class NavAppTaskScanner {
    private static final String COMMAND = "dumpsys activity activities";
    private static final int MAIN_DISPLAY_ID = 0;
    private static final int IMPORTANCE_NOT_RUNNING = Integer.MAX_VALUE;
    private static final Pattern DISPLAY_SECTION_PATTERN =
            Pattern.compile(".*Display #([0-9]+).*");
    private static final Pattern ROOT_TASK_PATTERN =
            Pattern.compile(".*RootTask id=([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern ROOT_TASK_HASH_PATTERN =
            Pattern.compile(".*RootTask\\{[^#]*#([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern TASK_HASH_PATTERN =
            Pattern.compile(".*Task\\{[^#]*#([0-9]+).*displayId=([0-9]+).*");
    private static final Pattern TASK_HASH_NO_DISPLAY_PATTERN =
            Pattern.compile(".*Task\\{[^#]*#([0-9]+).*");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)(?=[/\\s}:,]|$)");
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static NavAppTaskScanner instance;

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static synchronized NavAppTaskScanner get(Context context) {
        if (instance == null) {
            instance = new NavAppTaskScanner(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Object lock = new Object();
    private Snapshot snapshot;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavAppTaskScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    Snapshot currentSnapshot() {
        synchronized (lock) {
            if (snapshot != null) {
                return snapshot;
            }
        }
        Snapshot processOnly = scanProcessOnly("initial");
        synchronized (lock) {
            if (snapshot == null) {
                snapshot = processOnly;
            }
            return snapshot;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    Snapshot forceScan() {
        Snapshot scanned = scanWithTasks();
        synchronized (lock) {
            snapshot = scanned;
        }
        return scanned;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private Snapshot scanWithTasks() {
        long now = System.currentTimeMillis();
        Map<String, RowBuilder> rows = collectProcessRows();
        String source = "task";
        String status = "ok";
        boolean taskOnly = false;
        try {
            LocalAdbBridge.ShellResult result =
                    LocalAdbBridge.runRuntimeShellCommand(context, COMMAND);
            if (result.success()) {
                parseTaskRows(result.output, rows);
                taskOnly = true;
            } else {
                source = "process";
                status = "adb " + result.shortDetail();
            }
        } catch (IOException | SecurityException e) {
            source = "process";
            status = e.getClass().getSimpleName();
        }
        Snapshot result = buildSnapshot(rows, now, source, status, taskOnly);
        AppEventLogger.event(context, "apps_scan source=" + result.source
                + " rows=" + result.rows.size()
                + " status=" + safe(result.status));
        return result;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private Snapshot scanProcessOnly(String status) {
        long now = System.currentTimeMillis();
        return buildSnapshot(collectProcessRows(), now, "process", status, false);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private Map<String, RowBuilder> collectProcessRows() {
        Map<String, RowBuilder> rows = new HashMap<>();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes =
                manager == null ? null : manager.getRunningAppProcesses();
        if (processes == null) {
            return rows;
        }
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            String[] packages = process.pkgList == null || process.pkgList.length == 0
                    ? new String[]{process.processName}
                    : process.pkgList;
            for (String packageName : packages) {
                String normalized = normalizePackage(packageName);
                if (!isVisiblePackage(normalized)) {
                    continue;
                }
                RowBuilder row = rows.get(normalized);
                if (row == null) {
                    row = new RowBuilder(normalized);
                    rows.put(normalized, row);
                }
                if (!row.hasProcess || process.importance < row.importance) {
                    row.hasProcess = true;
                    row.processName = process.processName == null ? normalized : process.processName;
                    row.importance = process.importance;
                }
            }
        }
        return rows;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private void parseTaskRows(String dumpsys, Map<String, RowBuilder> rows) {
        String safeDump = dumpsys == null ? "" : dumpsys;
        String[] lines = safeDump.split("\\r?\\n");
        int currentTaskId = -1;
        int currentDisplayId = -1;
        int sectionDisplayId = -1;
        StringBuilder block = new StringBuilder();
        for (String line : lines) {
            Matcher displaySection = DISPLAY_SECTION_PATTERN.matcher(line);
            if (displaySection.matches()) {
                sectionDisplayId = parseInt(displaySection.group(1), -1);
            }
            int[] task = parseTaskHeader(line, sectionDisplayId);
            if (task != null) {
                parseTaskBlock(currentTaskId, currentDisplayId, block, rows);
                currentTaskId = task[0];
                currentDisplayId = task[1];
                block.setLength(0);
            }
            if (currentTaskId >= 0) {
                block.append(line).append('\n');
            }
        }
        parseTaskBlock(currentTaskId, currentDisplayId, block, rows);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private void parseTaskBlock(
            int taskId,
            int displayId,
            StringBuilder block,
            Map<String, RowBuilder> rows) {
        if (taskId < 0 || block == null || block.length() == 0) {
            return;
        }
        String value = block.toString();
        Set<String> packages = extractInstalledPackages(value);
        if (packages.isEmpty()) {
            return;
        }
        boolean visible = parseVisible(value);
        int safeDisplayId = displayId < 0 ? MAIN_DISPLAY_ID : displayId;
        for (String packageName : packages) {
            RowBuilder row = rows.get(packageName);
            if (row == null) {
                row = new RowBuilder(packageName);
                rows.put(packageName, row);
            }
            row.hasTask = true;
            row.visible = visible;
            row.taskId = taskId;
            row.displayId = safeDisplayId;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private Set<String> extractInstalledPackages(String value) {
        Set<String> packages = new HashSet<>();
        Matcher matcher = PACKAGE_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String normalized = normalizePackage(matcher.group(1));
            if (isVisiblePackage(normalized) && isInstalledPackage(normalized)) {
                packages.add(normalized);
            }
        }
        return packages;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean isVisiblePackage(String packageName) {
        return !packageName.isEmpty()
                && !NavAppFilter.shouldHideFromCaptureList(context, packageName);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean isInstalledPackage(String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
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

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static boolean parseVisible(String block) {
        String safe = block == null ? "" : block;
        if (safe.contains("visible=false") || safe.contains("isVisible=false")) {
            return false;
        }
        return safe.contains("visible=true") || safe.contains("isVisible=true") || !safe.isEmpty();
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private Snapshot buildSnapshot(
            Map<String, RowBuilder> rows,
            long scannedAtMs,
            String source,
            String status,
            boolean taskOnly) {
        List<Row> result = new ArrayList<>();
        for (RowBuilder builder : rows.values()) {
            if (!builder.hasTask && !builder.hasProcess) {
                continue;
            }
            if (taskOnly && !builder.hasTask) {
                continue;
            }
            result.add(builder.toRow());
        }
        Collections.sort(result, new Comparator<Row>() {
            @Override
            //keeps this step explicit so callers can rely on one documented behavior boundary.
            public int compare(Row left, Row right) {
                if (left.hasTask != right.hasTask) {
                    return left.hasTask ? -1 : 1;
                }
                int byDisplay = Integer.compare(left.displayId, right.displayId);
                if (byDisplay != 0) {
                    return byDisplay;
                }
                int byImportance = Integer.compare(left.importance, right.importance);
                if (byImportance != 0) {
                    return byImportance;
                }
                return left.packageName.compareToIgnoreCase(right.packageName);
            }
        });
        return new Snapshot(result, scannedAtMs, formatScanTime(scannedAtMs), source, status);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static String formatScanTime(long timeMs) {
        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(timeMs));
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

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    //models Snapshot data here so transport and parser layers share a stable contract.
    static final class Snapshot {
        final List<Row> rows;
        final long scannedAtMs;
        final String lastScanText;
        final String source;
        final String status;

        Snapshot(List<Row> rows, long scannedAtMs, String lastScanText, String source, String status) {
            this.rows = rows == null ? Collections.<Row>emptyList() : rows;
            this.scannedAtMs = scannedAtMs;
            this.lastScanText = lastScanText == null ? "--:--:--" : lastScanText;
            this.source = source == null ? "" : source;
            this.status = status == null ? "" : status;
        }
    }

    //defines the Row module boundary so related behavior stays readable inside one unit.
    static final class Row {
        final String packageName;
        final String processName;
        final int importance;
        final boolean hasProcess;
        final boolean hasTask;
        final int taskId;
        final int displayId;
        final boolean visible;

        Row(
                String packageName,
                String processName,
                int importance,
                boolean hasProcess,
                boolean hasTask,
                int taskId,
                int displayId,
                boolean visible) {
            this.packageName = packageName == null ? "" : packageName;
            this.processName = processName == null ? "" : processName;
            this.importance = importance;
            this.hasProcess = hasProcess;
            this.hasTask = hasTask;
            this.taskId = taskId;
            this.displayId = displayId;
            this.visible = visible;
        }
    }

    //defines the RowBuilder module boundary so related behavior stays readable inside one unit.
    private static final class RowBuilder {
        final String packageName;
        String processName = "";
        int importance = IMPORTANCE_NOT_RUNNING;
        boolean hasProcess;
        boolean hasTask;
        int taskId = -1;
        int displayId = MAIN_DISPLAY_ID;
        boolean visible;

        RowBuilder(String packageName) {
            this.packageName = packageName;
        }

        //renders this UI section here so screen structure stays traceable during preview and car testing.
        Row toRow() {
            return new Row(
                    packageName,
                    processName.isEmpty() ? (hasTask ? "task" : "not running") : processName,
                    importance,
                    hasProcess,
                    hasTask,
                    taskId,
                    displayId,
                    visible);
        }
    }
}
