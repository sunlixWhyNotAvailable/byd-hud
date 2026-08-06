package com.bydhud.app;

import android.content.Context;
import android.util.Log;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
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

    static final class Result {
        final boolean ok;
        final File file;
        final String detail;

        Result(boolean ok, File file, String detail) {
            this.ok = ok;
            this.file = file;
            this.detail = detail == null ? "" : detail;
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
        try {
            List<SnapshotFile> files = snapshotFiles(context.getApplicationContext(), days);
            long bytes = 0L;
            for (SnapshotFile file : files) {
                bytes += file.length;
            }
            return new SelectionSummary(!files.isEmpty(), days.size(), files.size(), bytes,
                    files.isEmpty() ? "no readable files" : "ready");
        } catch (IOException | RuntimeException error) {
            return new SelectionSummary(false, days.size(), 0, 0L, error.getMessage());
        }
    }

    //Background-callable; copies a stable snapshot before releasing the topology writer lock.
    static synchronized Result create(Context context, List<String> selectedDays) {
        if (context == null) {
            return failure("missing context");
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
                + ".zip";
        File output = new File(shareDir, fileName);
        File part = new File(shareDir, fileName + ".part");
        File staging = new File(shareDir, fileName + ".staging");
        if (output.exists() || part.exists() || staging.exists()) {
            return failure("share name collision");
        }

        boolean writeHeld = false;
        try {
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
            NavigationLogStorage.lockTopologyWrite();
            writeHeld = true;
            List<SnapshotFile> sources = snapshotFiles(app, days);
            if (sources.isEmpty()) {
                throw new IOException("no readable files");
            }
            if (!staging.mkdirs()) {
                throw new IOException("cannot create staging directory");
            }
            List<SnapshotFile> snapshot = copySnapshotToStaging(staging, sources);
            NavigationLogStorage.unlockTopologyWrite();
            writeHeld = false;
            Log.i(TAG, "share_phase phase=COPYING duration_ms="
                    + (System.currentTimeMillis() - copyStarted)
                    + " files=" + snapshot.size()
                    + " pending=" + WazeCaptureDebugWriter.get().pendingTasks());

            phase(Phase.ARCHIVING, WazeCaptureDebugWriter.get().pendingTasks());
            long archiveStarted = System.currentTimeMillis();
            writeZip(part, snapshot);
            checkCancelled();
            if (!part.renameTo(output)) {
                throw new IOException("final rename failed");
            }
            Log.i(TAG, "share_phase phase=ARCHIVING duration_ms="
                    + (System.currentTimeMillis() - archiveStarted)
                    + " bytes=" + output.length()
                    + " pending=" + WazeCaptureDebugWriter.get().pendingTasks());
            return new Result(true, output,
                    "files=" + snapshot.size() + " bytes=" + output.length());
        } catch (IOException | RuntimeException e) {
            deleteArtifact(part);
            deleteArtifact(output);
            return failure(e.getMessage());
        } finally {
            if (writeHeld) {
                NavigationLogStorage.unlockTopologyWrite();
            }
            deleteTree(staging);
        }
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
                if (file == null || !isShareArtifact(file.getName())) {
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
            if (!current.canRead()) {
                continue;
            }
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
            File staging, List<SnapshotFile> sources) throws IOException {
        List<SnapshotFile> copied = new ArrayList<>(sources.size());
        for (SnapshotFile source : sources) {
            checkCancelled();
            File target = new File(staging,
                    source.entryName.replace('/', File.separatorChar));
            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IOException("cannot create staging path");
            }
            copyFile(source, target);
            target.setLastModified(source.file.lastModified());
            copied.add(new SnapshotFile(target, source.entryName, source.length));
        }
        return copied;
    }

    private static void copyFile(SnapshotFile source, File target) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileInputStream input = new FileInputStream(source.file);
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

    private static void writeZip(File part, List<SnapshotFile> files) throws IOException {
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
        File external = context.getExternalCacheDir();
        if (external != null) {
            File dir = new File(external, SHARE_DIR);
            if ((dir.isDirectory() || dir.mkdirs()) && dir.canWrite()) {
                return dir;
            }
        }
        File dir = new File(context.getCacheDir(), SHARE_DIR);
        return (dir.isDirectory() || dir.mkdirs()) && dir.canWrite() ? dir : null;
    }

    private static boolean isShareArtifact(String name) {
        return name != null
                && (name.startsWith(ZIP_PREFIX) || name.startsWith(CONFIG_ZIP_PREFIX))
                && (name.endsWith(".zip") || name.endsWith(".zip.part")
                || name.endsWith(".zip.staging"));
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
