package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class StorageAndLogsUiSourceContractTest {
    @Test
    public void storageActionsKeepSelectionAndBusyGatesAtTheirCallbacks() throws IOException {
        String storage = between(source(), "private fun StorageTab(", "private fun PatchTab(");
        assertTrue(storage.contains("enabled = selectedDayNames.isNotEmpty() && !storageActionBusy"));
        assertTrue(storage.contains("onClick = { onShareSelected(selectedDayNames) }"));
        assertTrue(storage.contains("onClick = { onDeleteSelected(selectedDayNames) }"));
        assertTrue(storage.contains("enabled = !storageActionBusy && !configurationShareBusy"));
        assertTrue(storage.contains("enabled = !storageActionBusy && !logcatBusy"));
        assertTrue(storage.contains("if (snapshot.logcatRecording) onStopLogcat() else onStartLogcat()"));
        assertTrue(storage.contains("onToggle = { onToggleDay(day.name) }"));
    }

    @Test
    public void configurationExportOffersCreateAndCancelWithoutSentry() throws IOException {
        String source = source();
        String modal = between(source, "private fun ConfigurationShareDestinationOverlay(",
                "private fun StorageDeleteConfirmOverlay(");
        assertFalse(modal.contains("copy.shareLogsSentryNotice"));
        assertFalse(modal.contains("onClick = onSentry"));
        assertTrue(modal.contains("onClick = onCreate"));
        assertTrue(modal.contains("onClick = onCancel"));
    }

    private static String source() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

}
