package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises catalog cleanup against its real persisted identities and files. */
public final class NavigatorCatalogCleanupTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void cleanupRemovesOnlyExactOwnedFilenameAndDigest() throws Exception {
        File downloads = temporaryFolder.newFolder("downloads");
        File updates = temporaryFolder.newFolder("updates");
        String oldBytes = "previous catalog APK";
        String oldSha = sha256(oldBytes.getBytes(Charset.defaultCharset()));
        File mismatchedDownload = new File(downloads, "maps-direct.apk");
        File ownedUpdate = new File(updates, "maps-direct.apk");
        File renamedCurrent = new File(downloads, "maps-direct-v2.apk");
        File unrelated = new File(downloads, "other-navigator.apk");
        Files.write(mismatchedDownload.toPath(), "new bytes".getBytes(Charset.defaultCharset()));
        Files.write(ownedUpdate.toPath(), oldBytes.getBytes(Charset.defaultCharset()));
        Files.write(renamedCurrent.toPath(), "current bytes".getBytes(Charset.defaultCharset()));
        Files.write(unrelated.toPath(), oldBytes.getBytes(Charset.defaultCharset()));

        String pending = NavigatorCatalogCleanup.enqueue("", "maps-direct.apk", oldSha);
        assertEquals(pending, NavigatorCatalogCleanup.enqueue(
                pending, "maps-direct.apk", oldSha.toUpperCase(Locale.US)));
        List<NavigatorCatalogCleanup.Entry> entries = NavigatorCatalogCleanup.entries(pending);
        assertEquals(1, entries.size());
        assertEquals("maps-direct.apk", entries.get(0).fileName);
        assertEquals(oldSha, entries.get(0).sha256);

        NavigatorCatalogCleanup.deleteOwnedCopies(downloads, updates, entries.get(0));

        assertTrue(mismatchedDownload.isFile());
        assertFalse(ownedUpdate.exists());
        assertTrue(renamedCurrent.isFile());
        assertTrue(unrelated.isFile());
    }

    @Test public void cleanupQueueRejectsPathsWithoutDiscardingExistingEntries() throws Exception {
        String sha = sha256("known bytes".getBytes(Charset.defaultCharset()));
        String pending = NavigatorCatalogCleanup.enqueue("", "maps-direct.apk", sha);

        assertEquals(pending, NavigatorCatalogCleanup.enqueue(pending, "../maps-direct.apk", sha));
        assertEquals(pending, NavigatorCatalogCleanup.enqueue(pending, "", sha));
        assertEquals(1, NavigatorCatalogCleanup.entries(pending).size());
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }
}
