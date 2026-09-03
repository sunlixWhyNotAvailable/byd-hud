package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ConfigurationExportUiSourceContractTest {
    @Test
    public void exportObservesProcessStateAndNeverOwnsTheWorkerInCompose() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String runtime = between(source, "private fun RuntimeApp(", "private fun Header(");
        assertContains(runtime,
                "val configurationExport by VehicleConfigurationExport.snapshot.collectAsState()",
                "val configurationShareBusy = configurationExport?.let { configurationExportBusy(it.phase) } ?: false",
                "configurationShareVisible || configurationExport != null -> \"configuration-share\"",
                "configurationExport?.let { state ->", "state = state",
                "onCancel = { activity.composeCancelConfigurationExport() }",
                "onClose = { activity.composeDismissConfigurationExport() }",
                "onShare = { activity.composeShareConfigurationExport() }");
        assertTrue(runtime.indexOf("configurationExport?.let { state ->") > runtime.indexOf("BottomTabs(copy"));
        assertFalse(source.contains("LaunchedEffect(configurationShare"));
        assertFalse(source.contains("composeShareVehicleConfiguration"));
        assertFalse(source.contains("composeUploadVehicleConfigurationToSentry"));
        assertFalse(source.contains("var configurationShareBusy"));
        String begin = between(runtime, "fun beginConfigurationShare(", "LaunchedEffect(sentryUploadCooldownUntilMs)");
        assertContains(begin,
                "if (activity.composeBeginConfigurationExport(destination == StorageShareDestination.Sentry))",
                "configurationShareVisible = false", "configurationStartFailed = true");
        assertTrue(begin.indexOf("composeBeginConfigurationExport") < begin.indexOf("configurationShareVisible = false"));
        assertFalse(begin.contains("launch"));
        assertFalse(begin.contains("withContext"));
        // Navigation-log collection and its distinct Sentry result window remain wired as before.
        assertContains(runtime, "activity.composeUploadStorageDaysToSentry(", "activity.composeShareStorageDays(",
                "runInterruptible(Dispatchers.IO)", "configuration = false",
                "sentryButtonEnabled = sentryButtonRemaining == 0");
    }

    @Test
    public void progressUsesOnlyRealSnapshotCountersAndKeepsPartialArchivesHonest() throws IOException {
        String overlay = overlay();
        assertContains(overlay,
                "state: ConfigurationExportSnapshot", "state.elapsedSeconds", "state.currentFile",
                "state.totalBytes", "state.copiedBytes", "state.totalFiles", "state.copiedFiles",
                "state.unavailableFiles", "state.archiveName", "state.archiveBytes", "state.eventId",
                "state.phase == ConfigurationExportPhase.COPYING && totalBytes != null",
                "totalBytes > 0L && state.copiedBytes < totalBytes",
                "state.copiedBytes.toDouble() / totalBytes * 100", ".coerceIn(0, 99)",
                "UpdateProgressBar(\"$percent%\", palette)", "LoadingSpinner(palette)",
                "state.totalFiles?.toString() ?: \"?\"", "Total size and count will be known after inventory",
                "val partial = state.unavailableFiles > 0", "ConfigurationExportPhase.READY -> if (partial)",
                "if (partial && state.archiveAvailable)", "manifest.json");
        assertFalse(overlay.contains("100%"));
        assertFalse(overlay.contains("delay("));
        assertFalse(overlay.contains("ConfigurationExportPreview"));
        assertFalse(overlay.contains("Simulation"));
        for (String phase : new String[] { "INVENTORY", "DIAGNOSTICS", "COPYING", "ARCHIVING", "VERIFYING",
                "READY", "UPLOADING", "SENT", "FAILED", "CANCELLING", "CANCELLED" }) {
            assertTrue("missing phase " + phase, overlay.contains("ConfigurationExportPhase." + phase));
        }
    }

    @Test
    public void backAndButtonsCancelCollectionButNeverClaimToCancelDispatchedUploads() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String overlay = overlay();
        assertContains(overlay,
                "val canCancel = busy && state.phase != ConfigurationExportPhase.UPLOADING",
                "&& state.phase != ConfigurationExportPhase.CANCELLING",
                "BackHandler {\n        if (canCancel) onCancel() else if (!busy) onClose()",
                "ModalInputBlocker()", ".heightIn(max = maxHeight - 36.dp)",
                ".verticalScroll(rememberScrollState())", "enabled = canCancel || !busy",
                "onClick = if (canCancel) onCancel else onClose",
                "cancellation cannot be guaranteed. Wait for the result");
        String busy = between(source, "private fun configurationExportBusy(", "private fun ConfigurationShareDestinationOverlay(");
        assertContains(busy, "ConfigurationExportPhase.CANCELLING -> true",
                "ConfigurationExportPhase.FAILED, ConfigurationExportPhase.CANCELLED -> false");
        String hostBack = between(source("MainActivity.java"), "public void onBackPressed()", "//builds this artifact here");
        assertContains(hostBack, "getOnBackPressedDispatcher().hasEnabledCallbacks()",
                "getOnBackPressedDispatcher().onBackPressed()", "moveTaskToBack(true)");
    }

    @Test
    public void consentDisclosesRawFilesAndRetainsAnotherAppForOversizeOrUploadFailure() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String consent = between(source, "private fun ConfigurationShareDestinationOverlay(", "private fun StorageDeleteConfirmOverlay(");
        assertContains(consent, "BackHandler(onBack = onCancel)", "ModalInputBlocker()",
                "copy.configurationWarning", "copy.shareLogsSentryNotice",
                "onClick = onSentry", "onClick = onAnotherApp", "onClick = onCancel", "startError");
        assertContains(source,
                "split APK", "libraries, framework, cluster resources", "бібліотеки, framework, ресурси приборки",
                "The package may be large and collection may take time", "Пакет може бути великим",
                "text diagnostics and configuration values are masked", "у текстовій діагностиці та конфігурації маскуються",
                "Binary firmware files are copied unchanged", "Бінарні файли прошивки копіюються без змін",
                "may contain vendor-embedded data", "можуть містити вбудовані виробником дані",
                "trusted recipient", "довіреному отримувачу");
        assertContains(overlay(),
                "val canShare = state.archiveAvailable && !busy && state.phase != ConfigurationExportPhase.CANCELLED",
                "state.archiveBytes > SentryLogUploader.MAX_ZIP_BYTES",
                "The complete archive is retained", "if (canShare)", "onClick = onShare",
                "ConfigurationExportPhase.FAILED -> if (state.archiveAvailable && state.toDeveloper)");
    }

    private static String overlay() throws IOException {
        return between(source("BydHudRuntimeCompose.kt"), "private fun ConfigurationExportOverlay(",
                "private fun configurationExportBusy(");
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing source boundary: " + start + " / " + end, from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static void assertContains(String source, String... markers) {
        for (String marker : markers) assertTrue("missing source marker: " + marker, source.contains(marker));
    }
}
