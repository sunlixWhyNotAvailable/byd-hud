package com.bydhud.app;

import android.content.Context;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//Creates bounded log archives without copying selected storage into an intermediate tree.
final class LogShareZip {
    private static final String SHARE_DIR = "log-shares";
    private static final String ZIP_PREFIX = "BYD-HUD-logs-";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long COMPLETED_ZIP_MIN_AGE_MS = 10L * 60L * 1000L;
    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean(false);

    private LogShareZip() {
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

    //Background-callable; waits for pre-existing capture writes before fixing the file snapshot.
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
        NavigationLogStorage.withWriteLock(() -> { });
        if (!WazeCaptureDebugWriter.get().awaitIdle()) {
            return failure("capture writer interrupted");
        }

        File shareDir = writableShareDir(app);
        if (shareDir == null) {
            return failure("share cache unavailable");
        }
        String fileName = ZIP_PREFIX
                + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date())
                + ".zip";
        File output = new File(shareDir, fileName);
        File part = new File(shareDir, fileName + ".part");
        if (output.exists() || part.exists()) {
            return failure("share name collision");
        }

        List<SnapshotFile> snapshot;
        boolean writeHeld = false;
        boolean readHeld = false;
        try {
            NavigationLogStorage.lockTopologyWrite();
            writeHeld = true;
            snapshot = snapshotFiles(app, days);
            if (snapshot.isEmpty()) {
                throw new IOException("no readable files");
            }
            NavigationLogStorage.lockTopologyRead();
            readHeld = true;
            NavigationLogStorage.unlockTopologyWrite();
            writeHeld = false;

            writeZip(part, snapshot);
            if (!part.renameTo(output)) {
                throw new IOException("final rename failed");
            }
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
            if (readHeld) {
                NavigationLogStorage.unlockTopologyRead();
            }
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
                if (file == null || !file.isFile() || !isShareArtifact(file.getName())) {
                    continue;
                }
                boolean partial = file.getName().endsWith(".part");
                long ageMs = Math.max(0L, now - file.lastModified());
                if ((partial || ageMs >= COMPLETED_ZIP_MIN_AGE_MS) && file.delete()) {
                    deleted++;
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

    private static void writeZip(File part, List<SnapshotFile> files) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileOutputStream fileOut = new FileOutputStream(part, false);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            for (SnapshotFile source : files) {
                ZipEntry entry = new ZipEntry(source.entryName);
                entry.setTime(source.file.lastModified());
                zip.putNextEntry(entry);
                try (FileInputStream input = new FileInputStream(source.file)) {
                    long remaining = source.length;
                    while (remaining > 0L) {
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

    private static File writableShareDir(Context context) {
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
                && name.startsWith(ZIP_PREFIX)
                && (name.endsWith(".zip") || name.endsWith(".zip.part"));
    }

    private static void deleteArtifact(File file) {
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (RuntimeException ignored) {
            //The share result still reports failure if cache cleanup is denied by the platform.
        }
    }

    private static Result failure(String detail) {
        return new Result(false, null, detail == null ? "share failed" : detail);
    }
}
