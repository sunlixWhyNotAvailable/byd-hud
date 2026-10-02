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
    static volatile File currentJob;
    static volatile JSONObject request;
    private static long lastProgressMs;
    private static String lastPhase = "";

    public static void main(String[] args) {
        if (Process.myUid() != 2000 || args == null || args.length != 1) return;
        try {
            Looper.prepareMainLooper();
            Context system = InstrumentProxyEntryPoint.systemContext();
            Context app = system.createPackageContext("com.bydhud.app", Context.CONTEXT_IGNORE_SECURITY);
            File root = ShellWorkFiles.root(app);
            File job = ShellWorkFiles.inside(root, args[0]);
            if (!job.getParentFile().equals(root.getCanonicalFile())) return;
            try (RandomAccessFile owner = new RandomAccessFile(new File(job, "owner.lock"), "rw");
                 FileLock lock = owner.getChannel().tryLock()) {
                if (lock == null || new File(job, "result.json").exists()) return;
                currentJob = job;
                ShellWorkFiles.write(new File(job, "started.json"), new JSONObject().put("pid", Process.myPid()));
                request = ShellWorkFiles.read(new File(job, "request.json"));
                if (request.optInt("uid") != app.getApplicationInfo().uid
                        || !request.optString("apk").equals(app.getApplicationInfo().sourceDir)
                        || request.optInt("version") != BuildConfig.VERSION_CODE
                        || request.optInt("boot", -1) != android.provider.Settings.Global.getInt(
                                system.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -2))
                    throw new IOException("Stale shell job identity");
                ShellWorkContext context = new ShellWorkContext(app, system, job);
                Thread ownerThread = Thread.currentThread();
                Thread cancellation = new Thread(() -> {
                    try {
                        while (!ownerThread.isInterrupted()) {
                            if (new File(job, "cancel").exists()) { ownerThread.interrupt(); return; }
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException ignored) { }
                }, "shell-job-cancel");
                cancellation.setDaemon(true); cancellation.start();
                progress(new JSONObject().put("phase", "RUNNING").put("pid", Process.myPid()));
                JSONObject result;
                try {
                    if (new File(job, "cancel").exists()) throw new InterruptedException("Cancelled before start");
                    result = execute(context, system).put("ok", true);
                }
                catch (Throwable error) {
                    android.util.Log.e("BYDHUD_SHELL_WORK", "job failed " + job.getName(), error);
                    result = new JSONObject().put("ok", false).put("error", error.toString())
                            .put("cancelled", Thread.currentThread().isInterrupted() || new File(job, "cancel").exists());
                }
                Thread.interrupted();
                ShellWorkFiles.write(new File(job, "result.json"), result);
                cancellation.interrupt();
            }
        } catch (Throwable error) {
            android.util.Log.e("BYDHUD_SHELL_WORK", "startup failed", error);
            try {
                if (currentJob != null) ShellWorkFiles.write(new File(currentJob, "result.json"),
                        new JSONObject().put("ok", false).put("error", error.toString()));
            } catch (Exception ignored) { }
        }
        System.exit(0);
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
                SentryLogUploader.Result result = SentryLogUploader.upload(context,
                        ShellWorkFiles.inside(ShellWorkFiles.root(context), input.getString("archive")),
                        strings(input.getJSONArray("days")), input.getString("operation"),
                        new SentryLogReport(input.optString("comment", ""), input.getString("title")));
                return new JSONObject().put("sent", result.ok).put("event", result.eventId).put("detail", result.detail);
            }
            case "shanghai": return ShellShanghaiRoute.run(context, system, input);
            default: throw new IOException("Unknown shell job");
        }
    }

    static List<String> strings(JSONArray array) throws org.json.JSONException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }

    static void progress(JSONObject value) throws IOException {
        long now = android.os.SystemClock.elapsedRealtime();
        String phase = value.optString("phase");
        if (phase.equals(lastPhase) && now - lastProgressMs < 200) return;
        ShellWorkFiles.write(new File(currentJob, "progress.json"), value);
        lastPhase = phase; lastProgressMs = now;
    }

    static void sign(File directory) throws Exception {
        File target = ShellWorkFiles.inside(currentJob.getParentFile(), directory.getPath());
        String id = java.util.UUID.randomUUID().toString();
        ShellWorkFiles.write(new File(currentJob, "sign.json"), new JSONObject()
                .put("id", id).put("directory", target.getPath()));
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            JSONObject response = ShellWorkFiles.read(new File(currentJob, "signed.json"));
            if (id.equals(response.optString("id"))) {
                if (!response.optBoolean("ok")) throw new IOException(response.optString("error"));
                return;
            }
            Thread.sleep(100);
        }
    }

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
