package com.bydhud.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;

import com.android.apksig.ApkVerifier;
import com.android.apksig.internal.apk.AndroidBinXmlParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Normalizes monolithic APKs and device-compatible APK containers into one installable set. */
final class NavigatorApkSet {
    static final long MAX_SOURCE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_CONTAINER_ENTRIES = 1024;
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final long MAX_DEX_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_DEX_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_DEX_ENTRIES = 32;
    private static final long STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L;

    static final class Member {
        final File file;
        final String installName;
        final String splitName;
        final boolean base;
        final String packageName;
        final String versionName;
        final long versionCode;
        final String signerSha256;
        final String kind;
        final boolean splitRequired;
        final boolean requiresAbi;
        final boolean requiresDensity;

        Member(File file, String installName, ManifestInfo manifest, String signerSha256) {
            this.file = file;
            this.installName = installName;
            this.splitName = manifest.splitName;
            this.base = manifest.splitName.isEmpty();
            this.packageName = manifest.packageName;
            this.versionName = manifest.versionName;
            this.versionCode = manifest.versionCode;
            this.signerSha256 = signerSha256;
            this.kind = manifest.kind;
            this.splitRequired = manifest.splitRequired;
            this.requiresAbi = manifest.requiresAbi;
            this.requiresDensity = manifest.requiresDensity;
        }
    }

    static final class SetInfo {
        final String packageName;
        final String versionName;
        final long versionCode;
        final String signerSha256;
        final String fingerprint;
        final List<Member> members;

