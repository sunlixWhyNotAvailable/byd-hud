package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class PatchOperationUiSourceContractTest {
    @Test
    public void ukrainianPatchCheckLabelsFitTheActionRow() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");

        assertTrue(source.contains("patchNotChecked = \"перевір\""));
        assertTrue(source.contains("checkPatch = \"Перевір\""));
        assertFalse(source.contains("patchNotChecked = \"перевірити\""));
        assertFalse(source.contains("checkPatch = \"Перевірити\""));
    }

    @Test
    public void productionUsesOneNonModalThreeCardStack() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String stack = between(source,
                "private fun OperationProgressStack(",
                "private fun OperationProgressCard(");
        String card = between(source,
                "private fun OperationProgressCard(",
                "private fun SentryUploadOverlay(");

        assertTrue(stack.contains("sortedByDescending { it.startedAt }.take(3)"));
        assertTrue(stack.contains("padding(end = 24.dp, bottom = 24.dp)"));
        assertTrue(stack.contains("Arrangement.spacedBy(12.dp)"));
        assertTrue(card.contains(".width(460.dp)"));
        assertTrue(card.contains(".height(170.dp)"));
        assertTrue(card.contains(".padding(16.dp)"));
        assertTrue(card.contains("\"Зупинити\" else \"Stop\""));
        assertFalse(card.contains("copy.cancel"));
        assertFalse(stack.contains("ModalInputBlocker"));
        assertFalse(stack.contains("Color.Black.copy"));
        assertTrue(source.contains("patchActionPendingProfiles"));
        assertTrue(source.contains("snapshot.patchOperations.firstOrNull"));
        assertTrue(source.contains("operation.cancelAllowed"));
        assertTrue(source.contains("operation.phase == \"FAILED\" || operation.phase == \"CANCELLED\""));
    }

    @Test
    public void previewMirrorsIndependentWazeGmapsAndShareCards() throws IOException {
        String source = previewSource();
        String stack = between(source,
                "private fun PreviewOperationStack(",
                "private fun previewOperationTitle(");

        assertTrue(source.contains("previewOperations.containsKey(\"waze\")"));
        assertTrue(source.contains("previewOperations.containsKey(\"gmaps\")"));
        assertTrue(source.contains("startPreviewOperation(\"share\""));
        assertTrue(stack.contains("sortedByDescending { it.startedAt }.take(3)"));
        assertTrue(stack.contains(".padding(24.dp)"));
        assertTrue(stack.contains("Arrangement.spacedBy(12.dp)"));
        assertTrue(stack.contains(".size(width = 460.dp, height = 170.dp)"));
        assertTrue(stack.contains(".padding(16.dp)"));
        assertFalse(stack.contains("ModalInputBlocker"));
        assertTrue(source.contains("startLogcat = \"Start Logcat\""));
        assertTrue(source.contains("stopLogcat = \"Stop Logcat\""));
        assertTrue(source.contains("startLogcat = \"Почати Logcat\""));
        assertTrue(source.contains("stopLogcat = \"Зупинити Logcat\""));
        assertFalse(source.contains("startLogcat = \"Record Logcat\""));
    }

    @Test
    public void composeSnapshotCarriesPersistedPerProfileOperations() throws IOException {
        String activity = source("app/src/main/java/com/bydhud/app/MainActivity.java");
        String store = source("app/src/main/java/com/bydhud/app/NavigatorPatchStore.java");

        assertTrue(activity.contains("NavigatorPatchStore.operations(this)"));
        assertTrue(activity.contains("public final List<ComposePatchOperation> patchOperations"));
        assertTrue(activity.contains("composeCancelNavigatorPatch"));
        assertTrue(activity.contains("composeDismissNavigatorPatch"));
        assertTrue(store.contains("KEY_OPERATION_STARTED_AT"));
        assertTrue(store.contains("KEY_OPERATION_TOKEN"));
        assertTrue(store.contains("KEY_READY_AT"));
        assertTrue(store.contains("CANCEL_REQUESTED"));
        assertTrue(store.contains("CANCELLED"));
    }

    @Test
    public void backendKeepsPerProfileOwnershipAndResumesThePersistedFifo() throws IOException {
        String store = source("app/src/main/java/com/bydhud/app/NavigatorPatchStore.java");
        String pipeline = source("app/src/main/java/com/bydhud/app/NavigatorPatchPipeline.java");
        String installer = source("app/src/main/java/com/bydhud/app/NavigatorPackageInstaller.java");

        assertTrue(store.contains("profileKey(profile, KEY_OPERATION_TOKEN)"));
        assertTrue(store.contains("nextReadyAt(context)"));
        assertTrue(pipeline.contains("ACTIVE.putIfAbsent(profile, current)"));
        assertTrue(pipeline.contains("static PreparedPatch resumePrepared"));
        assertTrue(pipeline.contains("NavigatorPatchStore.VERIFIED,\n                    \"Compatibility check completed\""));
        assertTrue(installer.contains("static void drainInstallQueue"));
        assertTrue(installer.contains("NavigatorPatchPipeline.resumePrepared(context, next)"));
        assertTrue(installer.contains("NavigatorPatchStore.INSTALLED_VERIFY"));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return normalize(file);
    }

    private static String previewSource() throws IOException {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path file = cursor.resolve(
                    "byd-hud-compose-preview/compose-preview/src/main/java/"
                            + "com/bydhud/preview/MainActivity.kt");
            if (Files.isRegularFile(file)) return normalize(file);
            cursor = cursor.getParent();
        }
        throw new IOException("preview source not found");
    }

    private static String normalize(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
