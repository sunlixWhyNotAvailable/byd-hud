package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Local lifecycle policy plus source wiring; does not claim Android/vehicle execution. */
public final class UserRuntimeSessionTest {
    @Test
    public void coldProcessNeedsAutoStartEvenWithSavedHudAndTbtSelections() {
        UserRuntimeSession session = new UserRuntimeSession();
        assertFalse(session.allowsRuntime(false, false));
        assertFalse(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, session.allowsRuntime(false, false), true, true));
        assertTrue(session.allowsRuntime(true, false));
        assertFalse(session.allowsRuntime(true, true));
        session.activate();
        assertTrue(session.allowsRuntime(false, false));
        assertFalse(new UserRuntimeSession().allowsRuntime(false, false));
    }

    @Test
    public void realEndThenFreshRouteRestartsWithoutBootOrWeakeningTerminalProof() {
        UserRuntimeSession session = new UserRuntimeSession();
        session.activate();
        WazeRouteLifecycleStore.Snapshot terminal = new WazeRouteLifecycleStore.Snapshot(
                false, 100L, 0L, 7L, 0, 7L);
        assertTrue(WazeRouteLifecycleStore.terminalFenceBlocks(
                terminal, 7L, false, WazeRouteLifecycleStore.REASON_UNAVAILABLE));
        assertFalse(WazeRouteLifecycleStore.freshRouteAcceptedForEvent(
                terminal, false, true, false,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE, 101L, 7L));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                session.allowsRuntime(false, false), true, false));

        WazeRouteLifecycleStore.Snapshot pending = new WazeRouteLifecycleStore.Snapshot(
                false, 200L, 0L, 8L, 0, 7L, 8L, 200L, 4);
        boolean fresh = WazeRouteLifecycleStore.freshRouteAcceptedForEvent(
                pending, false, true, false,
                WazeRouteLifecycleStore.REASON_UNAVAILABLE, 201L, 8L);
        assertTrue(fresh);
        assertTrue(session.allowsRuntime(false, false));
        assertTrue(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, session.allowsRuntime(false, false), true, false));
        assertTrue(NavHudLiveSender.shouldRestartWazeDirectForLifecycle(fresh, false, false));
        assertFalse(NavHudLiveSender.shouldRecoverWazeDirectForLifecycle(fresh, false));
        assertTrue(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, fresh, false, false));
    }

    @Test
    public void hudOffOnAndTbtOnlyUseSessionWithoutInventingRouteEvidence() {
        UserRuntimeSession session = new UserRuntimeSession();
        session.activate();
        for (String navigator : new String[]{"com.waze", GMapsDirectChannel.PACKAGE_NAME}) {
            boolean waze = "com.waze".equals(navigator);
            boolean observer = NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                    true, false, waze, !waze);
            assertTrue(observer);
            assertTrue(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                    session.allowsRuntime(false, false), observer, true));
            assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                    session.allowsRuntime(false, false), observer, false));
            assertTrue(NavHudLiveSender.acceptsDirectPromotionForTest(
                    true, true, false, navigator, 8L, navigator, 8L));
            assertFalse(NavHudLiveSender.acceptsDirectPromotionForTest(
                    true, true, false, navigator, 9L, navigator, 8L));
        }
        assertFalse(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, session.allowsRuntime(false, false), false, false));
        assertTrue(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, session.allowsRuntime(false, false), false, true));
        assertFalse(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, false, false, false));
        assertTrue(NavHudLiveSender.shouldStartTbtObserverForTest(
                false, true, false, false, false, false));
    }

    @Test
    public void shutdownRevokesSessionUntilExplicitReopen() {
        UserRuntimeSession session = new UserRuntimeSession();
        session.activate();
        session.shutdown();
        assertFalse(session.allowsRuntime(false, true));
        assertFalse(session.allowsRuntime(true, true));
        // Clearing the persisted Shutdown flag alone is not a user activation.
        assertFalse(session.allowsRuntime(false, false));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                session.allowsRuntime(false, false), true, true));
        session.activate();
        assertTrue(session.allowsRuntime(false, false));
        assertFalse(session.allowsRuntime(false, true));
    }

    @Test
    public void twoNavigatorsStillUseHudPriorityAndSurvivingRoute() throws IOException {
        UserRuntimeSession session = new UserRuntimeSession();
        session.activate();
        assertTrue(session.allowsRuntime(false, false));
        assertFalse(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, false, true));
        assertEquals("com.waze", NavHudLiveSender.selectRemainingTbtOwnerForTest(
                GMapsDirectChannel.PACKAGE_NAME, true, false, "com.waze", 10L, 20L));
        assertEquals(GMapsDirectChannel.PACKAGE_NAME,
                NavHudLiveSender.selectRemainingTbtOwnerForTest(
                        "com.waze", false, true, GMapsDirectChannel.PACKAGE_NAME, 10L, 20L));
        String sender = source("NavHudLiveSender.java");
        assertTrue(sender.contains("if (!manualTbtActive && shouldClaimTbtOwnerForFrameForTest("));
        assertTrue(sender.contains("recordDeferredLifecycle("));
        assertTrue(body(sender, "private boolean selectRemainingTbtRoute(")
                .contains("if (!isRuntimeEnabled()) return false;"));
    }

    @Test
    public void restartSnapshotObserverAndRetentionShareOneRuntimeAdmission() throws IOException {
        String sender = source("NavHudLiveSender.java");
        assertEquals(1, occurrences(sender, "HudPrefs.isBootEnabled(context)"));
        assertTrue(body(sender, "private boolean isRuntimeEnabled()")
                .contains("UserRuntimeSession.PROCESS.allowsRuntime("));
        String lifecycle = body(sender, "private void onWazeRouteLifecycleEventOnMain(");
        assertTrue(lifecycle.contains("if (!isRuntimeEnabled() || (!hudEnabled && !tbtEnabled))"));
        assertTrue(lifecycle.indexOf("if (result.terminal)")
                < lifecycle.indexOf("if (!isRuntimeEnabled()"));
        assertTrue(lifecycle.indexOf("if (wazeDirectRouteTerminalFence)")
                < lifecycle.indexOf("if (!isRuntimeEnabled()"));
        assertTrue(lifecycle.contains("wazeDirectChannel.openAcceptedFreshRoute("));
        assertTrue(lifecycle.contains("wazeSurfaceDirectChannel.openAcceptedFreshRoute("));
        assertTrue(body(sender, "private void requestWazeRouteStateSnapshot(")
                .contains("HudPrefs.isUserShutdownActive(context), isRuntimeEnabled()"));
        String observers = body(sender, "private void refreshTbtObserversOnMain()");
        assertTrue(observers.contains("refreshTbtObserver(WAZE_PACKAGE)"));
        assertTrue(observers.contains("refreshTbtObserver(GMapsDirectChannel.PACKAGE_NAME)"));
        String observer = body(sender, "private void refreshTbtObserver(String");
        assertTrue(observer.indexOf("if (!isRuntimeEnabled())")
                < observer.indexOf("reconcileTbtOwnershipForHud"));
        assertTrue(observer.contains("stopTbtObserver(packageName, \"runtime-disabled\")"));
        assertTrue(body(sender, "private boolean shouldRetainRouteForTbt(")
                .contains("boolean runtimeEnabled = isRuntimeEnabled()"));
        for (String signature : new String[]{"private void startOnMain(",
                "private boolean isCurrentWazeDirectCallback(",
                "private boolean isCurrentWazeSurfaceCallback(",
                "private boolean isCurrentGMapsDirectCallback(",
                "private void startManualOnWorker(", "private void tickHudCheck()"}) {
            assertTrue(signature, body(sender, signature).contains("isRuntimeEnabled()"));
        }
    }

    @Test
    public void onlyUserEntrypointsArmAndShutdownClearsBeforeQueuedCleanup() throws IOException {
        String main = source("MainActivity.java");
        String sender = source("NavHudLiveSender.java");
        assertTrue(body(main, "protected void onCreate(")
                .contains("NavHudLiveSender.activateUserRuntime(this)"));
        assertTrue(body(body(main, "protected void onResume()"), "if (!exitRequested)")
                .contains("NavHudLiveSender.get(this).resumeUserRuntime("));
        for (String signature : new String[]{"public void composeHudCheckToggleRunning()",
                "public void composeSetTbtWithoutHudOutputEnabled(",
                "private void setNavHudForPackage("}) {
            assertTrue(signature, body(main, signature).contains("activateUserRuntime(this)"));
        }
        assertTrue(body(sender, "void startManual(").contains("activateUserRuntime(context)"));
        assertFalse(body(sender, "static synchronized NavHudLiveSender get(")
                .contains("activateUserRuntime"));
        assertFalse(body(sender, "void start(String").contains("activateUserRuntime"));
        String activation = body(sender, "static boolean activateUserRuntime(");
        assertTrue(activation.indexOf("isUserShutdownActive")
                < activation.indexOf("UserRuntimeSession.PROCESS.activate()"));
        String resume = body(sender, "void resumeUserRuntime(");
        assertTrue(resume.indexOf("activateUserRuntime(context)") < resume.indexOf("handler.post"));
        assertTrue(resume.contains("if (!isRuntimeEnabled()) return;"));
        assertTrue(resume.contains("NavCapturePrefs.isHudEnabled(context, selected)"));
        assertTrue(resume.contains("refreshTbtObserversOnMain()"));
        assertTrue(resume.contains("requestWazeRouteStateSnapshot(reason, true)"));
        String shutdown = body(main, "private void shutdownAndExit(");
        int revoke = shutdown.indexOf("UserRuntimeSession.PROCESS.shutdown()");
        assertTrue(revoke >= 0 && revoke < shutdown.indexOf("setUserShutdownActive(this, true)"));
        assertTrue(revoke < shutdown.indexOf("stopRecorderAsync"));
        // Observer teardown must follow the HUD-clear token, not invalidate it mid-clear.
        assertTrue(shutdown.contains(
                "sender.stop(hudPackage, safeReason, true, sender::refreshTbtObservers)"));
        for (String signature : new String[]{"protected void onPause()",
                "protected void onStop()", "protected void onDestroy()"}) {
            assertFalse(signature, body(main, signature).contains("UserRuntimeSession.PROCESS.shutdown"));
        }
    }

    @Test
    public void autoStartDefaultAndColdServiceWatchdogGuardsRemainUnchanged() throws IOException {
        assertTrue(body(source("HudPrefs.java"), "static boolean isBootEnabled(")
                .contains("getBoolean(KEY_BOOT_ENABLED, true)"));
        String main = source("MainActivity.java");
        assertFalse(body(main, "protected void onCreate(").contains("setBootEnabled"));
        assertFalse(body(main, "protected void onResume()").contains("setBootEnabled"));
        for (String file : new String[]{"BootReceiver.java", "HudRuntimeService.java",
                "HudRuntimeSupervisor.java", "HudRuntimeWatchdogReceiver.java"}) {
            String cold = source(file);
            assertTrue(file, cold.contains("!HudPrefs.isBootEnabled("));
            assertFalse(file, cold.contains("UserRuntimeSession.PROCESS.activate()"));
        }
    }

    private static String source(String file) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve("app/src/main/java/com/bydhud/app/").resolve(file);
        if (!Files.isRegularFile(path)) path = root.resolve("src/main/java/com/bydhud/app/").resolve(file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Missing method: " + signature, start >= 0);
        int brace = source.indexOf('{', start);
        int depth = 1;
        int end = brace + 1;
        while (depth != 0 && end < source.length()) {
            char character = source.charAt(end++);
            if (character == '{') depth++;
            if (character == '}') depth--;
        }
        assertEquals("Unclosed method: " + signature, 0, depth);
        return source.substring(brace + 1, end - 1);
    }

    private static int occurrences(String source, String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }
}
