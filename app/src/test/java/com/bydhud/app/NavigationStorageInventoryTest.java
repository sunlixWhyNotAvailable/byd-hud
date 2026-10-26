package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NavigationStorageInventoryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void realFilesUseFullBaselineAndChangedPathAccounting() throws Exception {
        File publicRoot = temporary.newFolder("public");
        File privateRoot = temporary.newFolder("private");
        File day = new File(publicRoot, "20261025");
        File privateDay = new File(privateRoot, "20261024");
        File tombstone = new File(publicRoot, ".delete-20261023-123-0");
        File legacy = new File(publicRoot, "legacy");
        assertTrue(day.mkdir());
        assertTrue(privateDay.mkdir());
        assertTrue(tombstone.mkdir());
        assertTrue(legacy.mkdir());

        File growing = new File(day, "active.part");
        File finished = new File(day, "active.log");
        File moved = new File(privateDay, "session.log");
        File tombstoneFile = new File(tombstone, "retired.log");
        File legacyFile = new File(legacy, "old.log");
        write(growing, "abc", false);
        write(moved, "12345", false);
        write(tombstoneFile, "t", false);
        write(legacyFile, "ignored legacy data", false);
        for (int i = 0; i < 70; i++) {
            Files.write(new File(day, "history-" + i + ".bin").toPath(), new byte[0]);
        }

        NavigationStorageInventory inventory = new NavigationStorageInventory();
        NavigationStorageInventory.Snapshot baseline = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertTrue(baseline.fullScan);
        assertEquals(73L, baseline.visitedFiles);
        assertEquals(9L, baseline.totalBytes);
        assertEquals(3L, baseline.bytes(growing));
        assertEquals(1L, baseline.bytes(tombstone));
        assertEquals(0L, baseline.bytes(legacy));
        long firstFullCompletion = baseline.lastFullCompletedNanos;

        File newDay = new File(publicRoot, "20261026");
        assertTrue(newDay.mkdir());
        File newDayFile = new File(newDay, "new.log");
        write(newDayFile, "12", false);
        inventory.markChanged(newDay);
        NavigationStorageInventory.Snapshot rootChildAdded = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertFalse(rootChildAdded.fullScan);
        assertEquals(11L, rootChildAdded.totalBytes);
        assertEquals(1L, rootChildAdded.visitedFiles);
        assertTrue(newDayFile.delete());
        assertTrue(newDay.delete());
        inventory.markChanged(newDay);
        NavigationStorageInventory.Snapshot rootChildRemoved = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertFalse(rootChildRemoved.fullScan);
        assertEquals(9L, rootChildRemoved.totalBytes);

        write(growing, "de", true);
        inventory.markChanged(growing);
        NavigationStorageInventory.Snapshot appended = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertFalse(appended.fullScan);
        assertEquals(1L, appended.visitedFiles);
        assertEquals(11L, appended.totalBytes);
        assertEquals(5L, appended.bytes(day));
        assertEquals(firstFullCompletion, appended.lastFullCompletedNanos);
        assertEquals(3L, baseline.bytes(growing));

        Files.move(growing.toPath(), finished.toPath());
        inventory.markChanged(growing);
        inventory.markChanged(finished);
        NavigationStorageInventory.Snapshot renamed = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertEquals(11L, renamed.totalBytes);
        assertEquals(1L, renamed.visitedFiles);
        assertEquals(0L, renamed.bytes(growing));
        assertEquals(5L, renamed.bytes(finished));

        write(finished, "x", false);
        inventory.markChanged(finished);
        assertEquals(7L, inventory.refresh(Arrays.asList(publicRoot, privateRoot), false).totalBytes);

        File created = new File(day, "created.log");
        write(created, "more", false);
        inventory.markChanged(created);
        assertEquals(11L, inventory.refresh(Arrays.asList(publicRoot, privateRoot), false).totalBytes);

        assertTrue(created.delete());
        inventory.markChanged(created);
        assertEquals(7L, inventory.refresh(Arrays.asList(publicRoot, privateRoot), false).totalBytes);

        File external = new File(day, "external.log");
        write(external, "outside", false);
        NavigationStorageInventory.Snapshot forced = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), true);
        assertTrue(forced.fullScan);
        assertEquals(14L, forced.totalBytes);
        assertEquals(7L, forced.bytes(external));
        assertTrue(forced.lastFullCompletedNanos >= firstFullCompletion);

        write(finished, "!", true);
        inventory.markChanged(day);
        inventory.markChanged(finished);
        NavigationStorageInventory.Snapshot directoryUpdate = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertFalse(directoryUpdate.fullScan);
        assertEquals(72L, directoryUpdate.visitedFiles);
        assertEquals(15L, directoryUpdate.totalBytes);

        File retiredSession = new File(day, "retired-session");
        assertTrue(retiredSession.mkdir());
        File retiredA = new File(retiredSession, "a.log");
        File retiredB = new File(retiredSession, "b.log");
        write(retiredA, "12", false);
        write(retiredB, "345", false);
        inventory.markChanged(retiredSession);
        NavigationStorageInventory.Snapshot addedDirectory = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertEquals(20L, addedDirectory.totalBytes);
        assertEquals(2L, addedDirectory.visitedFiles);

        assertTrue(retiredA.delete());
        assertTrue(retiredB.delete());
        assertTrue(retiredSession.delete());
        inventory.markChanged(retiredSession);
        NavigationStorageInventory.Snapshot removedDirectory = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertEquals(15L, removedDirectory.totalBytes);
        assertEquals(0L, removedDirectory.bytes(retiredSession));

        NavigationStorageInventory.Snapshot idle = inventory.refresh(
                Arrays.asList(publicRoot, privateRoot), false);
        assertFalse(idle.fullScan);
        assertEquals(0L, idle.visitedFiles);
        assertEquals(15L, idle.totalBytes);
        assertEquals(removedDirectory.lastFullCompletedNanos, idle.lastFullCompletedNanos);
    }

    @Test public void missingRootCountsZeroAndExistingNonDirectoryInvalidates() throws Exception {
        File root = new File(temporary.getRoot(), "optional-root");
        NavigationStorageInventory inventory = new NavigationStorageInventory();
        NavigationStorageInventory.Snapshot absent = inventory.refresh(
                Collections.singletonList(root), false);
        assertTrue(absent.fullScan);
        assertEquals(0L, absent.totalBytes);

        assertTrue(root.mkdir());
        File day = new File(root, "20261027");
        assertTrue(day.mkdir());
        File data = new File(day, "data.log");
        write(data, "real", false);
        NavigationStorageInventory.Snapshot restored = inventory.refresh(
                Collections.singletonList(root), false);
        assertTrue(restored.fullScan);
        assertEquals(4L, restored.totalBytes);

        File notDirectory = new File(temporary.getRoot(), "not-a-directory");
        write(notDirectory, "file", false);
        try {
            inventory.refresh(Collections.singletonList(notDirectory), false);
            fail("an existing file cannot be an inventory root");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not a directory"));
        }
        assertNull(inventory.currentSnapshot());
    }

    private static void write(File file, String value, boolean append) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, append)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
