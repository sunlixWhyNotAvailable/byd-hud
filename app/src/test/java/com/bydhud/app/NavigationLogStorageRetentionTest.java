package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class NavigationLogStorageRetentionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void activeNavigationAndLogcatDaysAreNeverDeleted() throws IOException {
        File root = temporaryFolder.newFolder("logs");
        File activeNavigation = write(root, "20260720/logs/events.log", 10);
        File activeLogcat = write(root, "20260719/logs/logcat/logcat.txt", 10);
        File oldDay = write(root, "20260718/logs/events.log", 10);

        NavigationLogStorage.enforceNavCaptureRetentionForTest(
                root, "20260720", "20260719", "", "", "", 1L);

        assertTrue(activeNavigation.exists());
        assertTrue(activeLogcat.exists());
        assertFalse(oldDay.getParentFile().getParentFile().exists());
    }

    @Test
    public void currentCropSessionIsKeptWhileOlderSessionIsDeleted() throws IOException {
        File root = temporaryFolder.newFolder("sessions");
        File current = write(root,
                "20260720/waze-crop/current/source_frame_2.png", 10);
        File old = write(root,
                "20260720/waze-crop/old/source_frame_1.png", 10);

        NavigationLogStorage.enforceNavCaptureRetentionForTest(
                root, "20260720", "waze-crop", "current",
                "source_frame_2.png", 15L);

        assertTrue(current.exists());
        assertFalse(old.getParentFile().exists());
    }

    @Test
    public void retiredTombstoneCountsTowardRetentionLimit() throws IOException {
        File root = temporaryFolder.newFolder("tombstones");
        File active = write(root, "20260720/logs/events.log", 1);
        File oldDay = write(root, "20260718/logs/events.log", 10);
        File tombstone = write(root,
                ".delete-20260717-123-0/logs/events.log", 10);

        NavigationLogStorage.enforceNavCaptureRetentionForTest(
                root, "20260720", "", "", "", "", 15L);

        assertTrue(active.exists());
        assertTrue(tombstone.exists());
        assertFalse(oldDay.getParentFile().getParentFile().exists());
    }

    private static File write(File root, String relative, int bytes) throws IOException {
        File file = new File(root, relative.replace('/', File.separatorChar));
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), new byte[bytes]);
        return file;
    }
}
