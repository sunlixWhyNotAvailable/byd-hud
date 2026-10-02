package com.bydhud.app;

import android.content.Context;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//Creates cancellable log archives from a stable staging snapshot.
final class LogShareZip {
    private static final String TAG = "BydHudLogShare";
    private static final String SHARE_DIR = "log-shares";
    private static final String ZIP_PREFIX = "BYD-HUD-logs-";
    private static final String CONFIG_ZIP_PREFIX = "BYD-HUD-vehicle-config-";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long WRITER_CHECKPOINT_TIMEOUT_MS = 2_000L;
    private static final long COMPLETED_ZIP_MIN_AGE_MS = 10L * 60L * 1000L;
    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean(false);
    private static final ThreadLocal<Consumer<Phase>> PROGRESS_LISTENER = new ThreadLocal<>();

    private LogShareZip() {
    }

    enum Phase {
        WAITING_FOR_WRITES,
        COPYING,
        ARCHIVING
    }

    static void attachProgressListener(Consumer<Phase> listener) {
        if (listener == null) {
            PROGRESS_LISTENER.remove();
        } else {
            PROGRESS_LISTENER.set(listener);
        }
    }

    static void clearProgressListener() {
        PROGRESS_LISTENER.remove();
    }

    enum CollectionOutcome { FULL, PARTIAL, FAILED }

    static final class Result {
        final boolean ok;
        final File file;
        final String detail;
        final CollectionOutcome outcome;
        final boolean patchReportsIncomplete;

        Result(boolean ok, File file, String detail) {
            this(ok ? CollectionOutcome.FULL : CollectionOutcome.FAILED, file, detail, false);
        }
        Result(CollectionOutcome outcome, File file, String detail, boolean patchReportsIncomplete) {
            this.outcome = outcome;
            this.ok = outcome != CollectionOutcome.FAILED;
            this.file = file;
            this.detail = detail == null ? "" : detail;
            this.patchReportsIncomplete = patchReportsIncomplete;
        }
    }

    static final class SelectionSummary {
        final boolean ok;
        final int dayCount;
        final int fileCount;
        final long sourceBytes;
        final String detail;

