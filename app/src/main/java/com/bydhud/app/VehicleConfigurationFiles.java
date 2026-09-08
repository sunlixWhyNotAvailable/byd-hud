package com.bydhud.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only inventory of firmware files relevant to the HUD/cluster path.
 *
 * <p>This class intentionally inventories metadata only. File bodies are streamed by the
 * archive collector through {@link LocalAdbBridge.ConfigurationExportSession}.</p>
 */
final class VehicleConfigurationFiles {
    private static final String STAT_FORMAT = "%f %s %Y %d %i %y";
    private static final Pattern STAT_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.([0-9]{1,9}) [+-][0-9]{4}");
    private static final Pattern STAT_COMMAND = Pattern.compile(
            "^stat -Lc '" + Pattern.quote(STAT_FORMAT) + "' ('?)(/[^'\\r\\n]+)\\1$");
    private static final Pattern READLINK_COMMAND = Pattern.compile(
            "^readlink -f ('?)(/[^'\\r\\n]+)\\1$");
    private static final Pattern PM_PATH_COMMAND = Pattern.compile(
            "^pm path ([A-Za-z0-9][A-Za-z0-9_.-]{0,127})$");
    private static final Pattern MAPS_COMMAND = Pattern.compile("^cat /proc/[0-9]{1,10}/maps$");
    private static final Pattern EXE_COMMAND = Pattern.compile(
            "^readlink -f /proc/[0-9]{1,10}/exe$");
    private static final Pattern ELF_COMMAND = Pattern.compile(
            "^(?:readelf|llvm-readelf) -d ('?)(/[^'\\r\\n]+)\\1$");
    private static final Pattern NEEDED = Pattern.compile("Shared library: \\[([^]]+)\\]");
    private static final Pattern FILE_KEY = Pattern.compile(".*dev=([0-9a-fA-F]+).*ino=([0-9]+).*");
    private static final Pattern PROCESS_COMMAND = Pattern.compile("^ps -A -o pid,args$");
    private static final Pattern PROCESS_PACKAGE = Pattern.compile("\\b(com\\.[a-z0-9_.]+)");
    private static final Pattern FIND_COMMAND = Pattern.compile(
            "^find (/(?:system|vendor|product|odm|system_ext)(?:/(?:etc|lib|lib64|framework|bin|app|priv-app))?"
                    + "|/cluster)(?: (/(?:system|vendor|product|odm|system_ext)(?:/(?:etc|lib|lib64|framework|bin|app|priv-app))?"
                    + "|/cluster))* -type f$");

