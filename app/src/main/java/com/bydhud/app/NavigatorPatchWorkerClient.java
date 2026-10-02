package com.bydhud.app;

import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;

/** IO-thread observer of a durable shell patch operation. */
final class NavigatorPatchWorkerClient {

    private NavigatorPatchWorkerClient() {
    }

    static NavigatorPatchPipeline.ScanResult scan(Context context,
            NavigatorPatchStore.Profile profile, String operation, File source, File output)
            throws Exception {
        Bundle request = request(context, NavigatorPatchWorkerService.MSG_SCAN, operation,
                profile, source, output, null, null, false);
        return NavigatorPatchPipeline.workerUnbundle(
                request.getBundle(NavigatorPatchWorkerService.KEY_SCAN));
    }

    static NavigatorPatchPipeline.WorkerPatchResult prepare(Context context,
            NavigatorPatchStore.Profile profile, String operation, File source,
            File transaction, NavigatorPatchPipeline.ScanResult expected) throws Exception {
        Bundle request = request(context, NavigatorPatchWorkerService.MSG_PREPARE, operation,
                profile, source, null, transaction, expected, false);
        Bundle payload = request.getBundle(NavigatorPatchWorkerService.KEY_SCAN);
        if (payload == null) throw new IOException("Patcher returned no result");
        NavigatorPatchPipeline.ScanResult input = NavigatorPatchPipeline.workerUnbundle(
                payload.getBundle(NavigatorPatchWorkerService.KEY_INPUT));
        NavigatorPatchPipeline.ScanResult output = NavigatorPatchPipeline.workerUnbundle(
                payload.getBundle(NavigatorPatchWorkerService.KEY_OUTPUT_RESULT));
        File resultTransaction = new File(payload.getString(
                NavigatorPatchWorkerService.KEY_TRANSACTION, transaction.getAbsolutePath()));
        return new NavigatorPatchPipeline.WorkerPatchResult(input, output, resultTransaction,
                payload.getBoolean(NavigatorPatchWorkerService.KEY_OPTIONAL_APPLIED, false));
    }

    static NavigatorPatchPipeline.ScanResult inspectInstalled(Context context,
            NavigatorPatchStore.Profile profile, String operation, File scratch)
            throws Exception {
        Bundle response = request(context, NavigatorPatchWorkerService.MSG_SCAN, operation,
                profile, null, scratch, null, null, false);
        return NavigatorPatchPipeline.workerUnbundle(
                response.getBundle(NavigatorPatchWorkerService.KEY_SCAN));
    }

    static NavigatorPatchPipeline.ScanResult inspectDirectory(Context context,
            NavigatorPatchStore.Profile profile, String operation, File directory)
            throws Exception {
        Bundle response = request(context, NavigatorPatchWorkerService.MSG_SCAN, operation,
                profile, directory, null, null, null, true);
        return NavigatorPatchPipeline.workerUnbundle(
                response.getBundle(NavigatorPatchWorkerService.KEY_SCAN));
    }

    private static Bundle request(Context context, int command, String operation,
            NavigatorPatchStore.Profile profile, File source, File output, File transaction,
            NavigatorPatchPipeline.ScanResult expected, boolean directory) throws Exception {
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
        File job = ShellWorkClient.prepare(context, "patch", id, input);
        boolean primary = !directory && (transaction != null || output != null && output.getName().contains("-worker-scan-"));
        if (primary) ShellRuntimeSession.prefs(context).edit().putString("patch_" + profile.id, job.getPath()).commit();
        org.json.JSONObject completed = ShellWorkClient.await(context, job, null);
        if (completed.has("report")) NavigatorPatchReportStore.recordStage(context, operation,
                "SHELL_REPORT", "SUCCESS", "Retained shell worker report", completed.getJSONObject("report"));
        Bundle request = new Bundle();
        request.putString(NavigatorPatchWorkerService.KEY_STATUS, NavigatorPatchWorkerService.STATUS_OK);
        request.putBundle(NavigatorPatchWorkerService.KEY_SCAN, ShellWorkFiles.bundle(completed.getJSONObject("payload")));
        String status = request.getString(NavigatorPatchWorkerService.KEY_STATUS, "");
        if (NavigatorPatchWorkerService.STATUS_CANCELLED.equals(status)) {
            throw new NavigatorPatchPipeline.OperationCancelledException();
        }
        if (!NavigatorPatchWorkerService.STATUS_OK.equals(status)) {
            throw new IOException(request.getString(NavigatorPatchWorkerService.KEY_ERROR,
                    "Navigator patcher failed"));
        }
        return request;
    }

    static boolean retained(Context context, NavigatorPatchStore.Profile profile) {
        String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
        return !path.isEmpty() && !ShellWorkFiles.settled(new File(path));
    }

    static boolean pending(Context context, NavigatorPatchStore.Profile profile) {
        String path = ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, "");
        if (path.isEmpty()) return false;
        try {
            org.json.JSONObject input = ShellWorkFiles.read(new File(path, "request.json")).getJSONObject("input");
            NavigatorPatchStore.OperationSnapshot state = NavigatorPatchStore.operation(context, profile);
            return input.getString("operation").equals(state.operationToken)
                    && !NavigatorPatchStore.READY_TO_INSTALL.equals(state.phase)
                    && (state.busy() || retained(context, profile));
        } catch (Exception ignored) { return false; }
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
        NavigatorPatchReportStore.begin(context, operation, profile, "SHELL", System.currentTimeMillis(), new org.json.JSONObject());
        Bundle payload;
        if (input.getInt("command") == NavigatorPatchWorkerService.MSG_PREPARE) {
            NavigatorPatchPipeline.WorkerPatchResult result = NavigatorPatchPipeline.workerPrepare(context, profile,
                    source, new File(input.getString("transaction")), NavigatorPatchPipeline.workerUnbundle(
                    ShellWorkFiles.bundle(input.getJSONObject("expected"))), operation);
            payload = new Bundle();
            payload.putBundle(NavigatorPatchWorkerService.KEY_INPUT, NavigatorPatchPipeline.workerBundle(result.input));
            payload.putBundle(NavigatorPatchWorkerService.KEY_OUTPUT_RESULT, NavigatorPatchPipeline.workerBundle(result.output));
            payload.putString(NavigatorPatchWorkerService.KEY_TRANSACTION, result.transaction.getPath());
            payload.putBoolean(NavigatorPatchWorkerService.KEY_OPTIONAL_APPLIED, result.optionalApplied);
        } else {
            NavigatorPatchPipeline.ScanResult result = input.optBoolean("directory")
                    ? NavigatorPatchPipeline.workerInspectDirectory(context, profile, source)
                    : NavigatorPatchPipeline.workerScan(context, profile, source, new File(input.getString("output")));
            payload = NavigatorPatchPipeline.workerBundle(result);
        }
        return new org.json.JSONObject().put("payload", ShellWorkFiles.json(payload))
                .put("report", NavigatorPatchReportStore.exportSnapshot(context));
    }
}
