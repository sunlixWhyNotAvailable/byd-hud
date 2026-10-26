package com.bydhud.app;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;

/** Each admitted job owns a shell process, not an Android Service or its client's death recipient. */
public final class ShellWorkEntryPoint {
    static volatile File currentControl;
    static volatile File currentJob;
    static volatile JSONObject request;
    private static long lastProgressMs;
    private static String lastPhase = "";

    public static void main(String[] args) {
        if (Process.myUid() != 2000 || args == null || args.length != 1
                || !args[0].matches("[A-Za-z0-9_-]{1,80}")) return;
        try {
            Looper.prepareMainLooper();
            Context system = InstrumentProxyEntryPoint.systemContext();
            Context app = system.createPackageContext("com.bydhud.app", Context.CONTEXT_IGNORE_SECURITY);
            File control = ShellWorkFiles.controlJob(args[0]);
            if (!control.getParentFile().equals(ShellWorkFiles.controlRoot())) return;
            try (RandomAccessFile owner = new RandomAccessFile(new File(control, "owner.lock"), "rw");
                 FileLock lock = owner.getChannel().tryLock()) {
                if (lock == null) return;
                currentControl = control;
                JSONObject manifest = ShellWorkFiles.readManifest(new File(control, "request.json"));
                validateManifest(app, args[0], manifest);
                String savedState = ShellWorkFiles.read(new File(control, "state.json")).optString("state");
                if (InstrumentNavigationProxyService.isTerminalWorkState(savedState)
                        || "UNCERTAIN".equals(savedState)) return;
                currentJob = ShellWorkFiles.inside(ShellWorkFiles.root(app), manifest.getString("artifact"));
                if (!currentJob.getParentFile().equals(ShellWorkFiles.root(app).getCanonicalFile()))
                    throw new IOException("Invalid shell artifact workspace");
                request = new JSONObject().put("id", manifest.getString("id"))
                        .put("type", manifest.getString("type"))
                        .put("input", manifest.getJSONObject("workerInput"));
                ShellWorkFiles.write(new File(control, "started.json"),
                        new JSONObject().put("pid", Process.myPid()).put("id", args[0]));
                Thread ownerThread = Thread.currentThread();
                Thread cancellation = new Thread(() -> {
                    try {
                        while (!ownerThread.isInterrupted()) {
                            if (new File(control, "cancel").exists()) { ownerThread.interrupt(); return; }
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException ignored) { }
                }, "shell-job-cancel");
                cancellation.setDaemon(true); cancellation.start();
                progress(new JSONObject().put("phase", "RUNNING").put("pid", Process.myPid()));
                JSONObject result;
                String state;
                try {
                    if (new File(control, "cancel").exists())
                        throw new InterruptedException("Cancelled before start");
                    result = execute(new ShellWorkContext(app, system, currentJob), system)
                            .put("ok", true);
                    state = "SUCCEEDED";
                } catch (Throwable error) {
                    boolean unknown = error instanceof InstrumentProxyManager.ShellWorkOutcomeUnknownException;
                    android.util.Log.e("BYDHUD_SHELL_WORK", FailureDiagnostics.format(
                            error, "shell-work " + safeId(args[0]), lastPhase));
                    boolean cancelled = !unknown && (Thread.currentThread().isInterrupted()
                            || new File(control, "cancel").exists());
                    result = new JSONObject().put("ok", false).put("error", safeFailure(error))
                            .put("cancelled", cancelled)
                            .put("diagnostic", FailureDiagnostics.formatBounded(
                                    error, "shell-work " + safeId(args[0]), lastPhase, 24 * 1024));
                    state = unknown ? "UNCERTAIN" : cancelled ? "CANCELLED" : "FAILED";
                }
                if (!"UNCERTAIN".equals(state)) Thread.interrupted();
                writeState(control, state, result, new JSONObject().put("phase", state));
                cancellation.interrupt();
                if (!"UNCERTAIN".equals(state)) {
                    try {
                        ExportArtifactFiles.Removed removed = new ExportArtifactFiles.Removed();
                        ShellExportCleanup.cleanCopies(manifest.optString("type"), control, currentJob, removed);
                        if (removed.files > 0) android.util.Log.i("BYDHUD_SHELL_WORK",
                                "export_copies_retired id=" + args[0] + " files=" + removed.files
                                        + " bytes=" + removed.bytes);
                    } catch (IOException cleanupError) {
                        // The terminal result stays available; a later sweep retries deletion.
                        android.util.Log.w("BYDHUD_SHELL_WORK", "export_copies_retained id=" + args[0]
                                + " reason=" + ShellExportCleanup.failure(cleanupError));
                    }
                }
            }
        } catch (Throwable error) {
            boolean unknown = error instanceof InstrumentProxyManager.ShellWorkOutcomeUnknownException;
            android.util.Log.e("BYDHUD_SHELL_WORK",
                    FailureDiagnostics.format(error, "shell-work", "startup"));
            try {
                if (currentControl != null) {
                    writeState(currentControl, unknown ? "UNCERTAIN"
                                    : error instanceof InterruptedException ? "CANCELLED" : "FAILED",
                            new JSONObject().put("ok", false)
                                    .put("cancelled", !unknown && error instanceof InterruptedException)
                                    .put("error", safeFailure(error))
                                    .put("diagnostic", FailureDiagnostics.formatBounded(
                                            error, "shell-work", "startup", 24 * 1024)),
                            new JSONObject().put("phase", unknown ? "UNCERTAIN" : "FAILED"));
                }
            } catch (Exception recordFailure) {
                error.addSuppressed(recordFailure);
                android.util.Log.e("BYDHUD_SHELL_WORK", FailureDiagnostics.format(
                        error, "shell-work", "persist-startup-failure"));
            }
        } finally {
            currentControl = null;
            currentJob = null;
            request = null;
        }
        System.exit(0);
    }

    private static void validateManifest(Context app, String id, JSONObject manifest) throws Exception {
        if (!id.equals(manifest.optString("id")) || manifest.optInt("uid", -1) != app.getApplicationInfo().uid
                || !manifest.optString("apk").equals(app.getApplicationInfo().sourceDir)
                || manifest.optInt("version", -1) != BuildConfig.VERSION_CODE
                || manifest.optInt("boot", -1) != bootCount())
            throw new IOException("Stale trusted shell operation identity");
        if (!"REGISTERED".equals(manifest.optString("state"))
                && !"RUNNING".equals(manifest.optString("state")))
            throw new IOException("Shell operation is not registered");
        if ("patch".equals(manifest.optString("type"))) {
            JSONObject workerInput = manifest.optJSONObject("workerInput");
            if (workerInput == null || !workerInput.has("command"))
                throw new IOException("Trusted patch command is missing");
        }
    }

    private static JSONObject execute(ShellWorkContext context, Context system) throws Exception {
        JSONObject input = request.getJSONObject("input");
        switch (request.getString("type")) {
            case "patch": return NavigatorPatchWorkerClient.runInShell(context, input);
            case "log": return LogShareZip.runInShell(context, input);
            case "configuration": {
                VehicleConfigurationZip.Result result = VehicleConfigurationZip.createFull(context,
                        new VehicleConfigurationZip.Control(), (phase, file, bytes, total, files, count, missing) -> {
                            try { progress(new JSONObject().put("phase", phase).put("file", file)
                                    .put("bytes", bytes).put("total", total).put("files", files)
                                    .put("count", count).put("missing", missing)); }
                            catch (Exception error) { throw new IllegalStateException(error); }
                        });
                if (!result.ok) throw new IOException(result.detail);
                JSONArray volumes = new JSONArray();
                for (File file : result.volumes) volumes.put(file.getAbsolutePath());
                return new JSONObject().put("volumes", volumes).put("completedAt", result.completedAtMs)
                        .put("missing", result.unavailableFiles);
            }
            case "upload": {
                File archive = ShellWorkFiles.inside(ShellWorkFiles.controlRoot(), input.getString("archive"));
                SentryLogUploader.Result result = SentryLogUploader.upload(context, archive,
                        strings(input.getJSONArray("days")), input.getString("operation"),
                        new SentryLogReport(input.optString("comment", ""), input.getString("title")));
                return new JSONObject().put("sent", result.ok).put("event", result.eventId).put("detail", result.detail);
            }
            case "shanghai": return ShellShanghaiRoute.run(context, system, input);
            default: throw new IOException("Unknown shell operation");
        }
    }

    static List<String> strings(JSONArray array) throws org.json.JSONException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }

