package com.bydhud.app;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;

/** IO-thread observer of a durable shell patch operation. */
final class NavigatorPatchWorkerClient {
    static final int MSG_SCAN = 1;
    static final int MSG_PREPARE = 2;
    static final String KEY_SCAN = "scan";
    static final String KEY_INPUT = "input";
    static final String KEY_OUTPUT_RESULT = "output_result";
    static final String KEY_TRANSACTION = "transaction";
    static final String KEY_OPTIONAL_APPLIED = "optional_applied";

    private NavigatorPatchWorkerClient() {
    }

    static NavigatorPatchPipeline.ScanResult scan(Context context,
            NavigatorPatchStore.Profile profile, String operation, File source, File output)
            throws Exception {
        Bundle request = request(context, MSG_SCAN, operation,
                profile, source, output, null, null, false);
        return NavigatorPatchPipeline.workerUnbundle(
                request.getBundle(KEY_SCAN));
    }

    static NavigatorPatchPipeline.WorkerPatchResult prepare(Context context,
            NavigatorPatchStore.Profile profile, String operation, File source,
            File transaction, NavigatorPatchPipeline.ScanResult expected) throws Exception {
        Bundle request = request(context, MSG_PREPARE, operation,
                profile, source, null, transaction, expected, false);
        Bundle payload = request.getBundle(KEY_SCAN);
        if (payload == null) throw new IOException("Patcher returned no result");
        NavigatorPatchPipeline.ScanResult input = NavigatorPatchPipeline.workerUnbundle(
                payload.getBundle(KEY_INPUT));
        NavigatorPatchPipeline.ScanResult output = NavigatorPatchPipeline.workerUnbundle(
                payload.getBundle(KEY_OUTPUT_RESULT));
        File resultTransaction = new File(payload.getString(
                KEY_TRANSACTION, transaction.getAbsolutePath()));
        return new NavigatorPatchPipeline.WorkerPatchResult(input, output, resultTransaction,
                payload.getBoolean(KEY_OPTIONAL_APPLIED, false));
    }

    static NavigatorPatchPipeline.ScanResult inspectInstalled(Context context,
            NavigatorPatchStore.Profile profile, String operation, File scratch)
            throws Exception {
        Bundle response = request(context, MSG_SCAN, operation,
                profile, null, scratch, null, null, false);
        return NavigatorPatchPipeline.workerUnbundle(
                response.getBundle(KEY_SCAN));
    }

    static NavigatorPatchPipeline.ScanResult inspectDirectory(Context context,
            NavigatorPatchStore.Profile profile, String operation, File directory)
            throws Exception {
        Bundle response = request(context, MSG_SCAN, operation,
                profile, directory, null, null, null, true);
        return NavigatorPatchPipeline.workerUnbundle(
                response.getBundle(KEY_SCAN));
    }

    private static Bundle request(Context context, int command, String operation,
            NavigatorPatchStore.Profile profile, File source, File output, File transaction,
            NavigatorPatchPipeline.ScanResult expected, boolean directory) throws Exception {
        if (operation == null || operation.isEmpty() || profile == null)
            throw new IOException("Navigator inspection identity is incomplete");
        org.json.JSONObject input = new org.json.JSONObject().put("command", command)
                .put("operation", operation).put("profile", profile.id)
                .put("source", source == null ? "" : source.getAbsolutePath())
                .put("output", output == null ? "" : output.getAbsolutePath())
                .put("transaction", transaction == null ? "" : transaction.getAbsolutePath())
                .put("directory", directory)
                .put("certificate", NavigatorSigningKey.localCertificateSha256())
                .put("expected", ShellWorkFiles.json(expected == null ? null : NavigatorPatchPipeline.workerBundle(expected)));
        String id = operation + "-" + command + "-" + Integer.toHexString(
                (String.valueOf(source) + "|" + output + "|" + transaction).hashCode());
        NavigatorPatchStore.OperationSnapshot operationState =
                NavigatorPatchStore.operation(context, profile);
        boolean operationOwned = operation.equals(operationState.operationToken);
        boolean standaloneInspection = command == MSG_SCAN
                && !operationOwned;
        long startedAt = operationOwned ? operationState.startedAt : System.currentTimeMillis();
        input.put("startedAt", startedAt);
        NavigatorPatchReportStore.begin(context, operation, profile.id,
                command == MSG_PREPARE
                        ? NavigatorPatchStore.OP_PATCH : "INSPECT",
                startedAt,
                new org.json.JSONObject());
        File job = null;
        boolean reportFinished = false;
        try {
            job = ShellWorkClient.prepare(context, "patch", id, input);
        boolean primary = !directory && (transaction != null || output != null && output.getName().contains("-worker-scan-"));
        if (primary) ShellRuntimeSession.prefs(context).edit().putString("patch_" + profile.id, job.getPath()).commit();
        org.json.JSONObject completed = ShellWorkClient.await(context, job, null);
        if (completed.has("report")) NavigatorPatchReportStore.recordStage(context, operation,
                "SHELL_REPORT", "SUCCESS", "Retained shell worker report", completed.getJSONObject("report"));
        Bundle request = new Bundle();
        Bundle payload = ShellWorkFiles.bundle(completed.getJSONObject("payload"));
        request.putBundle(KEY_SCAN, payload);
        if (standaloneInspection) {
            NavigatorPatchPipeline.ScanResult inspected =
                    NavigatorPatchPipeline.workerUnbundle(payload);
            NavigatorPatchPipeline.reportScan(context, operation, "INSPECTION_COMPLETE", inspected);
            NavigatorPatchReportStore.finish(context, operation, "SUCCESS",
                    "Navigator inspection completed");
            reportFinished = true;
        }
        return request;
        } catch (Exception failure) {
            if (standaloneInspection && !reportFinished) {
                try {
                    NavigatorPatchReportStore.finish(context, operation, "FAILED",
                            failure.getMessage() == null ? failure.getClass().getSimpleName()
                                    : failure.getMessage());
                } catch (IOException reportFailure) {
                    reportFailure.addSuppressed(failure);
                    throw reportFailure;
                }
            }
            throw failure;
        }
    }

