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
    @Test public void activityRecordPatternUsesIcuCompatibleLiteralBraces() throws Exception {
        java.lang.reflect.Field field = VehicleConfigurationDiagnostics.class.getDeclaredField("ACTIVITY");
        field.setAccessible(true);
        String pattern = ((java.util.regex.Pattern) field.get(null)).pattern();
        // The host JVM accepts a bare closing brace, but Android ICU rejects it at class initialization.
        assertTrue(pattern.contains("ActivityRecord\\{"));
        assertTrue("Android ICU requires the literal closing brace to be escaped", pattern.endsWith("\\}"));
    }

    @Test public void diagnosticCommandsAreFixedAndAllowed() {
        assertEquals("dumpsys window displays", VehicleConfigurationDiagnostics.adbCommands().get("focus"));
        for (Map.Entry<String, String> entry : VehicleConfigurationDiagnostics.adbCommands().entrySet()) {
            assertTrue(entry.getValue(), VehicleConfigurationReadback.isAllowedCommand(entry.getValue()));
            assertFalse(entry.getValue().contains(" am "));
            assertFalse(entry.getValue().contains("service call"));
            assertFalse(entry.getValue().contains(" put "));
        }
    }

    @Test public void taskRecordsExcludeIntentsRoutesAndOtherApps() throws Exception {
        String source = "Display #4 (activities)\n"
                + "  * Task{abcd #123 type=standard U=0 visible=true}\n"
                + "    * Hist #0: ActivityRecord{abcd u0 com.waze/.MainActivity t123}\n"
                + "      Intent { data=geo:50.000,30.000 extras={destination=Secret Street}}\n"
                + "  * Task{xyz #99 type=standard U=0 visible=true}\n"
                + "    * Hist #1: ActivityRecord{xyz u0 com.private.chat/.Conversation t99}\n"
                + "      message=private\n";
        JSONObject result = VehicleConfigurationDiagnostics.parseAdb("tasks", source);
        JSONObject task = result.getJSONArray("records").getJSONObject(0);
        assertEquals("com.waze/.MainActivity", task.getString("component"));
        assertEquals(4, task.getInt("displayId"));
        assertEquals(123, task.getInt("taskId"));
        assertEquals(0, task.getInt("userId"));
        assertTrue(task.isNull("visible"));
        assertEquals(1, result.getJSONArray("records").length());
        assertFalse(result.toString().contains("Secret"));
        assertFalse(result.toString().contains("geo:"));
        assertFalse(result.toString().contains("private.chat"));
    }

    @Test public void capturedActivityBlocksDoNotBorrowEmptyDisplayOrGlobalReferences() throws Exception {
        // Structural excerpt of debug 20.05.2026/dumpsys-activity.txt:95-126,293-317.
        // Identities replaced; only dump syntax and boolean fields retained.
        String source = "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc #12 type=standard U=0 visible=true}\n"
                + "    topResumedActivity=ActivityRecord{one u0 com.bydhud.app/.MainActivity t12}\n"
                + "    * Hist  #0: ActivityRecord{one u0 com.bydhud.app/.MainActivity t12}\n"
                + "      rootOfTask=true task=Task{abc #12 U=0 visible=false}\n"
                + "      mVisibleRequested=true mVisible=true mClientVisible=true reportedVisible=true\n"
                + "  * Task{def #9 type=standard U=0 visible=false}\n"
                + "    mLastPausedActivity: ActivityRecord{two u0 com.android.launcher3/.Launcher t9}\n"
                + "    * Hist  #0: ActivityRecord{two u0 com.android.launcher3/.Launcher t9}\n"
                + "      mVisibleRequested=false mVisible=false mClientVisible=false reportedVisible=false\n"
                + "  Resumed activities in task display areas (from top to bottom):\n"
                + "    Resumed: ActivityRecord{one u0 com.bydhud.app/.MainActivity t12}\n"
                + "Display #2 (activities from top to bottom):\n"
                + "Display #3 (activities from top to bottom):\n"
                + "Display #4 (activities from top to bottom):\n"
                + "  ResumedActivity: ActivityRecord{one u0 com.bydhud.app/.MainActivity t12}\n"
                + "ActivityTaskSupervisor state:\n"
                + "  deepestLastOrientationSource=ActivityRecord{one u0 com.bydhud.app/.MainActivity t12}\n"
                + "  Display: mDisplayId=0 rootTasks=2\n"
                + "  mFocusedApp=ActivityRecord{one u0 com.bydhud.app/.MainActivity t12}\n"
                + "  displayId=0\n";
        JSONArray records = VehicleConfigurationDiagnostics.parseAdb("tasks", source).getJSONArray("records");
        assertEquals(2, records.length());
        assertEquals(12, records.getJSONObject(0).getInt("taskId"));
        assertEquals(0, records.getJSONObject(0).getInt("displayId"));
        assertTrue(records.getJSONObject(0).getBoolean("visible"));
        assertEquals(9, records.getJSONObject(1).getInt("taskId"));
        assertEquals(0, records.getJSONObject(1).getInt("displayId"));
        assertFalse(records.getJSONObject(1).getBoolean("visible"));
    }

    @Test public void visibilityBelongsToDirectActivityFieldsNotTaskWindowOrNextActivity() throws Exception {
        String source = "Display #0 (activities from top to bottom):\n"
                + "  * Task{a #8 U=0 visible=true}\n"
                + "    * Hist #2: ActivityRecord{a u0 com.waze/.First t8}\n"
                + "      rootOfTask=true task=Task{a #8 U=0 visible=true}\n"
                + "      windows:\n        mVisible=true\n"
                + "      nowVisible=true reportedVisible=true\n"
                + "    * Hist #1: ActivityRecord{b u0 com.private.app/.Second t8}\n"
                + "      mVisible=true\n"
                + "    * Hist #0: ActivityRecord{c u0 com.waze/.Third t8}\n"
                + "      mVisibleRequested=true mVisible=false mClientVisible=true\n"
                + "    mVisible=true\n";
        JSONArray records = VehicleConfigurationDiagnostics.parseAdb("tasks", source).getJSONArray("records");
        assertEquals(2, records.length());
        assertTrue(records.getJSONObject(0).isNull("visible"));
        assertFalse(records.getJSONObject(1).getBoolean("visible"));
        assertFalse(records.toString().contains("private.app"));
    }

    @Test public void taskIdentityDeduplicatesOnlySameUserTaskAndCanonicalComponent() throws Exception {
        String source = "Display #0 (activities from top to bottom):\n"
                + "  * Hist #0: ActivityRecord{a u0 com.waze/.MainActivity t12}\n"
                + "  * Hist #0: ActivityRecord{a u0 com.waze/com.waze.MainActivity t12}\n"
                + "    mVisible=true\n"
                + "  * Hist #0: ActivityRecord{b u10 com.waze/.MainActivity t12}\n"
                + "  * Hist #0: ActivityRecord{c u0 com.waze/.MainActivity t13}\n"
                + "  * Hist #0: ActivityRecord{d u0 com.waze/.OtherActivity t12}\n";
        JSONArray records = VehicleConfigurationDiagnostics.parseAdb("tasks", source).getJSONArray("records");
        assertEquals(4, records.length());
        assertTrue(records.getJSONObject(0).getBoolean("visible"));
        assertEquals(10, records.getJSONObject(1).getInt("userId"));
        assertEquals(13, records.getJSONObject(2).getInt("taskId"));
        assertEquals("com.waze/.OtherActivity", records.getJSONObject(3).getString("component"));
    }

    @Test public void absentDisplayAndConflictingActivityObservationsRemainUnknown() throws Exception {
        String source = "Display #4 (activities from top to bottom):\n"
                + "ActivityTaskSupervisor state:\n  displayId=4\n"
                + "  * Hist #0: ActivityRecord{a u0 com.waze/.First t12}\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  * Hist #0: ActivityRecord{b u0 com.waze/.Second t13}\n    mVisible=true\n"
                + "Display #4 (activities from top to bottom):\n"
                + "  * Hist #0: ActivityRecord{b u0 com.waze/.Second t13}\n    mVisible=false\n"
                + "  * Hist #0: ActivityRecord{b u0 com.waze/.Second t13}\n    mVisible=true\n"
                + "  * Hist #0: ActivityRecord{x u999999999999 com.waze/.Malformed t14}\n";
        JSONArray records = VehicleConfigurationDiagnostics.parseAdb("tasks", source).getJSONArray("records");
        assertEquals(2, records.length());
        assertTrue(records.getJSONObject(0).isNull("displayId"));
        assertTrue(records.getJSONObject(0).isNull("visible"));
        assertTrue(records.getJSONObject(1).isNull("displayId"));
        assertTrue(records.getJSONObject(1).isNull("visible"));
        assertEquals("conflicting_activity_blocks", records.getJSONObject(1).getString("displayIdReason"));
        assertEquals("conflicting_activity_blocks", records.getJSONObject(1).getString("visibleReason"));
        assertEquals("unsupported", VehicleConfigurationDiagnostics.parseAdb("tasks",
                "Display #4 (activities from top to bottom):\n"
                        + "  ResumedActivity: ActivityRecord{a u0 com.waze/.MainActivity t12}\n").getString("status"));
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
        assertTrue(focus.getJSONArray("records").getJSONObject(0).isNull("displayId"));
        assertTrue(focus.getJSONArray("records").getJSONObject(1).isNull("relevantComponent"));
        assertFalse(focus.toString().contains("extras"));
    }

    @Test public void capturedFocusUsesOwningDisplayAndPreservesExplicitNull() throws Exception {
        // Structural excerpt of native-path-probe/20260606_130418/window_before.txt:216-222,476-484.
        String source = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
                + "  Display: mDisplayId=4 rootTasks=0\n"
                + "  mLayoutSeq=1\n  mCurrentFocus=null\n  mFocusedApp=null\n  displayId=4\n"
                + "  Display: mDisplayId=0 rootTasks=2\n"
                + "  mLayoutSeq=2\n"
                + "  mCurrentFocus=Window{a u0 com.waze/com.waze.MainActivity}\n"
                + "  mFocusedApp=ActivityRecord{b u0 com.waze/.MainActivity t10}\n"
                + "  displayId=0\n"
                + "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
                + "  mFocusedApp=ActivityRecord{c u0 com.bydhud.app/.MainActivity t12}\n";
        JSONArray records = VehicleConfigurationDiagnostics.parseAdb("focus", source).getJSONArray("records");
        assertEquals(4, records.length());
        for (int i = 0; i < 2; i++) {
            assertEquals(4, records.getJSONObject(i).getInt("displayId"));
            assertFalse(records.getJSONObject(i).getBoolean("present"));
            assertTrue(records.getJSONObject(i).isNull("relevantComponent"));
            assertEquals("explicit_null", records.getJSONObject(i).getString("reason"));
        }
        assertEquals(0, records.getJSONObject(2).getInt("displayId"));
        assertEquals(0, records.getJSONObject(3).getInt("displayId"));
        assertEquals("currentFocus", records.getJSONObject(2).getString("field"));
        assertEquals("focusedApp", records.getJSONObject(3).getString("field"));
        assertEquals("com.waze/com.waze.MainActivity", records.getJSONObject(2).getString("relevantComponent"));
        assertTrue(records.getJSONObject(3).getBoolean("present"));
        assertFalse(records.toString().contains("com.bydhud.app"));
    }

    @Test public void absentUnparsedAndOtherFocusAreNotExplicitNull() throws Exception {
        JSONObject absent = VehicleConfigurationDiagnostics.parseAdb("focus", "  Display: mDisplayId=4 rootTasks=0\n");
        assertEquals("unsupported", absent.getString("status"));
        assertEquals(0, absent.getJSONArray("records").length());
        JSONArray records = VehicleConfigurationDiagnostics.parseAdb("focus",
                "  Display: mDisplayId=0 rootTasks=2\n"
                        + "    mCurrentFocus=Window{nested u0 com.waze/.Nested}\n"
                        + "  mCurrentFocus=unavailable extras=com.waze/.NotAFocus\n"
                        + "  mFocusedApp=ActivityRecord{x u0 com.private.app/.Conversation t77}\n")
                .getJSONArray("records");
        assertEquals(2, records.length());
        assertTrue(records.getJSONObject(0).isNull("present"));
        assertEquals("unparsed", records.getJSONObject(0).getString("reason"));
        assertTrue(records.getJSONObject(1).getBoolean("present"));
        assertEquals("other_component", records.getJSONObject(1).getString("reason"));
        assertTrue(records.getJSONObject(1).isNull("relevantComponent"));
        assertFalse(records.toString().contains("private.app"));
        assertFalse(records.toString().contains("extras"));
    }

    @Test public void structuralRecordsRemainBoundedWithoutDroppingLastActivityVisibility() throws Exception {
        StringBuilder tasks = new StringBuilder("Display #0 (activities from top to bottom):\n");
        StringBuilder focus = new StringBuilder("  Display: mDisplayId=0 rootTasks=0\n");
        for (int i = 0; i < 129; i++) {
            tasks.append("  * Hist #0: ActivityRecord{x u0 com.waze/.MainActivity t").append(i).append("}\n    mVisible=true\n");
            focus.append("  mCurrentFocus=null\n");
        }
        JSONObject taskResult = VehicleConfigurationDiagnostics.parseAdb("tasks", tasks.toString());
        assertEquals(128, taskResult.getJSONArray("records").length());
        assertTrue(taskResult.getBoolean("recordsTruncated"));
        assertTrue(taskResult.getJSONArray("records").getJSONObject(127).getBoolean("visible"));
        JSONObject focusResult = VehicleConfigurationDiagnostics.parseAdb("focus", focus.toString());
        assertEquals(128, focusResult.getJSONArray("records").length());
        assertTrue(focusResult.getBoolean("recordsTruncated"));
        String oversized = "com.waze/." + new String(new char[1024]).replace('\0', 'X');
        assertEquals(0, VehicleConfigurationDiagnostics.parseAdb("tasks",
                "  * Hist #0: ActivityRecord{x u0 " + oversized + " t1}\n").getJSONArray("records").length());
        assertTrue(VehicleConfigurationDiagnostics.parseAdb("focus",
                "mCurrentFocus=Window{x u0 " + oversized + "}\n").getJSONArray("records").getJSONObject(0).isNull("relevantComponent"));
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
