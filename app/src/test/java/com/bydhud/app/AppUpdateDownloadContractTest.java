package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class AppUpdateDownloadContractTest {
    @Test
    public void operationUsesUniqueOwnedPathsAndPersistsExposureBeforeUriCreation() throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/AppUpdateManager.kt");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/AppUpdateManager.kt");
        }
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue(source.contains("val baseName = \"op-$operationId-BYD-HUD-$safeVersion.apk\""));
        assertTrue(source.contains("partName = \"$baseName.part\""));
        assertTrue(source.contains("AppUpdateOwnershipPhase.EXPOSED"));
        int persisted = source.indexOf("store.write(marked)");
        int uri = source.indexOf("launchInstaller(context, file)", persisted);
        assertTrue(persisted >= 0 && uri > persisted);
        assertFalse(source.contains("downloadAndInstall("));
    }

    @Test
    public void copyingAndValidationStayInIoAndTimeoutUsesElapsedRealtime()
            throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/AppUpdateManager.kt");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/AppUpdateManager.kt");
        }
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int prepare = source.indexOf("override suspend fun prepare(");
        int handoff = source.indexOf("override suspend fun handoff(", prepare);
        String preparation = source.substring(prepare, handoff);
        assertTrue(preparation.contains("withContext(Dispatchers.IO)"));
        assertTrue(preparation.contains("input.copyTo(output)"));
        assertTrue(preparation.contains("validateDownloadedApk(context, part)"));
        assertFalse(preparation.contains("Dispatchers.Main"));
        int pollStart = source.indexOf("private suspend fun pollDownload(");
        int pollEnd = source.indexOf("private suspend fun handleDownloadRow(", pollStart);
        String poll = source.substring(pollStart, pollEnd);
        assertTrue(poll.contains("val startedAt = SystemClock.elapsedRealtime()"));
        assertTrue(poll.contains(
                "SystemClock.elapsedRealtime() - startedAt > DOWNLOAD_TIMEOUT_MS"));
        assertFalse(poll.contains("System.currentTimeMillis()"));
    }

}
