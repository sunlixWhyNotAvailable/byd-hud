package com.bydhud.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Process-local, changed-path accounting for dated navigation storage. */
final class NavigationStorageInventory {
    private static final Pattern DAY = Pattern.compile("\\d{8}");
    private static final Pattern TOMBSTONE = Pattern.compile(
            "\\.delete-\\d{8}-\\d+-\\d+(?:-\\d+)?");
    private static final int MAX_DIRTY_PATHS = 4096;
    private static final int BATCH_FILES = 64;
    private static final long BATCH_NANOS = 150_000_000L;
    private static final long BATCH_PAUSE_MS = 20L;

    private final Object notificationLock = new Object();
    private final Object refreshLock = new Object();
    private final Map<String, Long> dirtyPaths = new LinkedHashMap<>();
    private volatile Snapshot snapshot;
    private long notificationSequence;
    private long invalidationSequence;
    private long fullRequestSequence;
    private boolean fullRequired = true;

    /** Queues a path cheaply; filesystem checks happen during refresh. */
    void markChanged(File path) {
        String key = key(Objects.requireNonNull(path, "path").toPath());
        synchronized (notificationLock) {
            long sequence = ++notificationSequence;
            if (!dirtyPaths.containsKey(key) && dirtyPaths.size() >= MAX_DIRTY_PATHS) {
                dirtyPaths.clear();
                fullRequired = true;
                invalidationSequence++;
                fullRequestSequence++;
                snapshot = null;
                return;
            }
            dirtyPaths.put(key, sequence);
        }
    }

    /** Drops the published view and requires a new complete baseline. */
    void invalidate() {
        synchronized (notificationLock) {
            invalidationSequence++;
            fullRequestSequence++;
            fullRequired = true;
            snapshot = null;
        }
    }

    Snapshot currentSnapshot() {
        return snapshot;
    }

