package com.bydhud.app;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NavigatorRouteArtifactQueueTest {
    @Test
    public void memoryLimitRejectsBeforeCountLimitAndReleasesCompletedReservation() throws Exception {
        Path logs = Files.createTempDirectory("navigator-route-memory-");
        NavigatorRouteArtifactQueue queue = new NavigatorRouteArtifactQueue();
        double[] points = new double[2_000_000];
        try {
            for (int revision = 1; revision <= 4; revision++) {
                NavigatorRouteData route = new NavigatorRouteData(
                        new NavigatorRouteData.Header("gmaps-26.30", 42, revision, "large", 123456),
                        true, false, points, new NavigatorRouteData.Maneuver[0]);
                assertTrue(queue.offer(null, "20261016", logs.toFile(), route).accepted());
            }
            NavigatorRouteData fifth = new NavigatorRouteData(
                    new NavigatorRouteData.Header("gmaps-26.30", 42, 5, "large", 123456),
                    true, false, points, new NavigatorRouteData.Maneuver[0]);
            NavigatorRouteArtifactQueue.Entry active = queue.take();
            assertEquals("route_memory_full",
                    queue.offer(null, "20261016", logs.toFile(), fifth).rejectionReason);
            assertEquals(4, queue.retainedRoutes());
            queue.complete(active);
            assertTrue(queue.offer(null, "20261016", logs.toFile(), fifth).accepted());
            assertTrue(queue.retainedBytes() <= NavigatorRouteArtifactQueue.MAX_RETAINED_BYTES);
            queue.failPending();
            assertEquals(0L, queue.retainedBytes());
        } finally {
            deleteTree(logs);
        }
    }

    @Test
    public void boundsRetainedRoutesIncludingTheActiveWriteAndPreservesFifo() throws Exception {
        File logs = Files.createTempDirectory("navigator-route-queue-").toFile();
        NavigatorRouteArtifactQueue queue = new NavigatorRouteArtifactQueue();
        try {
            for (int revision = 1; revision <= 8; revision++) {
                NavigatorRouteArtifactQueue.Offer offer = queue.offer(null, "20261016",
                        logs, route(revision));
                assertTrue(offer.accepted());
                assertNotNull(offer.entry);
            }
            assertEquals(NavigatorRouteArtifactQueue.MAX_RETAINED_ROUTES,
                    queue.retainedRoutes());
            assertTrue(queue.retainedBytes() > 0L);

            NavigatorRouteArtifactQueue.Offer duplicate = queue.offer(null, "20261016",
                    logs, route(8));
            assertTrue(duplicate.duplicate);
            NavigatorRouteArtifactQueue.Offer saturated = queue.offer(null, "20261016",
                    logs, route(9));
            assertFalse(saturated.accepted());
            assertEquals("route_queue_full", saturated.rejectionReason);

            NavigatorRouteArtifactQueue.Entry currentWrite = queue.take();
            assertNotNull(currentWrite);
            assertEquals(1L, currentWrite.route.header().routeRevision);
            assertTrue(queue.offer(null, "20261016", logs, route(1)).duplicate);
            assertEquals(NavigatorRouteArtifactQueue.MAX_RETAINED_ROUTES,
                    queue.retainedRoutes());
            assertFalse(queue.offer(null, "20261016", logs, route(10)).accepted());

            assertTrue(NavigatorRouteArtifact.write(currentWrite.logsDir(), currentWrite.route));
            queue.complete(currentWrite);
            assertEquals(NavigatorRouteArtifactQueue.MAX_RETAINED_ROUTES - 1,
                    queue.retainedRoutes());
            assertTrue(queue.offer(null, "20261016", logs, route(10)).accepted());

            NavigatorRouteArtifactQueue.Entry entry;
            for (long expectedRevision = 2L; expectedRevision <= 8L; expectedRevision++) {
                entry = queue.take();
                assertNotNull(entry);
                assertEquals(expectedRevision, entry.route.header().routeRevision);
                assertTrue(NavigatorRouteArtifact.write(entry.logsDir(), entry.route));
                queue.complete(entry);
            }
            entry = queue.take();
            assertNotNull(entry);
            assertEquals(10L, entry.route.header().routeRevision);
            assertTrue(NavigatorRouteArtifact.write(entry.logsDir(), entry.route));
            queue.complete(entry);
            assertNull(queue.take());
            assertEquals(0, queue.retainedRoutes());
            assertEquals(0L, queue.retainedBytes());
            for (int revision = 1; revision <= 8; revision++) {
                assertTrue(artifact(logs, revision).isFile());
            }
            assertFalse(artifact(logs, 9).exists());
            assertTrue(artifact(logs, 10).isFile());
        } finally {
            deleteTree(logs.toPath());
        }
    }

    private static NavigatorRouteData route(long revision) {
        NavigatorRouteData.Header header = new NavigatorRouteData.Header(
                "gmaps-26.30", 42L, revision, "queue-test-" + revision, 123456L);
        return new NavigatorRouteData(header, true, false,
                new double[] {31.1, 121.1, 31.2, 121.2},
                new NavigatorRouteData.Maneuver[0]);
    }

    private static File artifact(File logs, long revision) {
        return new File(logs,
                "route-geometry/gmaps-26.30/42-" + revision + ".json.gz");
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
