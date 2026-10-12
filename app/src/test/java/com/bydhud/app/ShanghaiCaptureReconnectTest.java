package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 29)
public final class ShanghaiCaptureReconnectTest {
    @Test public void reconnectRegistersCallbackAndOnlyPreviouslyAcceptedTopics() throws Exception {
        ShanghaiBehaviorSupport.ControlledSomeIpBinder first = new ShanghaiBehaviorSupport.ControlledSomeIpBinder(2L);
        ShanghaiBehaviorSupport.BoundContext context = new ShanghaiBehaviorSupport.BoundContext(
                RuntimeEnvironment.getApplication(), first);
        File file = new File(context.getCacheDir(), "reconnect.jsonl");
        ShanghaiEventJournal journal = new ShanghaiEventJournal(file, Arrays.asList(1L, 2L, 3L));
        ShanghaiSomeIpClient client = new ShanghaiSomeIpClient(context, "test", Arrays.asList(1L, 2L, 3L), journal);
        try {
            assertTrue(client.start(100, () -> false));
            assertTrue(client.healthy());
            context.connection.onServiceDisconnected(null);
            assertFalse(client.healthy());
            ShanghaiBehaviorSupport.ControlledSomeIpBinder second = new ShanghaiBehaviorSupport.ControlledSomeIpBinder(2L);
            context.connection.onServiceConnected(null, second);
            assertFalse(client.healthy());
            client.restoreConnection(() -> false);
            assertTrue(client.healthy());
            assertNotNull(second.callback);
            assertEquals(Arrays.asList(1L, 3L), second.requestedTopics);
            ShanghaiBehaviorSupport.sendEvent(second.callback, 1L, 1L, new byte[]{1, 2});
            assertEquals(1L, journal.receivedCount());
        } finally { client.close(); journal.close(); }
        assertFalse(client.healthy());
        assertTrue(file.delete());
    }

    @Test public void reattachedDiagnosticsReadActualShellCoverageAndKeepFailuresPartial() throws Exception {
        File root = new File(RuntimeEnvironment.getApplication().getCacheDir(), "coverage-capture");
        File shell = new File(root, "shell-runtime");
        assertTrue(shell.mkdirs());
        File segment = new File(root, "reattach-123");
        assertTrue(segment.mkdir());
        ShanghaiDiagnostics diagnostics = new ShanghaiDiagnostics(RuntimeEnvironment.getApplication(), segment, "20261012");
        ReflectionHelpers.callInstanceMethod(diagnostics, "refreshShellCoverage");
        assertTrue(diagnostics.coverage().channels.isNull("adb"));
        JSONObject actual = new JSONObject().put("adas", new JSONObject().put("status", "failed")
                .put("error", "Invalid Shanghai session token"));
        File coverage = new File(shell, "diagnostic-coverage.json");
        Files.write(coverage.toPath(), actual.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ReflectionHelpers.callInstanceMethod(diagnostics, "refreshShellCoverage");
        assertEquals(actual.toString(), diagnostics.coverage().channels.getJSONObject("adb").toString());
        assertTrue(diagnostics.coverage().hasPartialCoverage());
        assertTrue(coverage.delete()); assertTrue(shell.delete()); assertTrue(segment.delete()); assertTrue(root.delete());
    }
}
