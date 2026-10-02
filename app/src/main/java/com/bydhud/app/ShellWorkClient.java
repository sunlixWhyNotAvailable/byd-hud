package com.bydhud.app;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** Reattach by operation ID. Losing this observer never cancels its shell worker. */
final class ShellWorkClient {
    private static final java.util.Map<String, java.util.List<String>> logPins = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean pinsDay(String day) {
        for (java.util.Map.Entry<String, java.util.List<String>> entry : logPins.entrySet()) {
            if (ShellWorkFiles.settled(new File(entry.getKey()))) { logPins.remove(entry.getKey()); continue; }
            if (entry.getValue().contains(day)) return true;
        }
        return false;
    }

    static void restorePins(Context context) {
        try {
            File[] jobs = ShellWorkFiles.root(context).listFiles();
            if (jobs != null) for (File job : jobs) {
                if (!job.getName().startsWith("log-") || new File(job, "result.json").exists()) continue;
                JSONObject request = ShellWorkFiles.read(new File(job, "request.json"));
                if (request.optInt("boot", -1) == Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -2))
                    logPins.put(job.getPath(), ShellWorkEntryPoint.strings(request.getJSONObject("input").getJSONArray("days")));
            }
        } catch (Exception error) { AppEventLogger.event(context, "shell_pins_restore_failed " + error); }
    }

    static void cancelAll(Context context) {
        try {
            File[] jobs = ShellWorkFiles.root(context).listFiles();
            if (jobs != null) for (File job : jobs)
                if (new File(job, "request.json").isFile() && !new File(job, "result.json").exists()) cancel(job);
        } catch (IOException error) { AppEventLogger.event(context, "shell_work_cancel_failed " + error); }
    }
    static File job(Context context, String type, String id) throws IOException {
        if (!type.matches("patch|log|configuration|upload|shanghai")
                || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Invalid operation ID");
        return ShellWorkFiles.directory(new File(ShellWorkFiles.root(context), type + "-" + id));
    }

    static File prepare(Context context, String type, String id, JSONObject input) throws Exception {
        File directory = job(context, type, id);
        File request = new File(directory, "request.json");
        if (!request.exists()) ShellWorkFiles.write(request, new JSONObject().put("type", type)
                .put("id", id).put("uid", context.getApplicationInfo().uid)
                .put("version", BuildConfig.VERSION_CODE)
                .put("apk", context.getApplicationInfo().sourceDir)
                .put("boot", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1))
                .put("input", input));
        if ("log".equals(type)) logPins.put(directory.getPath(), ShellWorkEntryPoint.strings(input.getJSONArray("days")));
        return directory;
    }

    static JSONObject await(Context context, File directory, Consumer<JSONObject> progress) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IOException("Shell job cannot wait on UI thread");
        String lastProgress = "";
        long startedWaiting = android.os.SystemClock.elapsedRealtime();
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!new File(directory, "result.json").exists()) {
                JSONObject request = ShellWorkFiles.read(new File(directory, "request.json"));
                boolean cleanupOnly = "shanghai".equals(request.optString("type"))
                        && "cleanup".equals(request.getJSONObject("input").optString("action"));
                try { InstrumentProxyManager.get(context).launchWork(directory, cleanupOnly); }
                catch (IOException error) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    throw error;
                }
            }
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                serviceSigning(context, directory);
                JSONObject current = ShellWorkFiles.read(new File(directory, "progress.json"));
                String serialized = current.toString();
                if (progress != null && !serialized.equals(lastProgress)) progress.accept(current);
                lastProgress = serialized;
                File resultFile = new File(directory, "result.json");
                if (!resultFile.exists() && android.os.SystemClock.elapsedRealtime() - startedWaiting > 15_000
                        && !ShellWorkFiles.running(directory)) {
                    // Reconcile through the owner: a started operation is never replayed.
                    InstrumentProxyManager.get(context).launchWork(directory);
                    if (!resultFile.exists()) throw new IOException("Shell worker unavailable; artifacts retained");
                }
                if (ShellWorkFiles.settled(directory)) {
                    logPins.remove(directory.getPath());
                    JSONObject result = ShellWorkFiles.read(resultFile);
                    if (!result.optBoolean("ok")) throw new IOException(result.optString("error", "Shell work failed"));
                    return result;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException cancelled) {
            cancel(directory);
            // Fence resource cleanup: the old worker must stop touching its artifacts first.
            long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            while (!ShellWorkFiles.settled(directory)
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
            if (!ShellWorkFiles.settled(directory)) InstrumentProxyManager.get(context).cancelWork(directory);
            if (ShellWorkFiles.settled(directory)) logPins.remove(directory.getPath());
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Shell work cancelled; artifacts retained until worker exits");
        }
    }

    static void cancel(File directory) throws IOException {
        ShellWorkFiles.write(new File(directory, "cancel"), new JSONObject());
    }

    static synchronized void serviceSigning(Context context, File directory) throws Exception {
        JSONObject request = ShellWorkFiles.read(new File(directory, "sign.json"));
        String id = request.optString("id");
        if (id.isEmpty() || id.equals(ShellWorkFiles.read(new File(directory, "signed.json")).optString("id"))) return;
        File target = ShellWorkFiles.inside(ShellWorkFiles.root(context), request.getString("directory"));
        JSONObject result = new JSONObject().put("id", id);
        try { NavigatorPatchPipeline.signSet(target); result.put("ok", true); }
        catch (Exception error) { result.put("ok", false).put("error", error.toString()); }
        ShellWorkFiles.write(new File(directory, "signed.json"), result);
    }

    static boolean hasRequest(Context context, String type, String id) {
        try { return new File(job(context, type, id), "request.json").isFile(); }
        catch (IOException ignored) { return false; }
    }
}
