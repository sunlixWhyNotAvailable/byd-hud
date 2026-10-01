package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the crash-safe transaction-directory publication order in preparation. */
public final class NavigatorPatchTransactionSourceContractTest {
    @Test
    public void transactionDirectoryIsCreatedBeforeWorkerAndPublishedByMain()
            throws IOException {
        String source = source("NavigatorPatchPipeline.java");
        String prepare = between(source,
                "static PreparedPatch prepareViaWorker(Context context, NavigatorPatchStore.Profile profile)",
                "/** Heavy read-only work executed by NavigatorPatchWorkerService. */");
        String transaction = between(source,
                "static File workerTransaction(Context context, NavigatorPatchStore.Profile profile)",
                "static ScanResult cachedScan(");

        int mkdir = transaction.indexOf("if (!transaction.mkdirs())");
        int worker = prepare.indexOf("NavigatorPatchWorkerClient.prepare(");
        int cancelFence = prepare.indexOf("checkCancelled(context, profile);", worker);
        int setTransaction = prepare.indexOf("NavigatorPatchStore.setTransaction(");

        assertTrue("transaction directory must be created", mkdir >= 0);
        assertTrue("worker receives pre-created transaction directory", worker > 0);
        assertTrue("cancellation must fence transaction publication", cancelFence > worker);
        assertTrue("final transaction metadata remains main-owned", setTransaction > cancelFence);
    }

    @Test
    public void retryRemovesAnyPriorProfileStagingBeforeIssuingNewToken() throws IOException {
        String source = source("NavigatorPatchStore.java");
        String claim = between(source,
                "static synchronized void claim(Context context, Profile profile, String kind,",
                "static synchronized void claimRecovery(");

        int capture = claim.indexOf("File previousTransaction = localOperation(context, profile)");
        int clear = claim.indexOf("clearTransactionMetadata(context, profile)");
        int delete = claim.indexOf("deleteTreeQuietly(previousTransaction)");
        int newToken = claim.indexOf("KEY_OPERATION_TOKEN");

        assertTrue("retry must capture the prior profile transaction", capture >= 0);
        assertTrue("retry must clear prior metadata", clear > capture);
        assertTrue("retry must remove prior staging", delete > clear);
        assertTrue("retry must issue its new token after cleanup", newToken > delete);
    }

    @Test
    public void installRequiresPreparedReportBeforeCreatingAnInstallSession() throws IOException {
        String source = source("NavigatorPackageInstaller.java");
        String begin = between(source,
                "static void begin(Context context, NavigatorPatchPipeline.PreparedPatch prepared)",
                "static void drainInstallQueue(Context context)");

        int requirePrepared = begin.indexOf("NavigatorPatchReportStore.requirePrepared(context, reportId)");
        int compareFingerprint = begin.indexOf("reportedSha.equalsIgnoreCase(prepared.output.sha256)");
        int claim = begin.indexOf("NavigatorPatchStore.claimInstall(context, prepared.profile)");
        int prepare = begin.indexOf("prepareSession(context, prepared.profile, patched)");
        int uninstall = begin.indexOf("getPackageInstaller().uninstall(");

        assertTrue("durable PREPARED report is required", requirePrepared >= 0);
        assertTrue("prepared report fingerprint is checked", compareFingerprint > requirePrepared);
        assertTrue("report check precedes install ownership", claim > compareFingerprint);
        assertTrue("report check precedes session creation", prepare > compareFingerprint);
        assertTrue("report check precedes package uninstall", uninstall > compareFingerprint);
    }

    @Test
    public void installedAndRecoveryResultsAreReportedBeforeTransactionCleanup()
            throws IOException {
        String source = source("NavigatorPackageInstaller.java");
        String installed = between(source,
                "static void verifyInstalledAsync(Context context, NavigatorPatchStore.Profile profile)",
                "static void verifyRestoredAsync(Context context, NavigatorPatchStore.Profile profile)");
        String recovery = between(source,
                "private static void completeRestore(Context context, NavigatorPatchStore.Profile profile,",
                "private static boolean initialInstalledTargetUnchanged(Context context,");

        int installedScan = installed.indexOf("reportScan(");
        int installedFinish = installed.indexOf("NavigatorPatchReportStore.finish(");
        int installedSuccess = installed.indexOf("NavigatorPatchStore.VERIFIED, detail");
        int installedCleanup = installed.indexOf("clearTransactionMetadata(appContext, profile)");
        int recoveryScan = recovery.indexOf("reportScan(");
        int recoveryFinish = recovery.indexOf("NavigatorPatchReportStore.finish(");
        int recoveryCleanup = recovery.indexOf("completeRestoreTransaction(context, profile, detail)");

        assertTrue("installed result is saved before report completion",
                installedScan >= 0 && installedFinish > installedScan);
        assertTrue("installed report is complete before transaction cleanup",
                installedCleanup > installedFinish);
        assertTrue("success is published only after its durable report succeeds",
                installedSuccess > installedFinish && installedCleanup > installedSuccess);
        assertTrue("recovery result is saved before report completion",
                recoveryScan >= 0 && recoveryFinish > recoveryScan);
        assertTrue("recovery report is complete before transaction cleanup",
                recoveryCleanup > recoveryFinish);
    }

    @Test
    public void installedVerificationPreservesMapOutcomeAndReason() throws IOException {
        String source = source("NavigatorPackageInstaller.java");
        String verify = between(source,
                "private static void verifyExpected(Context context, NavigatorPatchStore.Profile profile,",
                "private static boolean sameArtifact(");
        String mapCopy = between(source,
                "private static NavigatorPatchPipeline.ScanResult withExpectedOptionalFailure(",
                "private static boolean sameArtifact(");

        assertTrue("verification compares the persisted map component", verify.contains("expectedMap")
                && verify.contains("actual.mapState"));
        assertTrue("map failure can match the inspectable patchable baseline",
                verify.contains("NavigatorPatchStore.FAILED.equals(expectedMap)"));
        assertTrue("map failure, reason, and revision survive visible-result reconstruction",
                mapCopy.contains("expectedMapReason(context, profile)")
                        && mapCopy.contains("expectedMapRevision(context, profile)")
                        && mapCopy.contains("mapState, mapReason, mapRevision"));
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
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
