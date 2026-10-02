package com.bydhud.app;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class ShellWorkExports {
    static VehicleConfigurationZip.Result configuration(Context context, String id,
            VehicleConfigurationZip.Control control, VehicleConfigurationZip.ProgressListener progress) throws Exception {
        File job = ShellWorkClient.job(context, "configuration", id);
        if (!new File(job, "request.json").exists()) {
            // Freeze app-owned service/cache observations before crossing the process boundary.
            ShellWorkClient.prepare(context, "configuration", id, new JSONObject()
                    .put("runtime", VehicleConfigurationDiagnostics.runtime(context))
                    .put("someip", VehicleConfigurationDiagnostics.someIp(context))
                    .put("diagnostics", VehicleConfigurationDiagnostics.collect(context)));
        }
        control.check();
        JSONObject completed = ShellWorkClient.await(context, job, update -> {
            String phase = update.optString("phase");
            if ("RUNNING".equals(phase) || phase.isEmpty()) return;
            progress.changed(phase, update.optString("file"), update.optLong("bytes"), update.optLong("total", -1),
                    update.optInt("files"), update.optInt("count", -1), update.optInt("missing"));
        });
        List<File> volumes = new ArrayList<>();
        for (String path : ShellWorkEntryPoint.strings(completed.getJSONArray("volumes")))
            volumes.add(ShellWorkFiles.inside(job, path));
        VehicleConfigurationZip.Result result = new VehicleConfigurationZip.Result(true,
                volumes.isEmpty() ? null : volumes.get(0), "", completed.optInt("missing"));
        result.volumes = volumes; result.completedAtMs = completed.getLong("completedAt");
        return result;
    }

    static SentryLogUploader.Result upload(Context context, File archive, List<String> days,
            String id, SentryLogReport report) throws Exception {
        File job = ShellWorkClient.prepare(context, "upload", id, new JSONObject().put("operation", id)
                .put("archive", archive.getPath()).put("days", new JSONArray(days))
                .put("title", report.getTitle()).put("comment", report.getComment()));
        JSONObject result = ShellWorkClient.await(context, job, null);
        return new SentryLogUploader.Result(result.getBoolean("sent"), result.optString("event"), result.optString("detail"));
    }
}