        SetInfo(String packageName, String versionName, long versionCode,
                String signerSha256, String fingerprint, List<Member> members) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.fingerprint = fingerprint;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
        }
    }

    private static final class ManifestInfo {
        String packageName = "";
        String splitName = "";
        String configForSplit = "";
        String versionName = "";
        long versionCode = -1L;
        boolean featureSplit;
        boolean splitRequired;
        boolean usesSplit;
        boolean requiresAbi;
        boolean requiresDensity;
        String kind = "base";
    }

    private static final class Candidate {
        final File file;
        final ManifestInfo manifest;
        final String signerSha256;

        Candidate(File file, ManifestInfo manifest, String signerSha256) {
            this.file = file;
            this.manifest = manifest;
            this.signerSha256 = signerSha256;
        }
    }

    private NavigatorApkSet() {
    }

    static SetInfo materializeSource(Context context, NavigatorPatchStore.Profile profile,
            File source, File outputDirectory) throws Exception {
        deleteTree(outputDirectory);
        if (!outputDirectory.mkdirs()) throw new IOException("Cannot create APK-set directory");
        File candidates = new File(outputDirectory, "candidates");
        if (!candidates.mkdirs()) throw new IOException("Cannot create APK-set staging");
        List<Candidate> all;
        if (isMonolithicApk(source)) {
            File candidate = new File(candidates, "candidate-0.apk");
            copyFileBounded(source, candidate, MAX_SOURCE_BYTES);
            all = Collections.singletonList(readCandidate(context, candidate));
        } else {
            all = extractCandidates(context, source, candidates);
        }
        SetInfo result = selectAndCopy(context, profile, all, outputDirectory);
        deleteTree(candidates);
        writeManifest(outputDirectory, result);
        return result;
    }

    private static boolean isMonolithicApk(File source) throws IOException {
        rejectSize(source.length());
        try (ZipFile zip = new ZipFile(source)) {
            return zip.getEntry("AndroidManifest.xml") != null;
        } catch (java.util.zip.ZipException error) {
            throw new IOException("Unsupported source format", error);
        }
    }

    static SetInfo materializeInstalled(Context context, NavigatorPatchStore.Profile profile,
            File outputDirectory) throws Exception {
        deleteTree(outputDirectory);
        if (!outputDirectory.mkdirs()) throw new IOException("Cannot create APK-set directory");
        PackageInfo before = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (before.applicationInfo == null || before.applicationInfo.sourceDir == null) {
            throw new IOException("Installed APK path is unavailable");
        }
        List<String> paths = new ArrayList<>();
        paths.add(before.applicationInfo.sourceDir);
        if (before.applicationInfo.splitSourceDirs != null) {
            paths.addAll(Arrays.asList(before.applicationInfo.splitSourceDirs));
        }
        File candidatesDirectory = new File(outputDirectory, "candidates");
        if (!candidatesDirectory.mkdirs()) {
            throw new IOException("Cannot create installed APK-set staging");
        }
        List<Candidate> candidates = new ArrayList<>();
        int index = 0;
        for (String path : paths) {
            File input = new File(path);
            File target = new File(candidatesDirectory, "candidate-" + index++ + ".apk");
            requireFreeSpace(candidatesDirectory, input.length());
            copyFileBounded(input, target, MAX_SOURCE_BYTES);
            candidates.add(readCandidate(context, target));
        }
        PackageInfo after = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (before.lastUpdateTime != after.lastUpdateTime
                || before.getLongVersionCode() != after.getLongVersionCode()
                || !samePaths(before.applicationInfo, after.applicationInfo)) {
            throw new IOException("Installed navigator changed during copy");
        }
        SetInfo result = selectAndCopy(context, profile, candidates, outputDirectory);
        deleteTree(candidatesDirectory);
        if (result.versionCode != before.getLongVersionCode()
                || !result.signerSha256.equals(
                NavigatorSigningKey.installedCertificateSha256(
                        context, profile.packageName))) {
            throw new IOException("Copied APK-set differs from installed package");
        }
        writeManifest(outputDirectory, result);
        return result;
    }

    static SetInfo inspectInstalled(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        PackageInfo before = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (before.applicationInfo == null || before.applicationInfo.sourceDir == null) {
            throw new IOException("Installed APK path is unavailable");
        }
        List<String> paths = new ArrayList<>();
        paths.add(before.applicationInfo.sourceDir);
        if (before.applicationInfo.splitSourceDirs != null) {
            paths.addAll(Arrays.asList(before.applicationInfo.splitSourceDirs));
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String path : paths) candidates.add(readCandidate(context, new File(path)));
        PackageInfo after = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (before.lastUpdateTime != after.lastUpdateTime
                || before.getLongVersionCode() != after.getLongVersionCode()
                || !samePaths(before.applicationInfo, after.applicationInfo)) {
            throw new IOException("Installed navigator changed during inspection");
        }
        SetInfo result = validateSelected(profile, candidates);
        if (result.versionCode != before.getLongVersionCode()
                || !result.signerSha256.equals(
                NavigatorSigningKey.installedCertificateSha256(
                        context, profile.packageName))) {
            throw new IOException("Installed APK-set metadata mismatch");
        }
        return result;
    }

    static SetInfo readDirectory(Context context, NavigatorPatchStore.Profile profile,
            File directory) throws Exception {
        File membersDirectory = new File(directory, "members");
        File[] files = membersDirectory.listFiles((file, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".apk"));
        if (files == null || files.length == 0) {
            throw new IOException("APK-set has no members");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (File file : files) candidates.add(readCandidate(context, file));
        return validateSelected(profile, candidates);
    }

    static List<File> memberFiles(File directory) throws IOException {
        File membersDirectory = new File(directory, "members");
        File[] files = membersDirectory.listFiles((file, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("APK-set is empty");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<File> result = new ArrayList<>(Arrays.asList(files));
        return result;
    }

    private static List<Candidate> extractCandidates(Context context, File source,
            File candidatesDirectory) throws Exception {
        rejectSize(source.length());
        List<Candidate> result = new ArrayList<>();
        long expanded = 0L;
        int entryCount = 0;
        try (ZipFile zip = new ZipFile(source)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > MAX_CONTAINER_ENTRIES) {
                    throw new IOException("Container has too many entries");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IOException("Container contains unsafe path");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".obb") || lower.endsWith(".apkm")
                        || lower.endsWith(".apks") || lower.endsWith(".xapk")) {
                    throw new IOException("Container contains unsupported nested payload");
                }
                if (entry.isDirectory() || !lower.endsWith(".apk")) continue;
                File candidate = new File(candidatesDirectory,
                        "candidate-" + result.size() + ".apk");
                long declared = entry.getSize();
                if (declared > MAX_SOURCE_BYTES) throw new IOException("APK member is too large");
                expanded += copyEntry(zip, entry, candidate, MAX_SOURCE_BYTES);
                if (expanded > MAX_EXPANDED_BYTES) {
                    throw new IOException("Expanded APK-set exceeds limit");
                }
                result.add(readCandidate(context, candidate));
            }
        }
        if (result.isEmpty()) {
            throw new IOException("Unsupported source format: container contains no APK members");
        }
        return result;
    }

    private static SetInfo selectAndCopy(Context context, NavigatorPatchStore.Profile profile,
            List<Candidate> candidates, File outputDirectory) throws Exception {
        validatePackage(profile, candidates);
        List<Candidate> selected = selectCompatible(candidates, context);
        File membersDirectory = new File(outputDirectory, "members");
        if (!membersDirectory.mkdirs()) throw new IOException("Cannot create APK-set members");
        List<Member> members = new ArrayList<>();
        for (Candidate candidate : selected) {
            String name = canonicalName(candidate.manifest);
            File target = new File(membersDirectory, name);
            requireFreeSpace(membersDirectory, candidate.file.length());
            copyFileBounded(candidate.file, target, MAX_SOURCE_BYTES);
            members.add(new Member(target, name, candidate.manifest, candidate.signerSha256));
        }
        return validateMembers(profile, members);
    }

    private static SetInfo validateSelected(NavigatorPatchStore.Profile profile,
            List<Candidate> candidates) throws Exception {
        validatePackage(profile, candidates);
        List<Member> members = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String name = canonicalName(candidate.manifest);
            members.add(new Member(candidate.file, name,
                    candidate.manifest, candidate.signerSha256));
        }
        return validateMembers(profile, members);
    }

    private static SetInfo validateMembers(NavigatorPatchStore.Profile profile,
            List<Member> members) throws Exception {
        if (members.size() == 0) throw new IOException("APK-set has no members");
        Member base = null;
        Set<String> splitNames = new HashSet<>();
        Set<String> configKinds = new HashSet<>();
        String signer = "";
        long total = 0L;
        for (Member member : members) {
            total += member.file.length();
            if (total > MAX_SOURCE_BYTES) throw new IOException("Selected APK-set exceeds 512 MiB");
            if (profile != null && !profile.packageName.equals(member.packageName)) {
                throw new IOException("APK-set package mismatch");
            }
            if (member.base) {
                if (base != null) throw new IOException("APK-set has multiple base APKs");
                base = member;
            } else if (!splitNames.add(member.splitName)) {
                throw new IOException("APK-set has duplicate split names");
            } else if (!"language".equals(member.kind) && !configKinds.add(member.kind)) {
                throw new IOException("APK-set has multiple ABI or density splits");
            }
            if (signer.isEmpty()) signer = member.signerSha256;
            else if (!signer.equals(member.signerSha256)) throw new IOException("APK-set signer mismatch");
        }
        if (base == null) throw new IOException("APK-set base APK is missing");
        if ((base.splitRequired && members.size() == 1)
                || (base.requiresAbi && !configKinds.contains("abi"))
                || (base.requiresDensity && !configKinds.contains("density"))) {
            throw new IOException("APK-set is missing required configuration splits");
        }
        for (Member member : members) {
            if (member.versionCode != base.versionCode
                    || (!member.versionName.isEmpty()
                    && !member.versionName.equals(base.versionName))) {
                throw new IOException("APK-set version mismatch");
            }
            if (!member.base && !isConfigKind(member.kind)) {
                throw new IOException("APK-set contains a feature or unknown split");
            }
        }
        return new SetInfo(base.packageName, base.versionName, base.versionCode,
                signer, fingerprint(members), members);
    }

    private static List<Candidate> selectCompatible(List<Candidate> candidates,
            Context context) throws IOException {
        List<Candidate> bases = new ArrayList<>();
        Map<String, List<Candidate>> groups = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.manifest.splitName.isEmpty()) bases.add(candidate);
            else if (isConfigKind(candidate.manifest.kind)) {
                groups.computeIfAbsent(candidate.manifest.kind,
                        ignored -> new ArrayList<>()).add(candidate);
            } else {
                throw new IOException("APK-set contains feature or unknown split");
            }
        }
        if (bases.size() != 1) throw new IOException("APK-set base topology is ambiguous");
        List<Candidate> selected = new ArrayList<>(bases);
        String abi = matchingAbi(groups.get("abi"));
        if (groups.containsKey("abi")) {
            if (abi.isEmpty()) throw new IOException("APK-set has no compatible ABI");
            selected.add(findQualifier(groups.get("abi"), abi));
        }
        if (groups.containsKey("density")) {
            selected.add(bestDensity(groups.get("density"), context));
        }
        // Language splits are mutually additive and are retained to preserve language switching.
        if (groups.containsKey("language")) selected.addAll(groups.get("language"));
        for (String group : groups.keySet()) {
            if (!"abi".equals(group) && !"density".equals(group)
                    && !"language".equals(group)) {
                throw new IOException("APK-set contains unknown configuration split");
            }
        }
        return selected;
    }

    private static String matchingAbi(List<Candidate> candidates) {
        if (candidates == null) return "";
        for (String supported : Build.SUPPORTED_ABIS) {
            String normalized = normalizeAbi(supported);
            for (Candidate candidate : candidates) {
                if (normalized.equals(normalizeAbi(qualifier(candidate.manifest.splitName)))) {
                    return qualifier(candidate.manifest.splitName);
                }
            }
        }
        return "";
    }

    private static Candidate findQualifier(List<Candidate> candidates, String qualifier)
            throws IOException {
        Candidate found = null;
        for (Candidate candidate : candidates) {
            if (qualifier.equals(qualifier(candidate.manifest.splitName))) {
                if (found != null) throw new IOException("APK-set has duplicate ABI split");
                found = candidate;
            }
        }
        if (found == null) throw new IOException("Compatible ABI split is missing");
        return found;
    }

    private static Candidate bestDensity(List<Candidate> candidates, Context context)
            throws IOException {
        int target = context.getResources().getDisplayMetrics().densityDpi;
        Candidate best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            int density = density(qualifier(candidate.manifest.splitName));
            if (density < 0) throw new IOException("Unknown density configuration");
            int distance = Math.abs(target - density);
            if (best == null || distance < bestDistance
                    || (distance == bestDistance && density > density(qualifier(best.manifest.splitName)))) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best == null) throw new IOException("Compatible density split is missing");
        return best;
    }

    private static void validatePackage(NavigatorPatchStore.Profile profile,
            List<Candidate> candidates) throws IOException {
        if (candidates.isEmpty()) throw new IOException("APK-set is empty");
        String packageName = candidates.get(0).manifest.packageName;
        Set<String> splitNames = new HashSet<>();
        Set<String> configQualifiers = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (!packageName.equals(candidate.manifest.packageName)) {
                throw new IOException("APK-set contains multiple packages");
            }
            String splitName = candidate.manifest.splitName;
            if (!splitName.isEmpty() && !splitNames.add(splitName)) {
                throw new IOException("APK-set has duplicate split names");
            }
            if (isConfigKind(candidate.manifest.kind)) {
                String qualifier = qualifier(splitName);
                String key;
                if ("abi".equals(candidate.manifest.kind)) {
                    key = "abi:" + normalizeAbi(qualifier);
                } else if ("density".equals(candidate.manifest.kind)) {
                    key = "density:" + density(qualifier);
                } else {
                    key = "language:" + qualifier.toLowerCase(Locale.ROOT);
                }
                if (!configQualifiers.add(key)) {
                    throw new IOException("APK-set has duplicate configuration qualifiers");
                }
            }
        }
        if (profile != null && !profile.packageName.equals(packageName)) {
            throw new IOException("APK package=" + packageName
                    + ", expected=" + profile.packageName);
        }
    }

    private static Candidate readCandidate(Context context, File file) throws Exception {
        rejectSize(file.length());
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null) throw new IOException("AndroidManifest could not be parsed");
        ManifestInfo manifest = readManifest(file);
        if (!info.packageName.equals(manifest.packageName)) {
            throw new IOException("APK manifest package mismatch");
        }
        manifest.versionName = info.versionName == null ? "" : info.versionName;
        manifest.versionCode = info.getLongVersionCode();
        ApkVerifier.Result verified = new ApkVerifier.Builder(file).build().verify();
        if (!verified.isVerified() || verified.getSignerCertificates().size() != 1) {
            throw new IOException("APK signature verification failed");
        }
        String signer = NavigatorSigningKey.archiveCertificateSha256(
                verified.getSignerCertificates());
        validateDexLimits(file);
        return new Candidate(file, manifest, signer);
    }

    private static String canonicalName(ManifestInfo manifest) throws IOException {
        return manifest.splitName.isEmpty()
                ? "base.apk" : "split_" + safeName(manifest.splitName) + ".apk";
    }

    private static ManifestInfo readManifest(File apk) throws IOException {
        byte[] bytes;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IOException("APK manifest is missing");
            bytes = readEntry(zip, entry, MAX_MANIFEST_BYTES);
        }
        ManifestInfo result = new ManifestInfo();
        try {
            AndroidBinXmlParser parser = new AndroidBinXmlParser(
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            int event = parser.getEventType();
            while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                    String name = parser.getName();
                    if ("uses-split".equals(name)) result.usesSplit = true;
                    if ("manifest".equals(name)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            String attr = parser.getAttributeName(i);
                            int type = parser.getAttributeValueType(i);
                            if (type == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                String value = parser.getAttributeStringValue(i);
                                if ("package".equals(attr)) result.packageName = value;
                                else if ("split".equals(attr)) result.splitName = value;
                                else if ("configForSplit".equals(attr)) result.configForSplit = value;
                                else if ("requiredSplitTypes".equals(attr)) {
                                    for (String required : value.split(",")) {
                                        if ("base__abi".equals(required)) result.requiresAbi = true;
                                        else if ("base__density".equals(required)) {
                                            result.requiresDensity = true;
                                        } else if (!required.isEmpty()) {
                                            throw new IOException(
                                                    "Unknown required split configuration");
                                        }
                                    }
                                }
                            } else if (type == AndroidBinXmlParser.VALUE_TYPE_BOOLEAN) {
                                if ("isFeatureSplit".equals(attr)) result.featureSplit =
                                        parser.getAttributeBooleanValue(i);
                                else if ("isSplitRequired".equals(attr)) result.splitRequired =
                                        parser.getAttributeBooleanValue(i);
                            }
                        }
                    }
                }
                event = parser.next();
            }
        } catch (AndroidBinXmlParser.XmlParserException error) {
            throw new IOException("Binary AndroidManifest could not be parsed", error);
        }
        if (result.packageName.isEmpty()) throw new IOException("APK package is missing");
        if (result.usesSplit || result.featureSplit) {
            throw new IOException("APK feature split topology is unsupported");
        }
        if (result.splitName.isEmpty()) result.kind = "base";
        else if (!result.configForSplit.isEmpty() && !"base".equals(result.configForSplit)) {
            throw new IOException("APK config split does not target base");
        } else if (result.splitName.startsWith("config.")) {
            result.kind = configKind(qualifier(result.splitName));
            if ("unknown".equals(result.kind)) {
                throw new IOException("Unknown APK configuration split");
            }
        } else {
            result.kind = "feature";
        }
        return result;
    }

    private static boolean isConfigKind(String kind) {
        return "abi".equals(kind) || "density".equals(kind) || "language".equals(kind);
    }

    static String configKind(String qualifier) {
        String normalizedAbi = normalizeAbi(qualifier);
        if (normalizedAbi.equals("arm64-v8a") || normalizedAbi.equals("armeabi-v7a")
                || normalizedAbi.equals("x86-64") || normalizedAbi.equals("x86")) return "abi";
        if (density(qualifier) >= 0) return "density";
        if (qualifier.matches("[A-Za-z]{2,3}(-r[A-Za-z]{2}|_[A-Za-z]{2})?")
                || qualifier.startsWith("b+")) return "language";
        return "unknown";
    }

    private static String qualifier(String splitName) {
        return splitName.startsWith("config.") ? splitName.substring("config.".length()) : splitName;
    }

    private static String normalizeAbi(String value) {
        return value.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static int density(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "ldpi": return DisplayMetrics.DENSITY_LOW;
            case "mdpi": return DisplayMetrics.DENSITY_MEDIUM;
            case "tvdpi": return DisplayMetrics.DENSITY_TV;
            case "hdpi": return DisplayMetrics.DENSITY_HIGH;
            case "xhdpi": return DisplayMetrics.DENSITY_XHIGH;
            case "xxhdpi": return DisplayMetrics.DENSITY_XXHIGH;
            case "xxxhdpi": return DisplayMetrics.DENSITY_XXXHIGH;
            default:
                if (lower.endsWith("dpi")) {
                    try { return Integer.parseInt(lower.substring(0, lower.length() - 3)); }
                    catch (NumberFormatException ignored) { return -1; }
                }
                return -1;
        }
    }

    private static String safeName(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 120
                || !value.matches("[A-Za-z0-9._+-]+")) {
            throw new IOException("Invalid APK split name");
        }
        return value;
    }

    private static String fingerprint(List<Member> members) throws Exception {
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(member -> member.installName));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Member member : sorted) {
            digest.update(member.installName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(sha256Bytes(member.file));
        }
        return hex(digest.digest());
    }

    private static byte[] sha256Bytes(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private static void writeManifest(File directory, SetInfo set) throws IOException {
        List<Member> sorted = new ArrayList<>(set.members);
        sorted.sort(Comparator.comparing(member -> member.installName));
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"package\": \"").append(escape(set.packageName)).append("\",\n");
        json.append("  \"versionName\": \"").append(escape(set.versionName)).append("\",\n");
        json.append("  \"versionCode\": ").append(set.versionCode).append(",\n");
        json.append("  \"signerSha256\": \"").append(set.signerSha256).append("\",\n");
        json.append("  \"fingerprint\": \"").append(set.fingerprint).append("\",\n");
        json.append("  \"members\": [\n");
        for (int i = 0; i < sorted.size(); i++) {
            Member member = sorted.get(i);
            json.append("    {\"name\": \"").append(escape(member.installName))
                    .append("\", \"split\": \"").append(escape(member.splitName))
                    .append("\", \"sha256\": \"").append(sha256(member.file))
                    .append("\"}").append(i + 1 == sorted.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        File manifest = new File(directory, "manifest.json");
        try (FileOutputStream output = new FileOutputStream(manifest)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static void copyFileBounded(File source, File target, long limit) throws IOException {
        if (!source.isFile()) throw new IOException("APK source is missing");
        rejectSize(source.length());
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            copyStream(input, output, limit);
            output.getFD().sync();
        }
    }

    private static long copyEntry(ZipFile zip, ZipEntry entry, File target, long limit)
            throws IOException {
        requireFreeSpace(target.getParentFile(), entry.getSize());
        try (InputStream input = zip.getInputStream(entry);
             FileOutputStream output = new FileOutputStream(target)) {
            long total = copyStream(input, output, limit);
            output.getFD().sync();
            return total;
        }
    }

    private static long copyStream(InputStream input, FileOutputStream output, long limit)
            throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new IOException("APK payload exceeds limit");
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IOException("APK manifest exceeds limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void rejectSize(long size) throws IOException {
        if (size <= 0L) throw new IOException("APK source is empty");
        if (size > MAX_SOURCE_BYTES) throw new IOException("APK exceeds 512 MiB limit");
    }

    private static void validateDexLimits(File apk) throws IOException {
        int count = 0;
        long total = 0L;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                if (++count > MAX_DEX_ENTRIES) {
                    throw new IOException("APK contains too many DEX files");
                }
                long expanded = drainEntry(zip, entry, MAX_DEX_BYTES);
                total += expanded;
                if (total > MAX_TOTAL_DEX_BYTES) {
                    throw new IOException("APK DEX payload exceeds 256 MiB limit");
                }
            }
        }
    }

    private static long drainEntry(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        long total = 0L;
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IOException("APK entry exceeds limit: " + entry.getName());
                }
            }
        }
        return total;
    }

    private static void requireFreeSpace(File directory, long declaredBytes) throws IOException {
        long expected = declaredBytes < 0L ? MAX_SOURCE_BYTES : declaredBytes;
        if (directory.getUsableSpace() < expected + STORAGE_RESERVE_BYTES) {
            throw new IOException("Insufficient storage for APK-set staging");
        }
    }

    private static boolean samePaths(android.content.pm.ApplicationInfo first,
            android.content.pm.ApplicationInfo second) {
        return first != null && second != null
                && java.util.Objects.equals(first.sourceDir, second.sourceDir)
                && Arrays.equals(first.splitSourceDirs, second.splitSourceDirs);
    }

    private static String sha256(File file) throws IOException {
        try {
            return hex(sha256Bytes(file));
        } catch (Exception error) {
            throw new IOException("Cannot fingerprint APK member", error);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
