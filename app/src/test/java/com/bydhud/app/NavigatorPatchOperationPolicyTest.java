package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class NavigatorPatchOperationPolicyTest {
    @Test
    public void onlyLocalCheckAndPatchPhasesAreCancellable() {
        NavigatorPatchStore.OperationSnapshot check = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_CHECK,
                NavigatorPatchStore.SCANNING, "scan", "token", 10L, 40,
                "", 0L, false, false);
        NavigatorPatchStore.OperationSnapshot select = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_SELECT,
                NavigatorPatchStore.COPYING, "select", "token", 11L, 10,
                "", 0L, false, false);
        NavigatorPatchStore.OperationSnapshot cancelled = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.CANCELLED, "cancelled", "token", 12L, 0,
                "cancelled", 0L, false, false);
        NavigatorPatchStore.OperationSnapshot installer = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.INSTALL_PREPARING, "installer", "token", 13L, 100,
                "", 100L, false, false);
        NavigatorPatchStore.OperationSnapshot installedVerify =
                new NavigatorPatchStore.OperationSnapshot(
                        NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                        NavigatorPatchStore.INSTALLED_VERIFY, "verify", "token", 14L, 100,
                        "", 100L, false, false);
        assertTrue(NavigatorPatchStore.canCancel(check));
        assertFalse(NavigatorPatchStore.canCancel(select));
        assertFalse(NavigatorPatchStore.canCancel(cancelled));
        assertFalse(NavigatorPatchStore.canCancel(installer));
        assertFalse(NavigatorPatchStore.canCancel(installedVerify));
        assertTrue(cancelled.terminal());
    }

    @Test
    public void olderReadyOperationStaysAheadOfSecondProfile() {
        NavigatorPatchStore.OperationSnapshot waze = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.READY_TO_INSTALL, "ready", "waze-token", 10L, 100,
                "", 100L, false, false);
        NavigatorPatchStore.OperationSnapshot gmaps = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.GMAPS, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.READY_TO_INSTALL, "ready", "gmaps-token", 20L, 100,
                "", 200L, false, false);
        assertTrue(NavigatorPatchStore.readyBefore(waze, gmaps.readyAt));
        assertFalse(NavigatorPatchStore.readyBefore(gmaps, waze.readyAt));
    }

    @Test
    public void stagedOutputVerificationIsNotInstalledVerification() {
        assertTrue(NavigatorPatchStore.isInstalledVerificationPhase(
                NavigatorPatchStore.INSTALLED_VERIFY));
        assertFalse(NavigatorPatchStore.isInstalledVerificationPhase(
                NavigatorPatchStore.OUTPUT_VERIFY));
    }

    @Test
    public void onlyMatchingLegacyGlobalTerminalUsesGlobalDismissal() {
        NavigatorPatchStore.OperationSnapshot failed = operation(
                NavigatorPatchStore.Profile.GMAPS,
                NavigatorPatchStore.OP_CHECK,
                NavigatorPatchStore.FAILED);
        assertTrue(NavigatorPatchStore.shouldDismissLegacyGlobal(
                false, failed, NavigatorPatchStore.Profile.GMAPS));
        assertFalse(NavigatorPatchStore.shouldDismissLegacyGlobal(
                true, failed, NavigatorPatchStore.Profile.GMAPS));
        assertFalse(NavigatorPatchStore.shouldDismissLegacyGlobal(
                false, failed, NavigatorPatchStore.Profile.WAZE));
        assertFalse(NavigatorPatchStore.shouldDismissLegacyGlobal(
                false, operation(NavigatorPatchStore.Profile.GMAPS,
                        NavigatorPatchStore.OP_RECOVERY, NavigatorPatchStore.FAILED),
                NavigatorPatchStore.Profile.GMAPS));
        assertFalse(NavigatorPatchStore.shouldDismissLegacyGlobal(
                false, operation(NavigatorPatchStore.Profile.GMAPS,
                        NavigatorPatchStore.OP_CHECK,
                        NavigatorPatchStore.RECOVERY_REQUIRED),
                NavigatorPatchStore.Profile.GMAPS));
    }

    @Test
    public void legacyDismissClearsTheGlobalOperationNamespace() throws IOException {
        String source = source("NavigatorPatchStore.java");
        String dismiss = between(source,
                "static synchronized boolean dismiss(Context context, Profile profile)",
                "static synchronized boolean claimInstall(");
        String clear = between(source,
                "private static void clearLegacyTerminal(Context context)",
                "static synchronized void clearTransactionMetadata(Context context, Profile profile)");

        assertTrue(dismiss.contains("clearLegacyTerminal(context)"));
        assertTrue(clear.contains("removeGlobalTransactionMetadata(prefs(context).edit())"));
        assertTrue(clear.contains(".remove(KEY_OPERATION_PROFILE)"));
        assertTrue(clear.contains(".remove(KEY_OPERATION_PHASE)"));
        assertTrue(clear.contains(".remove(KEY_OPERATION_DETAIL)"));
    }

    private static NavigatorPatchStore.OperationSnapshot operation(
            NavigatorPatchStore.Profile profile, String kind, String phase) {
        return new NavigatorPatchStore.OperationSnapshot(
                profile, kind, phase, "detail", "token", 10L, 0,
                "error", 0L, false, false);
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