        SelectionSummary(boolean ok, int dayCount, int fileCount,
                long sourceBytes, String detail) {
            this.ok = ok;
            this.dayCount = Math.max(0, dayCount);
            this.fileCount = Math.max(0, fileCount);
            this.sourceBytes = Math.max(0L, sourceBytes);
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class SnapshotFile {
        final File file;
        final String entryName;
        final long length;
        byte[] captured;

        SnapshotFile(File file, String entryName, long length) {
            this.file = file;
            this.entryName = entryName;
            this.length = length;
        }
    }

    //Describes the current selection before the user chooses an export destination.
    static SelectionSummary summarize(Context context, List<String> selectedDays) {
        if (context == null) {
            return new SelectionSummary(false, 0, 0, 0L, "missing context");
        }
        List<String> days;
        try {
            days = checkedDays(selectedDays);
        } catch (IOException error) {
            return new SelectionSummary(false, 0, 0, 0L, error.getMessage());
        }
        boolean locked = false;
        try {
            locked = awaitStorageLock();
            if (!locked) return new SelectionSummary(false, days.size(), 0, 0L,
                    "storage busy; retry export when the current operation completes");
            NavigatorPatchReportStore.CachedSummary reports =
                    NavigatorPatchReportStore.cachedSummary();
            List<SnapshotFile> files = snapshotFiles(
                    context.getApplicationContext(), days, reports.days);
            long bytes = 0L;
            for (SnapshotFile file : files) {
                bytes += file.length;
            }
            bytes += reports.reportBytes;
            int count = files.size() + 1;
            boolean ready = !files.isEmpty();
            String detail = !ready ? "no readable files or patch reports"
                    : "ready; navigator patch report history will be included";
            return new SelectionSummary(ready, days.size(), count, bytes, detail);
        } catch (IOException | RuntimeException error) {
            return new SelectionSummary(false, days.size(), 0, 0L, error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new SelectionSummary(false, days.size(), 0, 0L, "cancelled");
        } finally {
            if (locked) NavigationLogStorage.unlockTopologyWrite();
        }
    }

    // Freeze topology/lengths briefly, then let journal appends continue during copying.
    static synchronized Result create(Context context, List<String> selectedDays) {
        return create(context, selectedDays, "");
    }

    //Navigation-log Sentry uploads carry a short correlation ID in the archive name.
    static synchronized Result create(Context context, List<String> selectedDays, String uploadId) {
        if (context == null) {
            return failure("missing context");
        }
        if (uploadId == null || (!uploadId.isEmpty() && !uploadId.matches("[0-9a-f]{8}"))) {
            return failure("invalid upload id");
        }
        List<String> days;
        try {
            days = checkedDays(selectedDays);
        } catch (IOException e) {
            return failure(e.getMessage());
        }
        Context app = context.getApplicationContext();
        File shareDir = writableShareDir(app);
        if (shareDir == null) {
            return failure("share cache unavailable");
        }
        String fileName = ZIP_PREFIX
                + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date())
                + (uploadId.isEmpty() ? "" : "-" + uploadId)
                + ".zip";
        File output = new File(shareDir, fileName);
        File part = new File(shareDir, fileName + ".part");
        File staging = new File(shareDir, fileName + ".staging");
        if (output.exists() || part.exists() || staging.exists()) {
            return failure("share name collision");
        }

        boolean writeHeld = false;
        boolean pinned = false;
        try {
            JSONObject reportSnapshot = NavigatorPatchReportStore.exportSnapshot(app);
            phase(Phase.WAITING_FOR_WRITES, WazeCaptureDebugWriter.get().pendingTasks());
            long waitStarted = System.currentTimeMillis();
            boolean checkpoint = WazeCaptureDebugWriter.get()
                    .awaitCheckpoint(WRITER_CHECKPOINT_TIMEOUT_MS);
            checkCancelled();
            Log.i(TAG, "share_phase phase=WAITING_FOR_WRITES duration_ms="
                    + (System.currentTimeMillis() - waitStarted)
                    + " checkpoint=" + (checkpoint ? "ready" : "timeout")
                    + " pending=" + WazeCaptureDebugWriter.get().pendingTasks());

            phase(Phase.COPYING, WazeCaptureDebugWriter.get().pendingTasks());
            long copyStarted = System.currentTimeMillis();
            if (!staging.mkdirs()) {
                throw new IOException("cannot create staging directory");
            }
            writeHeld = awaitStorageLock();
            if (!writeHeld) throw new IOException("storage busy: no log snapshot obtained; existing files preserved");
            NavigationLogStorage.pinExportDays(days);
            pinned = true;
            List<SnapshotFile> sources = snapshotFiles(app, days);
            if (sources.isEmpty()) throw new IOException("no readable recording files");
            List<String> omissions = new ArrayList<>();
            // Small replace-in-place metadata must be frozen while its writers are excluded.
            java.util.Iterator<SnapshotFile> metadata = sources.iterator();
            while (metadata.hasNext()) {
                SnapshotFile source = metadata.next();
                if (source.file.getName().endsWith(".json")) {
                    try {
                        if (source.length > 1024 * 1024) throw new IOException("oversized mutable metadata");
                        source.captured = Files.readAllBytes(source.file.toPath());
                    } catch (IOException failed) {
                        omissions.add(source.entryName + ": " + failed.getMessage());
                        metadata.remove();
                    }
                }
            }
            NavigationLogStorage.unlockTopologyWrite();
            writeHeld = false;
            List<SnapshotFile> snapshot = copySnapshotToStaging(staging, sources, omissions);
            NavigationLogStorage.unpinExportDays(days);
            pinned = false;
            if (snapshot.isEmpty()) throw new IOException("no recording files copied: " + omissions);
            boolean incomplete = addWriterStatus(staging, snapshot, days,
                    checkpoint, true, reportSnapshot, omissions);
            Log.i(TAG, "share_phase phase=COPYING duration_ms="
                    + (System.currentTimeMillis() - copyStarted)
                    + " files=" + snapshot.size()
                    + " pending=" + WazeCaptureDebugWriter.get().pendingTasks());

            phase(Phase.ARCHIVING, WazeCaptureDebugWriter.get().pendingTasks());
            long archiveStarted = System.currentTimeMillis();
            NavigatorPatchReportStore.ExportResult reportStatus = writeZip(
                    part, snapshot, app, reportSnapshot);
            checkCancelled();
            if (!part.renameTo(output)) {
                throw new IOException("final rename failed");
            }
            Log.i(TAG, "share_phase phase=ARCHIVING duration_ms="
                    + (System.currentTimeMillis() - archiveStarted)
                    + " bytes=" + output.length()
                    + " pending=" + WazeCaptureDebugWriter.get().pendingTasks());
            return new Result(incomplete ? CollectionOutcome.PARTIAL : CollectionOutcome.FULL, output,
                    "files=" + (snapshot.size() + 1 + (reportStatus.incomplete ? 1 : 0))
                            + " bytes=" + output.length()
                            + " reports=" + reportStatus.reportCount
                            + " recording=" + (incomplete ? "PARTIAL" : "FULL")
                            + " patchReports=" + (reportStatus.incomplete ? "INCOMPLETE" : "complete"),
                    reportStatus.incomplete);
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            deleteArtifact(part);
            deleteArtifact(output);
            try {
                String failure = "log_export_failed reason=" + e.getMessage()
                        + " health=" + WazeCaptureDebugWriter.healthSnapshot();
                Log.w(TAG, failure);
                AppEventLogger.event(app, failure);
            } catch (JSONException ignored) { Log.w(TAG, "log_export_failed", e); }
            return failure(e.getMessage());
        } finally {
            if (writeHeld) {
                NavigationLogStorage.unlockTopologyWrite();
            }
            if (pinned) NavigationLogStorage.unpinExportDays(days);
            deleteTree(staging);
        }
    }

    static Result createIndependent(Context context, List<String> days, String uploadId, String operationId) {
        File job = null;
        boolean pinned = false;
        try {
            job = ShellWorkClient.job(context, "log", operationId);
            File requestFile = new File(job, "request.json");
            if (!requestFile.exists()) {
                checkedDays(days);
                JSONObject reports = NavigatorPatchReportStore.exportSnapshot(context);
                boolean checkpoint = WazeCaptureDebugWriter.get().awaitCheckpoint(WRITER_CHECKPOINT_TIMEOUT_MS);
                JSONObject health = WazeCaptureDebugWriter.healthSnapshot();
                if (!awaitStorageLock()) throw new IOException("storage busy: existing files preserved");
                try {
                    NavigationLogStorage.pinExportDays(days);
                    pinned = true;
                    org.json.JSONArray sources = new org.json.JSONArray();
                    for (SnapshotFile file : snapshotFiles(context, days)) {
                        JSONObject source = new JSONObject().put("path", file.file.getCanonicalPath())
                                .put("entry", file.entryName).put("length", file.length);
                        if (file.file.getName().endsWith(".json")) {
                            if (file.length > 1024 * 1024) throw new IOException("oversized mutable metadata");
                            File frozen = new File(ShellWorkFiles.directory(new File(job, "metadata")), sources.length() + ".json");
                            Files.copy(file.file.toPath(), frozen.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            source.put("path", frozen.getPath()).put("length", frozen.length());
                        }
                        sources.put(source);
                    }
                    ShellWorkClient.prepare(context, "log", operationId, new JSONObject()
                            .put("sources", sources).put("reports", reports).put("health", health)
                            .put("checkpoint", checkpoint).put("days", new org.json.JSONArray(days))
                            .put("uploadId", uploadId));
                } finally { NavigationLogStorage.unlockTopologyWrite(); }
            } else {
                NavigationLogStorage.withWriteLock(() -> NavigationLogStorage.pinExportDays(days));
                pinned = true;
            }
            JSONObject result = ShellWorkClient.await(context, job, current -> {
                String stage = current.optString("phase");
                if ("COPYING".equals(stage) || "ARCHIVING".equals(stage)) phase(Phase.valueOf(stage), 0);
            });
            return new Result(CollectionOutcome.valueOf(result.getString("outcome")),
                    new File(result.getString("file")), result.optString("detail"), result.optBoolean("incompleteReports"));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return failure(error.toString());
        } finally {
            // The persistent job also pins its sources across application death.
            if (pinned) NavigationLogStorage.unpinExportDays(days);
        }
    }

    static JSONObject runInShell(Context context, JSONObject input) throws Exception {
        List<SnapshotFile> sources = new ArrayList<>();
        org.json.JSONArray values = input.getJSONArray("sources");
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            SnapshotFile file = new SnapshotFile(new File(value.getString("path")),
                    zipRelative(value.getString("entry")), value.getLong("length"));
            if (value.has("captured")) file.captured = android.util.Base64.decode(value.getString("captured"), android.util.Base64.DEFAULT);
            sources.add(file);
        }
        File shares = writableShareDir(context);
        if (shares == null) throw new IOException("Share cache unavailable");
        File output = new File(shares, ZIP_PREFIX + ShellWorkEntryPoint.request.getString("id") + ".zip");
        File part = new File(shares, output.getName() + ".part");
        File staging = new File(shares, output.getName() + ".staging");
        List<String> omissions = new ArrayList<>();
        try {
            ShellWorkFiles.directory(staging);
            ShellWorkEntryPoint.progress(new JSONObject().put("phase", "COPYING"));
            List<SnapshotFile> files = copySnapshotToStaging(staging, sources, omissions);
            if (files.isEmpty()) throw new IOException("No recording files copied: " + omissions);
            JSONObject reports = input.getJSONObject("reports");
            boolean incomplete = addWriterStatus(staging, files, ShellWorkEntryPoint.strings(input.getJSONArray("days")),
                    input.getBoolean("checkpoint"), true, reports, omissions, input.getJSONObject("health"));
            ShellWorkEntryPoint.progress(new JSONObject().put("phase", "ARCHIVING"));
            NavigatorPatchReportStore.ExportResult exported = writeZip(part, files, context, reports);
            checkCancelled();
            if (!part.renameTo(output)) throw new IOException("Final archive rename failed");
            return new JSONObject().put("file", output.getPath()).put("outcome", incomplete ? "PARTIAL" : "FULL")
                    .put("incompleteReports", exported.incomplete).put("detail", "files=" + files.size() + " bytes=" + output.length());
        } finally { deleteTree(staging); deleteArtifact(part); }
    }

    private static boolean awaitStorageLock() throws InterruptedException, IOException {
        long start = System.nanoTime(), lastProgressAt = start;
        long completed = WazeCaptureDebugWriter.completedTaskCount() + NavigationLogStorage.completedOperations();
        while (System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
            checkCancelled();
            if (NavigationLogStorage.tryLockTopologyWrite(100)) return true;
            long progress = WazeCaptureDebugWriter.completedTaskCount() + NavigationLogStorage.completedOperations();
            if (progress != completed) { completed = progress; lastProgressAt = System.nanoTime(); }
            if (System.nanoTime() - lastProgressAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) return false;
        }
        return false;
    }

    private static boolean addWriterStatus(File staging, List<SnapshotFile> files,
            List<String> days, boolean checkpoint, boolean storageReady,
            JSONObject reportSnapshot, List<String> omissions) throws IOException {
        try { return addWriterStatus(staging, files, days, checkpoint, storageReady, reportSnapshot,
                omissions, WazeCaptureDebugWriter.healthSnapshot()); }
        catch (JSONException error) { throw new IOException(error); }
    }

    private static boolean addWriterStatus(File staging, List<SnapshotFile> files,
            List<String> days, boolean checkpoint, boolean storageReady,
            JSONObject reportSnapshot, List<String> omissions, JSONObject health) throws IOException {
        try {
            boolean reportsIncomplete = reportSnapshot.optBoolean("incompleteReports");
            boolean incomplete = !checkpoint || !storageReady || health.getBoolean("lossObserved")
                    || !omissions.isEmpty();
            health.put("selectedDays", new org.json.JSONArray(days))
                    .put("omittedOrTruncatedFiles", new org.json.JSONArray(omissions))
                    .put("collectionOutcome", incomplete ? "PARTIAL" : "FULL")
                    .put("writerCheckpointReady", checkpoint).put("storageSnapshotReady", storageReady)
                    .put("navigatorPatchReportCount", reportSnapshot.optInt("reportCount"))
                    .put("navigatorPatchReportsIncluded", true)
                    .put("navigatorPatchReportsIncomplete", reportsIncomplete)
                    .put("recordingStatus", incomplete ? "INCOMPLETE" : "snapshot_ready")
                    .put("note", "Writer counters cover this process only; this is not a guarantee of historical coverage.");
            String directory = NavigatorPatchReportStore.exportDirectory(reportSnapshot);
            String statusEntry = directory + "recording-status.json";
            addStatusFile(staging, files, statusEntry, health.toString(2));
            if (incomplete) addStatusFile(staging, files, directory + "INCOMPLETE-RECORDING.txt",
                    "This archive may be missing diagnostic records.\n"
                    + "writerCheckpointReady=" + checkpoint + "\n"
                    + "storageSnapshotReady=" + storageReady + "\n"
                    + "lossObservedInCurrentProcess=" + health.getBoolean("lossObserved") + "\n"
                    + "omittedOrTruncatedFiles=" + omissions + "\n"
                    + (storageReady ? "Available files were copied.\n" : "Storage lock timed out; diagnostic status only, no log files copied.\n")
                    + "See " + statusEntry + " for writer progress, pending work and errors.\n");
            return incomplete;
        } catch (JSONException error) {
            throw new IOException("cannot serialize recording status", error);
        }
    }

    private static void addStatusFile(File staging, List<SnapshotFile> files, String name,
            String text) throws IOException {
        File file = new File(staging, name);
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        files.add(new SnapshotFile(file, name, file.length()));
    }

    //Removes completed and partial archives left by the previous app process.
    static int cleanupStaleArtifacts(Context context) {
        if (context == null || !CLEANUP_STARTED.compareAndSet(false, true)) {
            return 0;
        }
        Context app = context.getApplicationContext();
        List<File> parents = new ArrayList<>();
        File external = app.getExternalCacheDir();
        if (external != null) {
            parents.add(new File(external, SHARE_DIR));
        }
        parents.add(new File(app.getCacheDir(), SHARE_DIR));
        Set<String> retained = new HashSet<>();
        try {
            File[] jobs = ShellWorkFiles.root(app).listFiles();
            if (jobs != null) for (File job : jobs) {
                if (job.getName().startsWith("upload-") && !ShellWorkFiles.settled(job)) {
                    JSONObject input = ShellWorkFiles.read(new File(job, "request.json")).optJSONObject("input");
                    if (input != null) retained.add(input.optString("archive"));
                }
                if (job.getName().startsWith("log-") && ShellWorkFiles.settled(job))
                    parents.add(new File(job, "cache/log-shares"));
            }
            for (java.util.Map.Entry<String, ?> entry : ShellRuntimeSession.prefs(app).getAll().entrySet()) {
                if (!entry.getKey().startsWith("share_work_")) continue;
                JSONObject saved = new JSONObject(String.valueOf(entry.getValue()));
                if ("WAITING_FOR_SHARE".equals(saved.optString("phase")) || "OVERSIZED".equals(saved.optString("phase")))
                    retained.add(saved.optString("file"));
            }
        } catch (Exception error) { AppEventLogger.event(app, "shell_share_retention_failed " + error); }
        Set<String> visited = new HashSet<>();
        int deleted = 0;
        long now = System.currentTimeMillis();
        for (File parent : parents) {
            String canonical;
            try {
                canonical = parent.getCanonicalPath();
            } catch (IOException e) {
                continue;
            }
            if (!visited.add(canonical)) {
                continue;
            }
            File[] files = parent.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file == null || retained.contains(file.getAbsolutePath()) || !isShareArtifact(file.getName())) {
                    continue;
                }
                boolean partial = file.isDirectory() || file.getName().endsWith(".part");
                long ageMs = Math.max(0L, now - file.lastModified());
                if (partial || ageMs >= COMPLETED_ZIP_MIN_AGE_MS) {
                    boolean existed = file.exists();
                    if (file.isDirectory()) deleteTree(file); else deleteArtifact(file);
                    if (existed && !file.exists()) {
                        deleted++;
                    }
                }
            }
        }
        return deleted;
    }

