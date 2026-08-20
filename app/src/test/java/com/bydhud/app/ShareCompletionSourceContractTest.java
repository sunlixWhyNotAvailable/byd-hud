package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ShareCompletionSourceContractTest {
    @Test
    public void sentryCommitsOnlyImmutableSubmittedDaysAfterSuccess() throws IOException {
        String activity = source("MainActivity.java");
        String upload = between(activity,
                "public ComposeSentryUploadResult composeUploadStorageDaysToSentry(",
                "public ComposeSentryUploadResult composeUploadVehicleConfigurationToSentry(");

        assertTrue(upload.contains("List<String> submittedDays = immutableStorageDays(days)"));
        assertTrue(upload.contains("LogShareZip.create(this, submittedDays)"));
        assertTrue(upload.contains("SentryLogUploader.upload(\n                    this, archive.file, submittedDays)"));
        assertTrue(upload.contains("if (upload.ok && !publishShareCompletionIfCurrent("));
        assertTrue(activity.contains("STORAGE_SHARE_OPERATION_SEQUENCE.get() != operationToken"));
        assertTrue(activity.contains("public void composeCancelStorageShareOperation("));
        String compose = source("BydHudRuntimeCompose.kt");
        String cancel = between(compose, "onCancelShare = {", "onCloseShare = {");
        assertTrue(cancel.indexOf("composeCancelStorageShareOperation")
                < cancel.indexOf("storageShareBusy = false"));
    }

    @Test
    public void chooserAndSentryShareOneCompletionChannelWhileConfigHasNoDays()
            throws IOException {
        String activity = source("MainActivity.java");
        String deliver = between(activity, "private void deliverPendingShare()",
                "private static void notifyPendingShare()");
        String config = between(activity,
                "public ComposeSentryUploadResult composeUploadVehicleConfigurationToSentry(",
                "private static List<String> immutableStorageDays");

        assertTrue(deliver.contains("publishShareCompletion(pending.launchId, pending.storageDays)"));
        assertTrue(config.contains("SentryLogUploader.uploadConfiguration"));
        assertTrue(!config.contains("publishShareCompletion"));
        assertTrue(activity.contains("queuePendingShare(result.file, Collections.emptyList())"));
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
