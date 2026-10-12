package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public final class ShellWorkClientTrustTest {
    @Test
    @Config(shadows = TrustedEndpoint.class)
    public void forgedArtifactsCannotCompleteWorkButTrustedResultCan() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        TrustedEndpoint.calls = 0;
        String id = "forged-" + UUID.randomUUID();
        File job = ShellWorkClient.job(context, "patch", id);
        ShellWorkFiles.write(new File(job, "request.json"), new JSONObject().put("id", id).put("type", "patch"));
        ShellWorkFiles.write(new File(job, "status.json"), new JSONObject().put("state", "SUCCEEDED"));
        ShellWorkFiles.write(new File(job, "result.json"), new JSONObject().put("ok", true).put("source", "forged"));
        assertFalse(ShellWorkClient.hasRequest(context, "patch", id));
        org.junit.Assert.assertThrows(IOException.class, () -> ShellWorkClient.state(context, job));
        assertEquals(0, TrustedEndpoint.calls);

        File expectation = new File(ShellWorkFiles.expectationRoot(context), "patch-" + id + ".json");
        JSONObject expected = new JSONObject().put("id", id + "-other").put("type", "patch")
                .put("artifact", job.getAbsolutePath()).put("stage", "RUNNING");
        ShellWorkFiles.write(expectation, expected);
        org.junit.Assert.assertThrows(IOException.class, () -> ShellWorkClient.state(context, job));
        assertEquals(0, TrustedEndpoint.calls);
        assertFalse(ShellWorkFiles.read(expectation).has("acceptedResult"));

        expected.put("id", id);
        ShellWorkFiles.write(expectation, expected);
        TrustedEndpoint.reply = new android.os.Bundle();
        TrustedEndpoint.reply.putString("state", "RUNNING");
        assertEquals("RUNNING", ShellWorkClient.state(context, job).getString("state"));
        assertFalse(ShellWorkFiles.read(expectation).has("acceptedResult"));
        TrustedEndpoint.reply.putString("state", "SUCCEEDED");
        TrustedEndpoint.reply.putString("result_json", new JSONObject().put("source", "trusted").toString());
        assertEquals("trusted", ShellWorkClient.state(context, job).getJSONObject("result").getString("source"));
        assertEquals("trusted", ShellWorkFiles.read(expectation).getJSONObject("acceptedResult").getString("source"));
        assertEquals(2, TrustedEndpoint.calls);
    }

    @org.robolectric.annotation.Implements(InstrumentProxyManager.class)
    public static class TrustedEndpoint {
        static int calls;
        static android.os.Bundle reply;
        @org.robolectric.annotation.Implementation
        protected android.os.Bundle workState(String id) {
            calls++;
            return reply;
        }
    }

    @Test public void signingIsLimitedToTheActivePrepareOutputStages() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File transaction = new File(new File(ShellWorkFiles.root(context), "transactions"),
                "prepare-" + UUID.randomUUID());
        File baseline = new File(transaction, "patched-set");
        File mapCandidate = new File(transaction, "map-candidate-set");
        assertTrue(baseline.mkdirs());
        assertTrue(mapCandidate.mkdirs());
        JSONObject input = new JSONObject().put("command", NavigatorPatchWorkerClient.MSG_PREPARE)
                .put("transaction", transaction.getAbsolutePath());
        JSONObject expected = new JSONObject().put("type", "patch").put("input", input);

        File accepted = ShellWorkClient.authorizedSigningTarget(context, expected,
                new JSONObject().put("stage", "SIGN_BASELINE")
                        .put("directory", baseline.getAbsolutePath()));
        assertEquals(baseline.getCanonicalFile(), accepted);
        accepted = ShellWorkClient.authorizedSigningTarget(context, expected,
                new JSONObject().put("stage", "SIGN_MAP")
                        .put("directory", mapCandidate.getAbsolutePath()));
        assertEquals(mapCandidate.getCanonicalFile(), accepted);

        rejectSigningTarget(context, expected, new JSONObject().put("stage", "SIGN_BASELINE")
                .put("directory", mapCandidate.getAbsolutePath()));
        rejectSigningTarget(context, expected, new JSONObject().put("stage", "SIGN_BASELINE")
                .put("directory", new File(transaction, "source-set").getAbsolutePath()));
        rejectSigningTarget(context, new JSONObject().put("type", "patch")
                        .put("input", new JSONObject().put("command", NavigatorPatchWorkerClient.MSG_SCAN)
                                .put("transaction", transaction.getAbsolutePath())),
                new JSONObject().put("stage", "SIGN_BASELINE")
                        .put("directory", baseline.getAbsolutePath()));
    }

    private static void rejectSigningTarget(Context context, JSONObject expected,
            JSONObject request) throws Exception {
        try {
            ShellWorkClient.authorizedSigningTarget(context, expected, request);
            fail("unauthorized signing target must be rejected");
        } catch (IOException expectedFailure) {
            // The test deliberately exercises the production allowlist before signing starts.
        }
    }
}