    Snapshot refresh(List<File> roots, boolean force) throws IOException, InterruptedException {
        Objects.requireNonNull(roots, "roots");
        synchronized (refreshLock) {
            long startedNanos = System.nanoTime();
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("navigation storage inventory interrupted before refresh");
                }

                Map<String, Long> changed;
                long sequenceAtStart;
                long invalidationAtStart;
                long fullRequestAtStart;
                boolean requireFull;
                Snapshot previous;
                synchronized (notificationLock) {
                    changed = new LinkedHashMap<>(dirtyPaths);
                    sequenceAtStart = notificationSequence;
                    invalidationAtStart = invalidationSequence;
                    fullRequestAtStart = fullRequestSequence;
                    requireFull = fullRequired;
                    previous = snapshot;
                }

                List<Root> currentRoots = resolveRoots(roots);
                boolean full = force || requireFull || previous == null
                        || !previous.rootIdentities.equals(rootIdentities(currentRoots))
                        || previous.totalBytes == Long.MAX_VALUE
                        || changesRoot(changed, currentRoots);
                Scan scan;
                if (full) {
                    scan = scanAll(currentRoots);
                } else if (changed.isEmpty()) {
                    long elapsed = elapsedMs(startedNanos);
                    Snapshot idle = new Snapshot(previous, 0L, elapsed, false);
                    verifyRootsUnchanged(currentRoots, roots);
                    checkInterrupted();
                    publish(idle, sequenceAtStart, invalidationAtStart, fullRequestAtStart, false);
                    return idle;
                } else {
                    scan = scanChanged(previous, currentRoots, changed);
                }

                List<Root> verifiedRoots = resolveRoots(roots);
                if (!rootIdentities(currentRoots).equals(rootIdentities(verifiedRoots))) {
                    throw new IOException("Navigation storage root identity changed during inventory refresh");
                }
                checkInterrupted();
                long elapsed = elapsedMs(startedNanos);
                long completedNanos = System.nanoTime();
                Snapshot next = new Snapshot(
                        scan,
                        elapsed,
                        full,
                        full ? scan.visitedFiles : previous.lastFullScanVisitedFiles,
                        full ? elapsed : previous.lastFullScanElapsedMs,
                        full ? completedNanos : previous.lastFullCompletedNanos);
                publish(next, sequenceAtStart, invalidationAtStart, fullRequestAtStart, full);
                return next;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                invalidate();
                throw e;
            } catch (IOException e) {
                invalidate();
                throw e;
            } catch (RuntimeException e) {
                invalidate();
                throw e;
            }
        }
    }

    private void publish(
            Snapshot next,
            long sequenceAtStart,
            long invalidationAtStart,
            long fullRequestAtStart,
            boolean full) throws IOException {
        synchronized (notificationLock) {
            if (invalidationSequence != invalidationAtStart) {
                snapshot = null;
                throw new IOException("Navigation storage inventory was invalidated during refresh");
            }
            dirtyPaths.entrySet().removeIf(entry -> entry.getValue() <= sequenceAtStart);
            if (full && fullRequestSequence == fullRequestAtStart) {
                fullRequired = false;
            }
            snapshot = next;
        }
    }

    private static void verifyRootsUnchanged(List<Root> before, List<File> configured)
            throws IOException {
        if (!rootIdentities(before).equals(rootIdentities(resolveRoots(configured)))) {
            throw new IOException("Navigation storage root identity changed during inventory refresh");
        }
    }

    private static List<Root> resolveRoots(List<File> roots) throws IOException {
        Map<String, Root> unique = new LinkedHashMap<>();
        for (File file : roots) {
            if (file == null) continue;
            Path logical = file.toPath().toAbsolutePath().normalize();
            String logicalKey = key(logical);
            if (unique.containsKey(logicalKey)) continue;
            unique.put(logicalKey, resolveRoot(logical));
        }
        List<Root> result = new ArrayList<>(unique.values());
        result.sort((left, right) -> left.logicalKey.compareTo(right.logicalKey));
        return result;
    }

    private static Root resolveRoot(Path logical) throws IOException {
        final Path physical;
        try {
            physical = logical.toRealPath();
        } catch (NoSuchFileException e) {
            return new Root(logical, null, "missing");
        } catch (IOException e) {
            throw io("resolve root", logical, e);
        }
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(physical, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return new Root(logical, null, "missing");
        } catch (IOException e) {
            throw io("stat root", logical, e);
        }
        if (!attributes.isDirectory()) {
            throw new IOException("Navigation storage root is not a directory: " + logical);
        }
        final DirectoryStream<Path> stream;
        try {
            stream = Files.newDirectoryStream(physical);
        } catch (NoSuchFileException e) {
            return new Root(logical, null, "missing");
        } catch (IOException e) {
            throw io("open root", logical, e);
        }
        try (DirectoryStream<Path> ignored = stream) {
            // Opening the root detects access failures without traversing its history.
        } catch (IOException e) {
            throw io("close root", logical, e);
        }
        Object fileKey = attributes.fileKey();
        // fileKey is stable across child mutations; creation time is fallback only where omitted.
        String identity = physical + "|" + (fileKey != null
                ? fileKey
                : "created=" + attributes.creationTime().toMillis());
        return new Root(logical, physical, identity);
    }

    private static Map<String, String> rootIdentities(List<Root> roots) {
        Map<String, String> identities = new LinkedHashMap<>();
        for (Root root : roots) identities.put(root.logicalKey, root.identity);
        return identities;
    }

    private static boolean changesRoot(Map<String, Long> changed, List<Root> roots) {
        for (Root root : roots) {
            if (changed.containsKey(root.logicalKey)
                    || (root.physical != null && changed.containsKey(key(root.physical)))) return true;
        }
        return false;
    }

    private static Scan scanAll(List<Root> roots) throws IOException, InterruptedException {
        Scan scan = new Scan();
        ScanPacer pacer = new ScanPacer();
        for (Root root : roots) {
            scan.rootIdentities.put(root.logicalKey, root.identity);
            putDirectory(scan, root.logical, root.physical, 0L);
            if (root.physical == null) continue;
            long total = 0L;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root.physical)) {
                for (Path child : children) {
                    BasicFileAttributes attributes = attributes(child);
                    if (attributes != null && attributes.isDirectory()
                            && isTrackedRootChild(child.getFileName().toString())) {
                        Path logicalChild = root.logical.resolve(child.getFileName().toString());
                        total = add(total, scanDirectory(
                                child, logicalChild, scan, pacer));
                    }
                    pacer.afterEntry(false);
                }
            } catch (DirectoryIteratorException e) {
                throw io("list root", root.physical, e.getCause());
            } catch (IOException e) {
                throw io("list root", root.physical, e);
            }
            putDirectory(scan, root.logical, root.physical, total);
        }
        return scan;
    }

    private static long scanDirectory(
            Path physical,
            Path logical,
            Scan scan,
            ScanPacer pacer) throws IOException, InterruptedException {
        checkInterrupted();
        putDirectory(scan, logical, physical, 0L);
        long total = 0L;
        final DirectoryStream<Path> children;
        try {
            children = Files.newDirectoryStream(physical);
        } catch (NoSuchFileException e) {
            removeSubtree(scan.files, key(logical));
            removeSubtree(scan.files, key(physical));
            removeSubtree(scan.directories, key(logical));
            removeSubtree(scan.directories, key(physical));
            return 0L;
        } catch (IOException e) {
            throw io("open directory", physical, e);
        }
        try (DirectoryStream<Path> listing = children) {
            for (Path child : listing) {
                BasicFileAttributes attributes = attributes(child);
                if (attributes != null) {
                    String childName = child.getFileName().toString();
                    Path logicalChild = logical.resolve(childName);
                    if (attributes.isDirectory()) {
                        total = add(total, scanDirectory(child, logicalChild, scan, pacer));
                    } else if (attributes.isRegularFile()) {
                        long size = Math.max(0L, attributes.size());
                        putFile(scan, logicalChild, child, size);
                        scan.visitedFiles++;
                        total = add(total, size);
                    }
                }
                pacer.afterEntry(attributes != null && attributes.isRegularFile());
            }
        } catch (DirectoryIteratorException e) {
            throw io("list directory", physical, e.getCause());
        } catch (IOException e) {
            throw io("list directory", physical, e);
        }
        putDirectory(scan, logical, physical, total);
        return total;
    }

    private static Scan scanChanged(
            Snapshot previous,
            List<Root> roots,
            Map<String, Long> changed) throws IOException, InterruptedException {
        Scan scan = new Scan(previous);
        ScanPacer pacer = new ScanPacer();
        List<Path> paths = new ArrayList<>();
        for (Map.Entry<String, Long> entry : changed.entrySet()) {
            checkInterrupted();
            Path path = new File(entry.getKey()).toPath().toAbsolutePath().normalize();
            Root root = containingRoot(path, roots);
            if (root == null) continue;
            if (!path.startsWith(root.logical) && root.physical != null) {
                path = root.logical.resolve(root.physical.relativize(path)).normalize();
            }
            paths.add(path);
        }
        paths.sort((left, right) -> {
            int depth = Integer.compare(left.getNameCount(), right.getNameCount());
            return depth != 0 ? depth : left.toString().compareTo(right.toString());
        });
        Set<String> covered = new HashSet<>();
        for (Path path : paths) {
            checkInterrupted();
            String pathKey = key(path);
            if (covered.contains(pathKey)) continue;
            Path ancestor = path.getParent();
            boolean hasChangedAncestor = false;
            while (ancestor != null) {
                if (covered.contains(key(ancestor))) {
                    hasChangedAncestor = true;
                    break;
                }
                ancestor = ancestor.getParent();
            }
            if (hasChangedAncestor) continue;
            covered.add(pathKey);
            Root root = containingRoot(path, roots);
            if (root != null && !path.equals(root.logical)) {
                applyChangedPath(root, path, scan, pacer);
            }
        }
        for (Root root : roots) scan.rootIdentities.put(root.logicalKey, root.identity);
        return scan;
    }

    private static Root containingRoot(Path path, List<Root> roots) {
        Root result = null;
        int resultDepth = -1;
        for (Root root : roots) {
            int depth = path.startsWith(root.logical) ? root.logical.getNameCount() : -1;
            if (root.physical != null && path.startsWith(root.physical)) {
                depth = Math.max(depth, root.physical.getNameCount());
            }
            if (depth > resultDepth) {
                result = root;
                resultDepth = depth;
            }
        }
        return result;
    }

    private static void applyChangedPath(
            Root root,
            Path path,
            Scan scan,
            ScanPacer pacer) throws IOException, InterruptedException {
        Path relative = root.logical.relativize(path);
        if (relative.getNameCount() == 0
                || !isTrackedRootChild(relative.getName(0).toString())) {
            replaceSubtree(root, path, null, scan, pacer);
            return;
        }
        if (root.physical == null) {
            replaceSubtree(root, path, null, scan, pacer);
            return;
        }

        Path physical = root.physical;
        List<Path> ancestors = new ArrayList<>();
        ancestors.add(root.logical);
        Path logical = root.logical;
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            String part = relative.getName(i).toString();
            physical = physical.resolve(part);
            logical = logical.resolve(part);
            BasicFileAttributes parent = attributes(physical);
            if (parent == null || !parent.isDirectory()) {
                replaceSubtree(root, path, null, scan, pacer);
                return;
            }
            ancestors.add(logical);
        }
        for (Path ancestor : ancestors) {
            Path physicalAncestor = root.physical.resolve(root.logical.relativize(ancestor));
            putDirectoryIfAbsent(scan, ancestor, physicalAncestor);
        }

        Path target = physical.resolve(relative.getFileName().toString());
        BasicFileAttributes targetAttributes = attributes(target);
        replaceSubtree(root, path, targetAttributes, scan, pacer, target);
    }

    private static void replaceSubtree(
            Root root,
            Path path,
            BasicFileAttributes attributes,
            Scan scan,
            ScanPacer pacer) throws IOException, InterruptedException {
        replaceSubtree(root, path, attributes, scan, pacer, null);
    }

    private static void replaceSubtree(
            Root root,
            Path path,
            BasicFileAttributes attributes,
            Scan scan,
            ScanPacer pacer,
            Path physical) throws IOException, InterruptedException {
        String pathKey = key(path);
        Path physicalPath = root.physical == null
                ? null
                : root.physical.resolve(root.logical.relativize(path));
        String physicalKey = physicalPath == null ? null : key(physicalPath);
        Long oldDirectoryBytes = scan.directories.get(pathKey);
        if (oldDirectoryBytes == null && physicalKey != null) {
            oldDirectoryBytes = scan.directories.get(physicalKey);
        }
        long before = oldDirectoryBytes != null
                ? oldDirectoryBytes
                : scan.files.getOrDefault(pathKey,
                        physicalKey == null ? 0L : scan.files.getOrDefault(physicalKey, 0L));
        boolean knownDirectory = oldDirectoryBytes != null;
        if (knownDirectory) {
            removeSubtree(scan.files, pathKey);
            removeSubtree(scan.directories, pathKey);
            if (physicalKey != null && !physicalKey.equals(pathKey)) {
                removeSubtree(scan.files, physicalKey);
                removeSubtree(scan.directories, physicalKey);
            }
        } else {
            scan.files.remove(pathKey);
            scan.directories.remove(pathKey);
            if (physicalKey != null && !physicalKey.equals(pathKey)) {
                scan.files.remove(physicalKey);
                scan.directories.remove(physicalKey);
            }
        }
        long after = 0L;
        if (attributes != null && physical != null) {
            if (attributes.isDirectory()) {
                after = scanDirectory(physical, path, scan, pacer);
            } else if (attributes.isRegularFile()) {
                after = Math.max(0L, attributes.size());
                putFile(scan, path, physical, after);
                scan.visitedFiles++;
            }
        }
        long difference = after - before;
        Path parent = path.getParent();
        Path physicalParent = physicalPath == null ? null : physicalPath.getParent();
        while (parent != null && parent.startsWith(root.logical)) {
            String parentKey = key(parent);
            long current = scan.directories.getOrDefault(parentKey, 0L);
            long adjusted = adjust(current, difference);
            scan.directories.put(parentKey, adjusted);
            if (physicalParent != null) scan.directories.put(key(physicalParent), adjusted);
            if (parent.equals(root.logical)) break;
            parent = parent.getParent();
            physicalParent = physicalParent == null ? null : physicalParent.getParent();
        }
    }

    private static void putFile(Scan scan, Path logical, Path physical, long bytes) {
        scan.files.put(key(logical), bytes);
        scan.files.put(key(physical), bytes);
    }

    private static void putDirectory(Scan scan, Path logical, Path physical, long bytes) {
        scan.directories.put(key(logical), bytes);
        if (physical != null) scan.directories.put(key(physical), bytes);
    }

    private static void putDirectoryIfAbsent(Scan scan, Path logical, Path physical) {
        scan.directories.putIfAbsent(key(logical), 0L);
        if (physical != null) scan.directories.putIfAbsent(key(physical), 0L);
    }

    private static void removeSubtree(Map<String, Long> values, String key) {
        String prefix = key + File.separator;
        values.keySet().removeIf(path -> path.equals(key) || path.startsWith(prefix));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw io("stat", path, e);
        }
    }

    private static boolean isTrackedRootChild(String name) {
        return DAY.matcher(name).matches() || TOMBSTONE.matcher(name).matches();
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("navigation storage inventory interrupted");
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static IOException io(String operation, Path path, IOException cause) {
        return new IOException("Navigation storage inventory could not " + operation
                + " at " + path + ": " + cause.getMessage(), cause);
    }

    private static long add(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long adjust(long value, long difference) {
        if (difference >= 0L) return add(value, difference);
        return Math.max(0L, value + difference);
    }

    private static final class Root {
        final Path logical;
        final String logicalKey;
        final Path physical;
        final String identity;

        Root(Path logical, Path physical, String identity) {
            this.logical = logical;
            this.logicalKey = key(logical);
            this.physical = physical;
            this.identity = identity;
        }
    }

    private static final class Scan {
        final Map<String, Long> files = new HashMap<>();
        final Map<String, Long> directories = new HashMap<>();
        final Map<String, String> rootIdentities = new LinkedHashMap<>();
        long visitedFiles;

        Scan() { }

        Scan(Snapshot previous) {
            files.putAll(previous.fileBytes);
            directories.putAll(previous.directoryBytes);
            rootIdentities.putAll(previous.rootIdentities);
            visitedFiles = 0L;
        }

        long totalBytes() {
            long total = 0L;
            for (String root : rootIdentities.keySet()) {
                total = add(total, directories.getOrDefault(root, 0L));
            }
            return total;
        }
    }

    private static final class ScanPacer {
        private long batchStartedNanos = System.nanoTime();
        private int batchFiles;

        void afterEntry(boolean fileVisited) throws InterruptedException {
            checkInterrupted();
            if (fileVisited) batchFiles++;
            long now = System.nanoTime();
            if (batchFiles < BATCH_FILES && now - batchStartedNanos < BATCH_NANOS) return;
            try {
                Thread.sleep(BATCH_PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            batchFiles = 0;
            batchStartedNanos = System.nanoTime();
        }
    }

    static final class Snapshot {
        final long totalBytes;
        final long visitedFiles;
        final long elapsedMs;
        final boolean fullScan;
        final long lastFullScanVisitedFiles;
        final long lastFullScanElapsedMs;
        final long lastFullCompletedNanos;
        private final Map<String, Long> fileBytes;
        private final Map<String, Long> directoryBytes;
        private final Map<String, String> rootIdentities;

        Snapshot(
                Scan scan,
                long elapsedMs,
                boolean fullScan,
                long lastFullScanVisitedFiles,
                long lastFullScanElapsedMs,
                long lastFullCompletedNanos) {
            this.totalBytes = scan.totalBytes();
            this.visitedFiles = scan.visitedFiles;
            this.elapsedMs = elapsedMs;
            this.fullScan = fullScan;
            this.lastFullScanVisitedFiles = lastFullScanVisitedFiles;
            this.lastFullScanElapsedMs = lastFullScanElapsedMs;
            this.lastFullCompletedNanos = lastFullCompletedNanos;
            this.fileBytes = Collections.unmodifiableMap(new HashMap<>(scan.files));
            this.directoryBytes = Collections.unmodifiableMap(new HashMap<>(scan.directories));
            this.rootIdentities = Collections.unmodifiableMap(
                    new LinkedHashMap<>(scan.rootIdentities));
        }

        private Snapshot(Snapshot previous, long visitedFiles, long elapsedMs, boolean fullScan) {
            this.totalBytes = previous.totalBytes;
            this.visitedFiles = visitedFiles;
            this.elapsedMs = elapsedMs;
            this.fullScan = fullScan;
            this.lastFullScanVisitedFiles = previous.lastFullScanVisitedFiles;
            this.lastFullScanElapsedMs = previous.lastFullScanElapsedMs;
            this.lastFullCompletedNanos = previous.lastFullCompletedNanos;
            this.fileBytes = previous.fileBytes;
            this.directoryBytes = previous.directoryBytes;
            this.rootIdentities = previous.rootIdentities;
        }

        long bytes(File directoryOrFile) {
            String path = key(Objects.requireNonNull(directoryOrFile, "directoryOrFile").toPath());
            Long file = fileBytes.get(path);
            return file != null ? file : directoryBytes.getOrDefault(path, 0L);
        }
    }
}
