package com.bydhud.app;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Small, durable messages; APKs and archives stay streamed on disk. */
final class ShellWorkFiles {
    static boolean running(File job) throws IOException {
        try (java.io.RandomAccessFile owner = new java.io.RandomAccessFile(new File(job, "owner.lock"), "rw")) {
            try (java.nio.channels.FileLock lock = owner.getChannel().tryLock()) { return lock == null; }
            catch (java.nio.channels.OverlappingFileLockException lockedInThisProcess) { return true; }
        }
    }
    static boolean settled(File job) {
        try { return new File(job, "result.json").isFile() && !running(job); }
        catch (IOException ignored) { return false; }
    }

    static File root(Context context) throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Shared runtime storage unavailable");
        return directory(new File(external, "shell-work"));
    }

    static File directory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file);
        return file;
    }

    static File inside(File root, String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Runtime path outside owned workspace");
        return file;
    }

    static JSONObject read(File file) throws IOException {
        if (!file.exists()) return new JSONObject();
        if (file.length() > 8 * 1024 * 1024) throw new IOException("Oversized runtime message");
        try { return new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
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