    private static final Set<String> APPROVED_ROOTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "/system", "/vendor", "/product", "/odm", "/system_ext", "/cluster")));
    /** APEX is intentionally narrow: only its public native-library directories are eligible. */
    private static final Pattern APEX_LIBRARY_PATH = Pattern.compile(
            "^/apex/[^/]+/(?:lib|lib64)(?:/|$).+\\.so$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> PRIVATE_SEGMENTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "/data/user/", "/data/data/", "/data/misc/", "/data/system/",
                    "/data/vendor/", "/data/local/tmp/", "/data/local/", "/sdcard/",
                    "/storage/", "/mnt/", "/acct/", "/proc/", "/sys/")));
    private static final Set<String> RELEVANT_PACKAGE_TOKENS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "naviauto", "someip", "amapservice", "containerservice",
                    "clusterdebug", "cluster_hmi", "cluster", "launchermap", "carsetting",
                    "car.settings", "mapaccount", "adas", "kanzi", "fission", "vehicle")));
    private static final Set<String> KNOWN_PACKAGES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "com.byd.naviauto", "com.ts.car.someip.service", "com.byd.someipsystemservice",
                    "com.example.amapservice", "com.byd.amapservice", "com.byd.containerservice",
                    "com.byd.clusterdebug", "com.byd.launchermap", "com.byd.carsetting",
                    "com.byd.car.settings", "com.byd.carsettings", "com.byd.carsettings.plugins",
                    "com.byd.providers.carsettings", "com.byd.mapaccount", "com.byd.map.account",
                    "com.byd.adas", "com.byd.adasservice", "com.byd.auto_camera",
                    "com.byd.server.adasagent",
                    "com.byd.avc", "com.byd.bydcamera", "com.byd.camera.remotectrl",
                    "com.byd.cameramanager", "com.byd.cdr", "com.byd.diagnosticinfo",
                    "com.byd.dipilot.dms", "com.byd.eventcenter", "com.byd.sr",
                    "com.android.car.settings", "com.xdja.clusterdemo")));
    private static final Set<String> LIBRARY_FAMILIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "libbydcluster", "libbyddatasource", "libsomeip", "libcommonapi-someip",
                    "libvsomeipjni", "libsomeipnative", "libvsomeip3", "vendor.ts.someip@",
                    "libdi5lijie", "libvehicle", "libcan", "libprotobuf", "libbinder",
                    "libandroid_runtime", "libutils", "libbase", "liblog", "libc++",
                    "libdl.so", "libm.so", "libz.so")));
    private static final Set<String> FRAMEWORK_FAMILIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "framework.jar", "services.jar", "framework-res.apk", "android.policy.jar",
                    "framework.odex", "framework.vdex", "services.odex", "services.vdex",
                    "framework.art", "services.art", "framework-res.odex", "framework-res.vdex",
                    "framework-res.art", "framework.oat", "services.oat",
                    "boot-framework.oat", "boot-framework.vdex", "boot-framework.art",
                    "boot-services.oat", "boot-services.vdex", "boot-services.art",
                    "dilink", "bmmcamera",
                    "car-framework", "bydcluster", "kanzi")));
    private static final Set<String> SENSITIVE_NAMES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "account", "accounts", "credential", "credentials", "password", "passwords",
                    "passwd", "secret", "secrets", "token", "tokens", "private", "keystore",
                    "keychain", "vin", "location", "gps", "route", "routes", "recording",
                    "recordings", "history", "cookie")));
    private static final List<String> FIND_COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "find /system/etc /vendor/etc /product/etc /odm/etc /system_ext/etc -type f",
            "find /system/lib /system/lib64 /vendor/lib /vendor/lib64 /product/lib /product/lib64 "
                    + "/odm/lib /odm/lib64 /system_ext/lib /system_ext/lib64 -type f",
            "find /system/framework /vendor/framework /product/framework /odm/framework "
                    + "/system_ext/framework -type f",
            "find /system/bin /vendor/bin /product/bin /odm/bin /system_ext/bin /cluster -type f",
            "find /system/app /system/priv-app /vendor/app /vendor/priv-app /product/app "
                    + "/product/priv-app /odm/app /odm/priv-app /system_ext/app /system_ext/priv-app -type f",
            "find /apex -type f -name '*.so'"));

    private VehicleConfigurationFiles() { }

    interface ProgressListener {
        void changed(String currentFile, int foundFiles, long knownBytes, int unavailableFiles);
    }

    static final class FileStat {
        final long mode;
        final long size;
        final long modifiedEpochMs;
        final FileTime modifiedTime;
        final long device;
        final long inode;

        FileStat(long mode, long size, long modifiedEpochMs, long device, long inode) {
            this(mode, size, FileTime.fromMillis(modifiedEpochMs), device, inode);
        }

        FileStat(long mode, long size, FileTime modifiedTime, long device, long inode) {
            this.mode = mode;
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.modifiedEpochMs = modifiedTime.toMillis();
            this.device = device;
            this.inode = inode;
        }

        boolean isRegularFile() { return (mode & 0170000L) == 0100000L; }

        String identity() {
            return device > 0L && inode > 0L ? device + ":" + inode : "size=" + size + ":mtime=" + modifiedEpochMs;
        }

        static FileStat parse(String line) throws IOException {
            String[] values = line == null ? new String[0] : line.trim().split("\\s+", 6);
            if (values.length != 6) throw new IOException("invalid stat output");
            try {
                long mode = Long.parseLong(values[0], 16);
                long size = Long.parseLong(values[1]);
                long seconds = Long.parseLong(values[2]);
                Matcher timestamp = STAT_TIME.matcher(values[5]);
                if (!timestamp.matches()) throw new IOException("subsecond stat time unavailable");
                int nanos = Integer.parseInt((timestamp.group(1) + "000000000").substring(0, 9));
                FileTime modified = FileTime.from(Instant.ofEpochSecond(seconds, nanos));
                long device = Long.parseLong(values[3]);
                long inode = Long.parseLong(values[4]);
                if (size < 0L || device < 0L || inode < 0L) throw new IOException("negative stat value");
                FileStat stat = new FileStat(mode, size, modified, device, inode);
                if (!stat.isRegularFile()) throw new IOException("not a regular file");
                return stat;
            } catch (ArithmeticException | NumberFormatException | java.time.DateTimeException malformed) {
                throw new IOException("invalid stat output", malformed);
            }
        }
    }

    static final class Entry {
        final String sourcePath;
        final String archivePath;
        final long size;
        final long modifiedEpochMs;
        final FileTime modifiedTime;
        final long device;
        final long inode;
        final String type;
        final String category;
        final Set<String> aliases = new LinkedHashSet<>();

        Entry(String sourcePath, String archivePath, long size, long modifiedEpochMs,
                long device, long inode, String type, String category) {
            this(sourcePath, archivePath, size, FileTime.fromMillis(modifiedEpochMs), device, inode, type, category);
        }

        private Entry(String sourcePath, String archivePath, long size, FileTime modifiedTime,
                long device, long inode, String type, String category) {
            this.sourcePath = sourcePath;
            this.archivePath = archivePath;
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.modifiedEpochMs = modifiedTime.toMillis();
            this.device = device;
            this.inode = inode;
            this.type = type == null ? "regular" : type;
            this.category = category == null ? "other" : category;
        }

        Entry(FileStat stat, String sourcePath, String category) {
            this(sourcePath, archivePath(sourcePath), stat.size, stat.modifiedTime,
                    stat.device, stat.inode, "regular", category);
        }
    }

    static final class Unavailable {
        final String sourcePath;
        final String reason;

        Unavailable(String sourcePath, String reason) {
            this.sourcePath = sourcePath == null ? "" : sourcePath;
            this.reason = reason == null ? "unavailable" : reason;
        }
    }

    static final class Inventory {
        final List<Entry> entries = new ArrayList<>();
        final List<Unavailable> unavailable = new ArrayList<>();
        private final ProgressListener progress;
        long totalBytes;
        boolean partial;

        Inventory() {
            this(null);
        }

        Inventory(ProgressListener progress) {
            this.progress = progress;
        }

        void inspect(String path) {
            if (progress != null) {
                progress.changed(path == null ? "" : path, entries.size(), totalBytes,
                        unavailable.size());
            }
        }

        void unavailable(String path, String reason) {
            unavailable.add(new Unavailable(path, reason));
            partial = true;
            inspect(path);
        }

        void add(Entry entry, String alias) {
            if (entry == null) return;
            String identity = entryIdentity(entry);
            for (Entry existing : entries) {
                if (entryIdentity(existing).equals(identity)) {
                    if (alias != null && !alias.isEmpty()) existing.aliases.add(alias);
                    existing.aliases.addAll(entry.aliases);
                    existing.aliases.add(entry.sourcePath);
                    return;
                }
            }
            if (alias != null && !alias.isEmpty()) entry.aliases.add(alias);
            entry.aliases.add(entry.sourcePath);
            entries.add(entry);
            try {
                totalBytes = Math.addExact(totalBytes, entry.size);
            } catch (ArithmeticException overflow) {
                totalBytes = Long.MAX_VALUE;
                unavailable(entry.sourcePath, "inventory size overflow");
            }
            inspect(entry.sourcePath);
        }

        private static String entryIdentity(Entry entry) {
            return entry.device > 0L && entry.inode > 0L
                    ? entry.device + ":" + entry.inode : entry.sourcePath;
        }
    }

    /** Collects all selected metadata; no file body is read into memory. */
    static Inventory collect(Context context, LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled) throws IOException {
        return collect(context, session, cancelled, null);
    }

    static Inventory collect(Context context, LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled, ProgressListener progress) throws IOException {
        Inventory inventory = new Inventory(progress);
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        Set<String> packagePaths = new LinkedHashSet<>();
        Map<String, Set<String>> pathAliases = new LinkedHashMap<>();
        inventory.inspect("package-manager");
        discoverPackages(context, session, stop, packagePaths, pathAliases, inventory);
        for (String path : packagePaths) {
            inventory.inspect(path);
            String canonical = canonicalPath(path, session, stop, inventory);
            if (canonical != null) {
                addCandidate(canonical, "apk", pathAliases.get(path), session, stop, inventory);
            }
        }

        if (session == null) {
            Map<String, List<String>> localLibraries = new LinkedHashMap<>();
            discoverLocalFiles(stop, inventory, localLibraries);
            for (Entry entry : inventory.entries) {
                if (entry.category.startsWith("native")) {
                    localLibraries.computeIfAbsent(baseName(entry.sourcePath), ignored -> new ArrayList<>())
                            .add(entry.sourcePath);
                }
            }
            expandDependencies(context, null, stop, inventory, localLibraries);
            inventory.unavailable("remote-inventory", "ADB export session unavailable; local filesystem inventory attempted");
            inventory.entries.sort(Comparator.comparing(entry -> entry.archivePath));
            return inventory;
        }
        Map<String, List<String>> libraryIndex = new LinkedHashMap<>();
        for (String command : FIND_COMMANDS) {
            checkCancelled(stop);
            inventory.inspect(command);
            LocalAdbBridge.ShellResult result = run(session, command);
            if (result == null) {
                inventory.unavailable(command, "inventory command unavailable");
                continue;
            }
            if (result.truncated) inventory.unavailable(command,
                    "partial inventory: command output truncated; droppedBytes=" + result.droppedBytes);
            for (String line : lines(result.output)) {
                checkCancelled(stop);
                String rawPath = line.trim();
                if (!isAllowedPath(rawPath)) continue;
                if (rawPath.toLowerCase(Locale.ROOT).endsWith(".so")) {
                    libraryIndex.computeIfAbsent(baseName(rawPath), ignored -> new ArrayList<>()).add(rawPath);
                }
                if (!isRelevantCandidate(rawPath)) continue;
                inventory.inspect(rawPath);
                String path = canonicalPath(rawPath, session, stop, inventory);
                if (path == null || !isRelevantCandidate(path)) continue;
                addCandidate(path, category(path), null, session, stop, inventory);
            }
            if (!result.success() && !result.truncated) {
                inventory.unavailable(command, "inventory command failed: " + safe(result.error));
            }
        }
        discoverProcesses(session, stop, inventory, libraryIndex);
        expandDependencies(context, session, stop, inventory, libraryIndex);
        inventory.entries.sort(Comparator.comparing(entry -> entry.archivePath));
        return inventory;
    }

    private static void discoverPackages(Context context, LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled, Set<String> paths, Map<String, Set<String>> aliases,
            Inventory inventory) throws IOException {
        Set<String> packages = new TreeSet<>(KNOWN_PACKAGES);
        if (context != null) {
            try {
                PackageManager manager = context.getPackageManager();
                for (PackageInfo info : manager.getInstalledPackages(0)) {
                    checkCancelled(cancelled);
                    if (info != null && relevantPackageInfo(info)) packages.add(info.packageName);
                    if (info == null || !relevantPackageInfo(info)) continue;
                    addPackagePaths(info, paths, aliases);
                }
            } catch (RuntimeException error) {
                inventory.unavailable("package-manager", error.getClass().getSimpleName());
            }
        }
        for (String packageName : packages) {
            checkCancelled(cancelled);
            if (session == null) continue;
            inventory.inspect("package:" + packageName);
            LocalAdbBridge.ShellResult result;
            try {
                result = run(session, "pm path " + packageName);
            } catch (RuntimeException error) {
                inventory.unavailable("package:" + packageName, error.getClass().getSimpleName());
                continue;
            }
            if (result == null) {
                inventory.unavailable("package:" + packageName, "pm path unavailable");
                continue;
            }
            if (result.truncated) inventory.unavailable("package:" + packageName,
                    "partial pm path output; retained paths may be incomplete");
            if (!result.success() && result.output.trim().isEmpty()) {
                inventory.unavailable("package:" + packageName, "pm path failed");
                continue;
            }
            int discoveredPaths = 0;
            for (String line : lines(result.output)) {
                String path = line.trim();
                if (path.startsWith("package:")) path = path.substring("package:".length());
                if (!isAllowedPath(path) || !isApkPath(path)) {
                    if (!path.isEmpty()) inventory.unavailable("package:" + packageName,
                            "rejected path: " + path);
                    continue;
                }
                paths.add(path);
                discoveredPaths++;
                Set<String> discovered = aliases.computeIfAbsent(path, ignored -> new LinkedHashSet<>());
                discovered.add(path);
                discovered.add(packageName);
            }
            if (result.success() && discoveredPaths == 0) {
                inventory.unavailable("package:" + packageName, "package not installed or no APK path");
            }
        }
    }

    private static void addPackagePaths(PackageInfo info, Set<String> paths,
            Map<String, Set<String>> aliases) {
        List<String> values = new ArrayList<>();
        if (info.applicationInfo != null) {
            values.add(info.applicationInfo.sourceDir);
            if (info.applicationInfo.splitSourceDirs != null) {
                values.addAll(Arrays.asList(info.applicationInfo.splitSourceDirs));
            }
        }
        for (String value : values) {
            if (!isAllowedPath(value) || !isApkPath(value)) continue;
            paths.add(value);
            Set<String> discovered = aliases.computeIfAbsent(value, ignored -> new LinkedHashSet<>());
            discovered.add(value);
            discovered.add(info.packageName);
        }
    }

    private static void discoverProcesses(LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled, Inventory inventory, Map<String, List<String>> libraryIndex)
            throws IOException {
        inventory.inspect("processes");
        LocalAdbBridge.ShellResult processes = run(session, "ps -A -o pid,args");
        if (processes == null) {
            inventory.unavailable("processes", "process identity unavailable");
            return;
        }
        if (processes.truncated) inventory.unavailable("processes", "partial process identity output");
        if (!processes.success() && !processes.truncated) {
            inventory.unavailable("processes", "process identity command failed: " + safe(processes.error));
        }
        for (String line : lines(processes.output)) {
            checkCancelled(cancelled);
            String trimmed = line.trim();
            Matcher matcher = Pattern.compile("^([0-9]{1,10})\\s+(.+)$").matcher(trimmed);
            if (!matcher.matches() || !relevantProcess(matcher.group(2))) continue;
            String pid = matcher.group(1);
            inventory.inspect("/proc/" + pid);
            LocalAdbBridge.ShellResult exe = run(session, "readlink -f /proc/" + pid + "/exe");
            if (exe == null || !exe.success()) {
                inventory.unavailable("/proc/" + pid + "/exe",
                        exe == null ? "executable path unavailable" : exe.truncated
                                ? "executable path output truncated" : "executable path denied");
            }
            addProcessPath(exe, "cluster", session, cancelled, inventory, libraryIndex);
            LocalAdbBridge.ShellResult maps = run(session, "cat /proc/" + pid + "/maps");
            if (maps == null || !maps.success() || maps.truncated) {
                inventory.unavailable("/proc/" + pid + "/maps",
                        maps == null ? "maps unavailable" : maps.truncated
                                ? "path-only maps output truncated" : "maps denied");
                continue;
            }
            for (String mapLine : lines(maps.output)) {
                int slash = mapLine.indexOf('/');
                if (slash < 0) continue;
                String path = mapLine.substring(slash).trim();
                int space = path.indexOf(' ');
                if (space >= 0) path = path.substring(0, space);
                if (!isAllowedPath(path) || !path.toLowerCase(Locale.ROOT).endsWith(".so")
                        || isSensitiveName(path)) continue;
                libraryIndex.computeIfAbsent(baseName(path), ignored -> new ArrayList<>()).add(path);
                String canonical = canonicalPath(path, session, cancelled, inventory);
                if (canonical != null) addCandidate(canonical, "native", null,
                        session, cancelled, inventory);
            }
        }
    }

    private static void addProcessPath(LocalAdbBridge.ShellResult result, String category,
            LocalAdbBridge.ConfigurationExportSession session, BooleanSupplier cancelled,
            Inventory inventory, Map<String, List<String>> libraryIndex) throws IOException {
        if (result == null || result.truncated) return;
        for (String line : lines(result.output)) {
            String path = canonicalPath(line, session, cancelled, inventory);
            if (path != null && isRelevantCandidate(path)) {
                if (path.toLowerCase(Locale.ROOT).endsWith(".so")) {
                    libraryIndex.computeIfAbsent(baseName(path), ignored -> new ArrayList<>()).add(path);
                }
                addCandidate(path, category, null, session, cancelled, inventory);
            }
        }
    }

    private static void addCandidate(String path, String category, Set<String> aliases,
            LocalAdbBridge.ConfigurationExportSession session, BooleanSupplier cancelled,
            Inventory inventory) throws IOException {
        if (!isAllowedPath(path)) return;
        checkCancelled(cancelled);
        FileStat stat;
        try {
            stat = stat(path, session, cancelled);
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                throw error;
            }
            inventory.unavailable(path, error.getMessage());
            return;
        }
        Entry entry = new Entry(stat, path, category);
        if (aliases != null) entry.aliases.addAll(aliases);
        inventory.add(entry, null);
    }

    private static void discoverLocalFiles(BooleanSupplier cancelled, Inventory inventory,
            Map<String, List<String>> libraryIndex) throws IOException {
        for (String root : APPROVED_ROOTS) {
            inventory.inspect(root);
            try {
                Path rootPath = new File(root).toPath();
                if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        checkCancelled(cancelled);
                        String path = file.toString();
                        if (attrs.isRegularFile() && path.toLowerCase(Locale.ROOT).endsWith(".so")) {
                            libraryIndex.computeIfAbsent(baseName(path), ignored -> new ArrayList<>()).add(path);
                        }
                        if (attrs.isRegularFile() && isRelevantCandidate(path)) {
                            addCandidate(path, category(path), null, null, cancelled, inventory);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                        inventory.unavailable(file.toString(), "local read failed: " + safe(error.getMessage()));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                    throw error;
                }
                inventory.unavailable(root, "local inventory failed: " + safe(error.getMessage()));
            }
        }
        discoverLocalApexLibraries(cancelled, inventory, libraryIndex);
    }

    /** Index only APEX public native-library directories for dependency resolution. */
    private static void discoverLocalApexLibraries(BooleanSupplier cancelled, Inventory inventory,
            Map<String, List<String>> libraryIndex) throws IOException {
        Path apexRoot = new File("/apex").toPath();
        inventory.inspect("/apex");
        try {
            if (!Files.isDirectory(apexRoot, LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(apexRoot, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory,
                        BasicFileAttributes attrs) {
                    int depth = apexRoot.relativize(directory).getNameCount();
                    if (depth == 2) {
                        String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!("lib".equals(name) || "lib64".equals(name))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    checkCancelled(cancelled);
                    String path = file.toString();
                    if (attrs.isRegularFile() && APEX_LIBRARY_PATH.matcher(path).matches()) {
                        libraryIndex.computeIfAbsent(baseName(path), ignored -> new ArrayList<>())
                                .add(path);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                    inventory.unavailable(file.toString(),
                            "local APEX read failed: " + safe(error.getMessage()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                throw error;
            }
            inventory.unavailable("/apex", "local APEX inventory failed: " + safe(error.getMessage()));
        }
    }

    private static void expandDependencies(Context context,
            LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled, Inventory inventory, Map<String, List<String>> libraryIndex)
            throws IOException {
        if (inventory.entries.isEmpty() || libraryIndex.isEmpty()) return;
        // Device readelf is the fast path; VehicleConfigurationElf is the bounded-stream fallback
        // when readelf/llvm-readelf is absent, preserving dependency closure without whole-file RAM.
        Set<String> inspected = new LinkedHashSet<>();
        boolean readelfUnavailable = false;
        for (int index = 0; index < inventory.entries.size(); index++) {
            checkCancelled(cancelled);
            Entry entry = inventory.entries.get(index);
            if (!entry.category.startsWith("native") || !inspected.add(entry.sourcePath)) continue;
            inventory.inspect(entry.sourcePath);
            if (session == null) {
                try {
                    addDependencies(VehicleConfigurationElf.needed(new File(entry.sourcePath)), entry,
                            null, cancelled, inventory, libraryIndex);
                } catch (IOException parseFailure) {
                    inventory.unavailable(entry.sourcePath,
                            "ELF dependency parser unavailable: " + safe(parseFailure.getMessage()));
                }
                continue;
            }
            LocalAdbBridge.ShellResult result = null;
            if (!readelfUnavailable) {
                try {
                    result = run(session, "readelf -d " + quotePath(entry.sourcePath));
                } catch (RuntimeException error) {
                    if (context != null) {
                        List<String> fallback = streamedElfNeeded(context, entry, session, cancelled, inventory);
                        if (fallback != null) {
                            addDependencies(fallback, entry, session, cancelled, inventory, libraryIndex);
                            continue;
                        }
                    }
                    inventory.unavailable(entry.sourcePath,
                            "dependency metadata unavailable: " + error.getClass().getSimpleName());
                    continue;
                }
                if (result == null || result.exitCode == 127 || result.exitCode == 126) {
                    try {
                        LocalAdbBridge.ShellResult fallback = run(session,
                                "llvm-readelf -d " + quotePath(entry.sourcePath));
                        if (fallback != null && (result == null
                                || (fallback.exitCode != 127 && fallback.exitCode != 126))) result = fallback;
                    } catch (RuntimeException ignored) {
                        // Keep the original unsupported result below.
                    }
                }
                if (result == null || result.exitCode == 127 || result.exitCode == 126) {
                    readelfUnavailable = true;
                }
            }
            if (result == null || !result.success() || result.truncated) {
                // A present but denied/failed readelf is not proof that dependency metadata is
                // unavailable: retry through the bounded Java parser while the source is readable.
                if (context != null) {
                    List<String> fallback = streamedElfNeeded(context, entry, session, cancelled, inventory);
                    if (fallback != null) {
                        addDependencies(fallback, entry, session, cancelled, inventory, libraryIndex);
                        continue;
                    }
                }
                String reason = result == null ? "readelf unavailable"
                        : result.truncated ? "readelf output truncated" : "readelf unsupported or denied";
                inventory.unavailable(entry.sourcePath, "dependency metadata unavailable: " + reason);
                continue;
            }
            List<String> dependencies = new ArrayList<>();
            boolean dynamicSectionReported = false;
            for (String line : lines(result.output)) {
                if (line.toLowerCase(Locale.ROOT).contains("dynamic section")) {
                    dynamicSectionReported = true;
                }
                Matcher needed = NEEDED.matcher(line);
                if (needed.find()) dependencies.add(needed.group(1));
            }
            // Some vendor readelf builds return success with no useful text. Give the parser one
            // chance in that case; an empty parsed list is valid for a static/non-dynamic ELF.
            if (dependencies.isEmpty() && !dynamicSectionReported && context != null) {
                List<String> fallback = streamedElfNeeded(context, entry, session, cancelled, inventory);
                if (fallback != null) {
                    addDependencies(fallback, entry, session, cancelled, inventory, libraryIndex);
                    continue;
                }
            }
            addDependencies(dependencies, entry, session, cancelled, inventory, libraryIndex);
        }
    }

    private static void addDependencies(List<String> dependencies, Entry entry,
            LocalAdbBridge.ConfigurationExportSession session, BooleanSupplier cancelled,
            Inventory inventory, Map<String, List<String>> libraryIndex) throws IOException {
        for (String dependency : new LinkedHashSet<>(dependencies)) {
            checkCancelled(cancelled);
            inventory.inspect(entry.sourcePath + " -> " + dependency);
            List<String> candidates = libraryIndex.get(dependency);
            if (candidates == null || candidates.isEmpty()) {
                inventory.unavailable(entry.sourcePath, "dependency path not found: " + dependency);
                continue;
            }
            for (String path : candidates) {
                String canonical = canonicalPath(path, session, cancelled, inventory);
                if (canonical != null) addCandidate(canonical, "native-dependency", null,
                        session, cancelled, inventory);
            }
        }
    }

    private static List<String> streamedElfNeeded(Context context, Entry entry,
            LocalAdbBridge.ConfigurationExportSession session, BooleanSupplier cancelled,
            Inventory inventory) throws IOException {
        File directory = LogShareZip.writableShareDir(context);
        if (directory == null || !directory.isDirectory()) {
            inventory.unavailable(entry.sourcePath, "ELF fallback cache directory unavailable");
            return null;
        }
        if (entry.size > directory.getUsableSpace() - 16L * 1024 * 1024) {
            inventory.unavailable(entry.sourcePath, "insufficient storage for ELF dependency inspection");
            return null;
        }
        File temporary;
        try {
            temporary = File.createTempFile("BYD-HUD-vehicle-config-elf-", ".zip.source.part", directory);
        } catch (IOException error) {
            inventory.unavailable(entry.sourcePath, "ELF fallback temporary file unavailable");
            return null;
        }
        try {
            checkCancelled(cancelled);
            inventory.inspect(entry.sourcePath);
            FileStat before = stat(entry.sourcePath, session, cancelled);
            long copied;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                copied = session.readFile(entry.sourcePath, output, entry.size,
                        bytes -> {
                            if (Thread.currentThread().isInterrupted()
                                    || (cancelled != null && cancelled.getAsBoolean())) {
                                throw new IllegalStateException("export cancelled");
                            }
                        });
            }
            checkCancelled(cancelled);
            if (copied != entry.size || temporary.length() != entry.size) {
                inventory.unavailable(entry.sourcePath, "ELF fallback stream size changed");
                return null;
            }
            FileStat after = stat(entry.sourcePath, session, cancelled);
            if (before.size != after.size || !before.modifiedTime.equals(after.modifiedTime)
                    || (before.device > 0L && after.device > 0L && before.device != after.device)
                    || (before.inode > 0L && after.inode > 0L && before.inode != after.inode)) {
                inventory.unavailable(entry.sourcePath, "ELF fallback source changed during stream");
                return null;
            }
            try {
                return VehicleConfigurationElf.needed(temporary);
            } catch (IOException parseFailure) {
                inventory.unavailable(entry.sourcePath,
                        "ELF dependency parser unavailable: " + safe(parseFailure.getMessage()));
                return null;
            }
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                throw error;
            }
            inventory.unavailable(entry.sourcePath, "ELF fallback stream failed: " + safe(error.getMessage()));
            return null;
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                throw new IOException("export cancelled", error);
            }
            inventory.unavailable(entry.sourcePath, "ELF fallback stream failed: " + error.getClass().getSimpleName());
            return null;
        } finally {
            if (!temporary.delete() && temporary.exists()) {
                inventory.unavailable(entry.sourcePath, "ELF fallback temporary cleanup failed");
            }
        }
    }

    /** Re-stat a selected path immediately before and after streamed copying. */
    static FileStat stat(String path, LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled) throws IOException {
        if (!isAllowedPath(path)) throw new SecurityException("path outside diagnostic roots");
        checkCancelled(cancelled);
        File local = new File(path);
        if (local.isFile()) {
            try {
                Path localPath = local.toPath().toRealPath();
                if (!isAllowedPath(localPath.toString())) throw new IOException("symlink escaped roots");
                BasicFileAttributes attributes = Files.readAttributes(localPath,
                        BasicFileAttributes.class);
                if (!attributes.isRegularFile()) throw new IOException("not a regular file");
                long device = 0L;
                long inode = 0L;
                Matcher key = FILE_KEY.matcher(String.valueOf(attributes.fileKey()));
                if (key.matches()) {
                    try {
                        // UnixFileKey prints dev in hexadecimal (ino remains decimal).
                        device = Long.parseLong(key.group(1), 16);
                        inode = Long.parseLong(key.group(2));
                    } catch (NumberFormatException ignored) {
                        device = 0L;
                        inode = 0L;
                    }
                }
                return new FileStat(0100000L, attributes.size(), attributes.lastModifiedTime(),
                        device, inode);
            } catch (IOException error) {
                if (session == null) throw error;
            } catch (SecurityException denied) {
                if (session == null) throw new IOException("local stat denied", denied);
            }
        }
        if (session == null) throw new IOException("remote stat unavailable");
        LocalAdbBridge.ShellResult result = run(session, "stat -Lc '" + STAT_FORMAT + "' " + quotePath(path));
        if (result == null || !result.success() || result.truncated) {
            throw new IOException(result == null ? "stat unavailable"
                    : "stat failed: " + safe(result.error));
        }
        return FileStat.parse(result.output);
    }

    static boolean isAllowedPath(String path) {
        if (path == null || path.length() < 2 || path.length() > 1024
                || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0
                || path.contains("..") || path.contains("//") || path.indexOf('\n') >= 0
                || path.indexOf('\r') >= 0 || path.indexOf('\'') >= 0
                || !path.startsWith("/")) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String privateSegment : PRIVATE_SEGMENTS) if (lower.startsWith(privateSegment)) return false;
        if (isSensitiveName(path)) return false;
        if (APEX_LIBRARY_PATH.matcher(path).matches()) return true;
        boolean approved = false;
        for (String root : APPROVED_ROOTS) {
            if (path.equals(root) || path.startsWith(root + "/")) { approved = true; break; }
        }
        if (approved) return true;
        if (!lower.startsWith("/data/app/")) return false;
        String relative = path.substring("/data/app/".length());
        if (relative.isEmpty() || relative.contains("/../") || relative.endsWith("/..")) return false;
        return (isApkPath(path) || isNativeAppLibraryPath(path))
                && resolvedPackagePath(path);
    }

    static boolean isAllowedCommand(String command) {
        if (command == null || command.length() > 4096 || command.contains("..")
                || command.indexOf(';') >= 0 || command.indexOf('|') >= 0
                || command.indexOf('&') >= 0 || command.indexOf('\n') >= 0
                || command.indexOf('\r') >= 0 || command.indexOf('`') >= 0
                || command.contains("$(") || command.contains(">$")) return false;
        String safe = command.trim();
        if (safe.equals("ps -A -o pid,args")) return true;
        if (safe.equals("find /apex -type f -name '*.so'")) return true;
        if (safe.startsWith("find ")) return FIND_COMMAND.matcher(safe).matches();
        if (MAPS_COMMAND.matcher(safe).matches() || EXE_COMMAND.matcher(safe).matches()) return true;
        if (STAT_COMMAND.matcher(safe).matches() || READLINK_COMMAND.matcher(safe).matches()
                || ELF_COMMAND.matcher(safe).matches()) {
            String path = commandPath(safe);
            return isAllowedPath(path);
        }
        Matcher pm = PM_PATH_COMMAND.matcher(safe);
        return pm.matches() && relevantPackage(pm.group(1));
    }

    static String quotePath(String path) {
        if (!isAllowedPath(path)) throw new SecurityException("unsupported path");
        return "'" + path + "'";
    }

    static String archivePath(String path) {
        if (!isAllowedPath(path)) throw new SecurityException("unsupported path");
        return "files/" + path.substring(1);
    }

    static boolean isRelevantCandidate(String path) {
        if (!isAllowedPath(path) || isSensitiveName(path)) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (isApkPath(path) && !isFrameworkArtifact(lower)) {
            return false; // Other APKs enter only through resolved package inventory.
        }
        if (lower.startsWith("/cluster/")) return true;
        if (isRelevantLibraryPath(path)) return true;
        if (isFrameworkArtifact(lower)) return true;
        return lower.contains("/vintf/") || lower.contains("/etc/init/") || lower.contains("/init.")
                || lower.contains("/someip") || lower.contains("/cluster")
                || lower.contains("/bydcluster") || lower.contains("/byd_cluster")
                || lower.contains("/kanzi") || lower.contains("/fission")
                || lower.contains("/container") || lower.contains("/dios")
                || lower.contains("/instrument") || lower.contains("/hud")
                || lower.contains("/navi") || lower.contains("/adas")
                || lower.contains("/sflijie") || lower.contains("/kanzihw")
                || lower.contains("/qt")
                || lower.endsWith("/bydclustermanager")
                || lower.endsWith("/bydclusterkanzi")
                || lower.endsWith("/bydclusterlijie")
                || lower.endsWith("cluster.dios_host.rc") || lower.endsWith("startbydcluster.sh");
    }

    static boolean isRelevantLibraryPath(String path) {
        if (!isAllowedPath(path)) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".so") || isSensitiveName(path)) return false;
        for (String family : LIBRARY_FAMILIES) if (lower.contains(family)) return true;
        return lower.contains("/cluster/") || lower.contains("/someip/");
    }

    static String category(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (isFrameworkArtifact(lower)) return "framework";
        if (isApkPath(path)) return "apk";
        if (lower.endsWith(".so")) return "native";
        if (lower.contains("/framework/") || lower.endsWith("framework.jar")
                || lower.endsWith("services.jar")) return "framework";
        if (lower.startsWith("/cluster/") || lower.endsWith(".rc") || lower.endsWith(".sh")) return "cluster";
        if (lower.contains("/etc/") || lower.contains("/vintf/")) return "config";
        return "binary";
    }

    private static boolean isFrameworkArtifact(String lower) {
        if (lower == null || !lower.contains("/framework/")) return false;
        String name = baseName(lower);
        for (String family : FRAMEWORK_FAMILIES) {
            if (name.equals(family) || name.startsWith(family + ".")
                    || name.startsWith(family + "-") || name.startsWith(family + "_")) return true;
        }
        return false;
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String canonicalPath(String path, LocalAdbBridge.ConfigurationExportSession session,
            BooleanSupplier cancelled, Inventory inventory) throws IOException {
        String candidate = path == null ? "" : path.trim();
        if (!isAllowedPath(candidate)) return null;
        File local = new File(candidate);
        if (local.exists()) {
            try {
                String canonical = local.getCanonicalPath();
                if (!isAllowedPath(canonical)) {
                    inventory.unavailable(candidate, "canonical path escaped approved roots");
                    return null;
                }
                return canonical;
            } catch (IOException ignored) { return null; }
        }
        if (session == null) return candidate;
        LocalAdbBridge.ShellResult result = run(session, "readlink -f " + quotePath(candidate));
        if (result == null || !result.success() || result.truncated) {
            inventory.unavailable(candidate, result == null ? "canonical path unavailable" : "canonical path denied");
            return null;
        }
        String canonical = result.output.trim();
        if (!isAllowedPath(canonical)) {
            inventory.unavailable(candidate, "canonical path escaped approved roots");
            return null;
        }
        return canonical;
    }

    private static LocalAdbBridge.ShellResult run(LocalAdbBridge.ConfigurationExportSession session,
            String command) throws IOException {
        if (!isAllowedCommand(command)) throw new SecurityException("inventory command rejected");
        return session.runFileCommand(command);
    }

    private static String commandPath(String command) {
        Matcher stat = STAT_COMMAND.matcher(command);
        if (stat.matches()) return stat.group(2);
        Matcher readlink = READLINK_COMMAND.matcher(command);
        if (readlink.matches()) return readlink.group(2);
        Matcher elf = ELF_COMMAND.matcher(command);
        if (elf.matches()) return elf.group(2);
        int slash = command.indexOf(" /");
        if (slash < 0) return "";
        String path = command.substring(slash + 1).trim();
        if (path.startsWith("'") && path.endsWith("'")) path = path.substring(1, path.length() - 1);
        return path;
    }

    private static boolean relevantPackage(String packageName) {
        if (packageName == null || packageName.length() > 128) return false;
        if (KNOWN_PACKAGES.contains(packageName)) return true;
        if ("com.bydhud.app".equals(packageName)) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        for (String token : RELEVANT_PACKAGE_TOKENS) if (lower.contains(token)) return true;
        return false;
    }

    private static boolean relevantPackageInfo(PackageInfo info) {
        if (info == null || info.packageName == null) return false;
        if (KNOWN_PACKAGES.contains(info.packageName)) return true;
        if (!relevantPackage(info.packageName) || info.applicationInfo == null) return false;
        int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (info.applicationInfo.flags & systemFlags) != 0;
    }

    private static boolean relevantProcess(String command) {
        String lower = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (lower.contains("bydhud") || lower.contains("bydcollector")
                || lower.contains("turnsignal") || lower.contains("mate")
                || lower.contains("waze") || lower.contains("google") || lower.contains("maps")
                || lower.contains("revanced")) return false;
        Matcher packageMatcher = PROCESS_PACKAGE.matcher(lower);
        boolean packageMentioned = false;
        boolean knownPackageMentioned = false;
        while (packageMatcher.find()) {
            packageMentioned = true;
            String packageName = packageMatcher.group(1);
            if (KNOWN_PACKAGES.contains(packageName)
                    || (packageName.startsWith("com.byd.") && relevantPackage(packageName))
                    || (packageName.startsWith("com.ts.") && relevantPackage(packageName))
                    || (packageName.startsWith("com.android.car.") && relevantPackage(packageName))) {
                knownPackageMentioned = true;
            }
        }
        if (packageMentioned && !knownPackageMentioned) return false;
        return lower.contains("cluster") || lower.contains("someip") || lower.contains("kanzi")
                || lower.contains("naviauto") || lower.contains("instrument")
                || lower.contains("amapservice") || lower.contains("containerservice")
                || lower.contains("launchermap") || lower.contains("adas")
                || lower.contains("carsetting") || lower.contains("com.byd.sr")
                || lower.contains("bydcamera") || lower.contains("com.byd.avc");
    }

    private static boolean isSensitiveName(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        for (String name : SENSITIVE_NAMES) {
            if (lower.contains("/" + name + "/") || lower.endsWith("/" + name)
                    || lower.contains("/" + name + ".") || lower.contains("/" + name + "_")
                    || lower.contains("/" + name + "-")) return true;
        }
        return false;
    }

    private static boolean isApkPath(String path) {
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".apk")) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/data/app/")) return true;
        for (String root : APPROVED_ROOTS) if (lower.startsWith(root + "/")) return true;
        return false;
    }

    private static boolean isNativeAppLibraryPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.startsWith("/data/app/") && lower.contains("/lib/") && lower.endsWith(".so");
    }

    private static boolean resolvedPackagePath(String path) {
        String relative = path.substring("/data/app/".length());
        String[] segments = relative.split("/");
        if (segments.length < 2) return false;
        for (String known : KNOWN_PACKAGES) {
            if (segments[0].equals(known) || segments[0].startsWith(known + "-")) return true;
            if (segments.length > 2
                    && (segments[1].equals(known) || segments[1].startsWith(known + "-"))) return true;
        }
        // Updated OEM packages have randomized install directories. Selection still requires
        // PackageManager's system/updated-system flag or a known OEM process above.
        for (int index = 0; index < Math.min(2, segments.length - 1); index++) {
            String packageName = segments[index].split("-", 2)[0];
            if ((packageName.startsWith("com.byd.") || packageName.startsWith("com.ts.")
                    || packageName.startsWith("com.android.car.")) && relevantPackage(packageName)) return true;
        }
        return false;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new IOException("export cancelled");
        }
    }

    private static List<String> lines(String value) {
        if (value == null || value.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) if (!line.trim().isEmpty()) result.add(line.trim());
        return result;
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "no diagnostic output" : value.replaceAll("[\\r\\n]", " ");
    }
}
