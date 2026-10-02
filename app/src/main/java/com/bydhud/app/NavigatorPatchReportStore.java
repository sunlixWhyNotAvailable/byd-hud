package com.bydhud.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Durable patch transaction reports, separate from disposable patch staging and navigation logs. */
final class NavigatorPatchReportStore {
    private static final Object PROCESS_LOCK = new Object();
    private static final Object WRITE_ORDER_LOCK = new Object();
    private static final Object PENDING_LOCK = new Object();
    private static final Object CACHE_LOCK = new Object();
    private static final ExecutorService ASYNC_STAGE_WRITER = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "NavigatorPatchReportWriter");
                thread.setDaemon(true);
                return thread;
            });
    private static final List<Future<?>> PENDING_STAGES = new ArrayList<>();
    private static Map<String, Long> cachedDays = Collections.emptyMap();
    private static Map<String, Long> cachedReportSizes = Collections.emptyMap();
    private static long cachedBytes;
    private static boolean cacheLoaded;
    private static final String DIRECTORY = "navigator-patch-reports";
    private static final String LOCK_FILE = ".reports.lock";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;
    private static final String ZIP_ENTRY = "navigator-patch-reports.json";
    private static final String INCOMPLETE_ENTRY = "INCOMPLETE-NAVIGATOR-PATCH-REPORTS.txt";

    private NavigatorPatchReportStore() {
    }

    interface Work<T> {
        T run(File directory) throws IOException;
    }

    static final class ExportResult {
        final String entry;
        final int reportCount;
        final long bytes;
        final boolean incomplete;
        final String error;

        ExportResult(String entry, int reportCount, long bytes, boolean incomplete, String error) {
            this.entry = entry;
            this.reportCount = reportCount;
            this.bytes = bytes;
            this.incomplete = incomplete;
            this.error = error == null ? "" : error;
        }
    }

    static final class CachedSummary {
        final Map<String, Long> days;
        final int reportCount;
        final long reportBytes;

        CachedSummary(Map<String, Long> days, int reportCount, long reportBytes) {
            this.days = days;
            this.reportCount = reportCount;
            this.reportBytes = reportBytes;
        }
    }

    /** Creates one report per persisted operation token. Replayed begin calls preserve prior data. */
    static void begin(Context context, String operationId, String profile, String kind,
            long startedAt, JSONObject metadata) throws IOException {
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
            withLock(context, directory -> {
                File file = reportFile(directory, operationId);
                JSONObject existing = read(file);
                if (existing != null) {
                    if (!operationId.equals(existing.optString("operationId"))) {
                        throw new IOException("navigator patch report identity mismatch");
                    }
                    return null;
                }
                long time = startedAt > 0L ? startedAt : System.currentTimeMillis();
                JSONObject report = new JSONObject();
                try {
                    report.put("schemaVersion", SCHEMA_VERSION)
                            .put("operationId", operationId)
                            .put("profile", clean(profile, 80))
                            .put("kind", clean(kind, 40))
                            .put("day", NavCaptureStore.todayDir(time))
                            .put("startedAt", time)
                            .put("updatedAt", time)
                            .put("status", "IN_PROGRESS")
                            .put("currentStage", "STARTED")
                            .put("metadata", copy(metadata == null ? new JSONObject() : metadata))
                            .put("stages", new JSONArray())
                            .put("components", new JSONArray());
                } catch (JSONException error) {
                    throw new IOException("cannot create navigator patch report", error);
                }
                write(file, report);
                updateCache(report);
                return null;
            });
        }
    }

    /** Appends an ordered lifecycle event. Persistence errors propagate to the caller. */
    static void recordStage(Context context, String operationId, String stage,
            String outcome, String reason, JSONObject fields) throws IOException {
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
            recordStageNow(context, operationId, stage, outcome, reason, fields);
        }
    }

    /** Queue lightweight UI/lifecycle transitions so they never take a file lock on the main thread. */
    static Future<?> recordStageAsync(Context context, String operationId, String stage,
            String outcome, String reason, JSONObject fields) throws IOException {
        if (context == null) throw new IOException("missing navigator patch report context");
        Context app = context.getApplicationContext();
        JSONObject savedFields = fields == null ? null : copy(fields);
        synchronized (PENDING_LOCK) {
            Future<?> future = ASYNC_STAGE_WRITER.submit(() -> {
                recordStageNow(app == null ? context : app, operationId,
                        stage, outcome, reason, savedFields);
                return null;
            });
            PENDING_STAGES.add(future);
            return future;
        }
    }

    /** Waits for queued lifecycle writes and surfaces their first persistence failure. */
    static void awaitPendingWrites() throws IOException {
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
        }
    }

    private static void recordStageNow(Context context, String operationId, String stage,
            String outcome, String reason, JSONObject fields) throws IOException {
        mutate(context, operationId, report -> {
            String safeStage = clean(stage, 100);
            String safeOutcome = clean(outcome, 80).toUpperCase(Locale.ROOT);
            JSONObject event = event(safeStage, safeOutcome, reason, fields);
            report.getJSONArray("stages").put(event);
            boolean alreadyTerminal = isTerminal(report.optString("status"));
            if (!alreadyTerminal) report.put("currentStage", safeStage);
            report.put("updatedAt", System.currentTimeMillis());
            if (alreadyTerminal) {
                return;
            } else if ("PREPARED".equals(safeStage) && isSuccess(safeOutcome)) {
                report.put("status", "PREPARED");
            } else if ("RECOVERY_REQUIRED".equals(safeOutcome)) {
                report.put("status", "RECOVERY_REQUIRED");
            } else if (isTerminalStage(safeStage)) {
                report.put("status", canonicalTerminal(safeStage))
                        .put("currentStage", "TERMINAL")
                        .put("completedAt", System.currentTimeMillis());
            } else if (isFailureTerminal(safeOutcome)
                    || ("TERMINAL".equals(safeStage) && isSuccess(safeOutcome))) {
                report.put("status", canonicalTerminal(safeOutcome));
                report.put("completedAt", System.currentTimeMillis());
            } else if (!"PREPARED".equals(report.optString("status"))) {
                report.put("status", "IN_PROGRESS");
            }
        });
    }

    /** Preserves every component classification and action from the transaction. */
    static void recordComponent(Context context, String operationId, String component,
            String before, String action, String outcome, String reason, JSONObject fields)
            throws IOException {
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
            mutate(context, operationId, report -> {
                JSONObject result = event(clean(component, 100),
                        clean(outcome, 80).toUpperCase(Locale.ROOT), reason, fields);
                result.put("before", clean(before, 80))
                        .put("action", clean(action, 80));
                report.getJSONArray("components").put(result);
                report.put("updatedAt", System.currentTimeMillis());
            });
        }
    }

    /** Writes the final transaction outcome after the last install, verify, cancel, or recovery step. */
    static void finish(Context context, String operationId, String outcome, String reason)
            throws IOException {
        String terminal = canonicalTerminal(clean(outcome, 80).toUpperCase(Locale.ROOT));
        if (!isTerminal(terminal)) throw new IOException("invalid navigator patch terminal outcome");
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
            mutate(context, operationId, report -> {
                long now = System.currentTimeMillis();
                report.getJSONArray("stages").put(event("TERMINAL", terminal, reason, null));
                report.put("currentStage", "TERMINAL")
                        .put("status", terminal)
                        .put("updatedAt", now)
                        .put("completedAt", now);
            });
        }
    }

    /** Returns one consistent persisted report, or null if the operation was never started. */
    static JSONObject snapshot(Context context, String operationId) throws IOException {
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
            return withLock(context, directory -> {
                JSONObject report = read(reportFile(directory, operationId));
                return report == null ? null : addSnapshotStatus(report);
            });
        }
    }

    /** Returns detached snapshots of all retained attempts in start order. */
    static List<JSONObject> snapshotAll(Context context) throws IOException {
        synchronized (WRITE_ORDER_LOCK) {
            drainPendingLocked();
            return withLock(context, directory -> {
                List<JSONObject> reports = new ArrayList<>();
                File[] files = directory.listFiles((parent, name) -> name.endsWith(".json"));
                if (files == null) throw new IOException("cannot list navigator patch reports");
                for (File file : files) {
                    JSONObject report = read(file);
                    if (report != null) reports.add(addSnapshotStatus(report));
                }
                reports.sort((left, right) -> {
                    int byStart = Long.compare(left.optLong("startedAt"), right.optLong("startedAt"));
                    return byStart != 0 ? byStart
                            : left.optString("operationId").compareTo(right.optString("operationId"));
                });
                refreshCache(reports);
                return Collections.unmodifiableList(reports);
            });
        }
    }

    /** Day-to-latest-write map used to expose report-only days in Storage export selection. */
    static Map<String, Long> reportDays(Context context) throws IOException {
        Map<String, Long> days = new LinkedHashMap<>();
        for (JSONObject report : snapshotAll(context)) {
            String day = report.optString("day");
            if (!day.matches("\\d{8}")) continue;
            days.put(day, Math.max(days.getOrDefault(day, 0L), report.optLong("updatedAt")));
        }
        return Collections.unmodifiableMap(days);
    }

    /** Nonblocking UI preflight view; background storage scans refresh it from durable reports. */
    static CachedSummary cachedSummary() {
        synchronized (CACHE_LOCK) {
            return new CachedSummary(Collections.unmodifiableMap(new LinkedHashMap<>(cachedDays)),
                    cachedReportSizes.size(), cachedBytes);
        }
    }

    /** Used immediately before handing a patched set to Android's installer. */
    static String requirePrepared(Context context, String operationId) throws IOException {
        JSONObject report = snapshot(context, operationId);
        if (report == null) throw new IOException("navigator patch report missing before install");
        if (!"PREPARED".equals(report.optString("status"))) {
            throw new IOException("navigator patch report has no durable prepared output");
        }
        JSONArray stages = report.optJSONArray("stages");
        if (stages == null) throw new IOException("navigator patch report has no stages");
        for (int index = stages.length() - 1; index >= 0; index--) {
            JSONObject event = stages.optJSONObject(index);
            if (event == null || !"PREPARED".equals(event.optString("stage"))
                    || !isSuccess(event.optString("outcome"))) continue;
            JSONObject fields = event.optJSONObject("fields");
            JSONObject output = fields == null ? null : fields.optJSONObject("output");
            String sha256 = output == null ? ""
                    : output.optString("sha256", output.optString("apkSetSha256", ""));
            if (sha256.matches("(?i)[0-9a-f]{64}")) return sha256.toLowerCase(Locale.ROOT);
            throw new IOException("prepared report is missing output APK-set fingerprint");
        }
        throw new IOException("navigator patch report has no successful prepared stage");
    }

    /** Complete JSON snapshot for configuration and normal-log ZIPs; history ignores selected days. */
    static JSONObject exportSnapshot(Context context) throws IOException {
        List<JSONObject> reports = snapshotAll(context);
        boolean incomplete = false;
        JSONArray entries = new JSONArray();
        for (JSONObject report : reports) {
            entries.put(report);
            incomplete |= report.optBoolean("incomplete");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("schemaVersion", SCHEMA_VERSION)
                    .put("generatedAt", System.currentTimeMillis())
                    .put("status", incomplete ? "INCOMPLETE" : "COMPLETE")
                    .put("reportCount", entries.length())
                    .put("incompleteReports", incomplete)
                    .put("reports", entries);
            return result;
        } catch (JSONException error) {
            throw new IOException("cannot serialize navigator patch report history", error);
        }
    }

    /** Writes report history and an explicit marker for active/interrupted reports to a ZIP. */
    static ExportResult writeReports(Context context, ZipOutputStream zip) throws IOException {
        if (context == null) {
            JSONObject unavailable = new JSONObject();
            try {
                unavailable.put("schemaVersion", SCHEMA_VERSION)
                        .put("generatedAt", System.currentTimeMillis())
                        .put("status", "ERROR")
                        .put("reportCount", 0)
                        .put("incompleteReports", true)
                        .put("reportStorageError", "report context unavailable")
                        .put("reports", new JSONArray());
            } catch (JSONException error) {
                throw new IOException("cannot describe unavailable navigator patch reports", error);
            }
            return writeReports(null, zip, unavailable);
        }
        return writeReports(context, zip, exportSnapshot(context));
    }

    static ExportResult writeReports(Context context, ZipOutputStream zip, JSONObject raw)
            throws IOException {
        String directory = exportDirectory(raw);
        String entry = directory + ZIP_ENTRY;
        JSONObject safe = VehicleConfigurationZip.sanitizeJsonForExport(
                context, raw, ZIP_ENTRY);
        byte[] bytes = (safe.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        putEntry(zip, entry, bytes);
        boolean incomplete = safe.optBoolean("incompleteReports");
        if (incomplete) {
            String storageError = safe.optString("reportStorageError", "");
            String marker = storageError.isEmpty()
                    ? "At least one navigator patch operation is still active, prepared but "
                            + "not installed, interrupted, or awaiting recovery. See " + entry
                            + " for its current stage and outcome.\n"
                    : "Navigator patch report history could not be read: " + storageError
                            + ". See " + entry + " for export status.\n";
            putEntry(zip, directory + INCOMPLETE_ENTRY, marker.getBytes(StandardCharsets.UTF_8));
        }
        return new ExportResult(entry, safe.optInt("reportCount"), bytes.length, incomplete,
                safe.optString("reportStorageError", ""));
    }

    // Export-wide snapshots belong to the export date, not an arbitrary selected log day.
    static String exportDirectory(JSONObject snapshot) {
        return NavCaptureStore.todayDir(snapshot.optLong("generatedAt", System.currentTimeMillis()))
                + "/diagnostics/";
    }

    private interface Mutator {
        void apply(JSONObject report) throws IOException, JSONException;
    }

    private static void mutate(Context context, String operationId, Mutator mutator)
            throws IOException {
        withLock(context, directory -> {
            File file = reportFile(directory, operationId);
            JSONObject report = read(file);
            if (report == null) throw new IOException("navigator patch report missing");
            if (!operationId.equals(report.optString("operationId"))) {
                throw new IOException("navigator patch report identity mismatch");
            }
            try {
                mutator.apply(report);
            } catch (JSONException error) {
                throw new IOException("cannot update navigator patch report", error);
            }
            write(file, report);
            updateCache(report);
            return null;
        });
    }

    private static void drainPendingLocked() throws IOException {
        List<Future<?>> pending;
        synchronized (PENDING_LOCK) {
            if (PENDING_STAGES.isEmpty()) return;
            pending = new ArrayList<>(PENDING_STAGES);
        }
        List<Future<?>> completed = new ArrayList<>();
        IOException failure = null;
        for (Future<?> write : pending) {
            try {
                write.get();
                completed.add(write);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("waiting for navigator patch report writes interrupted");
            } catch (ExecutionException error) {
                completed.add(write);
                if (failure == null) {
                    Throwable cause = error.getCause();
                    failure = cause instanceof IOException ? (IOException) cause
                            : new IOException("navigator patch report write failed", cause);
                }
            }
        }
        synchronized (PENDING_LOCK) {
            PENDING_STAGES.removeAll(completed);
        }
        if (failure != null) throw failure;
    }

    private static void refreshCache(List<JSONObject> reports) {
        synchronized (CACHE_LOCK) {
            Map<String, Long> days = new LinkedHashMap<>();
            Map<String, Long> sizes = new LinkedHashMap<>();
            long totalBytes = 0L;
            for (JSONObject report : reports) {
                String id = report.optString("operationId");
                String day = report.optString("day");
                if (id.isEmpty() || !day.matches("\\d{8}")) continue;
                days.put(day, Math.max(days.getOrDefault(day, 0L),
                        report.optLong("updatedAt")));
                long size = report.toString().getBytes(StandardCharsets.UTF_8).length;
                sizes.put(id, size);
                totalBytes += size;
            }
            cachedDays = days;
            cachedReportSizes = sizes;
            cachedBytes = totalBytes;
            cacheLoaded = true;
        }
    }

    private static void updateCache(JSONObject report) {
        synchronized (CACHE_LOCK) {
            if (!cacheLoaded) return;
            String id = report.optString("operationId");
            String day = report.optString("day");
            if (id.isEmpty() || !day.matches("\\d{8}")) return;
            long size = report.toString().getBytes(StandardCharsets.UTF_8).length;
            Long previous = cachedReportSizes.put(id, size);
            cachedBytes += size - (previous == null ? 0L : previous);
            Map<String, Long> days = new LinkedHashMap<>(cachedDays);
            days.put(day, Math.max(days.getOrDefault(day, 0L), report.optLong("updatedAt")));
            cachedDays = days;
        }
    }

    private static JSONObject event(String stage, String outcome, String reason, JSONObject fields)
            throws IOException {
        JSONObject value = new JSONObject();
        try {
            value.put("stage", stage)
                    .put("outcome", outcome)
                    .put("at", System.currentTimeMillis())
                    .put("reason", clean(reason, 1000));
            if (fields != null && fields.length() > 0) value.put("fields", copy(fields));
            return value;
        } catch (JSONException error) {
            throw new IOException("cannot serialize navigator patch event", error);
        }
    }

    private static JSONObject addSnapshotStatus(JSONObject source) throws IOException {
        JSONObject report = copy(source);
        String status = report.optString("status", "IN_PROGRESS");
        boolean incomplete = !isTerminal(status);
        try {
            report.put("incomplete", incomplete);
            if (incomplete) report.put("incompleteReason", report.optString("currentStage"));
            return report;
        } catch (JSONException error) {
            throw new IOException("cannot prepare navigator patch report snapshot", error);
        }
    }

    private static boolean isSuccess(String outcome) {
        String value = outcome == null ? "" : outcome.toUpperCase(Locale.ROOT);
        return "SUCCESS".equals(value) || "SUCCEEDED".equals(value)
                || "COMPLETE".equals(value) || "COMPLETED".equals(value)
                || "PASS".equals(value) || "VERIFIED".equals(value)
                || "PREPARED".equals(value);
    }

    private static boolean isTerminal(String outcome) {
        String value = outcome == null ? "" : outcome.toUpperCase(Locale.ROOT);
        return "COMPLETE".equals(value) || "COMPLETED".equals(value)
                || "SUCCEEDED".equals(value) || "SUCCESS".equals(value)
                || "FAILED".equals(value) || "CANCELLED".equals(value)
                || "CANCELED".equals(value) || "INTERRUPTED".equals(value)
                || "RECOVERED".equals(value) || "RESTORED".equals(value);
    }

    private static boolean isFailureTerminal(String outcome) {
        String value = outcome == null ? "" : outcome.toUpperCase(Locale.ROOT);
        return "FAILED".equals(value) || "CANCELLED".equals(value)
                || "CANCELED".equals(value) || "INTERRUPTED".equals(value)
                || "RECOVERED".equals(value) || "RESTORED".equals(value);
    }

    private static boolean isTerminalStage(String stage) {
        String value = stage == null ? "" : stage.toUpperCase(Locale.ROOT);
        return "COMPLETE".equals(value) || "COMPLETED".equals(value)
                || "SUCCESS".equals(value) || "SUCCEEDED".equals(value)
                || "FAILED".equals(value) || "CANCELLED".equals(value)
                || "CANCELED".equals(value) || "INTERRUPTED".equals(value)
                || "RECOVERED".equals(value) || "RESTORED".equals(value);
    }

    private static String canonicalTerminal(String outcome) {
        if ("COMPLETED".equals(outcome) || "SUCCESS".equals(outcome)
                || "SUCCEEDED".equals(outcome)) return "COMPLETE";
        if ("CANCELED".equals(outcome)) return "CANCELLED";
        return outcome;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ');
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static JSONObject copy(JSONObject value) throws IOException {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new IOException("invalid navigator patch report JSON", error);
        }
    }

    private static File reportFile(File directory, String operationId) throws IOException {
        if (operationId == null || operationId.trim().isEmpty()) {
            throw new IOException("missing navigator patch operation id");
        }
        return new File(directory, sha256(operationId) + ".json");
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte next : digest) hex.append(String.format(Locale.ROOT, "%02x", next & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static JSONObject read(File file) throws IOException {
        return file.exists() ? readJson(file) : null;
    }

    private static JSONObject readJson(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_REPORT_BYTES) {
                    throw new IOException("navigator patch report exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IOException("navigator patch report is corrupt", error);
        }
    }

    private static void write(File file, JSONObject report) throws IOException {
        byte[] bytes = (report.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_BYTES) {
            throw new IOException("navigator patch report exceeds size limit");
        }
        File temporary = new File(file.getPath() + ".tmp");
        if (temporary.exists()) deleteChecked(temporary);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            if (!hasContents(file, bytes)) {
                throw new IOException("navigator patch report read-back did not match saved data");
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("atomic navigator patch report replacement unavailable",
                    unsupported);
        } catch (IOException | RuntimeException error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("cannot persist navigator patch report", error);
        }
    }

    private static boolean hasContents(File file, byte[] expected) throws IOException {
        if (!file.isFile() || file.length() != expected.length) return false;
        return java.util.Arrays.equals(expected, Files.readAllBytes(file.toPath()));
    }

    private static void deleteChecked(File file) throws IOException {
        if (file.exists() && !file.delete() && file.exists()) {
            throw new IOException("cannot remove stale navigator patch report file");
        }
    }

    private static <T> T withLock(Context context, Work<T> work) throws IOException {
        if (context == null) throw new IOException("missing navigator patch report context");
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        synchronized (PROCESS_LOCK) {
            File directory = new File(app.getFilesDir(), DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("cannot create navigator patch report directory");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("navigator patch report operation interrupted");
            }
            File lockFile = new File(directory, LOCK_FILE);
            try (RandomAccessFile access = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = access.getChannel()) {
                FileLock lock;
                try {
                    lock = channel.lock();
                } catch (OverlappingFileLockException error) {
                    throw new IOException("navigator patch report lock unavailable", error);
                }
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException(
                                "navigator patch report operation interrupted");
                    }
                    return work.run(directory);
                } finally {
                    lock.release();
                }
            }
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
