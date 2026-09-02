package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class RuntimeUiSessionTest {
    @Test
    public void sameProcessUiRecreationKeepsSelectionAndIndependentViewports() {
        RuntimeUiSession holder = new RuntimeUiSession();
        RuntimeUiSession.Session firstUi = holder.getOrCreate(() -> "Apps");
        firstUi.select("Options", "dashboard-widget");
        String[] keys = {"apps", "storage", "storage-days", "patch", "hud-check", "options-categories",
                "options:runtime-permissions", "options:basic-navigation", "options:route-eta",
                "options:speed-limit", "options:waze-features", "options:extra-navigation",
                "options:dashboard-window-profile", "options:dashboard-widget", "options:dashboard-move"};
        for (int i = 0; i < keys.length; i++) {
            firstUi.recordViewport(keys[i], new RuntimeUiSession.Viewport(i + 1, i + 11));
        }

        RuntimeUiSession.Session recreatedUi = holder.getOrCreate(() -> {
            throw new AssertionError("existing session must win over startup defaults");
        });
        assertSame(firstUi, recreatedUi);
        assertEquals("Options", recreatedUi.selectedTab());
        assertEquals("dashboard-widget", recreatedUi.selectedOptionsSection());
        for (int i = 0; i < keys.length; i++) {
            assertEquals(new RuntimeUiSession.Viewport(i + 1, i + 11), recreatedUi.viewport(keys[i]));
        }
        recreatedUi.recordViewport("storage-days", RuntimeUiSession.Viewport.TOP);
        assertEquals(RuntimeUiSession.Viewport.TOP, recreatedUi.viewport("storage-days"));
        assertEquals(new RuntimeUiSession.Viewport(2, 12), recreatedUi.viewport("storage"));
    }

    @Test
    public void exitDetachesOldCallbacksBeforeNewSessionStarts() {
        RuntimeUiSession holder = new RuntimeUiSession();
        RuntimeUiSession.Session oldUi = holder.getOrCreate(() -> "Options");
        oldUi.select("Storage", "speed-limit");
        holder.clear();
        oldUi.recordViewport("storage", new RuntimeUiSession.Viewport(7, 90));
        RuntimeUiSession.Session newUi = holder.getOrCreate(() -> "Apps");
        assertNotSame(oldUi, newUi);
        oldUi.select("HudCheck", "dashboard-move");
        oldUi.recordViewport("apps", new RuntimeUiSession.Viewport(3, 20));
        assertEquals("Apps", newUi.selectedTab());
        assertEquals("runtime-permissions", newUi.selectedOptionsSection());
        assertEquals(RuntimeUiSession.Viewport.TOP, newUi.viewport("storage"));
        assertEquals(RuntimeUiSession.Viewport.TOP, newUi.viewport("apps"));
    }

    @Test
    public void startupDefaultRunsOnlyForANewSessionAndFreshProcessHasNoPositions() {
        AtomicInteger startupCalls = new AtomicInteger();
        RuntimeUiSession holder = new RuntimeUiSession();
        RuntimeUiSession.Session first = holder.getOrCreate(() -> startupCalls.getAndIncrement() == 0 ? "Options" : "Apps");
        assertEquals("Options", first.selectedTab());
        first.select("Patch", "route-eta");
        first.recordViewport("patch", new RuntimeUiSession.Viewport(2, 30));
        assertSame(first, holder.getOrCreate(() -> { startupCalls.incrementAndGet(); return "Apps"; }));
        assertEquals(1, startupCalls.get());
        holder.clear();
        assertEquals("Apps", holder.getOrCreate(() -> startupCalls.getAndIncrement() == 0 ? "Options" : "Apps").selectedTab());
        assertEquals(2, startupCalls.get());
        RuntimeUiSession.Session freshProcess = new RuntimeUiSession().getOrCreate(() -> "Apps");
        assertEquals("Apps", freshProcess.selectedTab());
        assertEquals(RuntimeUiSession.Viewport.TOP, freshProcess.viewport("patch"));
    }

    @Test
    public void missingMeasurementDoesNotOverwritePositionButRealTopDoes() {
        RuntimeUiSession.Session session = new RuntimeUiSession().getOrCreate(() -> "Apps");
        RuntimeUiSession.Viewport saved = new RuntimeUiSession.Viewport(8, 35);
        session.recordViewport("apps", saved);
        session.recordViewport("apps", null);
        assertEquals(saved, session.viewport("apps"));
        session.recordViewport("apps", RuntimeUiSession.Viewport.TOP);
        assertEquals(RuntimeUiSession.Viewport.TOP, session.viewport("apps"));
        assertEquals(RuntimeUiSession.Viewport.TOP, new RuntimeUiSession.Viewport(-1, -5));
    }

    @Test
    public void composeRestoresThenCapturesLatestValuesWithoutRepublishingOrDisk() throws IOException {
        String compose = source("BydHudRuntimeCompose.kt");
        String install = between(compose, "fun install(activity:", "private enum class RuntimeTab");
        assertTrue(install.indexOf("RuntimeUiSession.PROCESS.getOrCreate {")
                < install.indexOf("HudPrefs.takeOptionsIntroForCurrentVersion(activity)"));
        assertTrue(install.contains("RuntimeApp(activity, uiSession)"));
        String runtime = between(compose, "private fun RuntimeApp(", "private fun OptionsTab(");
        assertTrue(runtime.contains("rememberUpdatedState(::captureUiSession)"));
        assertTrue(runtime.contains("LaunchedEffect(uiSession)"));
        assertTrue(runtime.contains(".collect { latestCaptureUiSession() }"));
        assertTrue(runtime.contains("uiSession.select(selectedTab.name, selectedOptionsSectionKey)"));
        assertTrue(runtime.contains("snapshot.appScanCacheAvailable || !snapshot.appScanInProgress"));
        assertTrue(runtime.contains("snapshot.storageCacheAvailable || !snapshot.storageCalculating"));
        assertFalse(runtime.contains("RuntimeUiSession.PROCESS"));
        String lifecycle = between(runtime, "DisposableEffect(activity)", "LaunchedEffect(");
        assertTrue(lifecycle.contains("Lifecycle.Event.ON_PAUSE"));
        assertTrue(lifecycle.contains("Lifecycle.Event.ON_STOP"));
        assertTrue(lifecycle.contains("Lifecycle.Event.ON_DESTROY"));
        assertEquals(2, occurrences(lifecycle, "latestCaptureUiSession()"));
        String viewport = between(compose, "private class SessionViewportState", "private fun RuntimeApp(");
        assertTrue(viewport.contains("LazyListState(initial.index, initial.offset)"));
        assertTrue(viewport.contains("if (contentReady && !viewport.restored)"));
        assertTrue(viewport.contains(".first { it > 0 }"));
        assertTrue(viewport.indexOf("scrollToItem(viewport.initial.index, viewport.initial.offset)")
                < viewport.indexOf("viewport.restored = true"));
        assertTrue(viewport.contains("restored && listState.layoutInfo.visibleItemsInfo.isNotEmpty()"));
        assertFalse(viewport.contains("rememberSaveable"));
        String holder = source("RuntimeUiSession.java");
        for (String forbidden : new String[]{"import android.", "import androidx.", "Context ",
                "LazyListState", "CoroutineScope", "SharedPreferences", "SavedState"}) {
            assertFalse(forbidden, holder.contains(forbidden));
        }
    }

    @Test
    public void onlyExplicitExitAndShutdownClearBeforeAsyncCleanup() throws IOException {
        String activity = source("MainActivity.java");
        String exit = between(activity, "private void exitAndFinish()", "private void shutdownAndExit(");
        String shutdown = between(activity, "private void shutdownAndExit(", "private void finishAfterStop()");
        for (String action : new String[]{exit, shutdown}) {
            assertTrue(action.indexOf("RuntimeUiSession.PROCESS.clear()") >= 0);
            assertTrue(action.indexOf("RuntimeUiSession.PROCESS.clear()") < action.indexOf("stopRecorderAsync("));
        }
        assertEquals(2, occurrences(activity, "RuntimeUiSession.PROCESS.clear()"));
        String lifecycle = between(activity, "protected void onCreate(", "public void onBackPressed()");
        assertFalse(lifecycle.contains("RuntimeUiSession.PROCESS.clear()"));
        String back = between(activity, "public void onBackPressed()", "\n    }");
        assertTrue(back.contains("moveTaskToBack(true)"));
        assertFalse(back.contains("RuntimeUiSession"));
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + name);
        if (!Files.isRegularFile(file)) file = root.resolve("src/main/java/com/bydhud/app/" + name);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing " + start, from >= 0);
        assertTrue("missing " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String text) {
        return (source.length() - source.replace(text, "").length()) / text.length();
    }
}
