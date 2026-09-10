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
    public void composeObservesBothProcessOwnedExportsWithoutOwningWorkers() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String runtime = between(source, "private fun RuntimeApp(", "private fun Header(");
        assertContains(runtime,
                "val storageLogShares by StorageLogShareWorkflow.snapshots.collectAsState()",
                "val configurationExport by VehicleConfigurationExport.snapshot.collectAsState()",
                "storageLogShares = storageLogShares", "configurationExport = configurationExport",
                "onCancelShare = { id -> activity.composeCancelStorageShare(id) }",
                "onCloseShare = { id -> activity.composeDismissStorageShare(id) }",
                "onCancelConfiguration = { activity.composeCancelConfigurationExport() }",
                "onCloseConfiguration = { activity.composeDismissConfigurationExport() }",
                "onShareConfiguration = { activity.composeShareConfigurationExport() }");
        assertTrue(runtime.indexOf("OperationProgressStack(") > runtime.indexOf("BottomTabs(copy"));
        assertContains(runtime,
                "activity.composeBeginStorageShare(",
                "if (activity.composeBeginConfigurationExport())",
                "sentryButtonEnabled = sentryButtonRemaining == 0");
        assertFalse(source.contains("LaunchedEffect(storageShareBusy, storageShareDays"));
        assertFalse(source.contains("ConfigurationExportOverlay("));
        assertFalse(source.contains("SentryUploadOverlay("));
        assertFalse(source.contains("composeShareStorageDays("));
        assertFalse(source.contains("composeUploadStorageDaysToSentry("));
    }

    @Test
    public void progressUsesGrowingInventoryThenRealCopyTotalsAndIndependentElapsed()
            throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String stack = stack(source);
        String config = between(stack,
                "visibleConfigurationExport?.let { state ->",
                "patchOperations.filter {");
        String card = between(source, "private fun OperationProgressCard(",
                "private fun storageLogShareBusy(");
        assertContains(config, "state.archiveAvailable", "state.expiresAtEpochMs", "state.volumeSizes");
        String detail = between(source, "private fun ConfigurationExportDetailsOverlay(", "private fun operationDetails(");
        assertContains(detail, "state.foundFiles", "state.copiedFiles", "state.unavailableFiles",
                "state.totalBytes ?: state.knownBytes", "state.copiedBytes");
        assertFalse(config.contains("%"));
        for (String phase : new String[] { "INVENTORY", "DIAGNOSTICS", "COPYING", "ARCHIVING",
                "WAITING_FOR_SHARE", "READY", "EXPIRED", "FAILED",
                "CANCELLING", "CANCELLED" }) {
            assertTrue("missing phase " + phase, config.contains("ConfigurationExportPhase." + phase));
        }
        assertContains(card,
                ".width(460.dp)", ".height(170.dp)",
                "SystemClock.elapsedRealtime()", "delay(1_000L)",
                "card.endedAtElapsedMs", "coerceAtLeast(0L) / 1_000L");
    }

    @Test
    public void compactCardsOnlyOpenAModalForExplicitDetails() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String stack = stack(source);
        String card = between(source, "private fun OperationProgressCard(",
                "private fun storageLogShareBusy(");
        String details = between(source, "private fun OperationDetailsOverlay(",
                "private fun ConfigurationShareDestinationOverlay(");
        assertFalse(stack.contains(".take(3)"));
        assertContains(stack,
                "visibleStorageShares", "visibleConfigurationExport",
                "visibleStorageShares.forEach { state ->",
                "visibleConfigurationExport?.let { state ->",
                "padding(end = 24.dp, bottom = 24.dp)", "Arrangement.spacedBy(12.dp)",
                "onDetails = { detailsKey = card.key }",
                "cards.firstOrNull { it.key == detailsKey }");
        assertFalse(stack.contains("ModalInputBlocker()"));
        assertContains(card,
                "if (card.details.isNotEmpty())", "language.choose(\"Деталі\", \"Details\", \"Детали\")",
                "if (card.busy && card.stopEnabled)", "if (card.closeEnabled)");
        assertContains(details, "BackHandler(onBack = onClose)", "ModalInputBlocker()",
                ".verticalScroll(rememberScrollState())", "onClick = onClose");
    }

    @Test
    public void sendingHasCloseWhileOnlyPreparationCanStop() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String stack = stack(source);
        assertContains(stack,
                "val sending = state.phase == StorageLogSharePhase.UPLOADING",
                "stopEnabled = busy && !sending && state.phase != StorageLogSharePhase.CANCELLING",
                "closeEnabled = sending || terminal",
                "stopEnabled = busy && state.phase != ConfigurationExportPhase.CANCELLING",
                "closeEnabled = !busy");
        String hostBack = between(source("MainActivity.java"), "public void onBackPressed()",
                "//builds this artifact here");
        assertContains(hostBack, "getOnBackPressedDispatcher().hasEnabledCallbacks()",
                "getOnBackPressedDispatcher().onBackPressed()", "moveTaskToBack(true)");

        String workflow = source("VehicleConfigurationExport.kt");
        assertFalse(workflow.contains("SentryLogUploader"));
        String cancel = between(workflow, "fun cancel()", "fun dismiss()");
        assertFalse(cancel.contains("ConfigurationExportPhase.UPLOADING"));
    }

    @Test
    public void configurationConsentAndDetailsMatchAcceptedPipeline() throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String consent = between(source, "private fun ConfigurationShareDestinationOverlay(",
                "private fun StorageDeleteConfirmOverlay(");
        String details = between(source, "private fun ConfigurationExportDetailsOverlay(",
                "private fun operationDetails(");
        assertContains(consent, "onClick = onCreate", "onClick = onCancel", "startError",
                "15 minutes", "1 GB", "copy.configurationWarning");
        assertFalse(consent.contains("Sentry"));
        assertFalse(details.contains("manifest.json"));
        assertFalse(details.contains("CarSettingsPlugins"));
        assertContains(details, "completedAtEpochMs", "expiresAtEpochMs", "volumeSizes", "unavailableFiles");
        String workflow = source("VehicleConfigurationExport.kt");
        assertFalse(workflow.contains("SentryLogUploader"));
        assertTrue(workflow.indexOf("ConfigurationExportArtifacts.checkBeforeExport(app)")
                < workflow.indexOf("VehicleConfigurationZip.createFull(app, control)"));
        assertContains(workflow, "inventory=${snapshot.foundFiles}", "volumes=${snapshot.volumeSizes.size}");
        String card = between(source, "private fun ConfigurationExportProgressCard(",
                "private fun ConfigurationExportDetailsOverlay(");
        assertContains(card, "alpha = 0.70f", "width = 100.dp", "width = 138.dp", "width = 108.dp");
        assertFalse(card.contains("unavailableFiles"));
        assertFalse(card.contains("copiedFiles"));
    }

    private static String stack(String source) {
        return between(source, "private fun OperationProgressStack(",
                "private fun OperationProgressCard(");
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
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
