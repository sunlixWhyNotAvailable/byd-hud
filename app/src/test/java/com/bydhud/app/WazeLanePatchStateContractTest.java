package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class WazeLanePatchStateContractTest {
    @Test
    public void wazeRequiresCompatibleDirectAndLaneTargetsBeforePatching() {
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.WAZE,
                NavigatorPatchStore.PATCHED,
                NavigatorPatchStore.PATCHABLE,
                NavigatorPatchStore.PATCHED,
                NavigatorPatchStore.PATCHED));
        assertFalse(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.WAZE,
                NavigatorPatchStore.PATCHABLE,
                NavigatorPatchStore.FAILED,
                NavigatorPatchStore.PATCHED,
                NavigatorPatchStore.PATCHED));
    }

    @Test
    public void wazeScanAndUiExposeLanesAsAnIndependentComponent() throws IOException {
        String pipeline = source("NavigatorPatchPipeline.java");
        String store = source("NavigatorPatchStore.java");
        String compose = source("BydHudRuntimeCompose.kt");

        assertTrue(pipeline.contains("String laneState = laneStock + lanePatched != 1"));
        assertTrue(pipeline.contains("return copyStates(metadata, directState, laneState,"));
        assertTrue(pipeline.contains(
                "NavigatorPatchStore.PATCHABLE.equals(input.gmsCoreState)"));
        assertTrue(pipeline.contains("Waze lanes post-verification failed"));
        assertTrue(store.contains("Waze\", \"Lanes\", \"Stable session"));
        assertTrue(store.contains("SCAN_CACHE_REVISION = 10"));
        assertTrue(compose.contains("\"Смуги\" else \"Lanes\""));
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