    // app_process is not registered with ActivityManager as a content-provider client.
    static int bootCount() throws IOException {
        LocalAdbBridge.ShellResult result = command("settings get global boot_count", 128, 5_000);
        if (result.exitCode != 0) throw new IOException("Cannot read shell operation boot identity");
        try { return Integer.parseInt(result.output.trim()); }
        catch (NumberFormatException error) { throw new IOException("Invalid shell operation boot identity", error); }
    }

    static void progress(JSONObject value) throws IOException {
        if (currentControl == null) throw new IOException("No active trusted shell operation");
        long now = android.os.SystemClock.elapsedRealtime();
        String phase = value.optString("phase");
        if (phase.equals(lastPhase) && now - lastProgressMs < 200) return;
        try {
            JSONObject state = new JSONObject().put("id", request.optString("id"))
                    .put("state", "RUNNING").put("progress", value);
            InstrumentNavigationProxyService.writeWorkState(currentControl, state);
        } catch (org.json.JSONException error) {
            throw new IOException("Cannot encode trusted shell progress", error);
        }
        lastPhase = phase; lastProgressMs = now;
    }

    static void sign(File directory) throws Exception {
        JSONObject input = request.getJSONObject("input");
        if (!"patch".equals(request.optString("type"))
                || input.optInt("command", -1) != NavigatorPatchWorkerClient.MSG_PREPARE)
            throw new IOException("Signing requires an approved PREPARE operation");
        File transaction = new File(input.getString("transaction")).getCanonicalFile();
        File target = directory.getCanonicalFile();
        String stage = target.equals(new File(transaction, "patched-set").getCanonicalFile())
                ? "SIGN_BASELINE"
                : target.equals(new File(transaction, "map-candidate-set").getCanonicalFile())
                ? "SIGN_MAP" : "";
        if (stage.isEmpty()) throw new IOException("Signing output is outside the PREPARE transaction");
        String id = java.util.UUID.randomUUID().toString();
        ShellWorkFiles.write(new File(currentControl, "sign-request.json"), new JSONObject()
                .put("id", id).put("stage", stage).put("directory", target.getPath()));
        File responseFile = new File(currentControl, "sign-response-" + id + ".json");
        while (true) {
            if (Thread.currentThread().isInterrupted() || new File(currentControl, "cancel").exists())
                throw new InterruptedException();
            JSONObject response = ShellWorkFiles.read(responseFile);
            if (id.equals(response.optString("id"))) {
                if (!response.optBoolean("ok")) throw new IOException(response.optString("detail",
                        "Signing was refused for this PREPARE operation"));
                return;
            }
            Thread.sleep(100);
        }
    }

