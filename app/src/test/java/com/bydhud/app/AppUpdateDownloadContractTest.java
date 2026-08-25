package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class AppUpdateDownloadContractTest {
    @Test
    public void downloadJobCleanupOwnsEveryNonInstallExit() throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/AppUpdateManager.kt");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/AppUpdateManager.kt");
        }
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int enqueue = source.indexOf("val downloadId = manager.enqueue(request)");
        int tryStart = source.indexOf("try {", enqueue);
        int finallyStart = source.indexOf("} finally {", tryStart);
        int remove = source.indexOf("manager.remove(downloadId)", finallyStart);
        int destinationDelete = source.indexOf("destination.delete()", finallyStart);
        assertTrue(enqueue >= 0 && tryStart > enqueue && finallyStart > tryStart);
        assertTrue(remove > finallyStart && destinationDelete > remove);
        assertEquals(1, count(source.substring(finallyStart), "manager.remove(downloadId)"));
        assertTrue(source.contains("if (!installHandedOff)"));
        assertTrue(source.contains("runCatching { manager.remove(downloadId) }"));
        assertTrue(source.contains("runCatching { destination.delete() }"));
    }

    @Test
    public void successfulInstallHandoffIsTheOnlySuccessMarkAndTimeoutUsesElapsedRealtime()
            throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/AppUpdateManager.kt");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/AppUpdateManager.kt");
        }
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int mainBlock = source.indexOf("withContext(Dispatchers.Main)");
        int install = source.indexOf("installDownloadedApk(context, staged)");
        int success = source.indexOf("installHandedOff = true", install);
        int mainBlockEnd = source.indexOf("\n            }\n        } finally {", mainBlock);
        int finallyIndex = source.indexOf("} finally {", mainBlock);
        assertTrue(mainBlock >= 0
                && install > mainBlock
                && success > install
                && mainBlockEnd > success
                && finallyIndex > mainBlockEnd);
        int pollStart = source.indexOf("private suspend fun pollDownload(");
        int pollEnd = source.indexOf("private suspend fun handleDownloadRow(", pollStart);
        String poll = source.substring(pollStart, pollEnd);
        assertTrue(poll.contains("val startedAt = SystemClock.elapsedRealtime()"));
        assertTrue(poll.contains(
                "SystemClock.elapsedRealtime() - startedAt > DOWNLOAD_TIMEOUT_MS"));
        assertFalse(poll.contains("System.currentTimeMillis()"));
    }

    private static int count(String source, String value) {
        int count = 0;
        for (int offset = 0; (offset = source.indexOf(value, offset)) >= 0; offset += value.length()) {
            count++;
        }
        return count;
    }
}
