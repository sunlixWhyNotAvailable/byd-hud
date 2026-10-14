package com.bydhud.app;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ExportArtifactFilesTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void expiredArchivesAndPartialCopiesAreDeletedButProtectedAndFreshFilesSurvive() throws Exception {
        File directory = files.newFolder("log-shares");
        long now = System.currentTimeMillis();
        File expired = write(directory, "BYD-HUD-logs-expired.zip", 7);
        File protectedZip = write(directory, "BYD-HUD-logs-upload.zip", 11);
        File fresh = write(directory, "BYD-HUD-logs-fresh.zip", 3);
        File unrelated = write(directory, "navigator.apk", 5);
        File partial = write(directory, "BYD-HUD-logs-aborted.zip.part", 13);
        assertTrue(expired.setLastModified(now - 600_001));
        assertTrue(protectedZip.setLastModified(now - 900_000));
        ExportArtifactFiles.Removed removed = new ExportArtifactFiles.Removed();
        ExportArtifactFiles.logArchives(directory, Set.of(protectedZip.getAbsolutePath()), now, removed);
        assertFalse(expired.exists());
        assertFalse(partial.exists());
        assertTrue(protectedZip.isFile());
        assertTrue(fresh.isFile());
        assertTrue(unrelated.isFile());
        assertEquals(2, removed.files);
        assertEquals(20, removed.bytes);
        ExportArtifactFiles.logArchives(directory, Set.of(protectedZip.getAbsolutePath()), now, removed);
        assertEquals(2, removed.files);
        assertEquals(20, removed.bytes);
    }

    @Test public void removingWorkingCopiesCannotEscapeOrRemoveTheWorkspaceRoot() throws Exception {
        File root = files.newFolder("job");
        File snapshots = new File(root, "snapshots");
        assertTrue(snapshots.mkdir());
        write(snapshots, "source.log", 19);
        File result = write(root, "result.zip", 4);
        File outside = files.newFile("outside.log");
        ExportArtifactFiles.Removed removed = new ExportArtifactFiles.Removed();
        assertThrows(IOException.class, () -> ExportArtifactFiles.delete(root, outside, removed));
        assertThrows(IOException.class, () -> ExportArtifactFiles.delete(root, root, removed));
        assertThrows(IOException.class, () -> ExportArtifactFiles.child(root, "../outside.log"));
        ExportArtifactFiles.delete(root, snapshots, removed);
        ExportArtifactFiles.delete(root, snapshots, removed);
        assertFalse(snapshots.exists());
        assertTrue(root.isDirectory());
        assertTrue(result.isFile());
        assertTrue(outside.isFile());
        assertEquals(1, removed.files);
        assertEquals(19, removed.bytes);
    }

    @Test public void admissionAndRetirementCannotOwnTheSameFileLockTogether() throws Exception {
        File lockFile = files.newFile("operation.lock");
        try (ShellWorkFiles.OperationLock held = new ShellWorkFiles.OperationLock(lockFile)) {
            assertThrows(IOException.class, () -> new ShellWorkFiles.OperationLock(lockFile));
        }
        try (ShellWorkFiles.OperationLock reopened = new ShellWorkFiles.OperationLock(lockFile)) {
            assertThrows(IOException.class, () -> new ShellWorkFiles.OperationLock(lockFile));
        }
    }

    private static File write(File parent, String name, int bytes) throws IOException {
        File file = new File(parent, name);
        Files.write(file.toPath(), new byte[bytes]);
        return file;
    }
}
