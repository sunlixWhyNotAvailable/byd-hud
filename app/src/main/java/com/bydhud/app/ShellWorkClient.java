package com.bydhud.app;

import android.content.Context;
import android.provider.Settings;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Reattach by operation ID. Shared storage contains artifacts only, never job authority. */
final class ShellWorkClient {
    private static final String[] TYPES = {"patch", "log", "configuration", "upload", "shanghai"};
    private static final java.util.Map<String, List<String>> logPins =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object CLEANUP_LOCK = new Object();
    private static final java.util.Set<String> CLEANUP_REPORTED = new java.util.HashSet<>();

    private ShellWorkClient() { }

    static java.util.Set<String> retainedArchives(Context context) throws Exception {
        java.util.Set<String> retained = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, ?> entry : ShellRuntimeSession.prefs(context).getAll().entrySet()) {
            if (!entry.getKey().startsWith("share_work_")) continue;
            try {
                JSONObject saved = new JSONObject(String.valueOf(entry.getValue()));
                if (pendingShare(saved) && !saved.optString("file").isEmpty())
                    retained.add(new File(saved.getString("file")).getAbsolutePath());
            } catch (org.json.JSONException unreadableShare) { retained.add("*"); }
        }
        return retained;
    }

    private static boolean pendingShare(JSONObject saved) {
        String phase = saved.optString("phase");
        if ("WAITING_FOR_DESTINATION".equals(phase) || "SAVING_DOCUMENT".equals(phase))
            return ArchiveDocumentSave.isPendingLogSave(saved.optString("id"));
        if ("SAVE_RESULT".equals(phase)) return false;
        return !phase.isEmpty() && !"READY".equals(phase) && !"SENT".equals(phase)
                && !"FAILED".equals(phase) && !"CANCELLED".equals(phase);
    }

    /** One transport acquisition per pass; results never authorize launch or signing. */
    static long retireExports(Context context, boolean logs) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IOException("Export cleanup cannot wait on UI thread");
        synchronized (CLEANUP_LOCK) {
            long start = android.os.SystemClock.elapsedRealtime();
            File root = ShellWorkFiles.root(context);
            File[] directories = root.listFiles();
            if (directories == null) {
                if (root.exists()) throw new IOException("artifact_directory_unreadable");
                return Long.MAX_VALUE;
            }
            java.util.Set<String> pending = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, ?> entry : ShellRuntimeSession.prefs(context).getAll().entrySet()) {
                if (!entry.getKey().startsWith("share_work_")) continue;
                try {
                    JSONObject saved = new JSONObject(String.valueOf(entry.getValue()));
                    if (pendingShare(saved))
                        pending.add(entry.getKey().substring("share_work_".length()));
                } catch (org.json.JSONException unreadableShare) { /* retainedArchives protects unknown archives. */ }
            }
            java.util.Set<String> retained = retainedArchives(context);
            List<JSONObject> jobs = new ArrayList<>();
            for (File directory : directories) {
                String name = directory.getName();
                if (!(logs ? name.startsWith("log-") || name.startsWith("upload-")
                        : name.startsWith("configuration-"))) continue;
                String type = name.substring(0, name.indexOf('-'));
                String id = name.substring(type.length() + 1);
                if (!id.matches("[A-Za-z0-9_-]{1,80}")) continue;
                JSONObject hint = new JSONObject().put("name", name)
                        .put("protected", pending.contains(id)
                                || (id.endsWith("-upload") && pending.contains(id.substring(0, id.length() - 7))));
                try {
                    JSONObject expected = expected(context, directory);
                    hint.put("stage", expected.optString("stage"))
                            .put("createdAt", expected.optLong("createdAt"));
                } catch (IOException absentOrLegacy) { /* Shell checks ownership and activity independently. */ }
                jobs.add(hint);
            }
            long next = Long.MAX_VALUE;
            long bytes = 0;
            int removed = 0, checked = 0, kept = 0, cleaned = 0, reported = 0;
            for (int from = 0; from < jobs.size(); from += 128) {
                JSONArray batch = new JSONArray();
                java.util.Set<String> batchRetained = new java.util.HashSet<>();
                for (int i = from; i < Math.min(from + 128, jobs.size()); i++) {
                    JSONObject job = jobs.get(i);
                    batch.put(job);
                    String prefix = new File(root, job.getString("name")).getAbsolutePath() + File.separator;
                    for (String path : retained) if (path.startsWith(prefix)) batchRetained.add(path);
                }
                // A large chooser backlog must not reject otherwise independent jobs.
                if (retained.contains("*") || batchRetained.size() > 256) {
                    batchRetained.clear();
                    batchRetained.add("*");
                }
                Bundle reply = InstrumentProxyManager.get(context).retireExportArtifacts(
                        new JSONObject().put("jobs", batch).put("retained", new JSONArray(batchRetained)).toString(), from == 0);
                JSONArray results = new JSONArray(reply.getString("results", "[]"));
                for (int i = 0; i < results.length(); i++) {
                    JSONObject result = results.getJSONObject(i);
                    checked++;
                    removed += result.optInt("files");
                    if (result.optInt("files") > 0) cleaned++; else kept++;
                    bytes += result.optLong("bytes");
                    next = Math.min(next, result.optLong("next", Long.MAX_VALUE));
                    String name = result.getString("name"), reason = result.getString("reason");
                    if (result.optBoolean("settled") && name.startsWith("log-")) {
                        String id = name.substring(4);
                        logPins.remove(id);
                        try {
                            JSONObject expected = expected(context, new File(root, name));
                            // Current observers own their result record; never overwrite it here.
                            if (!terminal(expected.optString("stage"))
                                    && (expected.optInt("version") != BuildConfig.VERSION_CODE
                                    || "REGISTER_FAILED".equals(expected.optString("stage")))) {
                                expected.put("stage", result.optString("state", "INTERRUPTED"));
                                writeExpectation(context, expected);
                            }
                        } catch (IOException oldFormat) { /* No app record is needed to retire legacy artifacts. */ }
                    }
                    if (reported < 16 && (result.optInt("files") > 0 || (!"settled".equals(reason)
                            && CLEANUP_REPORTED.size() < 256 && CLEANUP_REPORTED.add(name + ":" + reason)))) {
                        reported++;
                        AppEventLogger.event(context, "shell_export_cleanup id=" + name + " reason=" + reason
                                + " files=" + result.optInt("files") + " bytes=" + result.optLong("bytes"));
                    }
                }
            }
            if (checked > 0) AppEventLogger.event(context, "shell_export_cleanup_summary jobs=" + checked
                    + " cleaned=" + cleaned + " kept=" + kept + " files=" + removed + " bytes=" + bytes + " duration_ms="
                    + (android.os.SystemClock.elapsedRealtime() - start));
            return next;
        }
    }

    static boolean pinsDay(String day) {
        for (List<String> days : logPins.values()) if (days.contains(day)) return true;
        return false;
    }

    static void restorePins(Context context) {
        try {
            File[] records = ShellWorkFiles.expectationRoot(context).listFiles(
                    (parent, name) -> name.startsWith("log-") && name.endsWith(".json"));
            if (records == null) return;
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -2);
            if (!ShellRuntimeSession.mayRestore(context)) return;
            List<PinRestore> pending = new ArrayList<>();
            for (File record : records) {
                JSONObject expected = ShellWorkFiles.readManifest(record);
                if (expected.optInt("boot", -1) != boot) continue;
                String artifact = expected.optString("artifact");
                if (artifact.isEmpty()) continue;
                JSONObject input = expected.optJSONObject("input");
                String id = expected.optString("id");
                List<String> days = strings(input == null ? null : input.optJSONArray("days"));
                if (terminal(expected.optString("stage"))) {
                    logPins.remove(id);
                    continue;
                }
                // Keep retention state available during Application.onCreate; Binder startup is
                // reconciled asynchronously below and never blocks the main thread.
                logPins.put(id, days);
                pending.add(new PinRestore(id, new File(artifact), days));
            }
            if (!pending.isEmpty()) {
                Context app = context.getApplicationContext();
                Thread restore = new Thread(() -> {
                    for (PinRestore pin : pending) {
                        if (!ShellRuntimeSession.mayRestore(app)) {
                            logPins.remove(pin.id);
                            continue;
                        }
                        try {
                            JSONObject trusted = state(app, pin.artifact);
                            if (terminal(trusted.optString("state"))) logPins.remove(pin.id);
                            else logPins.put(pin.id, pin.days);
                        } catch (IOException unavailable) {
                            // Keep the durable private expectation's pin until trusted status is
                            // available; removing it here could prune evidence for live work.
                            AppEventLogger.event(app, "shell_pin_reconcile_pending id="
                                    + safeId(pin.id) + " reason=" + safeError(unavailable));
                        }
                    }
                }, "BydHudShellPinRestore");
                restore.setDaemon(true);
                restore.start();
            }
        } catch (Exception error) {
            AppEventLogger.event(context, "shell_pins_restore_failed " + safeError(error));
        }
    }

    private static final class PinRestore {
        final String id;
        final File artifact;
        final List<String> days;

        PinRestore(String id, File artifact, List<String> days) {
            this.id = id;
            this.artifact = artifact;
            this.days = days;
        }
    }

    static void cancelAll(Context context) {
        try {
            File[] records = ShellWorkFiles.expectationRoot(context).listFiles(
                    (parent, name) -> name.endsWith(".json"));
            if (records == null) return;
            for (File record : records) {
                JSONObject expected = ShellWorkFiles.readManifest(record);
                File job = new File(expected.optString("artifact", ""));
                if (expected.optString("artifact", "").isEmpty()) continue;
                try {
                    if (!terminal(state(context, job).optString("state"))) cancel(context, job);
                } catch (IOException unavailable) {
                    AppEventLogger.event(context, "shell_work_cancel_failed id="
                            + safeId(expected.optString("id")) + " reason=" + safeError(unavailable));
                }
            }
        } catch (Exception error) {
            AppEventLogger.event(context, "shell_work_cancel_failed " + safeError(error));
        }
    }

    static File job(Context context, String type, String id) throws IOException {
        if (!contains(TYPES, type) || id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IOException("Invalid operation ID");
        File root = ShellWorkFiles.directory(ShellWorkFiles.root(context));
        File job = new File(root, type + "-" + id).getCanonicalFile();
        if (!job.getParentFile().equals(root.getCanonicalFile()))
            throw new IOException("Invalid shell artifact directory");
        ShellWorkFiles.directory(job);
        return job;
    }

    static File prepare(Context context, String type, String id, JSONObject input) throws Exception {
        File artifact = job(context, type, id);
        File expectationFile = expectationFile(context, type, id);
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        JSONObject original = new JSONObject(input.toString());
        JSONObject expectation = new JSONObject()
                .put("schema", 1).put("id", id).put("type", type).put("boot", boot)
                .put("version", BuildConfig.VERSION_CODE).put("profile", original.optString("profile"))
                .put("artifact", artifact.getAbsolutePath()).put("input", original)
                .put("allowedResults", new JSONArray().put("SUCCEEDED").put("FAILED").put("CANCELLED"))
                .put("stage", "REGISTERING").put("registrationComplete", false)
                .put("createdAt", System.currentTimeMillis());
        if (expectationFile.isFile()) {
            JSONObject prior = ShellWorkFiles.readManifest(expectationFile);
            if (!sameOperation(prior, expectation))
                throw new IOException("Operation ID is already bound to a different request");
            expectation = prior;
        }
        try {
            JSONArray files = expectation.optJSONArray("snapshotPaths");
            if (files == null) {
                files = snapshotFiles(type, original);
                expectation.put("snapshotPaths", files);
            }
            expectation.put("stage", "REGISTERING");
            expectation.remove("error");
            expectation.remove("diagnostic");
            writeExpectation(context, expectation);
            if ("log".equals(type)) logPins.put(id, strings(original.optJSONArray("days")));
            InstrumentProxyManager proxy = InstrumentProxyManager.get(context);
            Bundle registered;
            try (ParcelFileDescriptor request = ParcelFileDescriptor.open(expectationFile, ParcelFileDescriptor.MODE_READ_ONLY)) {
                registered = proxy.registerWork(id, type, artifact, original, request,
                        0, new ParcelFileDescriptor[0], false, boot);
            }
            int next = registered.getInt("next_snapshot", -1);
            while ("REGISTERING".equals(registered.getString("state"))) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Shell registration cancelled");
                if (next < 0 || next > files.length()) throw new IOException("Invalid shell input cursor");
                int count = Math.min(ShellWorkSnapshots.BATCH_SIZE, files.length() - next);
                ParcelFileDescriptor[] descriptors = new ParcelFileDescriptor[count];
                try {
                    for (int i = 0; i < count; i++) descriptors[i] = ParcelFileDescriptor.open(
                            canonicalSnapshot(files.getString(next + i)), ParcelFileDescriptor.MODE_READ_ONLY);
                    registered = proxy.registerWork(id, type, artifact, original, null, next,
                            descriptors, next + count == files.length(), boot);
                } finally {
                    for (ParcelFileDescriptor descriptor : descriptors)
                        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
                }
                int accepted = registered.getInt("next_snapshot", -1);
                if (accepted != next + count) throw new IOException("Shell input batch was not accepted completely");
                next = accepted;
            }
            String stage = registered.getString("state", "");
            if (!("REGISTERED".equals(stage) || "RUNNING".equals(stage) || "SUCCEEDED".equals(stage)))
                throw new IOException("Shell registration ended in " + stage);
            expectation.put("registrationComplete", true).put("stage", stage);
            writeExpectation(context, expectation);
            AppEventLogger.event(context, "shell_work_registered id=" + safeId(id)
                    + " type=" + type + " files=" + files.length());
        } catch (Exception error) {
            boolean unknown = error instanceof InstrumentProxyManager.ShellWorkOutcomeUnknownException;
            boolean notDispatched = error instanceof InstrumentProxyManager.ShellWorkNotDispatchedException;
            boolean interrupted = !unknown && !notDispatched
                    && (error instanceof java.io.InterruptedIOException || Thread.currentThread().isInterrupted());
            String stage = unknown ? "REGISTER_UNKNOWN" : notDispatched ? "REGISTERING" : "REGISTER_FAILED";
            try {
                expectation.put("stage", stage).put("error", shortFailure(error))
                        .put("diagnostic", FailureDiagnostics.formatBounded(
                                error, "register-work", stage, 24 * 1024));
            } catch (Exception recordFailure) {
                error.addSuppressed(recordFailure);
            }
            // A lost Binder reply can follow a durable batch; keep its cursor for recovery.
            if (interrupted) {
                try {
                    cancel(context, artifact);
                    expectation.put("stage", "CANCELLED");
                    logPins.remove(id);
                } catch (IOException unavailable) {
                    error.addSuppressed(unavailable);
                }
            }
            try { writeExpectation(context, expectation); }
            catch (IOException recordFailure) { error.addSuppressed(recordFailure); }
            if (interrupted) Thread.currentThread().interrupt();
            throw error;
        }
        return artifact;
    }

    static JSONObject expected(Context context, File artifactJob) throws IOException {
        File artifact = validateArtifact(context, artifactJob);
        String name = artifact.getName();
        String type = null;
        String id = null;
        for (String candidate : TYPES) {
            String prefix = candidate + "-";
            if (name.startsWith(prefix)) { type = candidate; id = name.substring(prefix.length()); break; }
        }
        if (type == null || id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IOException("Unknown shell operation artifact");
        JSONObject expected = ShellWorkFiles.readManifest(expectationFile(context, type, id));
        if (!sameArtifact(expected, artifact) || !id.equals(expected.optString("id"))
                || !type.equals(expected.optString("type"))) {
            throw new IOException("App-side shell operation expectation is missing or mismatched");
        }
        return expected;
    }

    /** Status and result are fetched only from the UID/generation-checked Binder endpoint. */
    static JSONObject state(Context context, File artifactJob) throws IOException {
        JSONObject expected = expected(context, artifactJob);
        String before = expected.toString();
        try {
            Bundle result = InstrumentProxyManager.get(context).workState(expected.getString("id"));
            JSONObject value = new JSONObject().put("id", expected.getString("id"))
                    .put("state", result.getString("state"))
                    .put("progress", jsonObject(result, "progress_json"))
                    .put("result", jsonObject(result, "result_json"))
                    .put("signRequest", jsonObject(result, "sign_request_json"));
            String phase = value.getJSONObject("progress").optString("phase");
            if (!phase.isEmpty()) expected.put("stage", phase);
            if (terminal(value.optString("state"))) {
                expected.put("stage", value.optString("state"));
                if ("SUCCEEDED".equals(value.optString("state")))
                    expected.put("acceptedResult", value.getJSONObject("result"));
                logPins.remove(expected.optString("id"));
            }
            if (!before.equals(expected.toString())) writeExpectation(context, expected);
            return value;
        } catch (org.json.JSONException | RuntimeException error) {
            throw new IOException(FailureDiagnostics.format(
                    error, "work-state", "decode-or-persist"), error);
        }
    }

    static void launch(Context context, File artifactJob) throws IOException {
        launch(context, artifactJob, false);
    }

    private static void launch(Context context, File artifactJob, boolean verifiedRegisteredRetry)
            throws IOException {
        JSONObject expected = expected(context, artifactJob);
        JSONObject input = expected.optJSONObject("input");
        boolean cleanupOnly = "shanghai".equals(expected.optString("type"))
                && input != null && "cleanup".equals(input.optString("action"));
        String previousStage = expected.optString("stage");
        if ("LAUNCH_FAILED".equals(previousStage)) {
            JSONObject observed = state(context, artifactJob);
            String status = observed.optString("state");
            if ("RUNNING".equals(status) || terminal(status)) return;
            throw new IOException(expected.optString("error", "Previous shell launch failed"));
        }
        if ("LAUNCH_UNKNOWN".equals(previousStage) || "LAUNCH_PENDING".equals(previousStage)
                || "LAUNCH_SENT".equals(previousStage) || "CANCEL_PENDING".equals(previousStage)
                || "CANCEL_UNKNOWN".equals(previousStage) || "CANCEL_SENT".equals(previousStage)) {
            JSONObject observed = state(context, artifactJob);
            String status = observed.optString("state");
            if ("RUNNING".equals(status) || terminal(status) || "UNCERTAIN".equals(status)) return;
            if (!"REGISTERED".equals(status))
                throw new IOException("Shell launch state is " + status + "; no replay was sent");
            if ("LAUNCH_UNKNOWN".equals(previousStage) || "LAUNCH_PENDING".equals(previousStage)
                    || "CANCEL_PENDING".equals(previousStage) || "CANCEL_UNKNOWN".equals(previousStage)
                    || "CANCEL_SENT".equals(previousStage) || !verifiedRegisteredRetry) return;
        }
        try {
            expected.put("stage", "LAUNCH_PENDING");
            expected.remove("error");
            expected.remove("diagnostic");
            writeExpectation(context, expected);
        } catch (org.json.JSONException error) {
            throw new IOException("Cannot persist shell launch intent", error);
        }
        try {
            InstrumentProxyManager.get(context).launchWork(expected.optString("id"), cleanupOnly);
            try { expected.put("stage", "LAUNCH_SENT"); }
            catch (org.json.JSONException error) {
                throw new IOException("Cannot record shell launch acknowledgement", error);
            }
            writeExpectation(context, expected);
        } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException error) {
            recordLaunchFailure(context, expected, error, "LAUNCH_UNKNOWN");
            throw error;
        } catch (InstrumentProxyManager.ShellWorkNotDispatchedException error) {
            recordLaunchFailure(context, expected, error, "REGISTERED");
            throw error;
        } catch (IOException | RuntimeException error) {
            recordLaunchFailure(context, expected, error, "LAUNCH_FAILED");
            throw error;
        }
    }

    private static void recordLaunchFailure(Context context, JSONObject expected,
            Throwable failure, String stage) {
        try {
            expected.put("stage", stage).put("error", shortFailure(failure))
                    .put("diagnostic", FailureDiagnostics.formatBounded(
                            failure, "launch-work", stage, 24 * 1024));
            writeExpectation(context, expected);
        } catch (Exception recordFailure) {
            failure.addSuppressed(recordFailure);
        }
    }

    static JSONObject await(Context context, File artifact, Consumer<JSONObject> progress) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IOException("Shell job cannot wait on UI thread");
        JSONObject expected = expected(context, artifact);
        if (!expected.optBoolean("registrationComplete", true)
                || "REGISTERING".equals(expected.optString("stage"))
                || "REGISTER_UNKNOWN".equals(expected.optString("stage"))
                || "REGISTER_FAILED".equals(expected.optString("stage"))) {
            while (true) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Shell registration remains pending; artifacts retained");
                try {
                    prepare(context, expected.getString("type"), expected.getString("id"),
                            expected.getJSONObject("input"));
                    break;
                } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException unknown) {
                    if (!"register-work".equals(unknown.operation)) throw unknown;
                    try { Thread.sleep(100); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        unknown.addSuppressed(interrupted);
                        throw new InstrumentProxyManager.ShellWorkOutcomeUnknownException(
                                "register-work", expected.optString("id"),
                                "wait was interrupted while registration remained unresolved",
                                unknown.retainedCall, unknown);
                    }
                } catch (InstrumentProxyManager.ShellWorkNotDispatchedException busy) {
                    try { Thread.sleep(100); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        java.io.InterruptedIOException failure = new java.io.InterruptedIOException(
                                "Shell registration was not dispatched before interruption");
                        failure.initCause(interrupted);
                        throw failure;
                    }
                }
            }
        }
        long start = android.os.SystemClock.elapsedRealtime();
        String lastProgress = "";
        boolean cancelSent = false;
        boolean registeredRetrySent = false;
        try {
            while (true) {
                try {
                    launch(context, artifact);
                    break;
                } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException unknown) {
                    if ("launch-work".equals(unknown.operation)) break;
                    if (!"work-state".equals(unknown.operation)
                            || Thread.currentThread().isInterrupted()) throw unknown;
                    Thread.sleep(100);
                } catch (InstrumentProxyManager.ShellWorkNotDispatchedException busy) {
                    Thread.sleep(100);
                }
            }
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                JSONObject current;
                try { current = state(context, artifact); }
                catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException unknown) {
                    if (!"work-state".equals(unknown.operation)
                            || Thread.currentThread().isInterrupted()) throw unknown;
                    Thread.sleep(100);
                    continue;
                } catch (InstrumentProxyManager.ShellWorkNotDispatchedException busy) {
                    Thread.sleep(100);
                    continue;
                }
                if ("UNCERTAIN".equals(current.optString("state"))) {
                    JSONObject result = current.optJSONObject("result");
                    String detail = result == null ? "unknown"
                            : result.optString("diagnostic", result.optString("error", "unknown"));
                    throw new InstrumentProxyManager.ShellWorkOutcomeUnknownException(
                            "worker-result", expected.optString("id"),
                            "helper persisted UNCERTAIN state: " + detail, false,
                            new IOException(detail));
                }
                JSONObject sign = current.optJSONObject("signRequest");
                if (sign != null && !sign.optString("id").isEmpty()) serviceSigning(context, artifact, sign);
                JSONObject phase = current.optJSONObject("progress");
                String serialized = phase == null ? "{}" : phase.toString();
                if (progress != null && !serialized.equals(lastProgress)) progress.accept(phase);
                lastProgress = serialized;
                String status = current.optString("state");
                if ("SUCCEEDED".equals(status)) return current.getJSONObject("result");
                if ("FAILED".equals(status) || "CANCELLED".equals(status)
                        || "INTERRUPTED".equals(status)) {
                    JSONObject result = current.optJSONObject("result");
                    IOException failure = new IOException(result == null ? "Shell work " + status.toLowerCase()
                            : result.optString("error", "Shell work " + status.toLowerCase()));
                    if (result != null && !result.optString("diagnostic").isEmpty())
                        failure.addSuppressed(new IOException(result.optString("diagnostic")));
                    throw failure;
                }
                if (!registeredRetrySent && android.os.SystemClock.elapsedRealtime() - start > 15_000
                        && "REGISTERED".equals(status)) {
                    // A registered operation can be relaunched once. Started work is never replayed.
                    launch(context, artifact, true);
                    registeredRetrySent = true;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException cancelled) {
            java.io.InterruptedIOException interrupted = new java.io.InterruptedIOException(
                    "Shell work cancellation requested; artifacts retained until worker completion is confirmed");
            interrupted.initCause(cancelled);
            try {
                if (!cancelSent) { cancel(context, artifact); cancelSent = true; }
            } catch (Exception cancellationFailure) {
                interrupted.addSuppressed(cancellationFailure);
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            Throwable lastStateFailure = null;
            boolean terminalObserved = false;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    JSONObject current = state(context, artifact);
                    String state = current.optString("state");
                    if ("UNCERTAIN".equals(state)) {
                        JSONObject result = current.optJSONObject("result");
                        String detail = result == null ? "unknown"
                                : result.optString("diagnostic", result.optString("error", "unknown"));
                        lastStateFailure = new IOException("Helper state is UNCERTAIN: " + detail);
                    } else if (terminal(state)) {
                        terminalObserved = true;
                        break;
                    }
                } catch (Exception statusFailure) {
                    lastStateFailure = statusFailure;
                }
                try { Thread.sleep(50); }
                catch (InterruptedException repeatedInterrupt) { interrupted.addSuppressed(repeatedInterrupt); }
            }
            if (terminalObserved) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (lastStateFailure != null) interrupted.addSuppressed(lastStateFailure);
            Thread.currentThread().interrupt();
            throw new InstrumentProxyManager.ShellWorkOutcomeUnknownException(
                    "cancel-await", expected.optString("id"),
                    "cancellation was not confirmed within 10 seconds; artifacts were retained",
                    false, interrupted);
        }
    }

    static boolean cancel(Context context, File artifact) throws IOException {
        return cancel(context, artifact, false);
    }

    static boolean cancel(Context context, File artifact, boolean resetAnyMockGps) throws IOException {
        JSONObject expected = expected(context, artifact);
        JSONObject observed = state(context, artifact);
        String status = observed.optString("state");
        if (terminal(status)) return true;
        if ("UNCERTAIN".equals(status))
            throw new InstrumentProxyManager.ShellWorkOutcomeUnknownException(
                    "cancel-work", expected.optString("id"),
                    "helper state is UNCERTAIN; cancellation was not sent", false,
                    new IOException(observed.optJSONObject("result") == null ? "unknown"
                            : observed.optJSONObject("result").optString("diagnostic", "unknown")));
        String previousStage = expected.optString("stage");
        boolean previousResetAny = expected.optBoolean("cancelResetAny");
        if ("CANCEL_SENT".equals(previousStage)) {
            if (previousResetAny || !resetAnyMockGps)
                return true;
        }
        if ("CANCEL_PENDING".equals(previousStage) || "CANCEL_UNKNOWN".equals(previousStage)) {
            if (previousResetAny || !resetAnyMockGps)
                throw new InstrumentProxyManager.ShellWorkOutcomeUnknownException(
                        "cancel-work", expected.optString("id"),
                        "the previous cancellation request is unresolved; no replay was sent", false,
                        new IOException(expected.optString("diagnostic", "Cancellation outcome unknown")));
        }
        try {
            expected.put("stage", "CANCEL_PENDING").put("cancelResetAny", resetAnyMockGps);
            writeExpectation(context, expected);
        } catch (org.json.JSONException error) {
            throw new IOException("Cannot persist cancellation intent", error);
        }
        try {
            if (!InstrumentProxyManager.get(context).cancelWork(
                    expected.optString("id"), resetAnyMockGps)) {
                throw new IOException("Trusted shell worker did not acknowledge cancellation");
            }
            try {
                expected.put("stage", "CANCEL_SENT");
                expected.remove("error");
                expected.remove("diagnostic");
            }
            catch (org.json.JSONException error) {
                throw new IOException("Cannot persist cancellation acknowledgement", error);
            }
            writeExpectation(context, expected);
        } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException error) {
            recordCancelFailure(context, expected, error, "CANCEL_UNKNOWN");
            throw error;
        } catch (InstrumentProxyManager.ShellWorkNotDispatchedException error) {
            if (previousStage.startsWith("CANCEL_")) {
                try { expected.put("cancelResetAny", previousResetAny); }
                catch (org.json.JSONException recordFailure) { error.addSuppressed(recordFailure); }
            }
            recordCancelFailure(context, expected, error, previousStage);
            throw error;
        } catch (IOException | RuntimeException error) {
            recordCancelFailure(context, expected, error, "CANCEL_FAILED");
            throw error;
        }
        return true;
    }

    private static void recordCancelFailure(Context context, JSONObject expected,
            Throwable failure, String stage) {
        try {
            expected.put("stage", stage).put("cancelResetAny", expected.optBoolean("cancelResetAny"))
                    .put("error", shortFailure(failure))
                    .put("diagnostic", FailureDiagnostics.formatBounded(
                            failure, "cancel-work", stage, 24 * 1024));
            writeExpectation(context, expected);
        } catch (Exception recordFailure) {
            failure.addSuppressed(recordFailure);
        }
    }

    private static String shortFailure(Throwable failure) {
        String text = FailureDiagnostics.format(failure, "", "");
        int newline = text.indexOf('\n');
        String summary = newline < 0 ? text : text.substring(0, newline);
        return summary.length() > 256 ? summary.substring(0, 256) : summary;
    }

    private static void serviceSigning(Context context, File artifact, JSONObject request)
            throws IOException {
        JSONObject expected = expected(context, artifact);
        String signId = request.optString("id");
        boolean completionAttempted = false;
        try {
            JSONObject original = expected.optJSONObject("input");
            String stage = request.optString("stage");
            File target = authorizedSigningTarget(context, expected, request);

            JSONObject signs = expected.optJSONObject("signs");
            if (signs == null) signs = new JSONObject();
            JSONObject prior = signs.optJSONObject(stage);
            if (prior != null && !signId.equals(prior.optString("id")))
                throw new IOException("Signing refused: duplicate output stage");
            if (prior != null && "DONE".equals(prior.optString("state"))) {
                completionAttempted = true;
                completeSign(context, expected, signId, true, "Already signed by this operation");
                return;
            }
            if (prior != null)
                throw new IOException("Signing refused: prior signing attempt was interrupted");

            signs.put(stage, new JSONObject().put("id", signId).put("state", "IN_PROGRESS"));
            expected.put("signs", signs);
            writeExpectation(context, expected);
            boolean ok = false;
            String detail = "";
            try {
                NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(
                        original.optString("profile"));
                if (profile == null) throw new IOException("Unknown PREPARE profile");
                NavigatorApkSet.readDirectory(context, profile, target);
                NavigatorPatchPipeline.signSet(target);
                ok = true;
                signs.put(stage, new JSONObject().put("id", signId).put("state", "DONE"));
            } catch (Exception error) {
                detail = error.toString();
                signs.put(stage, new JSONObject().put("id", signId).put("state", "FAILED"));
            }
            expected.put("signs", signs);
            writeExpectation(context, expected);
            completionAttempted = true;
            completeSign(context, expected, signId, ok, detail);
            if (!ok) throw new IOException("Signing refused: " + detail);
        } catch (Exception error) {
            if (!completionAttempted && !signId.isEmpty()) {
                completionAttempted = true;
                try { completeSign(context, expected, signId, false, error.toString()); }
                catch (IOException completionError) { error.addSuppressed(completionError); }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Signing refused: " + error, error);
        }
    }

    static File authorizedSigningTarget(Context context, JSONObject expected, JSONObject request)
            throws IOException {
        JSONObject original = expected.optJSONObject("input");
        if (!"patch".equals(expected.optString("type")) || original == null
                || original.optInt("command", -1) != NavigatorPatchWorkerClient.MSG_PREPARE) {
            throw new IOException("Signing refused: operation is not an authorized PREPARE");
        }
        String stage = request.optString("stage");
        File transactionRoot = ShellWorkFiles.inside(
                new File(ShellWorkFiles.root(context), "transactions"),
                original.optString("transaction"));
        File allowed = "SIGN_BASELINE".equals(stage) ? new File(transactionRoot, "patched-set")
                : "SIGN_MAP".equals(stage) ? new File(transactionRoot, "map-candidate-set") : null;
        File target = new File(request.optString("directory")).getCanonicalFile();
        if (allowed == null || !target.equals(allowed.getCanonicalFile()))
            throw new IOException("Signing refused: output is outside the approved PREPARE stage");
        return target;
    }

    private static void completeSign(Context context, JSONObject expected, String signId,
            boolean ok, String detail) throws IOException {
        try {
            InstrumentProxyManager.get(context).completeWorkSign(expected.getString("id"),
                    signId, ok, detail == null ? "" : detail);
        } catch (org.json.JSONException | RuntimeException error) {
            throw new IOException("Cannot complete trusted signing request", error);
        }
    }

    static boolean hasRequest(Context context, String type, String id)
            throws InstrumentProxyManager.ShellWorkOutcomeUnknownException {
        try {
            File artifact = job(context, type, id);
            if (java.nio.file.Files.notExists(expectationFile(context, type, id).toPath()))
                return false;
            expected(context, artifact);
            return true;
        } catch (IOException | RuntimeException error) {
            throw new InstrumentProxyManager.ShellWorkOutcomeUnknownException(
                    "read-work-expectation", id,
                    "request presence or identity could not be verified; artifacts retained",
                    false, error);
        }
    }

    static boolean retained(Context context, NavigatorPatchStore.Profile profile) {
        String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
        if (path.isEmpty()) return false;
        try { return !terminal(state(context, new File(path)).optString("state")); }
        catch (IOException unavailable) { return true; }
    }

    static JSONObject expectedForPatch(Context context, NavigatorPatchStore.Profile profile) {
        String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
        if (path.isEmpty()) return null;
        try { return expected(context, new File(path)); }
        catch (IOException unavailable) { return null; }
    }

    static void servicePendingSigning(Context context, File artifact) throws IOException {
        JSONObject current = state(context, artifact);
        JSONObject sign = current.optJSONObject("signRequest");
        if (sign != null && !sign.optString("id").isEmpty()) serviceSigning(context, artifact, sign);
    }

    static JSONArray snapshotFiles(String type, JSONObject input) throws Exception {
        return ShellWorkSnapshots.paths(type, input);
    }

    private static File canonicalSnapshot(String path) throws IOException {
        File original = new File(path);
        if (java.nio.file.Files.isSymbolicLink(original.toPath()))
            throw new IOException("Shell input snapshot refuses symbolic links");
        File file = original.getCanonicalFile();
        if (!file.isFile()) throw new IOException("Shell input snapshot is not a regular file");
        return file;
    }

    private static File expectationFile(Context context, String type, String id) throws IOException {
        if (!contains(TYPES, type) || id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IOException("Invalid shell operation identity");
        return new File(ShellWorkFiles.expectationRoot(context), type + "-" + id + ".json");
    }

    private static void writeExpectation(Context context, JSONObject expected) throws IOException {
        String type = expected.optString("type");
        String id = expected.optString("id");
        ShellWorkFiles.write(expectationFile(context, type, id), expected);
    }

    private static File validateArtifact(Context context, File artifact) throws IOException {
        File root = ShellWorkFiles.root(context).getCanonicalFile();
        File value = artifact.getCanonicalFile();
        if (!value.getParentFile().equals(root)) throw new IOException("Invalid shell artifact path");
        return value;
    }

    private static boolean sameOperation(JSONObject left, JSONObject right) {
        return left.optString("id").equals(right.optString("id"))
                && left.optString("type").equals(right.optString("type"))
                && left.optString("artifact").equals(right.optString("artifact"))
                && left.optInt("boot", -2) == right.optInt("boot", -1)
                && left.optInt("version", -1) == right.optInt("version", -2)
                && left.optJSONObject("input") != null
                && left.optJSONObject("input").toString().equals(right.optJSONObject("input").toString());
    }

    private static boolean sameArtifact(JSONObject expected, File artifact) {
        String path = expected.optString("artifact", "");
        if (path.isEmpty()) return false;
        try { return artifact.equals(new File(path).getCanonicalFile()); }
        catch (IOException invalid) { return false; }
    }

    static boolean terminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state)
                || "CANCELLED".equals(state) || "INTERRUPTED".equals(state);
    }

    private static JSONObject jsonObject(Bundle values, String key) throws org.json.JSONException {
        return new JSONObject(values.getString(key, "{}"));
    }

    private static List<String> strings(JSONArray array) throws org.json.JSONException {
        List<String> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    private static boolean contains(String[] values, String value) {
        for (String candidate : values) if (candidate.equals(value)) return true;
        return false;
    }

    private static String safeId(String value) { return value.matches("[A-Za-z0-9_-]{1,120}") ? value : "unknown"; }
    private static String safeError(Throwable error) { return error == null ? "unknown" : error.getClass().getSimpleName(); }
}