    static boolean retained(Context context, NavigatorPatchStore.Profile profile) {
        String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
        if (path.isEmpty()) return false;
        try { return !ShellWorkClient.terminal(
                ShellWorkClient.state(context, new File(path)).optString("state")); }
        catch (IOException unavailable) { return true; }
    }

    static boolean pending(Context context, NavigatorPatchStore.Profile profile) {
        String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
        if (path.isEmpty()) return false;
        NavigatorPatchStore.OperationSnapshot state = NavigatorPatchStore.operation(context, profile);
        if (state.operationToken.isEmpty() || !state.busy()
                || NavigatorPatchStore.READY_TO_INSTALL.equals(state.phase)) return false;
        try {
            org.json.JSONObject expected = ShellWorkClient.expected(context, new File(path));
            org.json.JSONObject input = expected.optJSONObject("input");
            return input != null && state.operationToken.equals(input.optString("operation"))
                    && !ShellWorkClient.terminal(ShellWorkClient.state(context,
                    new File(path)).optString("state"));
        } catch (Exception unavailable) { return false; }
    }

    /** Runs before mayRestore so a device reboot cannot strand navigator UI in a busy phase. */
    static void reconcileBoot(Context context) {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return;
        for (NavigatorPatchStore.Profile profile : NavigatorPatchStore.Profile.values()) {
            NavigatorPatchStore.OperationSnapshot operation =
                    NavigatorPatchStore.operation(context, profile);
            if (!operation.busy() || NavigatorPatchStore.READY_TO_INSTALL.equals(operation.phase)) continue;
            int startedBoot = NavigatorPatchStore.operationBoot(context, profile);
            if (startedBoot == boot) continue;
            String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
            if (!path.isEmpty()) {
                try {
                    org.json.JSONObject expected = ShellWorkClient.expected(context, new File(path));
                    org.json.JSONObject input = expected.optJSONObject("input");
                    if (input != null && operation.operationToken.equals(input.optString("operation"))
                            && expected.optInt("boot", -1) == boot) {
                        NavigatorPatchStore.setOperationBoot(context, profile, boot);
                        continue;
                    }
                } catch (Exception ignored) { }
            }
            String detail = "Interrupted by device reboot before navigator work completed";
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.FAILED, detail);
            ShellRuntimeSession.prefs(context).edit().remove("patch_" + profile.id).commit();
            String report = NavigatorPatchStore.reportId(context, profile);
            if (!report.isEmpty()) {
                try { NavigatorPatchReportStore.finish(context, report, "INTERRUPTED", detail); }
                catch (IOException error) {
                    AppEventLogger.event(context, "navigator_patch interrupted_report_failed profile="
                            + profile.id + " error=" + error.getClass().getSimpleName());
                }
            }
        }
    }

    static void restore(Context context) {
        for (NavigatorPatchStore.Profile profile : NavigatorPatchStore.Profile.values()) {
            if (!pending(context, profile)) continue;
            File job = new File(ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, ""));
            new Thread(() -> NavigatorPatchPipeline.recoverShellWork(context, profile, job),
                    "patch-shell-reattach-" + profile.id).start();
        }
    }

    static org.json.JSONObject runInShell(Context context, org.json.JSONObject input) throws Exception {
        String profile = input.getString("profile");
        String operation = input.getString("operation");
        File source = input.optString("source").isEmpty() ? null : new File(input.getString("source"));
        NavigatorPatchReportStore.begin(context, operation, profile, "SHELL",
                input.optLong("startedAt", System.currentTimeMillis()), new org.json.JSONObject());
        Bundle payload;
        if (input.getInt("command") == MSG_PREPARE) {
            NavigatorPatchPipeline.WorkerPatchResult result = NavigatorPatchPipeline.workerPrepare(context, profile,
                    source, new File(input.getString("transaction")), NavigatorPatchPipeline.workerUnbundle(
                    ShellWorkFiles.bundle(input.getJSONObject("expected"))), operation);
            payload = new Bundle();
            payload.putBundle(KEY_INPUT, NavigatorPatchPipeline.workerBundle(result.input));
            payload.putBundle(KEY_OUTPUT_RESULT, NavigatorPatchPipeline.workerBundle(result.output));
            payload.putString(KEY_TRANSACTION, result.transaction.getPath());
            payload.putBoolean(KEY_OPTIONAL_APPLIED, result.optionalApplied);
        } else {
            NavigatorPatchPipeline.ScanResult result = input.optBoolean("directory")
                    ? NavigatorPatchPipeline.workerInspectDirectory(context, profile, source)
                    : NavigatorPatchPipeline.workerScan(context, profile, source, new File(input.getString("output")));
            payload = NavigatorPatchPipeline.workerBundle(result);
            NavigatorPatchPipeline.reportScan(context, operation, "CHECK_COMPLETE", result);
            NavigatorPatchReportStore.finish(context, operation, "SUCCESS", "Navigator inspection completed");
        }
        return new org.json.JSONObject().put("payload", ShellWorkFiles.json(payload))
                .put("report", NavigatorPatchReportStore.exportSnapshot(context));
    }
}
