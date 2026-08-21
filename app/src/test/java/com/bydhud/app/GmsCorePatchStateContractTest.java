package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class GmsCorePatchStateContractTest {
    @Test
    public void pipelineCarriesIndependentGmsCoreState() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/NavigatorPatchPipeline.java");
        assertTrue(source.contains("final String gmsCoreState"));
        assertTrue(source.contains("inspectGmsCoreClassification"));
        assertTrue(source.contains("patchGmsCore"));
        assertTrue(source.contains("verifyGmsCore"));
        assertTrue(source.contains("gmsCoreFailed"));
    }

    @Test
    public void storePersistsAndInvalidatesTheFourthState() throws IOException {
        String source = source("app/src/main/java/com/bydhud/app/NavigatorPatchStore.java");
        assertTrue(source.contains("SCAN_CACHE_REVISION = 8"));
        assertTrue(source.contains("_scan_gms_core"));
        assertTrue(source.contains("KEY_EXPECTED_GMS_CORE"));
        assertTrue(source.contains("_installed_gms_core"));
    }

    @Test
    public void productionAndPreviewExposeTheFourthGMapsPill() throws IOException {
        String production = source("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String preview = previewSource();
        assertTrue(production.contains("row.gmsCoreLabel to row.gmsCoreState"));
        assertTrue(preview.contains("gmapsGmsCorePatchStatus"));
        assertTrue(preview.contains("optionalLabel = \"GmsCore\""));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String previewSource() throws IOException {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path file = cursor.resolve(
                    "byd-hud-compose-preview/compose-preview/src/main/java/"
                            + "com/bydhud/preview/MainActivity.kt");
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n").replace('\r', '\n');
            }
            cursor = cursor.getParent();
        }
        throw new IOException("preview source not found");
    }
}
