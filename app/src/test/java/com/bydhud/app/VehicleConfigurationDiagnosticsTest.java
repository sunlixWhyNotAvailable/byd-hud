package com.bydhud.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class VehicleConfigurationDiagnosticsTest {
    @Test public void diagnosticCommandsAreFixedAndAllowed() {
        for (Map.Entry<String, String> entry : VehicleConfigurationDiagnostics.adbCommands().entrySet()) {
            assertTrue(entry.getValue(), VehicleConfigurationReadback.isAllowedCommand(entry.getValue()));
            assertFalse(entry.getValue().contains(" am "));
            assertFalse(entry.getValue().contains("service call"));
            assertFalse(entry.getValue().contains(" put "));
        }
    }

    @Test public void taskRecordsExcludeIntentsRoutesAndOtherApps() throws Exception {
        String source = "Display #4 (activities)\n"
                + "* Hist #0: ActivityRecord{abcd u0 com.waze/.MainActivity t123}\n"
                + "  Intent { data=geo:50.000,30.000 extras={destination=Secret Street}}\n"
                + "* Hist #1: ActivityRecord{xyz u0 com.private.chat/.Conversation t99}\n"
                + "  message=private\n";
        JSONObject result = VehicleConfigurationDiagnostics.parseAdb("tasks", source);
        JSONObject task = result.getJSONArray("records").getJSONObject(0);
        assertEquals("com.waze/.MainActivity", task.getString("component"));
        assertEquals(4, task.getInt("displayId"));
        assertEquals(123, task.getInt("taskId"));
        assertTrue(task.isNull("visible"));
        assertEquals(1, result.getJSONArray("records").length());
        assertFalse(result.toString().contains("Secret"));
        assertFalse(result.toString().contains("geo:"));
        assertFalse(result.toString().contains("private.chat"));
    }

    @Test public void accessibilityDoesNotTurnMissingOrLabelOnlyBindingIntoFalse() throws Exception {
        JSONObject absent = VehicleConfigurationDiagnostics.parseAdb("accessibility", "Enabled services: Some label\n");
        assertEquals("unsupported", absent.getString("status"));
        assertTrue(absent.isNull("boundSectionObserved"));
        JSONObject labels = VehicleConfigurationDiagnostics.parseAdb("accessibility", "Bound services: {Navigation capture}\n");
        assertTrue(labels.getBoolean("boundSectionObserved"));
        assertEquals(0, labels.getJSONArray("boundComponents").length());
        assertTrue(labels.getString("interpretation").contains("do not prove unbound"));
        JSONObject explicit = VehicleConfigurationDiagnostics.parseAdb("accessibility",
                "mBoundServices={com.bydhud.app/.NavAccessibilityService}\n");
        assertEquals("com.bydhud.app/.NavAccessibilityService", explicit.getJSONArray("boundComponents").getString(0));
    }

    @Test public void appOpsKeepModesNotHistoryOrUnrelatedOperations() throws Exception {
        JSONObject result = VehicleConfigurationDiagnostics.parseAdb("appops",
                "  SYSTEM_ALERT_WINDOW: allow; time=50m ago; rejectTime=1h ago\n"
                        + "  GET_USAGE_STATS: deny\n  CAMERA: allow; client=secret\n");
        assertEquals(2, result.getJSONArray("records").length());
        assertEquals("deny", result.getJSONArray("records").getJSONObject(1).getString("mode"));
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.toString().contains("rejectTime"));
    }

    @Test public void audioAndFocusOnlyExposeRelevantMetadata() throws Exception {
        JSONObject audio = VehicleConfigurationDiagnostics.parseAdb("audio",
                "mMode=0\npack: com.waze gain: 3 client: private@domain\n"
                        + "pack: com.private.calls gain: 2 phone=123456\naddress=AA:BB:CC:DD:EE:FF\n");
        assertEquals(2, audio.getJSONArray("records").length());
        assertFalse(audio.toString().contains("domain"));
        assertFalse(audio.toString().contains("calls"));
        assertFalse(audio.toString().contains("AA:BB"));
        JSONObject focus = VehicleConfigurationDiagnostics.parseAdb("focus",
                "mCurrentFocus=Window{hash u0 com.waze/.MainActivity extras=private}\n"
                        + "mFocusedApp=ActivityRecord{hash u0 com.private.chat/.Conversation t77}\n");
        assertEquals("com.waze/.MainActivity", focus.getJSONArray("records").getJSONObject(0).getString("relevantComponent"));
        assertTrue(focus.getJSONArray("records").getJSONObject(1).isNull("relevantComponent"));
        assertFalse(focus.toString().contains("extras"));
    }

    @Test public void systemCountersRemainTypedAndMissingStateUnknown() throws Exception {
        JSONArray cpu = VehicleConfigurationDiagnostics.parseAdb("cpu",
                " 12.5% 123/com.waze: 7% user\n 88% 456/com.private.app: 8% user\n").getJSONArray("records");
        assertEquals(1, cpu.length());
        assertEquals(12.5, cpu.getJSONObject(0).getDouble("percent"), 0.001);
        assertEquals(2, VehicleConfigurationDiagnostics.parseAdb("thermal", "Thermal Status: 2\n")
                .getJSONArray("records").getJSONObject(0).getInt("thermalStatus"));
        assertEquals("unsupported", VehicleConfigurationDiagnostics.parseAdb("thermal", "No service\n").getString("status"));
        assertEquals(42L, VehicleConfigurationDiagnostics.parseAdb("memory", " TOTAL PSS: 42\nSQL private.db\n")
                .getJSONArray("records").getJSONObject(0).getLong("kilobytes"));
    }

    @Test public void rawSettingsDistinguishNullZeroEmptyAndOemNamespace() throws Exception {
        assertTrue(VehicleConfigurationDiagnostics.parseAdb("accessibility_enabled", "null\n").isNull("raw"));
        assertEquals("0", VehicleConfigurationDiagnostics.parseAdb("accessibility_enabled", "0\n").getString("raw"));
        assertEquals("", VehicleConfigurationDiagnostics.parseAdb("accessibility_services", "\n").getString("raw"));
        assertTrue(VehicleConfigurationDiagnostics.parseAdb("instrument_display_setting", "2")
                .getString("namespace").contains("not OEM CarSettings"));
    }

    @Test public void cancellationDuringNativeGetPreservesInterruptForArchiveCleanup() throws Exception {
        CountDownLatch enteredRead = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread collector = new Thread(() -> {
            try (VehicleConfigurationDiagnostics.NativeReads reads =
                         new VehicleConfigurationDiagnostics.NativeReads(new JSONArray(), 5000, 10001)) {
                reads.add("blocked", "fixture", () -> {
                    enteredRead.countDown();
                    finishRead.await();
                    return 1;
                });
                failure.set(new AssertionError("cancelled acquisition returned normally"));
            } catch (InterruptedException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable unexpected) { failure.set(unexpected); }
        });
        collector.start();
        try {
            assertTrue(enteredRead.await(2, TimeUnit.SECONDS));
            collector.interrupt();
            collector.join(2000);
            assertFalse("collector must stop", collector.isAlive());
            assertNull(failure.get());
            assertTrue("outer collector must see cancellation", interrupted.get());
        } finally {
            finishRead.countDown();
            collector.interrupt();
            collector.join(2000);
        }
    }

    @Test public void collectionNeverCallsActiveRuntimeOrMigratingPreferenceGetters() throws Exception {
        String source = new String(Files.readAllBytes(Path.of("src/main/java/com/bydhud/app/VehicleConfigurationDiagnostics.java")), StandardCharsets.UTF_8);
        for (String forbidden : new String[]{"NavHudLiveSender.get(", "HudOutputCoordinator.get(",
                "InstrumentProxyManager.get(", "NavRuntimePermissionStatus.check(", "adbKeyFingerprint(",
                "checkAndRepair", "NavCapturePrefs.getHudPackage(", "HudPrefs.dashboardScreenMode(",
                "HudPrefs.routeMetricsMode(", ".edit()", "requestAudioFocus", "getAddress()", "getProductName()"}) {
            assertFalse(forbidden, source.contains(forbidden));
        }
        assertTrue(source.contains("TimeUnit.MILLISECONDS"));
        assertTrue(source.contains("worker.shutdownNow()"));
        assertTrue(source.contains("SomeIpServerService"));
        assertTrue(source.contains(".put(\"serviceId\", SomeIpHudClient.HUD_NAVI_INFO_SERVICE_ID)"));
        assertTrue(source.contains(".put(\"roadTopic\", SomeIpHudClient.HUD_ROAD_INFO_TOPIC)"));
        assertTrue(source.contains("configured constants, not device readback"));
    }
}
