package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class NavigatorPatchRecoveryContractTest {
    @Test
    public void onlyPostMutationPhasesCanRequireRecovery() {
        NavigatorPatchStore.OperationSnapshot preparing = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.INSTALL_PREPARING, "preparing", true);
        NavigatorPatchStore.OperationSnapshot uninstall = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.UNINSTALL_REQUESTED, "uninstall", true);
        assertFalse(NavigatorPatchStore.destructiveMutationStarted(preparing));
        assertTrue(NavigatorPatchStore.destructiveMutationStarted(uninstall));
    }

    @Test
    public void finalVerificationRetryRejectsDeterministicOutputFailures() {
        assertTrue(NavigatorPackageInstaller.isFinalVerificationRetryable(
                new IOException("BroadcastReceiver components are not allowed to bind to services")));
        assertFalse(NavigatorPackageInstaller.isFinalVerificationRetryable(
                new IOException("Installed APK does not match the staged artifact")));
        assertFalse(NavigatorPackageInstaller.isFinalVerificationRetryable(
                new IOException("Installed navigator changed during verification")));
    }

    @Test
    public void receiverNormalizesContextBeforeEveryContinuation() throws IOException {
        String source = source("NavigatorPackageResultReceiver.java");
        assertTrue(source.contains("Context appContext = context.getApplicationContext();"));
        assertTrue(source.contains("acceptCallback(\n                appContext,"));
        assertTrue(source.contains("verifyInstalledAsync(\n                        appContext, profile"));
        assertTrue(source.contains("verifyRestoredAsync(appContext, profile)"));
        assertFalse(source.contains("verifyInstalledAsync(\n                        context, profile"));
        assertFalse(source.contains("verifyRestoredAsync(context, profile)"));
    }

    @Test
    public void recoveryCardCloseAcknowledgesButDoesNotClearRecovery() throws IOException {
        String store = source("NavigatorPatchStore.java");
        String dismiss = between(store, "static synchronized boolean dismiss(",
                "static boolean shouldDismissLegacyGlobal(");
        assertTrue(dismiss.contains("RECOVERY_REQUIRED.equals(current.phase)"));
        assertTrue(dismiss.contains("KEY_RECOVERY_ACK_TOKEN"));
        String recovery = between(dismiss, "if (RECOVERY_REQUIRED.equals(current.phase))",
                "if (!(FAILED.equals(current.phase)");
        assertFalse(recovery.contains("clearTransactionMetadata(context, profile)"));

        String compose = source("BydHudRuntimeCompose.kt");
        String stack = between(compose, "private fun OperationProgressStack(",
                "private fun OperationProgressCard(");
        assertTrue(stack.contains("it.recoveryRequired && it.acknowledged"));
        assertTrue(stack.contains("|| operation.recoveryRequired"));
    }

    @Test
    public void knownReceiverFailureMigrationIsExactAndBounded() throws IOException {
        String installer = source("NavigatorPackageInstaller.java");
        assertTrue(installer.contains("isRestrictedReceiverContextFailure(operation.error)"));
        assertTrue(installer.contains("consumeFinalVerificationRetry(context, profile)"));
        assertTrue(installer.contains("finalVerificationTransactionMatches"));
        assertTrue(installer.contains("migrateKnownReceiverContextFailure"));
    }

    @Test
    public void rollbackUsesExactSetMetadataWithoutPatchProfileDetection() throws IOException {
        String pipeline = source("NavigatorPatchPipeline.java");
        String installer = source("NavigatorPackageInstaller.java");
        String store = source("NavigatorPatchStore.java");
        String sourceVerification = between(pipeline,
                "static ScanResult verifyRecoverySource(", "static void discardPrepared(");
        String beginRestore = between(installer,
                "static void beginRestore(", "static void commitPreparedSession(");
        String patchedVerification = between(installer,
                "static void verifyInstalledAsync(", "static void verifyRestoredAsync(");
        String restoredVerification = between(installer,
                "static void verifyRestoredAsync(",
                "static boolean isFinalVerificationRetryable(");
        String unchangedFence = between(installer,
                "private static boolean initialInstalledTargetUnchanged(Context context,\n"
                        + "            NavigatorPatchStore.Profile profile, boolean metadataOnly)",
                "private static void finishUnchangedFailure(");
        String retryFence = between(store,
                "static boolean finalVerificationTransactionMatches(",
                "static synchronized void releaseInstall(");

        assertTrue(sourceVerification.contains("NavigatorApkSet.readDirectory"));
        assertFalse(sourceVerification.contains("NavigatorPatchWorkerClient"));
        assertFalse(sourceVerification.contains("inspectComponents"));
        assertTrue(beginRestore.contains("initialRestoreTargetUnchanged"));
        assertTrue(patchedVerification.contains("NavigatorPatchPipeline.inspectInstalled("));
        assertFalse(patchedVerification.contains("inspectInstalledMetadata"));
        assertTrue(restoredVerification.contains("inspectInstalledMetadata"));
        assertFalse(restoredVerification.contains("NavigatorPatchPipeline.inspectInstalled("));
        assertTrue(occurrences(installer, "inspectInstalledMetadata") == 3);
        assertTrue(retryFence.contains("OP_RECOVERY.equals(operation.kind)"));
        assertTrue(retryFence.contains("NavigatorPatchPipeline.inspectInstalledMetadata"));
        assertTrue(retryFence.contains("NavigatorPatchPipeline.inspectInstalled(context"));
        assertTrue(unchangedFence.indexOf("initialSigner(context, profile)")
                < unchangedFence.indexOf("inspectInstalledMetadata(context, profile)"));
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
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
