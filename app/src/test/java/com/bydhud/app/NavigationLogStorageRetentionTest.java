package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class NavigationLogStorageRetentionTest {
    @Test
    public void findsNestedInterruptedRetentionTombstones() throws Exception {
        File root = temporaryFolder.newFolder("retention-cleanup");
        File session = new File(root, "20260720/waze-direct/session");
        assertTrue(session.mkdirs());
        File nestedDirectory = new File(session, ".delete-20260720-1-0");
        assertTrue(nestedDirectory.mkdirs());
        File nestedFile = new File(session, ".delete-20260720-2-0");
        Files.write(nestedFile.toPath(), new byte[]{1});

        List<File> tombstones = NavigationLogStorage.findRetiredTombstones(root);

        assertEquals(2, tombstones.size());
        assertTrue(tombstones.contains(nestedDirectory));
        assertTrue(tombstones.contains(nestedFile));
    }

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void activeNavigationAndLogcatDaysAreNeverDeleted() throws IOException {
        File root = temporaryFolder.newFolder("logs");
        File activeNavigation = write(root, "20260720/logs/events.log", 10);
        File activeLogcat = write(root, "20260719/logs/logcat/logcat.txt", 10);
        File oldDay = write(root, "20260718/logs/events.log", 10);

        NavigationLogStorage.enforceNavCaptureRetentionForTest(
                root, "20260720", "20260719", 1L);

        assertTrue(activeNavigation.exists());
        assertTrue(activeLogcat.exists());
        assertFalse(oldDay.getParentFile().getParentFile().exists());
    }

    @Test
    public void sameDayDirectRetentionPreservesRegisteredSession() throws IOException {
        File root = temporaryFolder.newFolder("direct-sessions");
        File active = write(root,
                "20260720/gmaps-direct/current/raw.jsonl", 10);
        File oldGMaps = write(root,
                "20260720/gmaps-direct/old/raw.jsonl", 10);
        File oldWaze = write(root,
                "20260720/waze-direct/old/raw.jsonl", 10);

        NavigationLogStorage.registerDirectSession(
                "20260720", NavigationLogStorage.GMAPS_DIRECT_DIR, "current");
        try {
            NavigationLogStorage.enforceNavCaptureRetentionForTest(
                    root, "20260720", "", 15L);
        } finally {
            NavigationLogStorage.unregisterDirectSession(
                    "20260720", NavigationLogStorage.GMAPS_DIRECT_DIR, "current");
        }

        assertTrue(active.exists());
        assertFalse(oldGMaps.getParentFile().exists());
        assertFalse(oldWaze.getParentFile().exists());
    }

    @Test
    public void sameDayRetentionCanRemoveHistoricalCropSession() throws IOException {
        File root = temporaryFolder.newFolder("historical-crop");
        File activeLog = write(root, "20260720/logs/events.log", 1);
        File oldCrop = write(root, "20260720/waze-crop/old/screen_1.png", 10);

        NavigationLogStorage.enforceNavCaptureRetentionForTest(
                root, "20260720", "", 1L);

        assertTrue(activeLog.exists());
        assertFalse(oldCrop.getParentFile().exists());
    }

    @Test
    public void retiredTombstoneCountsTowardRetentionLimit() throws IOException {
        File root = temporaryFolder.newFolder("tombstones");
        File active = write(root, "20260720/logs/events.log", 1);
        File oldDay = write(root, "20260718/logs/events.log", 10);
        File tombstone = write(root,
                ".delete-20260717-123-0/logs/events.log", 10);

        NavigationLogStorage.enforceNavCaptureRetentionForTest(
                root, "20260720", "", 15L);

        assertTrue(active.exists());
        assertTrue(tombstone.exists());
        assertFalse(oldDay.getParentFile().getParentFile().exists());
    }

    @Test
    public void directSessionDayRemainsActiveAcrossMidnight() {
        NavigationLogStorage.registerDirectSession(
                "20260720", NavigationLogStorage.WAZE_DIRECT_DIR, "current");
        try {
            assertTrue(NavigationLogStorage.isActiveNavCaptureDayForTest(
                    "20260720", "20260721", ""));
        } finally {
            NavigationLogStorage.unregisterDirectSession(
                    "20260720", NavigationLogStorage.WAZE_DIRECT_DIR, "current");
        }
    }

    @Test
    public void retentionPreservesActiveDirectSessionDayAcrossMidnight() throws IOException {
        File root = temporaryFolder.newFolder("direct-across-midnight");
        File current = write(root, "20260721/logs/events.log", 1);
        File activeDirect = write(root,
                "20260720/waze-direct/current/events.jsonl", 10);
        File oldDay = write(root, "20260719/logs/events.log", 10);

        NavigationLogStorage.registerDirectSession(
                "20260720", NavigationLogStorage.WAZE_DIRECT_DIR, "current");
        try {
            NavigationLogStorage.enforceNavCaptureRetentionForTest(
                    root, "20260721", "", 12L);
        } finally {
            NavigationLogStorage.unregisterDirectSession(
                    "20260720", NavigationLogStorage.WAZE_DIRECT_DIR, "current");
        }

        assertTrue(current.exists());
        assertTrue(activeDirect.exists());
        assertFalse(oldDay.getParentFile().getParentFile().exists());
    }

    @Test
    public void sessionCountIncludesCropAndBothDirectSources() throws IOException {
        File root = temporaryFolder.newFolder("session-count");
        File day = new File(root, "20260720");
        write(root, "20260720/waze-crop/crop/session.jsonl", 1);
        write(root, "20260720/waze-direct/waze/events.jsonl", 1);
        write(root, "20260720/gmaps-direct/gmaps/events.jsonl", 1);
        write(root, "20260720/logs/events.log", 1);

        assertEquals(3, NavigationLogStorage.countSessions(day));
    }

    private static File write(File root, String relative, int bytes) throws IOException {
        File file = new File(root, relative.replace('/', File.separatorChar));
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), new byte[bytes]);
        return file;
    }
}
