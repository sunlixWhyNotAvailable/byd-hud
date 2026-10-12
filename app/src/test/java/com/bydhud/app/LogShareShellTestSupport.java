package com.bydhud.app;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Replaces only the Binder/process boundary; preparation and archive writing remain real. */
@Implements(InstrumentProxyManager.class)
public final class LogShareShellTestSupport {
    private static Context context;
    private static Runnable beforeArchive;
    private String operationId;
    private File artifact;
    private JSONObject input;
    private JSONObject result;

    static LogShareZip.Result export(Context context, List<String> days) throws Exception {
        return export(context, days, () -> { });
    }

    static LogShareZip.Result export(Context appContext, List<String> days, Runnable prepared)
            throws Exception {
        String id = "test-" + UUID.randomUUID();
        context = appContext;
        beforeArchive = prepared;
        var worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-log-export");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return worker.submit(() -> LogShareZip.createIndependent(appContext, days, "", id))
                    .get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            if (!worker.awaitTermination(2, TimeUnit.SECONDS))
                throw new AssertionError("export worker did not stop");
            context = null;
            beforeArchive = null;
        }
    }

    @Implementation
    protected Bundle registerWork(String id, String type, File job, JSONObject request,
            ParcelFileDescriptor[] descriptors, int boot) throws Exception {
        operationId = id;
        artifact = job;
        input = new JSONObject(request.toString());
        result = null;
        Bundle reply = new Bundle();
        reply.putString("state", "REGISTERED");
        reply.putString("worker_input", input.toString());
        return reply;
    }

    @Implementation
    protected void launchWork(String id, boolean cleanupOnly) throws Exception {
        if (!id.equals(operationId)) throw new AssertionError("unregistered export");
        if (result != null) return;
        beforeArchive.run();
        JSONObject previousRequest = ShellWorkEntryPoint.request;
        File previousControl = ShellWorkEntryPoint.currentControl;
        File previousJob = ShellWorkEntryPoint.currentJob;
        try {
            ShellWorkEntryPoint.currentJob = artifact;
            ShellWorkEntryPoint.currentControl = ShellWorkFiles.directory(new File(artifact, "control"));
            ShellWorkEntryPoint.request = new JSONObject().put("id", id).put("type", "log");
            result = LogShareZip.runInShell(context, input);
        } finally {
            ShellWorkEntryPoint.request = previousRequest;
            ShellWorkEntryPoint.currentControl = previousControl;
            ShellWorkEntryPoint.currentJob = previousJob;
        }
    }

    @Implementation
    protected Bundle workState(String id) {
        if (!id.equals(operationId) || result == null)
            throw new AssertionError("export result requested before completion");
        Bundle reply = new Bundle();
        reply.putString("state", "SUCCEEDED");
        reply.putString("result_json", result.toString());
        return reply;
    }
}
