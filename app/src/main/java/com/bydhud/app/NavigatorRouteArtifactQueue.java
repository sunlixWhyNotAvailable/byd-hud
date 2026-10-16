package com.bydhud.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Bounded FIFO reservations for immutable route artifacts; retained accounting includes the write in progress. */
final class NavigatorRouteArtifactQueue {
    static final int MAX_RETAINED_ROUTES = 8;
    static final long MAX_RETAINED_BYTES = 64L * 1024L * 1024L;

    static final class Entry {
        final Context context;
        final String targetDay;
        final File directLogsDir;
        final NavigatorRouteData route;
        final long retainedBytes;
        final long enqueuedAtNanos;
        private final String key;
        private boolean completed;

        private Entry(Context context, String targetDay, File directLogsDir,
                NavigatorRouteData route, long retainedBytes, long enqueuedAtNanos,
                String key) {
            this.context = context;
            this.targetDay = targetDay;
            this.directLogsDir = directLogsDir;
            this.route = route;
            this.retainedBytes = retainedBytes;
            this.enqueuedAtNanos = enqueuedAtNanos;
            this.key = key;
        }

        File logsDir() {
            return directLogsDir != null ? directLogsDir
                    : NavigationLogStorage.logsDir(context, targetDay);
        }

        String key() {
            return key;
        }
    }

    static final class Offer {
        final Entry entry;
        final String rejectionReason;
        final boolean duplicate;

        private Offer(Entry entry, String rejectionReason, boolean duplicate) {
            this.entry = entry;
            this.rejectionReason = rejectionReason;
            this.duplicate = duplicate;
        }

        boolean accepted() {
            return entry != null || duplicate;
        }
    }

    private final ArrayDeque<Entry> queued = new ArrayDeque<>();
    private final Set<String> outstandingKeys = new HashSet<>();
    private int retainedRoutes;
    private long retainedBytes;

    synchronized Offer offer(Context context, String targetDay, File directLogsDir,
            NavigatorRouteData route) {
        if (route == null || (context == null && directLogsDir == null)) {
            return new Offer(null, "invalid_item", false);
        }
        String safeDay = targetDay == null ? "" : targetDay;
        if (!safeDay.matches("[0-9]{8}")) {
            return new Offer(null, "invalid_day", false);
        }
        NavigatorRouteData.Header header = route.header();
        String source = NavigatorRouteArtifact.safeSource(header.navigator);
        if (source == null || header.producerEpoch <= 0 || header.routeRevision <= 0) {
            return new Offer(null, "invalid_identity", false);
        }
        String key = safeDay + ":" + source + ":" + header.producerEpoch
                + ":" + header.routeRevision;
        if (outstandingKeys.contains(key)) {
            return new Offer(null, null, true);
        }

        long bytes = estimateRetainedBytes(route, safeDay);
        if (bytes > MAX_RETAINED_BYTES) {
            return new Offer(null, "route_memory_limit", false);
        }
        if (retainedRoutes >= MAX_RETAINED_ROUTES) {
            return new Offer(null, "route_queue_full", false);
        }
        if (retainedBytes > MAX_RETAINED_BYTES - bytes) {
            return new Offer(null, "route_memory_full", false);
        }

        Entry entry = new Entry(context, safeDay, directLogsDir, route, bytes,
                System.nanoTime(), key);
        queued.addLast(entry);
        outstandingKeys.add(key);
        retainedRoutes++;
        retainedBytes += bytes;
        return new Offer(entry, null, false);
    }

    synchronized Entry take() {
        return queued.pollFirst();
    }

    synchronized void complete(Entry entry) {
        if (entry == null || entry.completed || !outstandingKeys.remove(entry.key())) return;
        entry.completed = true;
        retainedRoutes--;
        retainedBytes -= entry.retainedBytes;
    }

    synchronized java.util.List<Entry> failPending() {
        java.util.List<Entry> failed = new java.util.ArrayList<>(queued);
        queued.clear();
        for (Entry entry : failed) complete(entry);
        return failed;
    }

    synchronized int retainedRoutes() {
        return retainedRoutes;
    }

    synchronized long retainedBytes() {
        return retainedBytes;
    }

    private static long estimateRetainedBytes(NavigatorRouteData route, String day) {
        NavigatorRouteData.Header header = route.header();
        long bytes = 160L + stringBytes(day) + stringBytes(header.navigator)
                + stringBytes(header.routeId);
        bytes = add(bytes, arrayBytes(route.pointCount(), 16L));
        bytes = add(bytes, arrayBytes(route.maneuverCount(), 8L));
        for (int i = 0; i < route.maneuverCount(); i++) {
            NavigatorRouteData.Maneuver maneuver = route.maneuver(i);
            bytes = add(bytes, 96L + stringBytes(maneuver.id)
                    + stringBytes(maneuver.type) + stringBytes(maneuver.turnSide));
        }
        return bytes;
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : 40L + (long) value.length() * 2L;
    }

    private static long arrayBytes(int count, long bytesPerItem) {
        return 16L + (long) count * bytesPerItem;
    }

    private static long add(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
