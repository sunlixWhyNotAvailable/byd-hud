package com.bydhud.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/** A durable registration cursor limits open descriptors, not the export's file count. */
final class ShellWorkSnapshots {
    static final int BATCH_SIZE = 32;

    static final class Source {
        final InputStream input;
        final long length;
        Source(InputStream input, long length) { this.input = input; this.length = length; }
    }

    private ShellWorkSnapshots() { }

    static JSONArray paths(String type, JSONObject input) throws Exception {
        JSONArray paths = new JSONArray();
        if ("patch".equals(type)) {
            String source = input.optString("source");
            if (!source.isEmpty() && input.optBoolean("directory")) {
                File[] files = new File(source, "members").listFiles((parent, name) ->
                        name.toLowerCase(Locale.ROOT).endsWith(".apk"));
                if (files == null || files.length == 0)
                    throw new IOException("APK-set inspection source has no members");
                java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                for (File file : files) paths.put(file.getAbsolutePath());
            } else if (!source.isEmpty()) paths.put(source);
        } else if ("upload".equals(type)) {
            paths.put(input.getString("archive"));
        } else if ("log".equals(type)) {
            JSONArray sources = input.optJSONArray("sources");
            if (sources != null) for (int i = 0; i < sources.length(); i++) {
                String path = sources.getJSONObject(i).optString("path");
                if (!path.isEmpty()) paths.put(path);
            }
        }
        return paths;
    }

    static JSONObject progress(File control, JSONObject manifest) throws Exception {
        if ("REGISTERED".equals(manifest.optString("state"))) return new JSONObject()
                .put("state", "REGISTERED").put("nextSnapshot", manifest.getInt("nextSnapshot"));
        JSONObject cursor = ShellWorkFiles.read(new File(control, "registration.json"));
        return cursor.length() == 0 ? new JSONObject().put("state", "REGISTERING").put("nextSnapshot", 0) : cursor;
    }