    private static void writeState(File control, String state, JSONObject result, JSONObject progress)
            throws IOException, org.json.JSONException {
        InstrumentNavigationProxyService.writeWorkState(control, new JSONObject()
                .put("id", request == null ? "" : request.optString("id"))
                .put("state", state).put("progress", progress).put("result", result));
    }

    private static String safeFailure(Throwable error) {
        if (error == null) return "Shell work failed";
        String full = FailureDiagnostics.format(error, "", "");
        int end = full.indexOf('\n');
        String summary = end < 0 ? full : full.substring(0, end);
        return summary.length() > 256 ? summary.substring(0, 256) : summary;
    }

    private static String safeId(String value) { return value.matches("[A-Za-z0-9_-]{1,80}") ? value : "unknown"; }

    static LocalAdbBridge.ShellResult command(String command, int limit, long timeoutMs) throws IOException {
        java.lang.Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true).start();
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(timeoutMs); timedOut.set(true); process.destroy(); }
            catch (InterruptedException ignored) { }
        }, "shell-command-deadline");
        deadline.setDaemon(true); deadline.start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        long dropped = 0;
        int cap = limit > 0 ? limit : 2 * 1024 * 1024;
        try (java.io.InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[16384]; int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                int retained = Math.min(read, cap - output.size());
                if (retained > 0) output.write(buffer, 0, retained);
                dropped += read - retained;
            }
            int code;
            try { code = process.waitFor(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
            String text = output.toString("UTF-8");
            return new LocalAdbBridge.ShellResult(text, timedOut.get() ? 124 : code, text,
                    dropped > 0, dropped, timedOut.get() ? "timeout" : code == 0 ? "success" : "error", "");
        } finally { deadline.interrupt(); process.destroy(); }
    }
}
