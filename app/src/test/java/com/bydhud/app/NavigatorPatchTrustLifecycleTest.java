package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public final class NavigatorPatchTrustLifecycleTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        NavigatorPatchStore.prefs(context).edit().clear().commit();
        ShellRuntimeSession.prefs(context).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 42);
    }

    @Test public void destructiveConsentIsOperationAndTargetBoundAndOneShot() throws Exception {
        NavigatorPatchPipeline.PreparedPatch prepared = readyPatch(
                NavigatorPatchStore.Profile.WAZE, true);
        assertTrue(NavigatorPatchStore.awaitingDestructiveApproval(
                context, NavigatorPatchStore.Profile.WAZE));
        try {
            NavigatorPatchStore.requireDestructiveInstallApproval(context, prepared);
            fail("destructive install must wait for explicit approval");
        } catch (NavigatorPatchStore.DestructiveApprovalRequiredException expected) {
            // Expected before the user confirms the prepared target.
        }

        NavigatorPatchPipeline.PreparedPatch changedTarget = new NavigatorPatchPipeline.PreparedPatch(
                prepared.profile, prepared.input, prepared.output, prepared.directory, true,
                prepared.optionalApplied, prepared.installedUpdateTime + 1,
                prepared.installedVersionCode, prepared.installedSignerSha256,
                prepared.installedFingerprint);
        try {
            NavigatorPatchStore.approveDestructiveInstall(context, changedTarget);
            fail("approval must not move to a changed installed target");
        } catch (IOException expected) {
            assertTrue(NavigatorPatchStore.awaitingDestructiveApproval(
                    context, NavigatorPatchStore.Profile.WAZE));
        }

        NavigatorPatchStore.approveDestructiveInstall(context, prepared);
        assertFalse(NavigatorPatchStore.awaitingDestructiveApproval(
                context, NavigatorPatchStore.Profile.WAZE));
        assertTrue(NavigatorPatchStore.claimInstall(context, NavigatorPatchStore.Profile.WAZE));
        assertEquals(NavigatorPatchStore.INSTALL_PREPARING,
                NavigatorPatchStore.operation(context, NavigatorPatchStore.Profile.WAZE).phase);
        try {
            NavigatorPatchStore.consumeDestructiveInstallApproval(context, changedTarget);
            fail("consent must remain bound to the prepared installed identity");
        } catch (NavigatorPatchStore.DestructiveApprovalRequiredException expected) {
            // The original confirmation remains available only to the same prepared target.
        }
        NavigatorPatchStore.consumeDestructiveInstallApproval(context, prepared);
        try {
            NavigatorPatchStore.consumeDestructiveInstallApproval(context, prepared);
            fail("consumed consent must not be replayable");
        } catch (NavigatorPatchStore.DestructiveApprovalRequiredException expected) {
            // One-shot approval has been consumed before uninstall begins.
        }
    }

    @Test public void consentBlockedOlderJobDoesNotStarveApprovedReadyJob() throws Exception {
        readyPatch(NavigatorPatchStore.Profile.WAZE, true);
        NavigatorPatchPipeline.PreparedPatch approved = readyPatch(
                NavigatorPatchStore.Profile.GMAPS, true);
        assertTrue(NavigatorPatchStore.awaitingDestructiveApproval(
                context, NavigatorPatchStore.Profile.WAZE));
        NavigatorPatchStore.approveDestructiveInstall(context, approved);

        assertTrue("approved queued install should pass the older consent-blocked job",
                NavigatorPatchStore.claimInstall(context, NavigatorPatchStore.Profile.GMAPS));
        assertEquals(NavigatorPatchStore.INSTALL_PREPARING,
                NavigatorPatchStore.operation(context, NavigatorPatchStore.Profile.GMAPS).phase);
        assertEquals(NavigatorPatchStore.READY_TO_INSTALL,
                NavigatorPatchStore.operation(context, NavigatorPatchStore.Profile.WAZE).phase);
    }

    @Test public void bootReconciliationAcceptsMatchingPrivateSameBootJobAndPreservesBackup()
            throws Exception {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.WAZE;
        NavigatorPatchStore.claim(context, profile, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.PATCHING, "Preparing");
        NavigatorPatchStore.OperationSnapshot operation = NavigatorPatchStore.operation(context, profile);
        NavigatorPatchStore.setOperationBoot(context, profile, boot - 1);

        File transaction = new File(NavigatorPatchStore.sharedRoot(context),
                "reconcile-" + UUID.randomUUID());
        assertTrue(transaction.mkdirs());
        File backup = new File(transaction, "original.apk");
        byte[] expectedBackup = new byte[]{8, 6, 7, 5, 3, 0, 9};
        Files.write(backup.toPath(), expectedBackup);
        NavigatorPatchStore.setTransactionDirectory(context, profile, transaction);

        String id = "reattach-" + UUID.randomUUID();
        File artifact = ShellWorkClient.job(context, "patch", id);
        JSONObject request = new JSONObject().put("operation", operation.operationToken);
        JSONObject expected = new JSONObject().put("schema", 1).put("id", id)
                .put("type", "patch").put("boot", boot)
                .put("artifact", artifact.getAbsolutePath()).put("input", request)
                .put("stage", "RUNNING");
        File privateExpectation = new File(ShellWorkFiles.expectationRoot(context),
                "patch-" + id + ".json");
        ShellWorkFiles.write(privateExpectation, expected);
        ShellRuntimeSession.prefs(context).edit()
                .putString("patch_" + profile.id, artifact.getAbsolutePath()).commit();

        NavigatorPatchWorkerClient.reconcileBoot(context);

        assertEquals(NavigatorPatchStore.PATCHING,
                NavigatorPatchStore.operation(context, profile).phase);
        assertEquals(boot, NavigatorPatchStore.operationBoot(context, profile));
        assertEquals(artifact.getAbsolutePath(), ShellRuntimeSession.prefs(context)
                .getString("patch_" + profile.id, ""));
        assertArrayEquals(expectedBackup, Files.readAllBytes(backup.toPath()));
    }

    @Test public void staleBusyOperationWithoutPrivateMatchingJobIsFailedButBackupSurvives()
            throws Exception {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.GMAPS;
        NavigatorPatchStore.claim(context, profile, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.PATCHING, "Preparing");
        NavigatorPatchStore.setOperationBoot(context, profile, boot - 1);
        File transaction = new File(NavigatorPatchStore.sharedRoot(context),
                "orphan-" + UUID.randomUUID());
        assertTrue(transaction.mkdirs());
        File backup = new File(transaction, "original.apk");
        byte[] expectedBackup = new byte[]{2, 4, 6, 8};
        Files.write(backup.toPath(), expectedBackup);
        NavigatorPatchStore.setTransactionDirectory(context, profile, transaction);
        ShellRuntimeSession.prefs(context).edit()
                .putString("patch_" + profile.id, new File(context.getCacheDir(), "untrusted-job").getAbsolutePath())
                .commit();

        NavigatorPatchWorkerClient.reconcileBoot(context);

        assertEquals(NavigatorPatchStore.FAILED,
                NavigatorPatchStore.operation(context, profile).phase);
        assertEquals("", ShellRuntimeSession.prefs(context).getString("patch_" + profile.id, ""));
        assertArrayEquals(expectedBackup, Files.readAllBytes(backup.toPath()));
    }

    private NavigatorPatchPipeline.PreparedPatch readyPatch(
            NavigatorPatchStore.Profile profile, boolean destructive) throws Exception {
        NavigatorPatchStore.claim(context, profile, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.PATCHING, "Preparing");
        NavigatorPatchPipeline.ScanResult expected = new NavigatorPatchPipeline.ScanResult(
                profile, "expected-sha", "1.0", 10L, "expected-signer",
                NavigatorPatchStore.PATCHABLE, NavigatorPatchStore.NOT_CHECKED,
                "OPTIONAL_UNCHANGED", "ALERT_UNCHANGED", "", "NOT_CHECKED", "", "");
        File transaction = new File(NavigatorPatchStore.sharedRoot(context),
                "consent-" + profile.id + "-" + UUID.randomUUID());
        assertTrue(transaction.mkdirs());
        assertTrue(NavigatorPatchStore.setTransaction(context, profile, transaction,
                destructive, expected, 101L, 202L, "installed-signer", "installed-fingerprint",
                "Prepared"));
        return new NavigatorPatchPipeline.PreparedPatch(profile, expected, expected, transaction,
                destructive, false, 101L, 202L, "installed-signer", "installed-fingerprint");
    }
}