    static JSONObject append(File control, JSONObject current, int offset,
            List<Source> sources, boolean complete) throws Exception {
        if (!"REGISTERING".equals(current.optString("state")))
            throw new IOException("Shell input registration is already sealed");
        JSONArray paths = current.getJSONArray("snapshotPaths");
        int next = progress(control, current).getInt("nextSnapshot");
        if (offset != next || sources.size() > BATCH_SIZE || offset < 0
                || sources.size() > paths.length() - offset)
            throw new IOException("Shell input batch does not match registration cursor");
        int end = offset + sources.size();
        if (complete && end != paths.length())
            throw new IOException("Shell input registration is incomplete");
        File root = ShellWorkFiles.directory(new File(control, "snapshots"));
        String type = current.getString("type");
        JSONArray selectedLogs = "log".equals(type) ? current.getJSONObject("input").getJSONArray("sources") : null;
        int selectedIndex = 0;
        if (selectedLogs != null) for (int seen = 0; seen < offset && selectedIndex < selectedLogs.length();) {
            if (!selectedLogs.getJSONObject(selectedIndex++).optString("path").isEmpty()) seen++;
        }
        JSONArray receipt = new JSONArray();
        for (int i = 0; i < sources.size(); i++) {
            checkCancelled(control);
            int index = offset + i;
            File target = target(type, current.getJSONObject("input"), root, index, paths.getString(index));
            Source source = sources.get(i);
            if (source.input == null || source.length < 0) throw new IOException("Invalid snapshot source");
            long length = source.length;
            if (selectedLogs != null) {
                while (selectedIndex < selectedLogs.length() && selectedLogs.getJSONObject(selectedIndex).optString("path").isEmpty()) selectedIndex++;
                if (selectedIndex == selectedLogs.length()) throw new IOException("Missing log snapshot source");
                long selectedLength = selectedLogs.getJSONObject(selectedIndex++).getLong("length");
                if (selectedLength < 0 || selectedLength > length)
                    throw new IOException("Selected log file shrank before snapshot");
                length = selectedLength;
            }
            receipt.put(copy(control, source.input, length, target).put("index", index)
                    .put("path", target.getAbsolutePath()));
        }
        checkCancelled(control);
        // Each receipt is durable before the cursor advances; retry replaces only an uncommitted batch.
        if (!sources.isEmpty()) ShellWorkFiles.write(new File(root, "batch-" + offset + ".json"),
                new JSONObject().put("files", receipt));
        JSONObject cursor = new JSONObject().put("nextSnapshot", end)
                .put("state", complete ? "REGISTERED" : "REGISTERING");
        if (complete) {
            JSONObject updated = new JSONObject(current.toString());
            JSONObject workerInput = updated.getJSONObject("workerInput");
            JSONArray fingerprints = new JSONArray();
            JSONArray logSources = workerInput.optJSONArray("sources");
            int logIndex = 0;
            for (int from = 0; from < end; ) {
                JSONArray batch = ShellWorkFiles.read(new File(root, "batch-" + from + ".json")).getJSONArray("files");
                if (batch.length() == 0 || batch.length() > BATCH_SIZE) throw new IOException("Invalid snapshot receipt");
                for (int i = 0; i < batch.length(); i++) {
                    JSONObject file = batch.getJSONObject(i);
                    if (file.getInt("index") != from || from >= end) throw new IOException("Snapshot receipt gap");
                    String path = file.getString("path");
                    if ("log".equals(type)) {
                        while (logIndex < logSources.length() && logSources.getJSONObject(logIndex).optString("path").isEmpty()) logIndex++;
                        if (logIndex == logSources.length()) throw new IOException("Missing log snapshot source");
                        logSources.getJSONObject(logIndex++).put("path", path);
                    } else replacePath(type, workerInput, from, path);
                    fingerprints.put(new JSONObject().put("index", from++).put("length", file.getLong("length"))
                            .put("sha256", file.getString("sha256")));
                }
            }
            checkCancelled(control);
            updated.put("inputFingerprints", fingerprints).put("nextSnapshot", end).put("state", "REGISTERED");
            ShellWorkFiles.write(new File(control, "request.json"), updated);
        }
        ShellWorkFiles.write(new File(control, "registration.json"), cursor);
        return cursor;
    }

    private static JSONObject copy(File control, InputStream input, long length, File target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File part = new File(target.getPath() + ".part");
        try {
            try (FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    checkCancelled(control);
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Snapshot source ended before its selected length");
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    remaining -= read;
                }
                output.getFD().sync();
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            StringBuilder sha = new StringBuilder(64);
            for (byte value : digest.digest()) sha.append(String.format(Locale.ROOT, "%02x", value & 255));
            return new JSONObject().put("length", length).put("sha256", sha.toString());
        } finally { Files.deleteIfExists(part.toPath()); }
    }

    private static File target(String type, JSONObject input, File root, int index, String path) throws IOException {
        if ("patch".equals(type) && input.optBoolean("directory")) {
            String base = new File(path).getName();
            if (!base.toLowerCase(Locale.ROOT).endsWith(".apk")) throw new IOException("Invalid APK-set member");
            return new File(ShellWorkFiles.directory(new File(root, "inspect-set/members")), base);
        }
        String base = new File(path).getName();
        String ext = base.matches(".*\\.[A-Za-z0-9]{1,8}$") ? base.substring(base.lastIndexOf('.')) : ".bin";
        return new File(root, String.format(Locale.ROOT, "%04d%s", index, ext));
    }

    private static void replacePath(String type, JSONObject input, int index, String path) throws Exception {
        if ("patch".equals(type)) input.put("source", input.optBoolean("directory")
                ? new File(path).getParentFile().getParentFile().getAbsolutePath() : path);
        else if ("upload".equals(type)) input.put("archive", path);
    }

    private static void checkCancelled(File control) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || new File(control, "cancel").exists())
            throw new InterruptedIOException("Shell input snapshot cancelled");
    }
}
