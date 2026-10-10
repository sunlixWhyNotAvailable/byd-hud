package com.bydhud.app;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NavigatorMapPatchFlowTest {

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

}
