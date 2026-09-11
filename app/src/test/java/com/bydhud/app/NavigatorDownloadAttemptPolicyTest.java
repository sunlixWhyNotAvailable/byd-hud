package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.DownloadManager;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

public final class NavigatorDownloadAttemptPolicyTest {
    @Test
    public void partialActiveFileSurvivesRepeatedPresenceReconciliation() throws IOException {
        Path partial = Files.createTempFile("navigator-active-", ".apk.attempt-4");
        try {
            Files.write(partial, new byte[]{'P', 'K', 3, 4});
            for (int refresh = 0; refresh < 5; refresh++) {
                assertFalse(NavigatorDownloadAttemptGuard.shouldRediscover(
                        NavigatorAssetManager.DOWNLOADING, false, partial.toFile()));
                assertTrue(Files.isRegularFile(partial));
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    @Test
    public void onlyIdleStableCacheIsEligibleForRediscovery() throws IOException {
        Path stable = Files.createTempFile("navigator-stable-", ".apk");
        try {
            assertTrue(NavigatorDownloadAttemptGuard.shouldRediscover(
                    NavigatorAssetManager.NOT_DOWNLOADED, false, stable.toFile()));
            assertFalse(NavigatorDownloadAttemptGuard.shouldRediscover(
                    NavigatorAssetManager.VERIFYING, false, stable.toFile()));
            assertFalse(NavigatorDownloadAttemptGuard.shouldRediscover(
                    NavigatorAssetManager.ERROR, false, stable.toFile()));
            assertFalse(NavigatorDownloadAttemptGuard.shouldRediscover(
                    NavigatorAssetManager.NOT_DOWNLOADED, true, stable.toFile()));
        } finally {
            Files.deleteIfExists(stable);
        }
    }

    @Test
    public void pendingRunningAndPausedRemainActive() {
        assertTrue(NavigatorAssetManager.isActiveDownloadManagerStatusForTest(
                DownloadManager.STATUS_PENDING));
        assertTrue(NavigatorAssetManager.isActiveDownloadManagerStatusForTest(
                DownloadManager.STATUS_RUNNING));
        assertTrue(NavigatorAssetManager.isActiveDownloadManagerStatusForTest(
                DownloadManager.STATUS_PAUSED));
        assertFalse(NavigatorAssetManager.isActiveDownloadManagerStatusForTest(
                DownloadManager.STATUS_SUCCESSFUL));
        assertFalse(NavigatorAssetManager.isActiveDownloadManagerStatusForTest(
                DownloadManager.STATUS_FAILED));
    }

    @Test
    public void restartCanRecoverLegacyDestinationButRetryRejectsStaleWorker()
            throws IOException {
        Path legacy = Files.createTempFile("google-maps-legacy-", ".apk");
        try {
            boolean legacyOwned = NavigatorAssetManager.ownsAttemptForTest(
                    0L, 82L, legacy.getFileName().toString(),
                    0L, 82L, legacy.getFileName().toString());
            assertTrue(legacyOwned);
            assertTrue(NavigatorDownloadAttemptGuard.settleValidated(
                    legacy.toFile(), legacy.toFile(), legacyOwned));
            assertTrue(Files.exists(legacy));

            assertFalse(NavigatorAssetManager.ownsAttemptForTest(
                    4L, 82L, "google-maps.apk.attempt-4",
                    5L, 83L, "google-maps.apk.attempt-5"));
            assertFalse(NavigatorAssetManager.ownsAttemptForTest(
                    4L, 82L, "google-maps.apk.attempt-4",
                    4L, 83L, "google-maps.apk.attempt-4"));
        } finally {
            Files.deleteIfExists(legacy);
        }
    }

    @Test
    public void staleValidationDeletesOnlyItsAttemptAndCannotPublishOverRetry()
            throws IOException {
        Path directory = Files.createTempDirectory("navigator-race-");
        Path stable = directory.resolve("navigator.apk");
        Path oldAttempt = directory.resolve("navigator.apk.attempt-4");
        Path newAttempt = directory.resolve("navigator.apk.attempt-5");
        try {
            Files.write(stable, new byte[]{1});
            Files.write(oldAttempt, new byte[]{4});
            Files.write(newAttempt, new byte[]{5});

            assertFalse(NavigatorDownloadAttemptGuard.settleValidated(
                    oldAttempt.toFile(), stable.toFile(), false));
            assertFalse(Files.exists(oldAttempt));
            assertTrue(Files.exists(newAttempt));
            assertTrue(Files.readAllBytes(stable)[0] == 1);

            assertTrue(NavigatorDownloadAttemptGuard.settleValidated(
                    newAttempt.toFile(), stable.toFile(), true));
            assertFalse(Files.exists(newAttempt));
            assertTrue(Files.readAllBytes(stable)[0] == 5);
        } finally {
            Files.deleteIfExists(oldAttempt);
            Files.deleteIfExists(newAttempt);
            Files.deleteIfExists(stable);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void terminalReasonsSeparateStorageNetworkAndSystemFailures() {
        assertTrue(NavigatorAssetManager.ERROR_STORAGE.equals(
                NavigatorAssetManager.downloadFailureCategoryForTest(
                        DownloadManager.ERROR_FILE_ERROR)));
        assertTrue(NavigatorAssetManager.ERROR_NETWORK.equals(
                NavigatorAssetManager.downloadFailureCategoryForTest(503)));
        assertTrue(NavigatorAssetManager.ERROR_SYSTEM.equals(
                NavigatorAssetManager.downloadFailureCategoryForTest(
                        DownloadManager.ERROR_UNKNOWN)));
    }

    @Test
    public void terminalFailureRemovesItsPartialAttempt() throws IOException {
        Path partial = Files.createTempFile("navigator-failed-", ".apk.attempt-9");
        NavigatorDownloadAttemptGuard.discardFailed(partial.toFile());
        assertFalse(Files.exists(partial));
    }

    @Test
    public void concurrentAdmissionsCannotReuseGenerationOrDestination() throws Exception {
        Object admissionLock = new Object();
        AtomicLong persistedGeneration = new AtomicLong();
        Set<String> destinations = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        Throwable[] failures = new Throwable[2];
        Thread[] admissions = new Thread[2];
        for (int index = 0; index < admissions.length; index++) {
            final int slot = index;
            admissions[index] = new Thread(() -> {
                try {
                    start.await();
                    NavigatorDownloadAttemptGuard.serialized(admissionLock, () -> {
                        long generation = persistedGeneration.get() + 1L;
                        try {
                            Thread.sleep(20L);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException(error);
                        }
                        persistedGeneration.set(generation);
                        destinations.add("navigator.apk.attempt-" + generation);
                        return null;
                    });
                } catch (Throwable error) {
                    failures[slot] = error;
                }
            });
            admissions[index].start();
        }
        start.countDown();
        for (Thread admission : admissions) admission.join();

        assertTrue(failures[0] == null);
        assertTrue(failures[1] == null);
        assertTrue(persistedGeneration.get() == 2L);
        assertTrue(destinations.contains("navigator.apk.attempt-1"));
        assertTrue(destinations.contains("navigator.apk.attempt-2"));
    }

    @Test
    public void verifyingRestartRecoversAlreadyPromotedStableFile() throws IOException {
        Path directory = Files.createTempDirectory("navigator-promoted-restart-");
        Path stable = directory.resolve("navigator.apk");
        Path missingAttempt = directory.resolve("navigator.apk.attempt-6");
        try {
            Files.write(stable, new byte[]{6});
            assertTrue(NavigatorDownloadAttemptGuard.validationCandidate(
                    NavigatorAssetManager.VERIFYING,
                    missingAttempt.toFile(), stable.toFile()).equals(stable.toFile()));
            assertTrue(NavigatorDownloadAttemptGuard.settleValidated(
                    stable.toFile(), stable.toFile(), true));
            assertTrue(Files.readAllBytes(stable)[0] == 6);
        } finally {
            Files.deleteIfExists(stable);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void interruptedAdmissionsNeverReuseAReservedDestination() {
        String first = NavigatorDownloadAttemptGuard.newAttemptFileName("navigator.apk", 7L);
        String second = NavigatorDownloadAttemptGuard.newAttemptFileName("navigator.apk", 7L);
        assertFalse(first.equals(second));
        assertTrue(first.startsWith("navigator.apk.attempt-7-"));
        assertTrue(second.startsWith("navigator.apk.attempt-7-"));
    }

    @Test
    public void admissionReservationIsDurableBeforeEnqueueAndRestartCanRecoverRow()
            throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.isDirectory(root.resolve("app"))) root = root.getParent();
        String source = new String(Files.readAllBytes(root.resolve(
                "app/src/main/java/com/bydhud/app/NavigatorAssetManager.java")),
                StandardCharsets.UTF_8);
        int method = source.indexOf("private static void startDownloadLocked");
        int reservation = source.indexOf(".remove(key(asset, \"download_id\"))", method);
        int enqueue = source.indexOf("manager.enqueue(request)", method);
        int recovery = source.indexOf("recoverReservedDownloadId(context, asset, ticket)");
        assertTrue(method >= 0 && reservation > method && enqueue > reservation);
        assertTrue(recovery > 0);
    }

    @Test
    public void typedMaterializationStorageFailureIsNotReportedAsInvalidApk() {
        assertTrue(NavigatorAssetManager.ERROR_STORAGE.equals(
                NavigatorAssetManager.materializationFailureCategoryForTest(
                        new NavigatorApkSet.StorageException("disk unavailable"))));
        assertTrue(NavigatorAssetManager.ERROR_INVALID_APK.equals(
                NavigatorAssetManager.materializationFailureCategoryForTest(
                        new IOException("structural failure"))));
    }

    @Test
    public void reservedDownloadRecoversFromHintBeforeLocalUriExists() throws IOException {
        Path expected = Files.createTempFile("navigator-reserved-", ".apk.attempt-11");
        try {
            assertTrue(NavigatorAssetManager.matchesReservedDestinationForTest(
                    expected.toFile(), null, expected.toFile().toURI().toString()));
            assertFalse(NavigatorAssetManager.matchesReservedDestinationForTest(
                    expected.toFile(), null,
                    expected.resolveSibling("other.apk.attempt-11").toString()));
        } finally {
            Files.deleteIfExists(expected);
        }
    }
}