    private static List<String> checkedDays(List<String> selectedDays) throws IOException {
        if (selectedDays == null || selectedDays.isEmpty()) {
            throw new IOException("no selected days");
        }
        List<String> days = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : selectedDays) {
            String day = value == null ? "" : value.trim();
            if (!day.matches("\\d{8}")) {
                throw new IOException("invalid day");
            }
            if (!unique.add(day)) {
                throw new IOException("duplicate day");
            }
            days.add(day);
        }
        return days;
    }

    private static List<SnapshotFile> snapshotFiles(Context context, List<String> days)
            throws IOException {
        return snapshotFiles(context, days, NavigatorPatchReportStore.reportDays(context));
    }

    private static List<SnapshotFile> snapshotFiles(Context context, List<String> days,
            Map<String, Long> reportDays) throws IOException {
        List<NavigationLogStorage.StorageRoot> roots =
                NavigationLogStorage.accessibleRoots(context);
        rejectDuplicateRoots(roots);
        List<SnapshotFile> files = new ArrayList<>();
        Set<String> canonicalFiles = new HashSet<>();
        Set<String> entryNames = new HashSet<>();
        for (String day : days) {
            List<NavigationLogStorage.StorageRoot> fragments = new ArrayList<>();
            for (NavigationLogStorage.StorageRoot root : roots) {
                File fragment = new File(root.dir, day);
                if (!fragment.exists()) {
                    continue;
                }
                requireSafeRelative(root.dir, fragment, true);
                if (!fragment.isDirectory()) {
                    throw new IOException("day is not a directory");
                }
                fragments.add(root);
            }
            if (fragments.isEmpty()) {
                if (reportDays.containsKey(day)) continue;
                throw new IOException("selected day missing: " + day);
            }
            boolean split = fragments.size() > 1;
            for (NavigationLogStorage.StorageRoot root : fragments) {
                File fragment = new File(root.dir, day);
                String prefix = split ? root.archivePrefix + "/" + day : day;
                collectFiles(fragment, prefix, files, canonicalFiles, entryNames);
            }
        }
        files.sort((left, right) -> left.entryName.compareTo(right.entryName));
        return files;
    }

    private static void collectFiles(
            File dayRoot,
            String prefix,
            List<SnapshotFile> output,
            Set<String> canonicalFiles,
            Set<String> entryNames) throws IOException {
        List<File> pending = new ArrayList<>();
        pending.add(dayRoot);
        while (!pending.isEmpty()) {
            File current = pending.remove(pending.size() - 1);
            String relative = requireSafeRelative(dayRoot, current, false);
            if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children == null) {
                    throw new IOException("directory unreadable");
                }
                Collections.addAll(pending, children);
                continue;
            }
            if (!current.isFile()) {
                throw new IOException("non-regular file");
            }
            if (current.getName().startsWith(".") || current.getName().endsWith(".tmp")
                    || (current.getName().endsWith(".part") && !current.getName().equals("logcat.log.part"))) continue;
            String canonical = current.getCanonicalPath();
            if (!canonicalFiles.add(canonical)) {
                throw new IOException("duplicate source file");
            }
            String entryName = prefix + "/" + zipRelative(relative);
            if (!entryNames.add(entryName)) {
                throw new IOException("duplicate ZIP entry");
            }
            output.add(new SnapshotFile(current, entryName, Math.max(0L, current.length())));
        }
    }

    private static String requireSafeRelative(File root, File candidate, boolean direct)
            throws IOException {
        File absoluteRoot = root.getAbsoluteFile();
        File absoluteCandidate = candidate.getAbsoluteFile();
        String rootPath = absoluteRoot.getPath();
        String candidatePath = absoluteCandidate.getPath();
        String prefix = rootPath.endsWith(File.separator)
                ? rootPath
                : rootPath + File.separator;
        String relative;
        if (candidatePath.equals(rootPath)) {
            relative = "";
        } else if (candidatePath.startsWith(prefix)) {
            relative = candidatePath.substring(prefix.length());
        } else {
            throw new IOException("path traversal");
        }
        if (direct && (relative.isEmpty() || relative.contains(File.separator))) {
            throw new IOException("day traversal");
        }
        File canonicalRoot = absoluteRoot.getCanonicalFile();
        File expected = relative.isEmpty()
                ? canonicalRoot
                : new File(canonicalRoot, relative).getAbsoluteFile();
        if (!absoluteCandidate.getCanonicalFile().equals(expected)) {
            throw new IOException("symlink or canonical escape");
        }
        return relative;
    }

    private static String zipRelative(String relative) throws IOException {
        String path = relative.replace(File.separatorChar, '/');
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            throw new IOException("invalid ZIP path");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("ZIP traversal");
            }
        }
        return path;
    }

    private static void rejectDuplicateRoots(List<NavigationLogStorage.StorageRoot> roots)
            throws IOException {
        Set<String> canonical = new HashSet<>();
        for (NavigationLogStorage.StorageRoot root : roots) {
            if (root == null || root.dir == null || !canonical.add(root.dir.getCanonicalPath())) {
                throw new IOException("duplicate storage root");
            }
        }
    }

    private static List<SnapshotFile> copySnapshotToStaging(
            File staging, List<SnapshotFile> sources, List<String> omissions) throws IOException {
        List<SnapshotFile> copied = new ArrayList<>(sources.size());
        for (SnapshotFile source : sources) {
            checkCancelled();
            File target = new File(staging,
                    source.entryName.replace('/', File.separatorChar));
            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IOException("cannot create staging path");
            }
            try {
                copyFile(source, target);
                if (appendLog(source.file)) trimIncompleteLine(target, source.entryName, omissions);
                target.setLastModified(source.file.lastModified());
                copied.add(new SnapshotFile(target, source.entryName, target.length()));
            } catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException failed) {
                deleteArtifact(target);
                omissions.add(source.entryName + ": " + failed.getMessage());
            }
        }
        return copied;
    }

    private static void copyFile(SnapshotFile source, File target) throws IOException {
        if (source.captured != null) { Files.write(target.toPath(), source.captured); return; }
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileInputStream input = openSnapshotSource(source.file);
             FileOutputStream output = new FileOutputStream(target, false)) {
            long remaining = source.length;
            while (remaining > 0L) {
                checkCancelled();
                int read = input.read(buffer, 0,
                        (int) Math.min((long) buffer.length, remaining));
                if (read < 0) {
                    throw new EOFException("source truncated: " + source.entryName);
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static FileInputStream openSnapshotSource(File file) throws IOException {
        try { return new FileInputStream(file); }
        catch (java.io.FileNotFoundException error) {
            // The live logcat journal can finalize after the inventory snapshot. It is
            // append-only, and finish only renames this same file (never overwrites).
            if (!file.getName().equals("logcat.log.part")) throw error;
            return new FileInputStream(new File(file.getParentFile(), "logcat.log"));
        }
    }
    private static boolean appendLog(File file) {
        return file.getName().matches("(?i).+\\.(log|jsonl)(\\.\\d+)?")
                || file.getName().equals("logcat.log.part") || file.getName().equals("logcat.txt");
    }
    private static void trimIncompleteLine(File file, String name, List<String> omissions) throws IOException {
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "rw")) {
            long length = input.length(), position = length;
            if (length == 0) return;
            byte[] tail = new byte[BUFFER_BYTES];
            while (position > 0) {
                checkCancelled();
                int count = (int) Math.min(position, tail.length);
                position -= count; input.seek(position); input.readFully(tail, 0, count);
                for (int i = count - 1; i >= 0; i--) if (tail[i] == '\n') {
                    long complete = position + i + 1;
                    if (complete != length) { input.setLength(complete); omissions.add(name + ": incomplete trailing record omitted"); }
                    return;
                }
            }
            input.setLength(0); omissions.add(name + ": no complete record at snapshot boundary");
        }
    }

    private static NavigatorPatchReportStore.ExportResult writeZip(
            File part, List<SnapshotFile> files, Context context, JSONObject reportSnapshot)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileOutputStream fileOut = new FileOutputStream(part, false);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            for (SnapshotFile source : files) {
                checkCancelled();
                ZipEntry entry = new ZipEntry(source.entryName);
                entry.setTime(source.file.lastModified());
                zip.putNextEntry(entry);
                try (FileInputStream input = new FileInputStream(source.file)) {
                    long remaining = source.length;
                    while (remaining > 0L) {
                        checkCancelled();
                        int read = input.read(buffer, 0,
                                (int) Math.min((long) buffer.length, remaining));
                        if (read < 0) {
                            throw new EOFException("source truncated: " + source.entryName);
                        }
                        zip.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
                zip.closeEntry();
            }
            return NavigatorPatchReportStore.writeReports(context, zip, reportSnapshot);
        }
    }

    private static void phase(Phase phase, int pendingTasks) {
        Log.i(TAG, "share_phase phase=" + phase + " pending=" + pendingTasks);
        Consumer<Phase> listener = PROGRESS_LISTENER.get();
        if (listener == null) return;
        try {
            listener.accept(phase);
        } catch (RuntimeException error) {
            Log.w(TAG, "share progress callback failed", error);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("share cancelled");
        }
    }

    static File writableShareDir(Context context) {
        File dir = new File(context.getCacheDir(), SHARE_DIR);
        return (dir.isDirectory() || dir.mkdirs()) && dir.canWrite() ? dir : null;
    }

    private static boolean isShareArtifact(String name) {
        return name != null
                && name.startsWith(ZIP_PREFIX)
                && (name.endsWith(".zip") || name.endsWith(".zip.part")
                || name.endsWith(".zip.staging") || name.endsWith(".zip.source.part"));
    }

    static void deleteArtifact(File file) {
        try {
            if (file != null && file.isFile()) file.delete();
        } catch (RuntimeException ignored) {
            //The share result still reports failure if cache cleanup is denied by the platform.
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        file.delete();
    }

    private static Result failure(String detail) {
        return new Result(false, null, detail == null ? "share failed" : detail);
    }
}
