package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

// Builds a privacy-bounded vehicle diagnostic archive without requiring ADB.
final class VehicleConfigurationZip {
    private static final String ZIP_PREFIX = "BYD-HUD-vehicle-config-";
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 24 * 1024 * 1024;
    private static final int MAX_CONFIG_PATHS = 256;
    private static final int MAX_LIBRARY_PATHS = 64;
    private static final Pattern CONFIG_KEY = Pattern.compile(
            "(?i)(?:^|[\\s\\\"'<,{;])([a-z0-9_.-]{1,128})"
                    + "(?=\\s*(?:[\\\"']\\s*)?[:=>])");
    private static final Pattern XML_SENSITIVE_CONTENT = Pattern.compile(
            "(?is)(<\\s*(password|passwd|passphrase|token|secret|api[_-]?key|"
                    + "access[_-]?key|private[_-]?key|client[_-]?secret|authorization|"
                    + "cookie|credential|credentials|username|email|vin|latitude|longitude|"
                    + "coordinates?|location|account(?:id)?|vehicleidentification)\\b[^>]*>)"
                    + ".*?(</\\s*\\2\\s*>)");
    private static final Pattern XML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern XML_ATTRIBUTE = Pattern.compile(
            "(?is)\\b([a-z_][a-z0-9_.:-]*)\\s*=\\s*([\\\"'])(.*?)\\2");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern PEM_VALUE = Pattern.compile(
            "(?s)-----BEGIN [^-]+-----.*?-----END [^-]+-----");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(?:password|passwd|passphrase|token|secret|api[_-]?key|access[_-]?key|"
                    + "private[_-]?key|client[_-]?secret|authorization|cookie|credential|"
                    + "credentials|username|email|user|vin|lat|latitude|lon|longitude|"
                    + "coordinates?|location|account(?:s|id)?|vehicleidentification)");
    private static final Pattern MAC_ADDRESS = Pattern.compile(
            "(?i)(?<![0-9a-f])((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})(?![0-9a-f])");
    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "(?<![0-9.])((?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\\.){3}"
                    + "(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2}))"
                    + "(/(?:3[0-2]|[12]?[0-9]))?(?![0-9.])");
    private static final Pattern IPV6_ADDRESS = Pattern.compile(
            "(?i)(?<![0-9a-f:])(\\[?)([0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7})"
                    + "(%[a-z0-9_.-]+)?(\\]?)"
                    + "(/(?:12[0-8]|1[01][0-9]|[1-9]?[0-9]))?"
                    + "(?=$|\\s|[,;)]|:\\d{1,5}\\b)");
    private static final Pattern IPV6_WILDCARD_ENDPOINT = Pattern.compile(
            "(?<![0-9A-Fa-f:])::(?=:\\d{1,5}\\b)");
    private static final String CONFIG_FIND =
            "find /system/etc /vendor/etc /product/etc /odm/etc -type f";
    private static final String LIBRARY_FIND =
            "find /system/lib /system/lib64 /vendor/lib /vendor/lib64 "
                    + "/product/lib /product/lib64 /odm/lib /odm/lib64 -type f";
    private static final String[] PACKAGE_NAMES = {
            "com.bydhud.app",
            "com.waze",
            "app.revanced.android.apps.maps",
            "com.google.android.apps.maps",
            "com.ts.car.someip.service",
            "com.byd.launchermap"
    };
    private static final String[] PROPERTY_NAMES = {
            "ro.build.fingerprint",
            "ro.build.display.id",
            "ro.product.brand",
            "ro.product.device",
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.product.name",
            "ro.hardware",
            "ro.board.platform",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "vendor.ro.build.system.fission_single_os",
            "ro.build.car.series",
            "debug.cluster.type"
    };

    private VehicleConfigurationZip() {
    }

    static final class Result {
        final boolean ok;
        final File file;
        final String detail;

        Result(boolean ok, File file, String detail) {
            this.ok = ok;
            this.file = file;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class ArchiveFile {
        final String path;
        final String source;
        final byte[] bytes;
        final String note;

        ArchiveFile(String path, String source, byte[] bytes, String note) {
            this.path = path;
            this.source = source;
            this.bytes = bytes;
            this.note = note == null ? "" : note;
        }
    }

    private static final class Collector {
        final Context context;
        final List<ArchiveFile> files = new ArrayList<>();
        final JSONArray unavailable = new JSONArray();
        final JSONArray excluded = new JSONArray();
        final NetworkMasker networkMasker = new NetworkMasker();
        int totalBytes;
        boolean adbAuthorized;

        Collector(Context context) {
            this.context = context.getApplicationContext();
        }

        void collect() {
            collectBaseline("app/device.json", "android", this::deviceJson);
            collectBaseline("app/packages.json", "package-manager", this::packagesJson);
            collectBaseline("app/runtime.json", "application", this::runtimeJson);
            collectBaseline("app/byd-api.json", "reflection", this::bydApiJson);
            collectBaseline("app/someip.json", "application", this::someIpJson);
            collectAdb();
        }

        private void collectBaseline(
                String path, String source, Callable<JSONObject> collector) {
            try {
                addJson(path, source, collector.call());
            } catch (Exception | LinkageError e) {
                unavailable(path, e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            }
        }

        private void collectAdb() {
            LocalAdbBridge.ShellResult identity = adb("id");
            if (identity == null || !identity.success()) {
                unavailable("adb", identity == null
                        ? "authorized runtime unavailable" : identity.shortDetail());
                return;
            }
            adbAuthorized = true;
            addText("adb/identity.txt", "adb:id", identity.output, "");

            JSONObject properties = new JSONObject();
            for (String property : PROPERTY_NAMES) {
                LocalAdbBridge.ShellResult result = adb("getprop " + property);
                try {
                    if (result != null && result.success()) {
                        properties.put(property, result.output.trim());
                    } else {
                        properties.put(property, JSONObject.NULL);
                        unavailable("property:" + property,
                                result == null ? "command failed" : result.shortDetail());
                    }
                } catch (Exception e) {
                    unavailable("property:" + property, e.getClass().getSimpleName());
                }
            }
            addJson("adb/properties.json", "adb:getprop", properties);

            Map<String, String> commands = new LinkedHashMap<>();
            commands.put("uname.txt", "uname -a");
            commands.put("services.txt", "service list");
            commands.put("processes.txt", "ps -A");
            commands.put("someip-package.txt", "dumpsys package com.ts.car.someip.service");
            commands.put("someip-services.txt",
                    "dumpsys activity services com.ts.car.someip.service");
            commands.put("display.txt", "dumpsys display");
            commands.put("network/ip-link.txt", "ip -details link");
            commands.put("network/ip-addresses.txt", "ip addr");
            commands.put("network/ip-routes.txt", "ip route show table all");
            commands.put("network/ip-rules.txt", "ip rule");
            commands.put("network/ip-neighbors.txt", "ip neigh");
            commands.put("network/sockets.txt", "ss -a -n -p");
            for (Map.Entry<String, String> command : commands.entrySet()) {
                collectCommand("adb/" + command.getKey(), command.getValue());
            }
            collectRemoteConfigs();
            collectRemoteLibraries();
            collectPackageApkMetadata();
            collectFidCatalog();
        }

        private void collectFidCatalog() {
            FidCatalog.Result result = FidCatalog.collect();
            try {
                addText("adb/fid-catalog.txt", "reflection", FidCatalog.text(result), "");
                addText("adb/fid-catalog.json", "reflection", FidCatalog.json(result), "");
                if (!result.available()) {
                    unavailable("fid-catalog", "BYD FID classes unavailable");
                }
            } catch (Exception e) {
                unavailable("fid-catalog", e.getClass().getSimpleName()
                        + ": " + safe(e.getMessage()));
            }
        }

        private void collectCommand(String entry, String command) {
            LocalAdbBridge.ShellResult result = adb(command);
            if (result == null || !result.success()) {
                unavailable(entry, result == null ? "command failed" : result.shortDetail());
                return;
            }
            addText(entry, "adb:" + command, result.output, "");
        }

        private void collectRemoteConfigs() {
            LocalAdbBridge.ShellResult find = adb(CONFIG_FIND);
            if (find == null) {
                unavailable("adb/config", "find failed");
                return;
            }
            if (!find.success()) unavailable("adb/config", find.shortDetail());
            List<String> paths = filteredPaths(find.output, true, MAX_CONFIG_PATHS);
            for (String path : paths) {
                long size = remoteSize(path);
                if (size < 0L) continue;
                if (size > MAX_CONFIG_BYTES || totalBytes + size > MAX_TOTAL_BYTES) {
                    excludeRemote(path, size, "configuration size limit");
                    continue;
                }
                LocalAdbBridge.ShellResult read = adb("cat " + path);
                if (read == null || !read.success()) {
                    unavailable(path, read == null ? "read failed" : read.shortDetail());
                    continue;
                }
                boolean redacted = containsSensitiveConfigContent(read.output);
                String body = redacted ? redactConfigContent(read.output) : read.output;
                addText("adb/config" + path, "adb:" + path, body,
                        "remoteBytes=" + size
                                + (redacted ? "; sensitiveValuesRedacted=true" : ""));
            }
        }

        private void collectRemoteLibraries() {
            LocalAdbBridge.ShellResult find = adb(LIBRARY_FIND);
            if (find == null) {
                unavailable("adb/native-libraries", "find failed");
                return;
            }
            if (!find.success()) unavailable("adb/native-libraries", find.shortDetail());
            for (String path : filteredPaths(find.output, false, MAX_LIBRARY_PATHS)) {
                long size = remoteSize(path);
                if (size >= 0L) excludeRemote(path, size, "native library not included");
            }
        }

        private void collectPackageApkMetadata() {
            for (String packageName : PACKAGE_NAMES) {
                LocalAdbBridge.ShellResult result = adb("pm path " + packageName);
                if (result == null || !result.success()) {
                    unavailable("package-apk:" + packageName,
                            result == null ? "pm path failed" : result.shortDetail());
                    continue;
                }
                for (String line : result.output.split("\\r?\\n")) {
                    String path = line.trim();
                    if (path.startsWith("package:")) path = path.substring(8);
                    if (!isSafeApkMetadataPath(path)) continue;
                    long size = remoteSize(path);
                    if (size >= 0L) excludeRemote(path, size, "APK not included");
                }
            }
        }

        private void excludeRemote(String path, long size, String reason) {
            JSONObject item = new JSONObject();
            try {
                item.put("path", path);
                item.put("size", size);
                item.put("sha256", remoteSha256(path));
                item.put("reason", reason);
                excluded.put(item);
            } catch (Exception e) {
                unavailable(path, "metadata failed: " + e.getClass().getSimpleName());
            }
        }

        private long remoteSize(String path) {
            LocalAdbBridge.ShellResult result = adb("stat -c %s " + path);
            if (result == null || !result.success()) {
                unavailable(path, result == null ? "stat failed" : result.shortDetail());
                return -1L;
            }
            try {
                return Long.parseLong(result.output.trim());
            } catch (NumberFormatException e) {
                unavailable(path, "invalid size");
                return -1L;
            }
        }

        private String remoteSha256(String path) {
            LocalAdbBridge.ShellResult result = adb("sha256sum " + path);
            if (result == null || !result.success()) return "";
            String output = result.output.trim();
            int space = output.indexOf(' ');
            String hash = space < 0 ? output : output.substring(0, space);
            return hash.matches("[A-Fa-f0-9]{64}") ? hash.toUpperCase(Locale.ROOT) : "";
        }

        private LocalAdbBridge.ShellResult adb(String command) {
            try {
                return LocalAdbBridge.runRuntimeShellCommand(context, command);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        private void addJson(String path, String source, JSONObject value) {
            addText(path, source, value.toString() + "\n", "");
        }

        private void addText(String path, String source, String value, String note) {
            String original = value == null ? "" : value;
            String masked = networkMasker.mask(original);
            if (masked == null) {
                unavailable(path, "network address masking failed");
                return;
            }
            if (!masked.equals(original)) {
                note = appendNote(note, "networkAddressesMasked=true");
            }
            byte[] bytes = masked.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_ENTRY_BYTES) {
                bytes = Arrays.copyOf(bytes, MAX_ENTRY_BYTES);
                note = appendNote(note, "truncatedAt=" + MAX_ENTRY_BYTES);
            }
            if (totalBytes + bytes.length > MAX_TOTAL_BYTES) {
                unavailable(path, "archive size limit");
                return;
            }
            files.add(new ArchiveFile(path, source, bytes, note));
            totalBytes += bytes.length;
        }

        private void unavailable(String path, String reason) {
            JSONObject item = new JSONObject();
            try {
                item.put("path", path == null ? "" : path);
                item.put("reason", reason == null ? "" : reason);
                unavailable.put(item);
            } catch (Exception ignored) {
            }
        }

        private JSONObject manifest() throws Exception {
            JSONObject manifest = new JSONObject();
            manifest.put("schemaVersion", 1);
            manifest.put("createdAtMs", System.currentTimeMillis());
            manifest.put("adbAuthorized", adbAuthorized);
            JSONObject policy = new JSONObject();
            policy.put("networkAddressesMasked", true);
            policy.put("navigationLogsIncluded", false);
            policy.put("apksIncluded", false);
            policy.put("nativeLibrariesIncluded", false);
            policy.put("configurationValuesRedacted", true);
            policy.put("excludedSensitiveData",
                    new JSONArray(Arrays.asList("VIN", "coordinates", "accounts")));
            manifest.put("policy", policy);

            JSONArray entries = new JSONArray();
            for (ArchiveFile file : files) {
                JSONObject item = new JSONObject();
                item.put("path", file.path);
                item.put("source", file.source);
                item.put("size", file.bytes.length);
                item.put("sha256", sha256(file.bytes));
                if (!file.note.isEmpty()) item.put("note", file.note);
                entries.put(item);
            }
            manifest.put("files", entries);
            manifest.put("unavailable", unavailable);
            manifest.put("excluded", excluded);
            return manifest;
        }

        private JSONObject deviceJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("manufacturer", Build.MANUFACTURER);
            value.put("brand", Build.BRAND);
            value.put("model", Build.MODEL);
            value.put("device", Build.DEVICE);
            value.put("product", Build.PRODUCT);
            value.put("hardware", Build.HARDWARE);
            value.put("board", Build.BOARD);
            value.put("display", Build.DISPLAY);
            value.put("fingerprint", Build.FINGERPRINT);
            value.put("sdk", Build.VERSION.SDK_INT);
            value.put("release", Build.VERSION.RELEASE);
            return value;
        }

        private JSONObject packagesJson() throws Exception {
            JSONObject value = new JSONObject();
            for (String packageName : PACKAGE_NAMES) {
                value.put(packageName, packageJson(packageName));
            }
            return value;
        }

        private JSONObject packageJson(String packageName) throws Exception {
            JSONObject value = new JSONObject();
            try {
                int flags = Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES;
                PackageInfo info = context.getPackageManager().getPackageInfo(packageName, flags);
                value.put("installed", true);
                value.put("versionName", info.versionName == null ? "" : info.versionName);
                value.put("versionCode", Build.VERSION.SDK_INT >= 28
                        ? info.getLongVersionCode() : info.versionCode);
                value.put("lastUpdateTime", info.lastUpdateTime);
                value.put("sourceDir", info.applicationInfo == null
                        ? "" : info.applicationInfo.sourceDir);
                JSONArray signers = new JSONArray();
                for (Signature signature : signatures(info)) {
                    signers.put(sha256(signature.toByteArray()));
                }
                value.put("signerSha256", signers);
            } catch (PackageManager.NameNotFoundException e) {
                value.put("installed", false);
            }
            return value;
        }

        private JSONObject runtimeJson() throws Exception {
            NavRuntimePermissionStatus permissions = NavRuntimePermissionStatus.check(context);
            JSONObject value = new JSONObject();
            value.put("hudPackage", NavCapturePrefs.getHudPackage(context));
            value.put("logOnlyPackages",
                    new JSONArray(NavCapturePrefs.getLogOnlyPackages(context)));
            value.put("observedPackages",
                    new JSONArray(NavCapturePrefs.getObservedPackages(context)));
            value.put("permissionSummary", permissions.summary());
            value.put("permissionRows", permissions.rows());
            value.put("captureReady", permissions.readyForCapture());
            value.put("runtime", HudRuntimeState.summary(context, SystemClock.elapsedRealtime()));
            value.put("someIpBound", HudOutputCoordinator.get(context).isBound());
            value.put("hudDeliveryRunning", HudDeliveryStatus.isRunning());
            value.put("hudTransportFailed", HudDeliveryStatus.hasTransportFailure());
            value.put("adbKeyFingerprint", LocalAdbBridge.adbKeyFingerprint(context));
            return value;
        }

        private JSONObject bydApiJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("BYDAutoManager", reflectedClass("android.hardware.BYDAutoManager"));
            value.put("protocol30011Service", serviceJson(
                    "com.byd.launchermap",
                    "com.autosdk.protocol.service.ProtocolService"));
            value.put("protocol30011Operations", "3=partial,4=fullscreen");
            return value;
        }

        private JSONObject someIpJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("service", serviceJson(
                    "com.ts.car.someip.service",
                    "com.ts.car.someip.service.manager.SomeIpServerService"));
            value.put("serviceId", "0x000B010A00010000");
            value.put("roadTopic", "0x4010A00018001");
            value.put("bound", HudOutputCoordinator.get(context).isBound());
            return value;
        }

        private JSONObject reflectedClass(String name) throws Exception {
            JSONObject value = new JSONObject();
            try {
                Class<?> type = Class.forName(name);
                value.put("available", true);
                JSONArray methods = new JSONArray();
                for (java.lang.reflect.Method method : type.getMethods()) {
                    if ("getInt".equals(method.getName()) || "setInt".equals(method.getName())) {
                        methods.put(method.toGenericString());
                    }
                }
                value.put("candidateMethods", methods);
            } catch (ClassNotFoundException | LinkageError e) {
                value.put("available", false);
                value.put("error", e.getClass().getSimpleName());
            }
            return value;
        }

        private JSONObject serviceJson(String packageName, String className) throws Exception {
            JSONObject value = new JSONObject();
            ComponentName component = new ComponentName(packageName, className);
            try {
                context.getPackageManager().getServiceInfo(component, 0);
                value.put("present", true);
            } catch (PackageManager.NameNotFoundException e) {
                value.put("present", false);
            }
            Intent intent = new Intent().setComponent(component);
            value.put("resolvable", context.getPackageManager().resolveService(intent, 0) != null);
            value.put("component", component.flattenToString());
            return value;
        }
    }

    static synchronized Result create(Context context) {
        if (context == null) return failure("missing context");
        File shareDir = LogShareZip.writableShareDir(context.getApplicationContext());
        if (shareDir == null) return failure("share cache unavailable");
        String fileName = ZIP_PREFIX
                + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date())
                + ".zip";
        File output = new File(shareDir, fileName);
        File part = new File(shareDir, fileName + ".part");
        try {
            Collector collector = new Collector(context);
            collector.collect();
            Collections.sort(collector.files, (left, right) -> left.path.compareTo(right.path));
            writeZip(part, collector.files, collector.manifest());
            if (!part.renameTo(output)) throw new IOException("final rename failed");
            verifyZip(output, collector.files.size() + 1);
            return new Result(true, output,
                    "files=" + (collector.files.size() + 1)
                            + " bytes=" + output.length()
                            + " adb=" + collector.adbAuthorized);
        } catch (Exception e) {
            LogShareZip.deleteArtifact(part);
            LogShareZip.deleteArtifact(output);
            return failure(e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
        }
    }

    private static void writeZip(File part, List<ArchiveFile> files, JSONObject manifest)
            throws Exception {
        try (FileOutputStream fileOut = new FileOutputStream(part, false);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            for (ArchiveFile file : files) writeEntry(zip, file.path, file.bytes);
            writeEntry(zip, "manifest.json",
                    (manifest.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            zip.finish();
            fileOut.getFD().sync();
        }
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void verifyZip(File file, int expectedEntries) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("manifest.json") == null || zip.size() != expectedEntries) {
                throw new IOException("ZIP verification failed");
            }
        }
    }

    static List<String> filteredPaths(String output, boolean configs, int limit) {
        Set<String> paths = new TreeSet<>();
        if (output == null || limit <= 0) return new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            String path = line.trim();
            boolean accepted = configs ? isRelevantConfigPath(path) : isRelevantLibraryPath(path);
            if (accepted) paths.add(path);
            if (paths.size() >= limit) break;
        }
        return new ArrayList<>(paths);
    }

    static boolean isRelevantConfigPath(String path) {
        if (!isSafeSystemPath(path) || !path.contains("/etc/")) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".log") || lower.contains("/log/")
                || lower.contains("history") || lower.contains("account")
                || lower.contains("/vin") || lower.contains("location")
                || lower.contains("gps")) {
            return false;
        }
        boolean relevant = lower.contains("someip") || lower.contains("cluster")
                || lower.contains("hud") || lower.contains("navi")
                || lower.contains("instrument");
        boolean text = lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".conf") || lower.endsWith(".cfg")
                || lower.endsWith(".ini") || lower.endsWith(".properties")
                || lower.endsWith(".txt") || lower.endsWith(".pbtxt")
                || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".rc") || lower.endsWith(".sh");
        return relevant && text;
    }

    static boolean isRelevantLibraryPath(String path) {
        if (!isSafeSystemPath(path)) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".so")
                && (lower.contains("someip") || lower.contains("cluster")
                || lower.contains("hud") || lower.contains("navi")
                || lower.contains("byddatasource"));
    }

    static boolean containsSensitiveConfigContent(String value) {
        if (value == null || value.isEmpty()) return false;
        java.util.regex.Matcher matcher = CONFIG_KEY.matcher(value);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            if (isSensitiveConfigKey(key)) {
                return true;
            }
        }
        return XML_SENSITIVE_CONTENT.matcher(value).find()
                || containsSensitiveXmlAttributeValue(value)
                || AUTHORIZATION_VALUE.matcher(value).find()
                || PEM_VALUE.matcher(value).find();
    }

    static String redactConfigContent(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String redacted = redactXmlAttributeValues(redactXmlValues(value));
        java.util.regex.Matcher matcher = CONFIG_KEY.matcher(redacted);
        List<RedactionRange> ranges = new ArrayList<>();
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!isSensitiveConfigKey(key)) {
                continue;
            }
            int start = matcher.end();
            while (start < redacted.length()
                    && Character.isWhitespace(redacted.charAt(start))) {
                start++;
            }
            if (start < redacted.length()
                    && (redacted.charAt(start) == '\"' || redacted.charAt(start) == '\'')) {
                start++;
                while (start < redacted.length()
                        && Character.isWhitespace(redacted.charAt(start))) {
                    start++;
                }
            }
            if (start < redacted.length()
                    && (redacted.charAt(start) == ':' || redacted.charAt(start) == '=')) {
                start++;
            }
            while (start < redacted.length()
                    && Character.isWhitespace(redacted.charAt(start))) {
                start++;
            }
            int end = configValueEnd(redacted, start);
            if (end > start) {
                ranges.add(new RedactionRange(start, end, redactionFor(
                        redacted.substring(start, end))));
            }
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            RedactionRange range = ranges.get(i);
            redacted = redacted.substring(0, range.start)
                    + range.replacement + redacted.substring(range.end);
        }
        redacted = AUTHORIZATION_VALUE.matcher(redacted)
                .replaceAll("$1 [REDACTED]");
        return PEM_VALUE.matcher(redacted).replaceAll("[REDACTED PEM]");
    }

    private static String redactXmlValues(String value) {
        java.util.regex.Matcher matcher = XML_SENSITIVE_CONTENT.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + "[REDACTED]" + matcher.group(3);
            matcher.appendReplacement(buffer,
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean containsSensitiveXmlAttributeValue(String value) {
        java.util.regex.Matcher tags = XML_TAG.matcher(value);
        while (tags.find()) {
            java.util.regex.Matcher attributes = XML_ATTRIBUTE.matcher(tags.group());
            while (attributes.find()) {
                String name = attributes.group(1).toLowerCase(Locale.ROOT);
                if ((name.equals("name") || name.equals("key"))
                        && isSensitiveConfigKey(attributes.group(3))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String redactXmlAttributeValues(String value) {
        java.util.regex.Matcher tags = XML_TAG.matcher(value);
        StringBuffer output = new StringBuffer();
        while (tags.find()) {
            String tag = tags.group();
            java.util.regex.Matcher attributes = XML_ATTRIBUTE.matcher(tag);
            boolean sensitive = false;
            while (attributes.find()) {
                String name = attributes.group(1).toLowerCase(Locale.ROOT);
                if ((name.equals("name") || name.equals("key"))
                        && isSensitiveConfigKey(attributes.group(3))) {
                    sensitive = true;
                    break;
                }
            }
            if (!sensitive) {
                continue;
            }
            attributes.reset();
            StringBuffer redactedTag = new StringBuffer();
            while (attributes.find()) {
                String name = attributes.group(1).toLowerCase(Locale.ROOT);
                if (!name.equals("value") && !name.equals("val")
                        && !name.equals("content") && !name.equals("data")) {
                    continue;
                }
                String replacement = attributes.group(1) + "="
                        + attributes.group(2) + "[REDACTED]" + attributes.group(2);
                attributes.appendReplacement(redactedTag,
                        java.util.regex.Matcher.quoteReplacement(replacement));
            }
            attributes.appendTail(redactedTag);
            tags.appendReplacement(output,
                    java.util.regex.Matcher.quoteReplacement(redactedTag.toString()));
        }
        tags.appendTail(output);
        return output.toString();
    }

    private static boolean isSensitiveConfigKey(String key) {
        String safe = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEY.matcher(safe.replace("_", "").replace("-", ""))
                .matches()) {
            return true;
        }
        for (String segment : safe.split("[._-]+")) {
            if (SENSITIVE_KEY.matcher(segment).matches()) {
                return true;
            }
        }
        return false;
    }

    private static int configValueEnd(String value, int start) {
        if (start >= value.length()) {
            return start;
        }
        char first = value.charAt(start);
        if (first == '\"' || first == '\'') {
            boolean escaped = false;
            for (int i = start + 1; i < value.length(); i++) {
                char current = value.charAt(i);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == first) {
                    return i + 1;
                }
            }
            return value.length();
        }
        if (first == '{' || first == '[') {
            char close = first == '{' ? '}' : ']';
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            for (int i = start; i < value.length(); i++) {
                char current = value.charAt(i);
                if (quote != 0) {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (current == '\"' || current == '\'') {
                    quote = current;
                } else if (current == first) {
                    depth++;
                } else if (current == close && --depth == 0) {
                    return i + 1;
                }
            }
            return value.length();
        }
        int end = start;
        while (end < value.length()) {
            char current = value.charAt(end);
            if (current == '\r' || current == '\n' || current == ';'
                    || current == ',' || current == '}') {
                break;
            }
            end++;
        }
        return end;
    }

    private static String redactionFor(String value) {
        if (value.length() >= 2
                && ((value.charAt(0) == '\"' && value.charAt(value.length() - 1) == '\"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            return value.charAt(0) + "[REDACTED]" + value.charAt(value.length() - 1);
        }
        return "[REDACTED]";
    }

    private static final class RedactionRange {
        final int start;
        final int end;
        final String replacement;

        RedactionRange(int start, int end, String replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }
    }

    static List<String> maskNetworkAddressesForTest(List<String> values) {
        NetworkMasker masker = new NetworkMasker();
        List<String> masked = new ArrayList<>();
        if (values == null) return masked;
        for (String value : values) masked.add(masker.mask(value == null ? "" : value));
        return masked;
    }

    private static final class NetworkMasker {
        private final Map<String, String> ipAliases = new LinkedHashMap<>();
        private final Map<String, String> macAliases = new LinkedHashMap<>();

        String mask(String value) {
            String masked = replaceMac(value == null ? "" : value);
            masked = replaceIpv4(masked);
            masked = replaceIpv6WildcardEndpoint(masked);
            masked = replaceIpv6(masked);
            return containsRawAddress(masked) ? null : masked;
        }

        private String replaceMac(String value) {
            java.util.regex.Matcher matcher = MAC_ADDRESS.matcher(value);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                String key = matcher.group(1).toLowerCase(Locale.ROOT).replace('-', ':');
                matcher.appendReplacement(output,
                        java.util.regex.Matcher.quoteReplacement(alias(macAliases, key, "MAC")));
            }
            matcher.appendTail(output);
            return output.toString();
        }

        private String replaceIpv4(String value) {
            java.util.regex.Matcher matcher = IPV4_ADDRESS.matcher(value);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                String replacement = alias(ipAliases, matcher.group(1), "IP")
                        + safeGroup(matcher, 2);
                matcher.appendReplacement(output,
                        java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(output);
            return output.toString();
        }

        private String replaceIpv6WildcardEndpoint(String value) {
            java.util.regex.Matcher matcher = IPV6_WILDCARD_ENDPOINT.matcher(value);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(
                        alias(ipAliases, "::", "IP")));
            }
            matcher.appendTail(output);
            return output.toString();
        }

        private String replaceIpv6(String value) {
            java.util.regex.Matcher matcher = IPV6_ADDRESS.matcher(value);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                String open = safeGroup(matcher, 1);
                String address = safeGroup(matcher, 2);
                String zone = safeGroup(matcher, 3);
                String close = safeGroup(matcher, 4);
                if (!validBrackets(open, close) || !isIpv6(address)) {
                    continue;
                }
                String replacement = open + alias(ipAliases,
                        address.toLowerCase(Locale.ROOT), "IP")
                        + zone + close + safeGroup(matcher, 5);
                matcher.appendReplacement(output,
                        java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(output);
            return output.toString();
        }

        private boolean containsRawAddress(String value) {
            if (MAC_ADDRESS.matcher(value).find() || IPV4_ADDRESS.matcher(value).find()
                    || IPV6_WILDCARD_ENDPOINT.matcher(value).find()) {
                return true;
            }
            java.util.regex.Matcher matcher = IPV6_ADDRESS.matcher(value);
            while (matcher.find()) {
                if (validBrackets(safeGroup(matcher, 1), safeGroup(matcher, 4))
                        && isIpv6(safeGroup(matcher, 2))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean validBrackets(String open, String close) {
            return (open.isEmpty() && close.isEmpty())
                    || (open.equals("[") && close.equals("]"));
        }

        private static boolean isIpv6(String address) {
            try {
                InetAddress parsed = InetAddress.getByName(address);
                return parsed instanceof Inet6Address;
            } catch (Exception ignored) {
                return false;
            }
        }

        private static String alias(Map<String, String> aliases, String key, String kind) {
            String normalized = key.toLowerCase(Locale.ROOT);
            String existing = aliases.get(normalized);
            if (existing != null) return existing;
            String created = "<" + kind + "_" + (aliases.size() + 1) + ">";
            aliases.put(normalized, created);
            return created;
        }

        private static String safeGroup(java.util.regex.Matcher matcher, int group) {
            String value = matcher.group(group);
            return value == null ? "" : value;
        }
    }

    static boolean isSafeSystemPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512
                || path.contains("..") || !path.matches("[A-Za-z0-9_./+@:-]+")) {
            return false;
        }
        return path.startsWith("/system/") || path.startsWith("/vendor/")
                || path.startsWith("/product/") || path.startsWith("/odm/");
    }

    static boolean isSafeApkMetadataPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512
                || path.contains("..") || !path.matches("[A-Za-z0-9_./+@=:-]+")) {
            return false;
        }
        return path.endsWith(".apk") && (path.startsWith("/data/app/")
                || isSafeSystemPath(path));
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            Signature[] values = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            return values == null ? new Signature[0] : values;
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(Locale.US, "%02X", value));
        return result.toString();
    }

    private static String appendNote(String current, String next) {
        return current == null || current.isEmpty() ? next : current + "; " + next;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Result failure(String detail) {
        return new Result(false, null, detail == null ? "share failed" : detail);
    }
}
