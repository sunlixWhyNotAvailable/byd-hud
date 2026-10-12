package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ShellWorkFilesTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void shanghaiEvidenceAcceptsBothRootsButRejectsOtherSessionsAndEscapes() throws Exception {
        File publicRoot = files.newFolder("Documents", "BYD-HUD");
        File externalRoot = files.newFolder("external-files", "BYD-HUD");
        String day = "20261012";
        String id = "0cbb70d1-f8b2-49d8-9af0-a17c53e4a980";
        for (File root : new File[]{publicRoot, externalRoot}) {
            File evidence = new File(root, day + "/logs/logcat/shanghai_" + id);
            java.nio.file.Files.createDirectories(evidence.toPath());
            assertEquals(evidence.getCanonicalFile(), ShellWorkFiles.shanghaiEvidence(
                    publicRoot, externalRoot, day, id, evidence.getPath()));
            assertThrows(IOException.class, () -> ShellWorkFiles.shanghaiEvidence(
                    publicRoot, externalRoot, "20261011", id, evidence.getPath()));
            assertThrows(IOException.class, () -> ShellWorkFiles.shanghaiEvidence(
                    publicRoot, externalRoot, day, "another-session", evidence.getPath()));
        }
        File outside = files.newFolder("BYD-HUD-other", day, "logs", "logcat", "shanghai_" + id);
        assertThrows(IOException.class, () -> ShellWorkFiles.shanghaiEvidence(
                publicRoot, externalRoot, day, id, outside.getPath()));
        assertThrows(IOException.class, () -> ShellWorkFiles.shanghaiEvidence(
                publicRoot, externalRoot, "../20261012", id, outside.getPath()));
    }

    @Test public void installedInspectionNeedsItsOwnPurposeAndCannotLeaveTheCache() throws Exception {
        File cache = files.newFolder("patch-cache");
        String suffix = "0cbb70d1-f8b2-49d8-9af0-a17c53e4a980";
        File installed = new File(cache, "waze-installed-" + suffix);
        java.nio.file.Files.createDirectories(installed.toPath());
        assertEquals(installed.getCanonicalFile(), ShellWorkFiles.patchInspectionOutput(
                cache, "waze", true, installed.getPath()));
        assertThrows(IOException.class, () -> ShellWorkFiles.patchInspectionOutput(
                cache, "waze", false, installed.getPath()));
        assertThrows(IOException.class, () -> ShellWorkFiles.patchInspectionOutput(
                cache, "gmaps", true, installed.getPath()));
        File scan = new File(cache, "waze-worker-scan-" + suffix);
        java.nio.file.Files.createDirectories(scan.toPath());
        assertEquals(scan.getCanonicalFile(), ShellWorkFiles.patchInspectionOutput(
                cache, "waze", false, scan.getPath()));
        File outside = files.newFolder("outside", installed.getName());
        assertThrows(IOException.class, () -> ShellWorkFiles.patchInspectionOutput(
                cache, "waze", true, outside.getPath()));
        File nested = files.newFolder("patch-cache", "nested", installed.getName());
        assertThrows(IOException.class, () -> ShellWorkFiles.patchInspectionOutput(
                cache, "waze", true, nested.getPath()));
    }
}
