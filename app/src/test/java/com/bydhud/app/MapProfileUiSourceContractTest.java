package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the production map-profile UI and its live session/cache bridges. */
public final class MapProfileUiSourceContractTest {
    @Test
    public void profileListAndAddControlAreAvailableInNativeAndExperimentalModes() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String rows = between(compose, "row(\"map-source-profiles\")", "row(\"map-layout-preset\")");
        assertTrue(rows.contains("MapProfileAddButton("));
        assertTrue(rows.contains("\"Додати профіль\", \"Add profile\", \"Добавить профиль\""));
        assertTrue(rows.contains("snapshot.mapProfiles.values.sortedBy { it.source.ordinal }"));
        assertTrue(rows.contains("profile.source in snapshot.installedMapProfileSources"));
        assertTrue(rows.contains("enabled = mapProfilesEnabled,"));
        assertTrue(rows.contains("Not installed"));
        assertTrue(rows.contains("onEdit = { onOpenMapProfile(profile.source) }"));
        assertTrue(rows.contains("onDelete = { onDeleteMapProfile(profile.source) }"));
        assertTrue(compose.contains("val mapProfilesEnabled = mapSettings.mode != HudMapSettings.OFF"));
    }

    @Test
    public void editorUsesRealSessionFrameAndApprovedTwoRowControls() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String editor = between(compose, "private fun MapProfileEditorDialog(", "private fun TransferProfileEditorDialog(");
        assertTrue(editor.contains("300.toDp()"));
        assertTrue(editor.contains("180.toDp()"));
        assertTrue(compose.contains("BitmapFactory.decodeByteArray"));
        assertTrue(compose.contains("withContext(Dispatchers.Default)"));
        assertTrue(compose.contains("bitmap.width != 300 || bitmap.height != 180"));
        assertTrue(editor.contains("background(Color.Black)"));
        assertTrue(editor.contains("decodedFrame.session == calibration.session"));
        assertTrue(editor.contains("decodedFrame.revision == calibration.frameRevision"));
        assertTrue(editor.contains("draft.x,"));
        assertTrue(editor.contains("draft.scale,"));
        assertTrue(editor.contains("draft.y,"));
        assertTrue(editor.contains("-100..100"));
        assertTrue(editor.contains("50..300"));
        assertTrue(editor.contains("Minus — right, plus — left"));
        assertTrue(editor.contains("Minus — up, plus — down"));
        assertTrue(editor.contains(".verticalScroll(rememberScrollState())"));
        assertTrue(editor.contains("editor.canSave && !saving"));
        assertFalse(editor.contains("MapProfileSample"));
        assertFalse(editor.contains("else -> calibration.reason"));
    }

    @Test
    public void profileCloseAndSourceChangesKeepSessionOwnershipSerialized() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String change = between(compose, "fun changeMapProfileDraft(", "fun saveMapProfile()");
        assertTrue(change.contains("activity.composeUpdateMapProfile(profile)"));
        assertFalse(change.contains("composeStopMapProfile(\"source-change\")"));
        assertFalse(change.contains("composeStartMapProfile(profile)"));
        assertTrue(compose.contains("activity.composeStopMapProfile(\"editor-close\")"));
        assertTrue(compose.contains("activity.composeStopMapProfile(\"editor-hide\")"));
        assertTrue(compose.contains("snapshot.mapProfileCalibration.running"));
    }

    @Test
    public void activityUsesAsyncProfileCacheAndOnlyShowsForCaptureTarget() throws Exception {
        String activity = source("MainActivity.java");
        String availability = between(activity,
                "private static Set<HudMapProfile.Source> installedMapProfileSources(",
                "public boolean composeSaveMapProfile(");
        assertTrue(availability.contains("packageName.equals(normalizePackage(row.packageName))"));
        assertFalse(availability.contains("isGoogleMapsAlias(row.packageName)"));
        assertTrue(activity.contains("requestMapProfileCacheRefresh(context)"));
        assertTrue(activity.contains("new Thread(() -> {"));
        String onStop = between(activity, "protected void onStop()", "protected void onDestroy()");
        assertTrue(onStop.contains("if (!NavHudLiveSender.mapProfileSnapshot().running)"));
        assertTrue(activity.contains("NavHudLiveSender.stopMapProfile(\"shutdown\")"));
        assertTrue(activity.contains("NavigatorMapCapture.refreshProfiles()"));
        assertTrue(activity.contains("map_profile saved source="));
        assertTrue(activity.contains("map_profile deleted source="));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/com/bydhud/app/" + name)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static Path projectRoot() {
        Path root = Paths.get(System.getProperty("user.dir"));
        return Files.isDirectory(root.resolve("app")) ? root : root.getParent();
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section: " + start);
        return source.substring(from, to);
    }
}
