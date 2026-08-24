package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class NavigatorPatchWorkerSourceContractTest {
    @Test
    public void workerIsPrivateAndUsesOneFifoExecutor() throws IOException {
        String manifest = source("app/src/main/AndroidManifest.xml");
        String worker = source("app/src/main/java/com/bydhud/app/NavigatorPatchWorkerService.java");
        assertTrue(manifest.contains("android:name=\".NavigatorPatchWorkerService\""));
        assertTrue(manifest.contains("android:exported=\"false\""));
        assertTrue(manifest.contains("android:process=\":navigator_patcher\""));
        assertTrue(worker.contains("Executors.newSingleThreadExecutor"));
        assertTrue(worker.contains("FutureTask"));
        assertTrue(worker.contains("Future<?> future"));
        assertTrue(worker.contains("volatile boolean cancelled"));
        assertTrue(worker.contains("future.cancel(true)"));
        assertTrue(worker.contains("checkCancelled(task);"));
        assertTrue(worker.contains("linkToDeath(task.replyDeath, 0)"));
        assertTrue(worker.contains("unlinkReplyDeath(task)"));
        assertTrue(worker.contains("Process.killProcess(android.os.Process.myPid())"));
        assertFalse(worker.contains("NavigatorPackageInstaller"));
        assertFalse(worker.contains("NavigatorPatchStore"));
    }

    @Test
    public void workerBoundaryReturnsPureResultsAndMainOwnsStoreInstaller() throws IOException {
        String pipeline = source("app/src/main/java/com/bydhud/app/NavigatorPatchPipeline.java");
        String gmaps = source(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
        String activity = source("app/src/main/java/com/bydhud/app/MainActivity.java");
        String client = source(
                "app/src/main/java/com/bydhud/app/NavigatorPatchWorkerClient.java");
        String installer = source(
                "app/src/main/java/com/bydhud/app/NavigatorPackageInstaller.java");
        assertTrue(pipeline.contains("static ScanResult workerScan(Context context, String profileId"));
        assertTrue(pipeline.contains("static WorkerPatchResult workerPrepare(Context context, String profileId"));
        assertTrue(pipeline.contains("buildUnsignedSet(\n                context, profile, sourceSet, patched, transaction, input, false)"));
        assertTrue(activity.contains("NavigatorPatchPipeline.scanViaWorker(this, profile)"));
        assertTrue(activity.contains("NavigatorPatchPipeline.prepareViaWorker(this, profile)"));
        assertTrue(pipeline.contains("if (worker != null) worker.interrupt();"));
        assertTrue(pipeline.contains("completeScanUnlessCancelled("));
        assertTrue(pipeline.contains("NavigatorPatchWorkerClient.inspectInstalled"));
        assertTrue(pipeline.contains("NavigatorPatchWorkerClient.inspectDirectory"));
        assertTrue(pipeline.contains("resumePrepared"));
        assertTrue(pipeline.contains("ScanResult output = NavigatorPatchWorkerClient.inspectDirectory("));
        assertTrue(pipeline.contains("finishQueuedCancellation(context, profile"));
        assertTrue(pipeline.contains("workerInspectDirectory"));
        assertFalse(pipeline.contains("NavigatorApkSet.inspectInstalled(context, profile)"));
        assertTrue(gmaps.contains("inspectComponents(List<File> apks)"));
        assertTrue(gmaps.contains("scanCandidates(apk, PROFILES)"));
        assertFalse(pipeline.contains("validatedGmapsProfile"));
        assertFalse(pipeline.contains("inspectProfileIdIfPresent(member.file)"));
        assertTrue(client.contains("Looper.myLooper() == Looper.getMainLooper()"));
        assertTrue(client.contains("cancelAndFence(remote.get(), completed, operation)"));
        assertTrue(client.contains("NavigatorPatchWorkerService.MSG_ABORT_PROCESS"));
        assertTrue(installer.contains("Executors.newSingleThreadExecutor"));
        assertTrue(installer.contains("INSTALL_QUEUE.execute(() -> drainInstallQueueNow(appContext))"));
        assertTrue(installer.contains("error instanceof NavigatorPatchPipeline.OperationCancelledException"));
        assertTrue(installer.contains("NavigatorPatchPipeline.finishQueuedCancellation("));
    }

    private static String source(String relative) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve(relative);
        if (!Files.isRegularFile(path) && relative.startsWith("app/")) {
            path = root.resolve(relative.substring("app/".length()));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
    }
}
