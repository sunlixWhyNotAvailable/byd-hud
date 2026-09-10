package com.bydhud.app;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ShareCompletionSourceContractTest {
    @Test
    public void sentryKeepsImmutableSelectionPerOperationWithoutPublishingStaleGlobalCompletion()
            throws IOException {
        String workflow = source("StorageLogShareWorkflow.kt");

        assertTrue(workflow.contains("val submittedDays = days.toList()"));
        assertTrue(workflow.contains("selectedDays = submittedDays"));
        assertTrue(workflow.contains("selectionRevision = selectionRevision"));
        assertTrue(workflow.contains("val upload = SentryLogUploader.upload(\n"
                + "                            app, archive.file, submittedDays, control.operationId, report!!)"));
        assertTrue(workflow.indexOf("StorageLogSharePhase.UPLOADING, detail,")
                < workflow.indexOf("SentryLogUploader.upload("));
        assertFalse(workflow.contains("publishStorageShareCompletion"));
        assertTrue(workflow.contains("if (latestState.value?.operationId == operationId)"));
    }

    @Test
    public void chooserCompletionCarriesDaysWhileConfigurationHasNoDays()
            throws IOException {
        String activity = source("MainActivity.java");
        String deliver = between(activity, "private void deliverPendingShare()",
                "private static void notifyPendingShare()");
        String config = source("VehicleConfigurationExport.kt");

        assertTrue(deliver.contains("publishShareCompletion(pending.launchId, pending.storageDays)"));
        assertTrue(!config.contains("SentryLogUploader"));
        assertFalse(config.contains("publishShareCompletion"));
        assertTrue(activity.contains(
                "queuePendingShare(files, Collections.emptyList(), ShareOwner.CONFIGURATION"));
    }

    @Test
    public void navigationSentryUploadThreadsOneIdIntoArchiveAndEvent() throws IOException {
        String workflow = source("StorageLogShareWorkflow.kt");
        assertTrue(workflow.contains("if (toDeveloper) SentryLogUploader.newUploadId()"));
        assertTrue(workflow.contains("app, submittedDays, if (toDeveloper) control.operationId else \"\""));
        assertTrue(workflow.contains(
                "app, archive.file, submittedDays, control.operationId, report"));

        String zip = source("LogShareZip.java");
        assertTrue(zip.contains("+ (uploadId.isEmpty() ? \"\" : \"-\" + uploadId)"));
        String sentry = source("SentryLogUploader.java");
        assertTrue(sentry.contains("event.setTag(\"upload_id\", uploadId)"));
        assertTrue(sentry.contains(
                "event.setFingerprints(Collections.singletonList(\"manual-navigation-upload:\" + uploadId))"));
    }

    @Test
    public void sentryZipBoundaryReusesOneOversizedArchiveAndPreparationDoesNotGateRawBytes()
            throws IOException {
        String workflow = source("StorageLogShareWorkflow.kt");
        int archiveCreate = workflow.indexOf("val archive = LogShareZip.create(");
        int sizeGate = workflow.indexOf("archive.file.length() >= SENTRY_ZIP_LIMIT_BYTES");
        int upload = workflow.indexOf("SentryLogUploader.upload(");
        String oversizeShare = between(workflow,
                "fun shareOversized(operationId: String): Boolean",
                "fun dismiss(operationId: String): Boolean");
        String cancel = between(workflow,
                "fun cancel(operationId: String): Boolean",
                "fun cancel() {");

        assertTrue(workflow.contains("const val SENTRY_ZIP_LIMIT_BYTES = 39_000_000L"));
        assertTrue(archiveCreate >= 0 && sizeGate > archiveCreate && upload > sizeGate);
        assertFalse(workflow.substring(sizeGate, upload).contains("knownBytes"));
        assertFalse(workflow.substring(sizeGate, upload).contains("selectedBytes"));
        assertTrue(oversizeShare.contains("val archive = control.archive ?: return false"));
        assertTrue(oversizeShare.contains("MainActivity.queueStorageShare(archive,"));
        assertFalse(oversizeShare.contains("LogShareZip.create("));
        assertTrue(cancel.contains("completeCancellation(control)"));
        assertTrue(workflow.contains("private fun completeCancellation("));
        assertTrue(workflow.contains("LogShareZip.deleteArtifact(control.archive)\n"
                + "            finish(control.operationId, StorageLogSharePhase.CANCELLED, \"\")"));
    }

    @Test
    public void completedOlderUploadCannotReplaceLatestOperationAndUploadsRunInParallel()
            throws IOException {
        String workflow = source("StorageLogShareWorkflow.kt");
        assertTrue(workflow.contains("newSingleThreadExecutor"));
        assertTrue(workflow.contains("newCachedThreadPool"));
        assertTrue(workflow.contains("uploadWorkers.execute"));
        assertTrue(workflow.contains(
                "if (latestState.value?.operationId == operationId) latestState.value = changed"));
        assertTrue(workflow.contains("snapshot.toDeveloper || snapshot.phase in setOf("));
        assertFalse(workflow.contains("state.value = null"));
    }

    @Test
    public void developerCommentDefersThirtySecondCooldownUntilAcceptedAdmission()
            throws IOException {
        String compose = source("BydHudRuntimeCompose.kt");
        String runtime = between(compose, "private fun RuntimeApp(", "private fun Header(");
        String destination = between(runtime,
                "if (storageShareSummaryVisible && !sentryCommentVisible)",
                "if (sentryCommentVisible)");
        String sentryAction = between(destination, "onSentry = {", "sentryButtonText =");
        String comment = between(runtime, "if (sentryCommentVisible)",
                "if (configurationShareVisible)");

        assertTrue(compose.contains("SENTRY_NAV_UPLOAD_COOLDOWN_MS = 30_000L"));
        assertTrue(compose.contains("sentryButtonEnabled = sentryButtonRemaining == 0"));
        assertTrue(runtime.contains(
                "var storageShareSummaryVisible by rememberSaveable { mutableStateOf(false) }"));
        assertTrue(runtime.contains(
                "var storageShareSummaryDayCount by rememberSaveable { mutableIntStateOf(0) }"));
        assertTrue(runtime.contains(
                "var storageShareSummaryFileCount by rememberSaveable { mutableIntStateOf(0) }"));
        assertTrue(runtime.contains(
                "var storageShareSummaryBytes by rememberSaveable { mutableLongStateOf(0L) }"));
        assertTrue(runtime.contains(
                "var storageShareDays by rememberSaveable { mutableStateOf(emptyList<String>()) }"));
        assertTrue(runtime.contains(
                "var sentryCommentVisible by rememberSaveable { mutableStateOf(false) }"));
        assertTrue(runtime.contains(
                "var sentryCommentDraft by rememberSaveable { mutableStateOf(\"\") }"));
        assertTrue(sentryAction.contains("sentryCommentDraft = \"\"\n"
                + "                    sentryCommentVisible = true"));
        assertFalse(sentryAction.contains("composeBeginStorageShare"));
        assertFalse(sentryAction.contains("sentryUploadCooldownUntilMs ="));
        assertTrue(runtime.contains("storageShareSummaryVisible || sentryCommentVisible -> "
                + "\"storage-share-consent\""));
        assertTrue(comment.contains("SentryLogReport.create(\n"
                + "                                sentryCommentDraft,\n"
                + "                                BuildConfig.VERSION_NAME,"));
        int cooldownCheck = comment.indexOf("if (sentryUploadCooldownUntilMs <= now)");
        int reportCreate = comment.indexOf("SentryLogReport.create(");
        int admission = comment.indexOf("if (activity.composeBeginStorageShare(");
        int cooldownStart = comment.indexOf(
                "sentryUploadCooldownUntilMs = now + SENTRY_NAV_UPLOAD_COOLDOWN_MS");
        int acceptedClose = comment.indexOf("sentryCommentVisible = false", admission);
        int failedStatus = comment.indexOf(
                "activity.composeAppendStatus(\"Storage share already running\")", admission);
        assertTrue(cooldownCheck >= 0 && cooldownCheck < reportCreate && reportCreate < admission);
        assertTrue(admission < cooldownStart && cooldownStart < acceptedClose
                && acceptedClose < failedStatus);
        assertFalse(comment.substring(failedStatus).contains("storageShareDays = emptyList()"));
        assertTrue(comment.contains("if (!sentryCommentSubmitting)"));
        assertTrue(comment.contains("okEnabled = !sentryCommentSubmitting"));
        assertTrue(compose.contains("dismissOnClickOutside = false"));

        String activity = source("MainActivity.java");
        assertTrue(activity.contains("int selectedFileCount, long selectedBytes, SentryLogReport report,\n"
                + "            int selectionRevision)"));
        assertTrue(activity.contains("selectedFileCount, selectedBytes,\n"
                + "                report, selectionRevision)"));
    }

    @Test
    public void reportTitleAppearsOnlyInStorageShareDetails() throws IOException {
        String compose = source("BydHudRuntimeCompose.kt");
        String stack = between(compose, "private fun OperationProgressStack(",
                "private fun OperationProgressCard(");
        String storage = between(stack,
                "visibleStorageShares.forEach { state ->",
                "visibleConfigurationExport?.let { state ->");
        String summary = between(storage, "val summary = buildString {", "add(OperationCardSpec(");
        String details = between(compose, "private fun operationDetails(",
                "private fun OperationDetailsOverlay(");

        assertFalse(summary.contains("reportTitle"));
        assertTrue(storage.contains("reportTitle = state.reportTitle"));
        assertTrue(details.contains("reportTitle: String = \"\""));
        assertTrue(details.contains("if (reportTitle.isNotBlank())"));
        assertTrue(details.contains(
                "language.choose(\"Заголовок звіту\", \"Report title\", \"Заголовок отчёта\")"));
    }

    @Test
    public void processStateKeepsDismissalAndLogsOperationIdentityThroughCompletion()
            throws IOException {
        String workflow = source("StorageLogShareWorkflow.kt");
        assertTrue(workflow.contains("object StorageLogShareWorkflow"));
        assertTrue(workflow.contains("MutableStateFlow<List<StorageLogShareSnapshot>>"));
        assertTrue(workflow.contains("newSingleThreadExecutor"));
        assertTrue(workflow.contains("newCachedThreadPool"));
        assertTrue(workflow.contains("val app = context.applicationContext"));
        assertTrue(workflow.contains("dismissed = true"));
        assertTrue(workflow.contains("fun dismiss(operationId: String): Boolean"));
        assertTrue(workflow.contains("storage_log_share operation_id=$operationId"));
        assertTrue(workflow.contains("endedAtElapsedMs = ended"));
        assertTrue(workflow.contains("MainActivity.releaseShareOperation()"));
        assertTrue(workflow.contains(
                "MainActivity.queueStorageShare(archive.file, submittedDays, control.operationId)"));
        assertTrue(workflow.contains("StorageLogSharePhase.WAITING_FOR_WRITES"));
        assertTrue(workflow.contains("StorageLogSharePhase.COPYING"));
        assertTrue(workflow.contains("StorageLogSharePhase.ARCHIVING"));
        assertTrue(workflow.contains("StorageLogSharePhase.OVERSIZED"));
        String cancel = between(workflow, "fun cancel(operationId: String): Boolean",
                "fun cancel() {");
        assertFalse(cancel.contains("StorageLogSharePhase.UPLOADING"));
    }

    @Test
    public void chooserOutcomeReturnsToTheExactOwningOperationAcrossRecreation()
            throws IOException {
        String activity = source("MainActivity.java");
        String deliver = between(activity, "private void deliverPendingShare()",
                "private static void publishShareCompletion(List<String> storageDays)");
        String pending = between(activity, "private enum ShareOwner",
                "public static final class ShareLaunchEvent");
        String logs = source("StorageLogShareWorkflow.kt");
        String config = source("VehicleConfigurationExport.kt");

        assertTrue(pending.contains("final ShareOwner owner"));
        assertTrue(pending.contains("final String operationId"));
        assertTrue(deliver.contains("if (destroyed || current != this)"));
        assertTrue(deliver.indexOf("if (destroyed || current != this)")
                < deliver.indexOf("PENDING_SHARE.compareAndSet(pending, null)"));
        assertTrue(deliver.contains("notifyShareFailed(pending, \"Archive is missing\", true)"));
        assertTrue(deliver.contains("boolean accepted = notifyShareLaunched(pending)"));
        assertTrue(deliver.contains("if (accepted && pending.owner == ShareOwner.STORAGE_LOGS)"));
        assertTrue(deliver.contains("notifyShareFailed(pending, detail, false)"));
        assertTrue(logs.contains("val current = find(operationId) ?: return false"));
        int waitingAdmission = logs.indexOf(
                "transition(control, StorageLogSharePhase.WAITING_FOR_SHARE, detail,");
        assertTrue(waitingAdmission >= 0);
        assertTrue(waitingAdmission < logs.indexOf(
                "MainActivity.queueStorageShare(archive.file, submittedDays, control.operationId)"));
        assertTrue(logs.contains("finish(operationId, StorageLogSharePhase.FAILED, detail)"));
        assertTrue(config.contains("if (current.operationId != operationId) return false"));
        assertTrue(config.contains("phase = ConfigurationExportPhase.WAITING_FOR_SHARE"));
        assertTrue(config.contains("archiveAvailable = !archiveMissing && current.archiveAvailable"));
        assertTrue(logs.contains("endedAtElapsedMs = SystemClock.elapsedRealtime()"));
        assertTrue(!config.contains("endedAtElapsedMs = 0L"));
        assertTrue(!logs.contains("dismissed = false"));
        assertTrue(!config.contains("dismissed = false"));
    }

    @Test
    public void reconciliationLeavesLiveWorkerAndStillHandlesOrphanWithoutWorker()
            throws IOException {
        String pipeline = source("NavigatorPatchPipeline.java");
        String installer = source("NavigatorPackageInstaller.java");
        String reconcile = between(installer, "private static void reconcileLocal(",
                "static boolean isInstalled(");

        assertTrue(pipeline.contains("static boolean hasActiveWorker"));
        assertTrue(pipeline.contains("worker != null && worker.isAlive()"));
        assertTrue(reconcile.contains("NavigatorPatchPipeline.hasActiveWorker(profile)"));
        assertTrue(reconcile.indexOf("hasActiveWorker(profile)")
                < reconcile.indexOf("NavigatorPatchStore.operation(context, profile)"));
        assertTrue(reconcile.contains("Interrupted before the installed navigator was changed"));
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        }
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
