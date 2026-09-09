package com.bydhud.app;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigurationExportArtifactsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String NAME = "BYD-HUD-vehicle-config-20260909-120000-123.zip";
    private static final long CREATED = 1_789_000_000_000L;
    private static final long TTL = ConfigurationExportArtifacts.RETENTION_MS;

    private File file(String name) throws Exception {
        File value = new File(temp.getRoot(), name);
        Files.write(value.toPath(), new byte[]{1, 2, 3});
        return value;
    }

    private long sweep(long now) {
        return ConfigurationExportArtifacts.sweep(temp.getRoot(), now, Collections.emptySet(), ignored -> { });
    }

    @Test public void allPartsUsePersistedCompletionDateAndNeverRenewAcrossChecks() throws Exception {
        File base = new File(temp.getRoot(), NAME);
        File first = file(NAME + ".001"), second = file(NAME + ".002");
        ConfigurationExportArtifacts.completed(base, CREATED);
        assertEquals(CREATED + TTL, sweep(CREATED + TTL - 1));
        assertTrue(first.exists());
        assertTrue(second.exists());
        assertEquals(Long.MAX_VALUE, sweep(CREATED + 24 * 60 * 60_000L));
        assertFalse(first.exists());
        assertFalse(second.exists());
        assertFalse(new File(base.getPath() + ".expiry.json").exists());
    }

    @Test public void legacyUsesLastWriteAndFutureFallbackIsPersistedOnlyOnce() throws Exception {
        File old = file(NAME);
        assertTrue(old.setLastModified(CREATED - 86_400_000));
        sweep(CREATED);
        assertFalse(old.exists());
        File future = file(NAME);
        assertTrue(future.setLastModified(CREATED + 86_400_000));
        assertEquals(CREATED + TTL, sweep(CREATED));
        assertEquals(CREATED + TTL, sweep(CREATED + TTL - 1));
        sweep(CREATED + TTL);
        assertFalse(future.exists());
    }

    @Test public void activePartAndUnrelatedFilesAreNotDeleted() throws Exception {
        File part = file(NAME + ".001.part");
        File unrelated = file("BYD-HUD-logs-20260909.zip");
        File lookalike = file("BYD-HUD-vehicle-config-other.zip");
        Set<String> active = Collections.singleton(new File(temp.getRoot(), NAME).getAbsolutePath());
        ConfigurationExportArtifacts.sweep(temp.getRoot(), CREATED, active, ignored -> { });
        assertTrue(part.exists());
        sweep(CREATED);
        assertFalse(part.exists());
        assertTrue(unrelated.exists());
        assertTrue(lookalike.exists());
    }

    @Test public void freshLegacyArchiveKeepsOriginalRemainingTime() throws Exception {
        File legacy = file(NAME);
        assertTrue(legacy.setLastModified(CREATED));
        assertEquals(CREATED + TTL, sweep(CREATED + 600_000));
        assertTrue(legacy.exists());
        sweep(CREATED + TTL);
        assertFalse(legacy.exists());
    }

    @Test public void failedDeletionRetainsDeadlineRetriesAndDoesNotClaimFreedBytes() throws Exception {
        File base = file(NAME);
        ConfigurationExportArtifacts.completed(base, CREATED);
        File refusesDelete = new File(base.getPath()) {
            @Override public boolean delete() { return false; }
        };
        File directory = new File(temp.getRoot().getPath()) {
            @Override public File[] listFiles() {
                return new File[]{refusesDelete, new File(base.getPath() + ".expiry.json")};
            }
        };
        List<String> events = new ArrayList<>();
        long next = ConfigurationExportArtifacts.sweep(directory, CREATED + TTL, Collections.emptySet(), events::add);
        assertTrue(base.exists());
        assertTrue(new File(base.getPath() + ".expiry.json").exists());
        assertTrue(next > CREATED + TTL && next < CREATED + TTL * 2);
        assertTrue(events.stream().anyMatch(event -> event.startsWith("cleanup_failed")));
        assertTrue(events.stream().noneMatch(event -> event.contains("bytes=")));
        sweep(next);
        assertFalse(base.exists());
    }
}
