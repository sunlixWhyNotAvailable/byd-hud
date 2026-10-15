package com.bydhud.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Small, durable messages; APKs and archives stay streamed on disk. */
final class ShellWorkFiles {
    static final String CONTROL_ROOT = "/data/local/tmp/com.bydhud.app/shell-work";

    static boolean running(File job) throws IOException {
        try (java.io.RandomAccessFile owner = new java.io.RandomAccessFile(new File(job, "owner.lock"), "rw")) {
            try (java.nio.channels.FileLock lock = owner.getChannel().tryLock()) { return lock == null; }
            catch (java.nio.channels.OverlappingFileLockException lockedInThisProcess) { return true; }
        }
    }
    static File root(Context context) throws IOException {
        File external = externalFiles(context);
        if (external == null) throw new IOException("Shared runtime storage unavailable");
        return new File(external, "shell-work").getCanonicalFile();
    }

    /** Private expectations; the authenticated shell receives only a read-only descriptor. */
    static File expectationRoot(Context context) throws IOException {
        return directory(new File(context.getNoBackupFilesDir(), "shell-work-expectations"))
                .getCanonicalFile();
    }

    /** Created only by the UID-2000 proxy, so workers can persist control state outside shared storage. */
    static File controlRoot() throws IOException {
        if (Process.myUid() != 2000) throw new IOException("Shell runtime owner required");
        File root = new File(CONTROL_ROOT).getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private shell runtime");
        try {
            Os.chmod(root.getPath(), 0700);
            StructStat stat = Os.stat(root.getPath());
            if (stat.st_uid != 2000 || (stat.st_mode & 0077) != 0) {
                throw new IOException("Private shell runtime ownership mismatch");
            }
        } catch (android.system.ErrnoException error) {
            throw new IOException("Cannot verify private shell runtime", error);
        }
        return root;
    }

    static File controlJob(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,120}"))
            throw new IOException("Invalid shell operation ID");
        File root = controlRoot();
        File job = ExportArtifactFiles.child(root, id).getCanonicalFile();
        if (!job.getParentFile().equals(root)) throw new IOException("Invalid private shell operation path");
        return job;
    }

    /** Serializes admission/launch/retirement across proxy processes, never the HUD monitor. */
    static OperationLock lockOperation(String id) throws IOException {
        File control = directory(controlJob(id));
        return new OperationLock(ExportArtifactFiles.child(control, "operation.lock"));
    }

    static final class OperationLock implements AutoCloseable {
        private final java.io.RandomAccessFile file;
        private final java.nio.channels.FileLock lock;

        OperationLock(File path) throws IOException {
            file = new java.io.RandomAccessFile(path, "rw");
            try {
                lock = file.getChannel().tryLock();
                if (lock == null) throw new IOException("operation_busy");
            } catch (IOException | RuntimeException error) {
                file.close();
                throw new IOException("operation_busy", error);
            }
        }

        @Override public void close() throws IOException {
            try { lock.release(); } finally { file.close(); }
        }
    }

    static File ownedPath(File root, String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Missing runtime path");
        File canonicalRoot = root.getCanonicalFile();
        File candidate = new File(path).getCanonicalFile();
        if (!candidate.equals(canonicalRoot)
                && !candidate.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("Runtime path outside owned workspace");
        }
        return candidate;
    }

    static File shanghaiEvidence(File publicRoot, File externalRoot,
            String day, String id, String path) throws IOException {
        if (day == null || !day.matches("[0-9]{8}")
                || id == null || !id.matches("[A-Za-z0-9_-]{1,80}")
                || path == null || path.isEmpty())
            throw new IOException("Invalid Shanghai evidence identity");
        File candidate = new File(path).getCanonicalFile();
        String relative = day + "/logs/logcat/shanghai_" + id;
        for (File root : new File[]{publicRoot, externalRoot}) {
            if (root != null && candidate.equals(new File(root, relative).getCanonicalFile()))
                return inside(root, path);
        }
        throw new IOException("Invalid Shanghai evidence path");
    }

    static File patchInspectionOutput(File cache, String profile,
            boolean installedInspection, String path) throws IOException {
        File scan = inside(cache, path);
        String name = scan.getName();
        boolean installed = installedInspection && name.matches(
                java.util.regex.Pattern.quote(profile + "-installed-")
                        + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        if (!scan.getParentFile().equals(cache.getCanonicalFile())
                || (!name.startsWith(profile + "-worker-scan-") && !installed))
            throw new IOException("Invalid patch inspection output path");
        return scan;
    }

    static File externalFiles(Context context) {
        if (android.os.Process.myUid() != 2000) return context.getExternalFilesDir(null);
        // app_process has no app attribution: ContextImpl's storage lookup rejects its shell UID.
        // Resolve the same primary emulated-volume path without impersonating the app in Binder calls.
        int user = context.getApplicationInfo().uid / 100000;
        return new File("/storage/emulated/" + user + "/Android/data/"
                + context.getPackageName() + "/files");
    }

    static File directory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file);
        return file;
    }

    static File inside(File root, String path) throws IOException {
        File file = ownedPath(root, path);
        if (file.equals(root.getCanonicalFile())) throw new IOException("Runtime path names workspace root");
        return file;
    }

    static JSONObject read(File file) throws IOException {
        if (!file.exists()) return new JSONObject();
        if (file.length() > 8 * 1024 * 1024) throw new IOException("Oversized runtime message");
        return readManifest(file);
    }

    // Export manifests scale with selected files; they never travel in a Binder string.
    static JSONObject readManifest(File file) throws IOException {
        if (!file.exists()) return new JSONObject();
        try (java.io.InputStream input = new java.io.FileInputStream(file)) { return readManifest(input); }
    }

    static JSONObject readManifest(java.io.InputStream input) throws IOException {
        java.io.Reader reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder json = new StringBuilder();
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Manifest read cancelled");
            json.append(buffer, 0, count);
        }
        try { return new JSONObject(json.toString()); }
        catch (org.json.JSONException error) { throw new IOException("Invalid runtime message", error); }
    }

    static void write(File file, JSONObject value) throws IOException {
        directory(file.getParentFile());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }

    static JSONObject json(Bundle bundle) throws org.json.JSONException {
        JSONObject value = new JSONObject();
        if (bundle != null) for (String key : bundle.keySet()) {
            Object item = bundle.get(key);
            value.put(key, item instanceof Bundle ? json((Bundle) item)
                    : item instanceof Long ? new JSONObject().put("$long", item) : item);
        }
        return value;
    }

    static Bundle bundle(JSONObject value) throws org.json.JSONException {
        Bundle bundle = new Bundle();
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next(); Object item = value.get(key);
            if (item instanceof JSONObject && ((JSONObject) item).has("$long"))
                bundle.putLong(key, ((JSONObject) item).getLong("$long"));
            else if (item instanceof JSONObject) bundle.putBundle(key, bundle((JSONObject) item));
            else if (item instanceof Boolean) bundle.putBoolean(key, (Boolean) item);
            else if (item instanceof Integer) bundle.putInt(key, (Integer) item);
            else if (item instanceof Number) bundle.putLong(key, ((Number) item).longValue());
            else if (item != JSONObject.NULL) bundle.putString(key, item.toString());
        }
        return bundle;
    }
}
