package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Authenticated retirement is separate from permission to execute a historical request. */
final class ShellExportCleanup {
    static boolean exportType(String type) {
        return "log".equals(type) || "upload".equals(type) || "configuration".equals(type);
    }

    static JSONArray run(Context app, int uid, int version, JSONObject selection,
            Map<String, Process> processes) throws Exception {
        JSONArray jobs = selection.getJSONArray("jobs");
        JSONArray paths = selection.getJSONArray("retained");
        if (jobs.length() > 128 || paths.length() > 256) throw new IOException("cleanup_batch_oversized");
        File root = ShellWorkFiles.root(app);
        int boot = ShellWorkEntryPoint.bootCount();
        String apk = app.getApplicationInfo().sourceDir;
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < paths.length(); i++) retained.add(paths.getString(i));
        retainUploads(uid, version, boot, apk, processes, retained);
        JSONArray results = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject hint = jobs.getJSONObject(i);
            String name = hint.getString("name");
            if (!name.matches("(?:log|upload|configuration)-[A-Za-z0-9_-]{1,80}"))
                throw new IOException("invalid_cleanup_identity");
            String type = name.substring(0, name.indexOf('-'));
            String id = name.substring(type.length() + 1);
            JSONObject result = new JSONObject().put("name", name).put("reason", "settled");
            ExportArtifactFiles.Removed removed = new ExportArtifactFiles.Removed();
            try (ShellWorkFiles.OperationLock admission = ShellWorkFiles.lockOperation(id)) {
                File artifact = ExportArtifactFiles.child(root, name);
                File control = ShellWorkFiles.controlJob(id);
                // An observer can still own preparation before a private request exists.
                if (hint.optBoolean("protected")) result.put("reason", "share_protected");
                else retire(uid, version, boot, apk, id, type, hint, artifact, control,
                        processes.get(id), retained, removed, result);
            } catch (Exception error) {
                result.put("reason", failure(error));
                if ("configuration".equals(type)) result.put("next", System.currentTimeMillis() + 60_000L);
            }
            if ("configuration".equals(type) && !result.has("next")
                    && new File(root, name + "/cache/log-shares").isDirectory()) {
                // Completion can reach the app just before the worker releases owner.lock.
                result.put("next", System.currentTimeMillis() + 60_000L);
            }
            result.put("files", removed.files).put("bytes", removed.bytes);
            results.put(result);
        }
        return results;
    }

    private static void retire(int uid, int version, int boot, String apk,
            String id, String type, JSONObject hint, File artifact, File control, Process process,
            Set<String> retained, ExportArtifactFiles.Removed removed, JSONObject result) throws Exception {
        File requestFile = ExportArtifactFiles.child(control, "request.json");
        JSONObject request;
        JSONObject state;
        try {
            request = ShellWorkFiles.read(requestFile);
            state = ShellWorkFiles.read(ExportArtifactFiles.child(control, "state.json"));
        } catch (IOException unreadable) { throw new IOException("private_record_unreadable", unreadable); }
        boolean trusted = requestFile.isFile();
        if (trusted && (!id.equals(request.optString("id")) || !type.equals(request.optString("type"))
                || request.optInt("uid", -1) != uid
                || !artifact.getAbsolutePath().equals(request.optString("artifact"))))
            throw new IOException("ownership_mismatch");
        if (process != null && process.isAlive()) { result.put("reason", "active"); return; }
        if (request.optInt("boot", -1) == boot && starting(state)) {
            result.put("reason", "starting"); return;
        }
        boolean settled = ShellWorkClient.terminal(state.optString("state"));
        if (trusted && !settled && current(request, version, boot, apk)
                && !"RUNNING".equals(state.optString("state"))) {
            result.put("reason", "registered_pending");
            return;
        }
        File retiredFile = ExportArtifactFiles.child(control, "retired.json");
        boolean retired = retiredFile.isFile();
        if (retired) {
            JSONObject marker = ShellWorkFiles.read(retiredFile);
            if (!id.equals(marker.optString("id")) || !type.equals(marker.optString("type"))
                    || marker.optInt("uid", -1) != uid) throw new IOException("ownership_mismatch");
        }
        long created = hint.optLong("createdAt", artifact.lastModified());
        if (!trusted && !retired && !"REGISTER_FAILED".equals(hint.optString("stage"))
                && (created <= 0 || System.currentTimeMillis() - created < ExportArtifactFiles.LOG_RETENTION_MS)) {
            result.put("reason", "preparation_pending");
            return;
        }
        try (RandomAccessFile owner = new RandomAccessFile(ExportArtifactFiles.child(control, "owner.lock"), "rw");
             FileLock lock = owner.getChannel().tryLock()) {
            if (lock == null) { result.put("reason", "active"); return; }
            // Older workers held owner.lock in the shared artifact directory.
            if (artifact.isDirectory()) {
                try (RandomAccessFile legacy = new RandomAccessFile(ExportArtifactFiles.child(artifact, "owner.lock"), "rw");
                     FileLock legacyLock = legacy.getChannel().tryLock()) {
                    if (legacyLock == null) { result.put("reason", "legacy_active"); return; }
                    retireLocked(id, type, uid, trusted, state, artifact, control, retained, removed, result);
                }
            } else retireLocked(id, type, uid, trusted, state, artifact, control, retained, removed, result);
        }
    }

    private static void retireLocked(String id, String type, int uid, boolean trusted,
            JSONObject state, File artifact, File control, Set<String> retained,
            ExportArtifactFiles.Removed removed, JSONObject result) throws Exception {
        if (trusted && !ShellWorkClient.terminal(state.optString("state"))) {
            state = new JSONObject().put("id", id).put("state", "INTERRUPTED")
                    .put("result", new JSONObject().put("ok", false).put("error", "Retired inactive export; not replayed"));
            InstrumentNavigationProxyService.writeWorkState(control, state);
        }
        if (!trusted && !new File(control, "retired.json").exists()) {
            // A small shell-owned tombstone prevents this orphan ID from being registered again.
            ShellWorkFiles.write(new File(control, "retired.json"), new JSONObject()
                    .put("id", id).put("type", type).put("uid", uid).put("retiredAt", System.currentTimeMillis()));
            result.put("reason", "orphan_retired");
        }
        result.put("settled", true).put("state", state.optString("state", "INTERRUPTED"));
        cleanCopies(type, control, artifact, removed);
        File shares = ExportArtifactFiles.child(artifact, "cache/log-shares");
        if ("log".equals(type)) {
            ExportArtifactFiles.logArchives(shares, retained, System.currentTimeMillis(), removed);
            if (retained.contains("*")) result.put("reason", "upload_state_unavailable");
        } else if ("configuration".equals(type)) {
            // Reuse the existing absolute expiry/legacy-adoption policy, under the worker lock.
            java.util.List<String> events = new java.util.ArrayList<>();
            long next = ConfigurationExportArtifacts.sweep(shares, System.currentTimeMillis(), retained,
                    event -> { if (events.size() < 8) events.add(event); }, removed);
            result.put("next", next);
            if (events.stream().anyMatch(event -> event.startsWith("cleanup_failed")))
                result.put("reason", "delete_failed");
        }
    }

    /** The worker calls this only after saving terminal state and closing its source streams. */
    static void cleanCopies(String type, File control, File artifact, ExportArtifactFiles.Removed removed)
            throws IOException {
        if (!exportType(type)) return;
        ExportArtifactFiles.delete(control, ExportArtifactFiles.child(control, "snapshots"), removed);
        ExportArtifactFiles.delete(artifact, ExportArtifactFiles.child(artifact, "metadata"), removed);
    }

    private static void retainUploads(int uid, int version, int boot, String apk,
            Map<String, Process> processes, Set<String> retained) throws Exception {
        File[] controls = ShellWorkFiles.controlRoot().listFiles();
        if (controls == null) throw new IOException("control_directory_unreadable");
        for (File directory : controls) {
            if (!directory.isDirectory()) continue;
            try {
            File control = ShellWorkFiles.controlJob(directory.getName());
            JSONObject request = ShellWorkFiles.read(ExportArtifactFiles.child(control, "request.json"));
            if (!"upload".equals(request.optString("type")) || request.optInt("uid", -1) != uid) continue;
            JSONObject state = ShellWorkFiles.read(ExportArtifactFiles.child(control, "state.json"));
            if (ShellWorkClient.terminal(state.optString("state"))) continue;
            Process process = processes.get(directory.getName());
            boolean busy = process != null && process.isAlive();
            busy |= (request.optInt("boot", -1) == boot && starting(state)) || (current(request, version, boot, apk)
                    && !"RUNNING".equals(state.optString("state")));
            if (!busy) {
                try (RandomAccessFile owner = new RandomAccessFile(ExportArtifactFiles.child(control, "owner.lock"), "rw");
                     FileLock lock = owner.getChannel().tryLock()) { busy = lock == null; }
                catch (java.nio.channels.OverlappingFileLockException activeHere) { busy = true; }
            }
            if (busy) retained.add(request.getJSONObject("input").getString("archive"));
            } catch (Exception unreadableUpload) {
                // Unknown upload ownership may protect a ZIP, but cannot block other cleanup.
                retained.add("*");
            }
        }
    }

    private static boolean current(JSONObject request, int version, int boot, String apk) {
        return request.optInt("version", -1) == version && request.optInt("boot", -1) == boot
                && apk.equals(request.optString("apk"));
    }

    private static boolean starting(JSONObject state) {
        JSONObject progress = state.optJSONObject("progress");
        return !ShellWorkClient.terminal(state.optString("state")) && progress != null
                && "STARTING".equals(progress.optString("phase"))
                && SystemClock.elapsedRealtime() < state.optLong("startupDeadlineElapsedMs");
    }

    static String failure(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z_]{1,64}") ? message
                : "cleanup_failed_" + error.getClass().getSimpleName();
    }

    private ShellExportCleanup() { }
}
