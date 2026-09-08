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
                "val storageLogShare by StorageLogShareWorkflow.snapshot.collectAsState()",
                "val configurationExport by VehicleConfigurationExport.snapshot.collectAsState()",
                "storageLogShare = storageLogShare", "configurationExport = configurationExport",
                "onCancelShare = { activity.composeCancelStorageShare() }",
                "onCloseShare = { activity.composeDismissStorageShare() }",
                "onCancelConfiguration = { activity.composeCancelConfigurationExport() }",
                "onCloseConfiguration = { activity.composeDismissConfigurationExport() }",
                "onShareConfiguration = { activity.composeShareConfigurationExport() }");
        assertTrue(runtime.indexOf("OperationProgressStack(") > runtime.indexOf("BottomTabs(copy"));
        assertContains(runtime,
                "activity.composeBeginStorageShare(",
                "if (activity.composeBeginConfigurationExport(destination == StorageShareDestination.Sentry))",
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
                "visibleConfigurationExport?.takeIf { !showStorageShare }?.let { state ->",
                "patchOperations.filter {");
        String card = between(source, "private fun OperationProgressCard(",
                "private fun storageLogShareBusy(");
        assertContains(config,
                "state.inventoryComplete", "state.foundFiles", "state.knownBytes",
                "known so far", "відомо наразі", "state.totalFiles ?: state.foundFiles",
                "state.totalBytes ?: state.knownBytes", "state.copiedFiles", "state.copiedBytes",
                "state.unavailableFiles", "state.archiveAvailable", "state.eventId");
        assertFalse(config.contains("%"));
        for (String phase : new String[] { "INVENTORY", "DIAGNOSTICS", "COPYING", "ARCHIVING",
                "VERIFYING", "WAITING_FOR_SHARE", "READY", "UPLOADING", "SENT", "FAILED",
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
                "visibleStorageShare", "visibleConfigurationExport", "showStorageShare",
                "padding(end = 24.dp, bottom = 24.dp)", "Arrangement.spacedBy(12.dp)",
                "onDetails = { detailsKey = card.key }",
                "cards.firstOrNull { it.key == detailsKey }");
        assertFalse(stack.contains("ModalInputBlocker()"));
        assertContains(card,
                "if (card.details.isNotEmpty())", "\"Деталі\" else \"Details\"",
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
                "val sending = state.phase == ConfigurationExportPhase.UPLOADING",
                "stopEnabled = busy && !sending && state.phase != ConfigurationExportPhase.CANCELLING",
                "closeEnabled = sending || !busy");
        String hostBack = between(source("MainActivity.java"), "public void onBackPressed()",
                "//builds this artifact here");
        assertContains(hostBack, "getOnBackPressedDispatcher().hasEnabledCallbacks()",
                "getOnBackPressedDispatcher().onBackPressed()", "moveTaskToBack(true)");

        String workflow = source("VehicleConfigurationExport.kt");
        String admission = between(workflow, "val uploadFile = synchronized(this) {",
                "if (uploadFile != null) {");
        assertContains(admission,
                "active === control && !control.isCancelled && toDeveloper",
                "state.value = state.value!!.copy(phase = ConfigurationExportPhase.UPLOADING)");
        assertTrue(workflow.indexOf("val uploadFile = synchronized(this) {")
                < workflow.indexOf("SentryLogUploader.uploadConfiguration"));
        String cancel = between(workflow, "fun cancel()", "fun dismiss()");
        assertFalse(cancel.contains("ConfigurationExportPhase.UPLOADING"));
    }

    @Test
    public void consentDisclosesRawFilesAndRetainsOversizeArchiveForAnotherApp()
            throws IOException {
        String source = source("BydHudRuntimeCompose.kt");
        String consent = between(source, "private fun ConfigurationShareDestinationOverlay(",
                "private fun StorageDeleteConfirmOverlay(");
        String details = between(source, "private fun configurationExportDetails(",
                "private fun operationDetails(");
        assertContains(consent, "BackHandler(onBack = onCancel)", "ModalInputBlocker()",
                "copy.configurationWarning", "copy.shareLogsSentryNotice",
                "onClick = onSentry", "onClick = onAnotherApp", "onClick = onCancel", "startError");
        assertContains(source,
                "split APK", "libraries, framework, cluster resources",
                "бібліотеки, framework, ресурси приборки",
                "Binary firmware files are copied unchanged",
                "Бінарні файли прошивки копіюються без змін", "trusted recipient", "довіреному отримувачу");
        assertContains(details,
                "Full reasons are recorded in manifest.json",
                "state.archiveBytes > SentryLogUploader.MAX_ZIP_BYTES",
                "The complete archive is retained for another app",
                "Повний архів збережено для іншого застосунку");
        assertContains(stack(source),
                "state.phase != ConfigurationExportPhase.WAITING_FOR_SHARE",
                "onPrimary = onShareConfiguration");
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
