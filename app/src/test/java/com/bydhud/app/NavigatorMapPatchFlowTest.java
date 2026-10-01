package com.bydhud.app;

import static org.junit.Assert.*;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NavigatorMapPatchFlowTest {
    private static NavigatorPatchPipeline.ScanResult result(String map) {
        return new NavigatorPatchPipeline.ScanResult(NavigatorPatchStore.Profile.GMAPS,
                "a".repeat(64), "26.30.09.950492155", 1068694917L, "b".repeat(64),
                NavigatorPatchStore.PATCHED, NavigatorPatchStore.PATCHED,
                NavigatorPatchStore.PATCHED, NavigatorPatchStore.PATCHED, "Compatible",
                map, "Anchor unavailable", "maps-r11");
    }

    @Test public void workerIpcRetainsIndependentFailureWithoutInvalidatingDirect() {
        NavigatorPatchPipeline.ScanResult original = result(NavigatorPatchStore.FAILED);
        NavigatorPatchPipeline.ScanResult actual = NavigatorPatchPipeline.workerUnbundle(
                NavigatorPatchPipeline.workerBundle(original));
        assertEquals(NavigatorPatchStore.PATCHED, actual.directState);
        assertEquals(original.mapState, actual.mapState);
        assertEquals(original.mapReason, actual.mapReason);
        assertEquals(original.mapRevision, actual.mapRevision);
        assertTrue(NavigatorPatchPipeline.navigationComponentsUnchanged(original, actual));
        assertEquals("PARTIAL_MAP: Anchor unavailable",
                NavigatorPatchPipeline.preparedDetail(actual, "Ready"));
    }

    @Test public void mapOnlyUpgradeNeedsVerifiedDirectAndDoesNotRepeatCurrentMap() {
        assertTrue(NavigatorPatchStore.isPatchEnabled(NavigatorPatchStore.Profile.GMAPS,
                "PATCHED", "PATCHED", "PATCHED", "PATCHED", "PATCHABLE"));
        assertFalse(NavigatorPatchStore.isPatchEnabled(NavigatorPatchStore.Profile.GMAPS,
                "FAILED", "PATCHED", "PATCHED", "PATCHED", "PATCHABLE"));
        assertFalse(NavigatorPatchStore.isPatchEnabled(NavigatorPatchStore.Profile.GMAPS,
                "PATCHED", "PATCHED", "PATCHED", "PATCHED", "PATCHED"));
    }

    @Test public void failedMapAcceptanceRestoresEntireBaselineAndSuccessfulSwapRetainsIt() throws Exception {
        File root = new File(RuntimeEnvironment.getApplication().getCacheDir(), "map-swap");
        File baseline = new File(root, "baseline"), candidate = new File(root, "candidate");
        File retained = new File(root, "retained");
        assertTrue(baseline.mkdirs());
        java.nio.file.Files.write(new File(baseline, "base.apk").toPath(), new byte[]{1, 2});
        try {
            NavigatorPatchPipeline.acceptMapCandidate(baseline, candidate, retained);
            fail("missing map candidate must not replace Direct baseline");
        } catch (java.io.IOException expected) {
            assertArrayEquals(new byte[]{1, 2}, java.nio.file.Files.readAllBytes(
                    new File(baseline, "base.apk").toPath()));
            assertFalse(retained.exists());
        }
        assertTrue(candidate.mkdirs());
        java.nio.file.Files.write(new File(candidate, "base.apk").toPath(), new byte[]{3, 4});
        NavigatorPatchPipeline.acceptMapCandidate(baseline, candidate, retained);
        assertArrayEquals(new byte[]{3, 4}, java.nio.file.Files.readAllBytes(
                new File(baseline, "base.apk").toPath()));
        assertArrayEquals(new byte[]{1, 2}, java.nio.file.Files.readAllBytes(
                new File(retained, "base.apk").toPath()));
    }

    @Test public void choosingSourceDoesNotLeaveAnUnfinishedPatchReport() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        NavigatorPatchStore.claim(context, NavigatorPatchStore.Profile.GMAPS,
                NavigatorPatchStore.OP_SELECT, NavigatorPatchStore.COPYING, "selected.apk");
        NavigatorPatchStore.transition(context, NavigatorPatchStore.Profile.GMAPS,
                NavigatorPatchStore.IDLE, "");
        assertTrue(NavigatorPatchReportStore.snapshotAll(context).isEmpty());
    }

    @Test public void preparedIdentityAndMapFailureSurviveTransactionCleanup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.GMAPS;
        NavigatorPatchStore.claim(context, profile, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.PATCHING, "test");
        String id = NavigatorPatchStore.reportId(context, profile);
        NavigatorPatchPipeline.ScanResult output = result(NavigatorPatchStore.FAILED);
        NavigatorPatchPipeline.reportScan(context, id, "PREPARED", output);
        assertEquals(output.sha256, NavigatorPatchReportStore.requirePrepared(context, id));
        File transaction = new File(context.getFilesDir(), "tx-map-test");
        assertTrue(transaction.mkdirs());
        assertTrue(NavigatorPatchStore.setTransaction(context, profile, transaction, false,
                output, -1, -1, "", "", "PARTIAL_MAP: Anchor unavailable"));
        assertEquals(output.mapState, NavigatorPatchStore.expectedMap(context, profile));
        assertEquals(output.mapReason, NavigatorPatchStore.expectedMapReason(context, profile));
        NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.CANCELLED, "cancel");
        NavigatorPatchStore.clearTransactionMetadata(context, profile);
        NavigatorPatchPipeline.deleteTree(transaction);
        JSONObject report = NavigatorPatchReportStore.snapshot(context, id);
        assertEquals("CANCELLED", report.getString("status"));
        assertEquals(5, report.getJSONArray("components").length());
        assertEquals("FAILED", report.getJSONArray("components").getJSONObject(4)
                .getString("outcome"));
        assertEquals(1, NavigatorPatchReportStore.snapshotAll(context).size());
    }
}
