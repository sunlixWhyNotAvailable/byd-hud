package com.bydhud.app;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ShellWorkExports {
    static VehicleConfigurationZip.Result configuration(Context context, String id,
            VehicleConfigurationZip.Control control, VehicleConfigurationZip.ProgressListener progress) throws Exception {
        File job = ShellWorkClient.job(context, "configuration", id);
        Exception prepareRetryable = null;
        if (!ShellWorkClient.hasRequest(context, "configuration", id)) {
            // Freeze app-owned service/cache observations before crossing the process boundary.
            JSONObject input = new JSONObject()
                    .put("runtime", VehicleConfigurationDiagnostics.runtime(context))
                    .put("someip", VehicleConfigurationDiagnostics.someIp(context))
                    .put("diagnostics", VehicleConfigurationDiagnostics.collect(context));
            try {
                ShellWorkClient.prepare(context, "configuration", id, input);
            } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException unknown) {
                if (Thread.currentThread().isInterrupted()) throw unknown;
                // Reconcile the precomputed same-ID job. prepare() is safe to reenter and uses
                // the persisted input/cursor if registration was accepted before the timeout.
                prepareRetryable = unknown;
            } catch (InstrumentProxyManager.ShellWorkNotDispatchedException busy) {
                if (Thread.currentThread().isInterrupted()) throw busy;
                // The shell executor did not receive this call; await() retries registration.
                prepareRetryable = busy;
            }
        }
        control.check();
        JSONObject completed;
        try {
            completed = ShellWorkClient.await(context, job, update -> {
                String phase = update == null ? "" : update.optString("phase");
                if (!isConfigurationCollectionPhase(phase)) return;
                progress.changed(phase, update.optString("file"), update.optLong("bytes"), update.optLong("total", -1),
                        update.optInt("files"), update.optInt("count", -1), update.optInt("missing"));
            });
        } catch (Exception awaitFailure) {
            if (prepareRetryable != null) awaitFailure.addSuppressed(prepareRetryable);
            throw awaitFailure;
        }
        List<File> volumes = new ArrayList<>();
        for (String path : ShellWorkEntryPoint.strings(completed.getJSONArray("volumes")))
            volumes.add(ShellWorkFiles.inside(job, path));
        VehicleConfigurationZip.Result result = new VehicleConfigurationZip.Result(true,
                volumes.isEmpty() ? null : volumes.get(0), "", completed.optInt("missing"));
        result.volumes = volumes; result.completedAtMs = completed.getLong("completedAt");
        return result;
    }

    static boolean isConfigurationCollectionPhase(String phase) {
        return "INVENTORY".equals(phase) || "DIAGNOSTICS".equals(phase)
                || "COPYING".equals(phase) || "ARCHIVING".equals(phase);
    }

    static String uploadWorkId(String operationId) {
        // The archive keeps the visible ID; private shell requests are keyed by ID without type.
        return operationId + "-upload";
    }

    static SentryLogUploader.Result upload(Context context, File archive, List<String> days,
            String id, SentryLogReport report) throws Exception {
        String workId = uploadWorkId(id);
        File job = ShellWorkClient.job(context, "upload", workId);
        Exception prepareRetryable = null;
        if (!ShellWorkClient.hasRequest(context, "upload", workId)) {
            JSONObject input = new JSONObject().put("operation", id)
                    .put("archive", archive.getPath()).put("days", new JSONArray(days))
                    .put("title", report.getTitle()).put("comment", report.getComment());
            try {
                ShellWorkClient.prepare(context, "upload", workId, input);
            } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException unknown) {
                if (Thread.currentThread().isInterrupted()) throw unknown;
                // await() resumes this exact job and reconciles registration from its saved cursor.
                prepareRetryable = unknown;
            } catch (InstrumentProxyManager.ShellWorkNotDispatchedException busy) {
                if (Thread.currentThread().isInterrupted()) throw busy;
                prepareRetryable = busy;
            }
        }
        JSONObject result;
        try {
            result = ShellWorkClient.await(context, job, null);
        } catch (Exception awaitFailure) {
            if (prepareRetryable != null) awaitFailure.addSuppressed(prepareRetryable);
            throw awaitFailure;
        }
        return new SentryLogUploader.Result(result.getBoolean("sent"), result.optString("event"), result.optString("detail"));
    }

    /** Requests cancellation and returns only a state read from the trusted shell worker. */
    static JSONObject cancelAndConfirm(Context context, File job) throws Exception {
        JSONObject before = ShellWorkClient.state(context, job);
        if (ShellWorkClient.terminal(before.optString("state"))) return before;

        ShellWorkClient.cancel(context, job);
        Exception awaitFailure = null;
        try {
            ShellWorkClient.await(context, job, null);
        } catch (InstrumentProxyManager.ShellWorkOutcomeUnknownException unknown) {
            throw unknown;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception error) {
            // await() throws for confirmed CANCELLED/FAILED/INTERRUPTED states. Read the
            // authoritative terminal state below before deciding what the caller may publish.
            awaitFailure = error;
        }

        JSONObject after;
        try {
            after = ShellWorkClient.state(context, job);
        } catch (Exception stateFailure) {
            if (awaitFailure != null) {
                awaitFailure.addSuppressed(stateFailure);
                throw awaitFailure;
            }
            throw stateFailure;
        }
        String status = after.optString("state");
        if (ShellWorkClient.terminal(status)) {
            if (("FAILED".equals(status) || "INTERRUPTED".equals(status)) && awaitFailure != null)
                throw awaitFailure;
            return after;
        }
        IOException unsettled = new IOException("Shell cancellation remains nonterminal: " + status);
        if (awaitFailure != null) unsettled.addSuppressed(awaitFailure);
        throw unsettled;
    }
}
