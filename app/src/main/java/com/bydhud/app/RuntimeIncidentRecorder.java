package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Incident-only evidence, independent of the navigation writer and storage topology lock. */
final class RuntimeIncidentRecorder {
    static final String FILE_NAME = "runtime-incidents.jsonl";
    private static final String PERSISTENCE_FILE = "persistence-uncertain.jsonl";
    private static final String TAG = "BydHudIncident";
    private static final Object FILE_LOCK = new Object();
    private static volatile RuntimeIncidentRecorder instance;
    private final File directory;
    private final ScheduledThreadPoolExecutor executor;
    private final Map<Watch, Boolean> watches = new ConcurrentHashMap<>();
    private final Map<String, String> lossDays = new ConcurrentHashMap<>();
    private boolean scheduled;
    private final String process = java.util.UUID.randomUUID().toString();
    private long nextIncident;
    private volatile boolean persistenceUncertain;

    abstract static class Watch {
        final String name;
        final long thresholdMs;
        private String incident;
        private String day;
        private long stalledToken, stalledSince;
        Watch(String name, long thresholdMs) { this.name = name; this.thresholdMs = thresholdMs; }
        abstract boolean pending();
        abstract long token();
        abstract long since();
        abstract JSONObject details() throws Exception;
    }

    private RuntimeIncidentRecorder(Context context) {
        directory = directory(context);
        executor = new ScheduledThreadPoolExecutor(1, work -> {
            Thread thread = new Thread(work, "BydHudIncidentRecorder");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    static synchronized void initialize(Context context) {
        if (instance == null) instance = new RuntimeIncidentRecorder(context.getApplicationContext());
    }

    static void watch(Watch watch) {
        RuntimeIncidentRecorder recorder = instance;
        if (recorder == null) return;
        synchronized (recorder) {
            recorder.watches.put(watch, true);
            if (!recorder.scheduled) {
                recorder.scheduled = true;
                recorder.executor.schedule(recorder::poll, 1, TimeUnit.SECONDS);
            }
        }
    }

    private void poll() {
        long now = SystemClock.elapsedRealtime();
        try {
            for (Watch watch : watches.keySet()) {
                try { sample(watch, now); }
                catch (Exception error) { Log.w(TAG, "incident_sample_failed " + watch.name, error); }
                synchronized (this) {
                    if (!watch.pending() && watch.incident == null) watches.remove(watch);
                }
            }
        } finally {
            synchronized (this) {
                scheduled = false;
                if (!watches.isEmpty()) {
                    scheduled = true;
                    try {
                        executor.schedule(this::poll, 1, TimeUnit.SECONDS);
                    } catch (RuntimeException rejected) {
                        scheduled = false;
                        throw rejected;
                    }
                }
            }
        }
    }

    private void sample(Watch watch, long now) throws Exception {
        boolean pending = watch.pending();
        long token = watch.token();
        if (watch.incident != null && (!pending || token != watch.stalledToken)) {
            persist(watch.day, record("recovery", watch.name, now)
                    .put("incident", watch.incident).put("durationMs", now - watch.stalledSince), 0);
            watch.incident = null;
        }
        long since = watch.since();
        if (!pending || watch.incident != null || since < 0 || now - since < watch.thresholdMs) return;
        watch.incident = process + "-" + (++nextIncident);
        watch.day = NavCaptureStore.todayDir();
        watch.stalledToken = token;
        watch.stalledSince = since;
        JSONObject record = record("stall", watch.name, now).put("incident", watch.incident)
                .put("ageMs", now - since).put("heap", heap());
        try { record.put("details", watch.details()); }
        catch (Exception error) { record.put("detailsUnavailable", error.toString()); }
        persist(watch.day, record, 0);
    }

    // Coalesce loss evidence per writer/day; never put one task per dropped bitmap on this queue.
    static void loss(String writer, String reason) {
        RuntimeIncidentRecorder recorder = instance;
        if (recorder == null) return;
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        String day = NavCaptureStore.todayDir(wall);
        if (day.equals(recorder.lossDays.put(writer, day))) return;
        recorder.executor.execute(() -> {
            try {
                recorder.persist(day, recorder.record("loss", writer, elapsed)
                        .put("wallMs", wall).put("reason", reason), 0);
            } catch (Exception error) {
                recorder.lossDays.remove(writer, day);
                Log.w(TAG, "incident_loss_record_failed", error);
            }
        });
    }

    private JSONObject record(String event, String source, long elapsed) throws Exception {
        return new JSONObject().put("event", event).put("source", source).put("process", process)
                .put("wallMs", System.currentTimeMillis()).put("elapsedMs", elapsed);
    }

    private void write(String day, JSONObject record) throws IOException {
        String line = record.toString();
        Log.w(TAG, line);
        append(day, FILE_NAME, line);
    }

    private void persist(String day, JSONObject record, int attempt) {
        try { write(day, record); }
        catch (IOException | RuntimeException error) {
            persistenceUncertain = true;
            Log.w(TAG, "incident_persistence_failed attempt=" + attempt, error);
            // A separate small marker survives an unavailable/corrupt incident file.
            // If the whole filesystem is unwritable, only current-process health/Logcat can report it.
            try {
                JSONObject warning = record("persistence_uncertain", record.optString("source"), SystemClock.elapsedRealtime())
                        .put("lossObserved", "loss".equals(record.optString("event")))
                        .put("incident", record.optString("incident")).put("attempt", attempt);
                append(day, PERSISTENCE_FILE, warning.toString());
            } catch (Exception markerError) { Log.w(TAG, "incident_persistence_marker_failed", markerError); }
            if (attempt < 2) {
                executor.schedule(() -> persist(day, record, attempt + 1), attempt == 0 ? 1 : 3, TimeUnit.SECONDS);
            } else if ("loss".equals(record.optString("event"))) {
                lossDays.remove(record.optString("source"), day);
            }
        }
    }

    static boolean persistenceUncertain() {
        RuntimeIncidentRecorder recorder = instance;
        return recorder != null && recorder.persistenceUncertain;
    }

    private void append(String day, String name, String line) throws IOException {
        synchronized (FILE_LOCK) {
            File parent = new File(directory, day);
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("incident directory unavailable");
            try (FileOutputStream out = new FileOutputStream(new File(parent, name), true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        }
    }

    static JSONObject thread(Thread thread) throws Exception {
        if (thread == null) return new JSONObject();
        JSONArray stack = new JSONArray();
        for (StackTraceElement frame : thread.getStackTrace()) stack.put(frame.toString());
        return new JSONObject().put("name", thread.getName()).put("state", thread.getState().name())
                .put("stack", stack);
    }

    private static JSONObject heap() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        return new JSONObject().put("usedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("allocatedBytes", runtime.totalMemory()).put("maxBytes", runtime.maxMemory());
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "runtime-incidents");
    }

    // Freeze private files before handing an export job to the shell UID.
    static Map<String, byte[]> snapshot(Context context, java.util.List<String> days) throws IOException {
        return snapshot(context, days, null);
    }

    // Unreadable optional journals are omissions, not a reason to discard readable ZIP sources.
    static Map<String, byte[]> snapshot(
            Context context, java.util.List<String> days, java.util.List<String> omissions)
            throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        synchronized (FILE_LOCK) {
            for (String day : days) {
                if (day == null || !day.matches("[0-9]{8}")) throw new IOException("invalid incident day");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                for (String name : new String[]{FILE_NAME, PERSISTENCE_FILE}) {
                    File file = new File(new File(directory(context), day), name);
                    if (file.exists() && !file.isFile()) {
                        if (omissions == null) {
                            throw new IOException("incident journal is not a regular file: " + file);
                        }
                        recordOmission(omissions, day, name, "not a regular file");
                    } else if (file.isFile()) {
                        try {
                            bytes.write(Files.readAllBytes(file.toPath()));
                            bytes.write('\n');
                        } catch (IOException error) {
                            if (omissions == null) throw error;
                            recordOmission(omissions, day, name,
                                    error.getClass().getSimpleName() + ": " + error.getMessage());
                        }
                    }
                }
                if (bytes.size() > 0) result.put(day + "/diagnostics/" + FILE_NAME, bytes.toByteArray());
            }
        }
        return result;
    }

    private static void recordOmission(
            java.util.List<String> omissions, String day, String name, String cause) {
        if (omissions != null) {
            omissions.add(day + "/diagnostics/" + name + ": " + cause);
        }
    }

    /** Removes only the app-private incident directory for a fully retired storage day. */
    static boolean deleteDay(File incidentRoot, String day) {
        if (incidentRoot == null || day == null || !day.matches("[0-9]{8}")) return false;
        synchronized (FILE_LOCK) {
            try {
                File root = incidentRoot.getCanonicalFile();
                File target = new File(root, day);
                if (!target.exists()) return true;
                if (Files.isSymbolicLink(target.toPath())
                        || !target.getCanonicalFile().getParentFile().equals(root)) return false;
                return deleteIncidentTree(target, root.getPath());
            } catch (IOException | SecurityException error) {
                Log.w(TAG, "incident_day_delete_failed day=" + day, error);
                return false;
            }
        }
    }

    private static boolean deleteIncidentTree(File file, String canonicalRoot) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) return file.delete() || !file.exists();
        String path = file.getCanonicalPath();
        if (!path.equals(canonicalRoot) && !path.startsWith(canonicalRoot + File.separator)) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteIncidentTree(child, canonicalRoot)) return false;
            }
        }
        return file.delete() || !file.exists();
    }

    static JSONObject summarize(Iterable<byte[]> files) throws Exception {
        boolean loss = false, unreadable = false;
        java.util.Set<String> unresolved = new java.util.HashSet<>();
        java.util.Set<String> recovered = new java.util.HashSet<>();
        boolean persistenceWarning = false;
        int incidents = 0;
        for (byte[] bytes : files) {
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) continue;
                try {
                    JSONObject record = new JSONObject(line);
                    String event = record.getString("event"), id = record.optString("incident");
                    if ("loss".equals(event)) loss = true;
                    if ("persistence_uncertain".equals(event)) {
                        persistenceWarning = true;
                        loss |= record.optBoolean("lossObserved");
                    }
                    if (record.has("detailsUnavailable")) persistenceWarning = true;
                    if ("stall".equals(event)) { unresolved.add(id); incidents++; }
                    if ("recovery".equals(event)) recovered.add(id);
                } catch (Exception error) { unreadable = true; }
            }
        }
        java.util.Set<String> unmatchedRecoveries = new java.util.HashSet<>(recovered);
        unmatchedRecoveries.removeAll(unresolved);
        unresolved.removeAll(recovered);
        return new JSONObject().put("lossObserved", loss).put("stallCount", incidents)
                .put("unresolvedStalls", unresolved.size()).put("unreadableRecords", unreadable)
                .put("unmatchedRecoveries", unmatchedRecoveries.size())
                .put("persistenceUncertain", persistenceWarning)
                .put("coverageUncertain", persistenceWarning || unreadable
                        || !unresolved.isEmpty() || !unmatchedRecoveries.isEmpty());
    }
}
