package com.bydhud.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.internal.apk.AndroidBinXmlParser;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class NavigatorPatchPipeline {
    private static final long MAX_SOURCE_APK_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_DEX_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_DEX_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_DEX_ENTRIES = 32;
    private static final String GMAPS_PATCHABLE = "PATCHABLE_STOCK";
    private static final String GMAPS_DIRECT = "MESSENGER_BRIDGE_POC";
    private static final String GMAPS_AUDIO = "NAVIGATION_AUDIO";

    static final class ScanResult {
        final NavigatorPatchStore.Profile profile;
        final String sha256;
        final String versionName;
        final long versionCode;
        final String signerSha256;
        final String directState;
        final String optionalState;
        final String reason;

        ScanResult(NavigatorPatchStore.Profile profile, String sha256,
                String versionName, long versionCode, String signerSha256,
                String directState, String optionalState, String reason) {
            this.profile = profile;
            this.sha256 = sha256;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.directState = directState;
            this.optionalState = optionalState;
            this.reason = reason;
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("profile", profile.id);
                value.put("sha256", sha256);
                value.put("versionName", versionName);
                value.put("versionCode", versionCode);
                value.put("signerSha256", signerSha256);
                value.put("direct", directState);
                value.put("optional", optionalState);
                value.put("reason", reason);
            } catch (Exception ignored) {
            }
            return value;
        }

        static ScanResult fromJson(JSONObject value) throws Exception {
            NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(
                    value.getString("profile"));
            if (profile == null) throw new IOException("unknown patch profile");
            return new ScanResult(profile, value.getString("sha256"),
                    value.optString("versionName"), value.optLong("versionCode", -1L),
                    value.optString("signerSha256"), value.optString("direct"),
                    value.optString("optional"), value.optString("reason"));
        }
    }

    static final class PreparedPatch {
        final NavigatorPatchStore.Profile profile;
        final ScanResult input;
        final ScanResult output;
        final File directory;
        final boolean destructive;
        final boolean optionalApplied;
        final long installedUpdateTime;
        final long installedVersionCode;
        final String installedSignerSha256;

        PreparedPatch(NavigatorPatchStore.Profile profile, ScanResult input, ScanResult output,
                File directory, boolean destructive, boolean optionalApplied,
                long installedUpdateTime, long installedVersionCode,
                String installedSignerSha256) {
            this.profile = profile;
            this.input = input;
            this.output = output;
            this.directory = directory;
            this.destructive = destructive;
            this.optionalApplied = optionalApplied;
            this.installedUpdateTime = installedUpdateTime;
            this.installedVersionCode = installedVersionCode;
            this.installedSignerSha256 = installedSignerSha256;
        }
    }

    private NavigatorPatchPipeline() {
    }

    static NavigatorPatchStore.Profile inspectSelectedPackage(
            Context context, Uri uri, String displayName) throws Exception {
        NavigatorPatchStore.claim(
                context, null, NavigatorPatchStore.COPYING, displayName);
        File input = temporary(context, "selected-", ".apk");
        try {
            rejectContainerName(displayName);
            copyUri(context, uri, input);
            PackageInfo info = archiveInfo(context, input);
            NavigatorPatchStore.Profile profile =
                    NavigatorPatchStore.Profile.fromPackage(info.packageName);
            if (profile == null) throw new IOException("Unsupported package: " + info.packageName);
            rejectSplitArchive(input, info);
            verifySignature(input);
            context.getContentResolver().takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            NavigatorPatchStore.selectExternal(
                    context, profile, uri.toString(), displayName);
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.IDLE, "");
            return profile;
        } catch (Exception error) {
            NavigatorPatchStore.transition(
                    context, null, NavigatorPatchStore.FAILED, error.getMessage());
            throw error;
        } finally {
            input.delete();
        }
    }

    static ScanResult scan(Context context, NavigatorPatchStore.Profile profile,
            boolean ignoreCache) throws Exception {
        NavigatorPatchStore.claim(
                context, profile, NavigatorPatchStore.COPYING, "Copying APK");
        File input = temporary(context, profile.id + "-scan-", ".apk");
        try {
            copyCurrentSource(context, profile, input);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.VERIFYING, "Verifying APK");
            ScanResult preflight = verifyAndInspectMetadata(context, profile, input);
            if (!ignoreCache) {
                ScanResult cached = NavigatorPatchStore.cached(
                        context, profile, preflight.sha256);
                if (cached != null
                        && cached.versionCode == preflight.versionCode
                        && cached.signerSha256.equals(preflight.signerSha256)) {
                    NavigatorPatchStore.saveScan(context, cached);
                    NavigatorPatchStore.transition(
                            context, profile, NavigatorPatchStore.IDLE, "");
                    return cached;
                }
            }
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.SCANNING, "Scanning DEX");
            ScanResult result = inspectComponents(context, input, preflight);
            NavigatorPatchStore.saveScan(context, result);
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.IDLE, "");
            return result;
        } catch (Exception error) {
            saveFailure(context, profile, error);
            throw error;
        } finally {
            input.delete();
        }
    }

    static PreparedPatch prepare(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        NavigatorPatchStore.claim(
                context, profile, NavigatorPatchStore.COPYING, "Copying APK");
        File transaction = null;
        try {
            PackageInfo initialInstalled = installedInfo(context, profile.packageName);
            long initialUpdateTime = initialInstalled == null
                    ? -1L : initialInstalled.lastUpdateTime;
            long initialVersionCode = initialInstalled == null
                    ? -1L : initialInstalled.getLongVersionCode();
            String initialSigner = initialInstalled == null ? ""
                    : NavigatorSigningKey.installedCertificateSha256(
                    context, profile.packageName);
            File root = new File(context.getFilesDir(), "navigator-patcher");
            if (!root.exists() && !root.mkdirs()) {
                throw new IOException("Cannot create patcher root");
            }
            cleanupInactiveTransactions(root, null);
            transaction = new File(root, "tx-" + UUID.randomUUID());
            if (!transaction.mkdirs()) {
                throw new IOException("Cannot create transaction directory");
            }
            File source = new File(transaction, "source.apk");
            File patched = new File(transaction, "patched.apk");
            copyCurrentSource(context, profile, source);
            ensureWorkingSpace(context, source.length());
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.VERIFYING, "Verifying source");
            ScanResult metadata = verifyAndInspectMetadata(context, profile, source);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.SCANNING, "Rechecking DEX");
            ScanResult input = inspectComponents(context, source, metadata);
            String previousSha = NavigatorPatchStore.prefs(context)
                    .getString(profile.id + "_scan_sha", "");
            if (previousSha == null || !previousSha.equals(input.sha256)) {
                throw new IOException("APK changed after compatibility check");
            }
            if (NavigatorPatchStore.FAILED.equals(input.directState)
                    || (!NavigatorPatchStore.PATCHABLE.equals(input.directState)
                    && !NavigatorPatchStore.PATCHABLE.equals(input.optionalState))) {
                throw new IOException("No compatible patch is available: " + input.reason);
            }

            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.PATCHING, "Patching direct channel");
            File unsigned = buildUnsigned(context, profile, source, transaction, input);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.SIGNING, "Signing APK");
            sign(unsigned, patched);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.OUTPUT_VERIFY, "Verifying output");
            ScanResult outputMetadata = verifyAndInspectMetadata(context, profile, patched);
            ScanResult output = inspectComponents(context, patched, outputMetadata);
            if (!NavigatorPatchStore.PATCHED.equals(output.directState)) {
                throw new IOException("Direct channel post-verification failed");
            }
            boolean optionalApplied = NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                    && NavigatorPatchStore.PATCHED.equals(output.optionalState);
            assertInstalledTarget(context, profile, initialUpdateTime,
                    initialVersionCode, initialSigner);
            boolean installed = initialInstalled != null;
            boolean destructive = installed
                    && !NavigatorSigningKey.installedUsesLocalKey(context, profile.packageName);
            NavigatorPatchStore.setTransaction(
                    context, profile, transaction, destructive, output,
                    initialUpdateTime, initialVersionCode, initialSigner);
            NavigatorPatchStore.transition(context, profile,
                    NavigatorPatchStore.READY_TO_INSTALL,
                    destructive ? "Replacement requires data removal" : "Ready to install");
            return new PreparedPatch(
                    profile, input, output, transaction, destructive, optionalApplied,
                    initialUpdateTime, initialVersionCode, initialSigner);
        } catch (Exception error) {
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.FAILED, error.getMessage());
            deleteTree(transaction);
            throw error;
        }
    }

    static ScanResult inspectInstalled(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        File input = temporary(context, profile.id + "-installed-", ".apk");
        try {
            copyInstalled(context, profile, input);
            ScanResult metadata = verifyAndInspectMetadata(context, profile, input);
            return inspectComponents(context, input, metadata);
        } finally {
            input.delete();
        }
    }

    static ScanResult verifyRecoverySource(Context context,
            NavigatorPatchStore.Profile profile, File source) throws Exception {
        validateArchiveLimits(source);
        ApkVerifier.Result verified = verifySignature(source);
        PackageInfo info = archiveInfo(context, source);
        if (!profile.packageName.equals(info.packageName)) {
            throw new IOException("Recovery APK package mismatch");
        }
        rejectSplitArchive(source, info);
        if (verified.getSignerCertificates().size() != 1) {
            throw new IOException("Recovery APK must have exactly one signer");
        }
        return new ScanResult(profile, sha256(source),
                info.versionName == null ? "" : info.versionName,
                info.getLongVersionCode(),
                NavigatorSigningKey.archiveCertificateSha256(
                        verified.getSignerCertificates()),
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED, "");
    }

    static void discardPrepared(Context context, PreparedPatch prepared, String reason) {
        if (prepared != null) deleteTree(prepared.directory);
        NavigatorPatchStore.clearTransactionMetadata(context);
        NavigatorPatchStore.transition(context,
                prepared == null ? null : prepared.profile,
                NavigatorPatchStore.FAILED, reason);
    }

    private static File buildUnsigned(Context context, NavigatorPatchStore.Profile profile,
            File source, File transaction, ScanResult input) throws Exception {
        if (profile == NavigatorPatchStore.Profile.WAZE) {
            return patchWaze(context, source, transaction, input);
        }
        return patchGmaps(context, source, transaction, input);
    }

    private static File patchGmaps(Context context, File source, File transaction,
            ScanResult input) throws Exception {
        File mandatory = new File(transaction, "mandatory-unsigned.apk");
        File loggerDex = new File(transaction, "gmaps-bridge.dex");
        File report = new File(transaction, "gmaps-direct-report.json");
        if (NavigatorPatchStore.PATCHABLE.equals(input.directState)) {
            PatchPayloadDex.extract(
                    context, "Lcom/bydhud/gmapsdiag/NavInfoLogger", loggerDex);
            GmapsDiagnosticPatcher.patchDirect(source, mandatory, loggerDex, report);
        } else {
            stripSignatures(source, mandatory);
        }
        if (!NavigatorPatchStore.PATCHABLE.equals(input.optionalState)) return mandatory;
        File optional = new File(transaction, "optional-unsigned.apk");
        try {
            NavigatorPatchStore.transition(context, NavigatorPatchStore.Profile.GMAPS,
                    NavigatorPatchStore.PATCHING, "Patching audio channel");
            GmapsDiagnosticPatcher.patchNavigationAudio(
                    mandatory, optional, new File(transaction, "gmaps-audio-report.json"));
            return optional;
        } catch (Exception optionalError) {
            AppEventLogger.event(context, "navigator_patch optional_failed profile=gmaps error="
                    + clean(optionalError.getMessage()));
            return mandatory;
        }
    }

    private static File patchWaze(Context context, File source, File transaction,
            ScanResult input) throws Exception {
        WazeApkInspection inspection = inspectWaze(source);
        Map<String, File> mandatoryReplacements = new HashMap<>();
        if (NavigatorPatchStore.PATCHABLE.equals(input.directState)) {
            File rewritten = new File(transaction, "waze-direct.dex");
            WazePatchEngine.patchWazeAllowlist(
                    readEntry(source, inspection.allowlistDex), rewritten);
            mandatoryReplacements.put(inspection.allowlistDex, rewritten);
        }
        File mandatory = new File(transaction, "mandatory-unsigned.apk");
        repack(source, mandatory, mandatoryReplacements, Collections.emptyMap());
        if (!NavigatorPatchStore.PATCHABLE.equals(input.optionalState)) return mandatory;

        try {
            NavigatorPatchStore.transition(context, NavigatorPatchStore.Profile.WAZE,
                    NavigatorPatchStore.PATCHING, "Patching stable session");
            WazeApkInspection current = inspectWaze(mandatory);
            if (current.lifecyclePatched()) return mandatory;
            Map<String, File> lifecycleReplacements = new HashMap<>();
            for (String dexEntry : current.lifecycleDexEntries) {
                File rewritten = new File(transaction,
                        "lifecycle-" + dexEntry.replace('/', '_'));
                WazePatchEngine.patchLifecycle(readEntry(mandatory, dexEntry), rewritten);
                lifecycleReplacements.put(dexEntry, rewritten);
            }
            File bridgeDex = new File(transaction, "waze-route-v2.dex");
            PatchPayloadDex.extract(
                    context, "Lcom/waze/bydhud/RouteStateBridgeV2", bridgeDex);
            Map<String, File> additions = new HashMap<>();
            additions.put(nextDexEntry(current.dexEntries), bridgeDex);
            File optional = new File(transaction, "optional-unsigned.apk");
            repack(mandatory, optional, lifecycleReplacements, additions);
            return optional;
        } catch (Exception optionalError) {
            AppEventLogger.event(context, "navigator_patch optional_failed profile=waze error="
                    + clean(optionalError.getMessage()));
            return mandatory;
        }
    }

    private static ScanResult verifyAndInspectMetadata(Context context,
            NavigatorPatchStore.Profile profile, File apk) throws Exception {
        validateArchiveLimits(apk);
        ApkVerifier.Result verified = verifySignature(apk);
        PackageInfo info = archiveInfo(context, apk);
        if (!profile.packageName.equals(info.packageName)) {
            throw new IOException("APK package=" + info.packageName
                    + ", expected=" + profile.packageName);
        }
        rejectSplitArchive(apk, info);
        PackageInfo installed = installedInfo(context, profile.packageName);
        if (installed != null && info.getLongVersionCode() < installed.getLongVersionCode()) {
            throw new IOException("Selected APK is older than installed version");
        }
        return new ScanResult(profile, sha256(apk),
                info.versionName == null ? "" : info.versionName,
                info.getLongVersionCode(),
                NavigatorSigningKey.archiveCertificateSha256(
                        verified.getSignerCertificates()),
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED, "");
    }

    private static ScanResult inspectComponents(
            Context context, File apk, ScanResult metadata) throws Exception {
        if (metadata.profile == NavigatorPatchStore.Profile.GMAPS) {
            String direct = GmapsDiagnosticPatcher.inspectDirectClassification(apk);
            if (!GMAPS_PATCHABLE.equals(direct) && !GMAPS_DIRECT.equals(direct)) {
                return copyStates(metadata, NavigatorPatchStore.FAILED,
                        NavigatorPatchStore.NOT_CHECKED,
                        "Mandatory Google Maps anchors are incompatible");
            }
            String audio = GmapsDiagnosticPatcher.inspectAudioClassification(apk);
            String directState = GMAPS_PATCHABLE.equals(direct)
                    ? NavigatorPatchStore.PATCHABLE : NavigatorPatchStore.PATCHED;
            String optionalState = GMAPS_PATCHABLE.equals(audio)
                    ? NavigatorPatchStore.PATCHABLE
                    : GMAPS_AUDIO.equals(audio)
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.FAILED;
            String reason = NavigatorPatchStore.FAILED.equals(optionalState)
                    ? "Audio channel anchors are incompatible" : "Compatible";
            return copyStates(metadata, directState, optionalState, reason);
        }
        WazeApkInspection inspection = inspectWaze(apk);
        String direct;
        if (WazePatchEngine.PATCHABLE_STOCK.equals(inspection.allowlistClassification)) {
            direct = NavigatorPatchStore.PATCHABLE;
        } else if (WazePatchEngine.ALREADY_PATCHED.equals(inspection.allowlistClassification)) {
            direct = NavigatorPatchStore.PATCHED;
        } else {
            return copyStates(metadata, NavigatorPatchStore.FAILED,
                    NavigatorPatchStore.NOT_CHECKED, inspection.reason);
        }
        String optional;
        if (inspection.lifecycleStock()) optional = NavigatorPatchStore.PATCHABLE;
        else if (inspection.lifecyclePatched()) {
            optional = NavigatorSigningKey.certificateMatchesLocalIfPresent(
                    metadata.signerSha256)
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.PATCHABLE;
        }
        else optional = NavigatorPatchStore.FAILED;
        String reason = NavigatorPatchStore.FAILED.equals(optional)
                ? "Stable session anchors are incompatible" : "Compatible";
        return copyStates(metadata, direct, optional, reason);
    }

    private static ScanResult copyStates(ScanResult source, String direct,
            String optional, String reason) {
        return new ScanResult(source.profile, source.sha256, source.versionName,
                source.versionCode, source.signerSha256, direct, optional, reason);
    }

    private static WazeApkInspection inspectWaze(File apk) throws IOException {
        WazeApkInspection result = new WazeApkInspection();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                result.dexEntries.add(entry.getName());
                byte[] bytes = readEntry(zip, entry.getName());
                WazePatchEngine.WazeInspection allowlist = WazePatchEngine.inspectWaze(bytes);
                if (allowlist.targetCount > 0) {
                    result.allowlistTargetCount += allowlist.targetCount;
                    result.allowlistDex = entry.getName();
                    result.allowlistClassification = allowlist.classification;
                    result.reason = allowlist.reason;
                }
                WazePatchEngine.LifecycleInspection lifecycle =
                        WazePatchEngine.inspectLifecycle(bytes);
                result.applicationTargetCount += lifecycle.applicationTargetCount;
                result.applicationHookCount += lifecycle.applicationHookCount;
                result.routeTargetCount += lifecycle.routeTargetCount;
                result.routeHookCount += lifecycle.routeHookCount;
                result.bridgeClassCount += lifecycle.bridgeClassCount;
                if (lifecycle.applicationTargetCount > 0
                        || lifecycle.routeTargetCount > 0) {
                    result.lifecycleDexEntries.add(entry.getName());
                }
                if (lifecycle.applicationTargetCount > 0) {
                    result.applicationGuard = lifecycle.applicationGuard;
                }
                if (lifecycle.routeTargetCount > 0) result.routeGuard = lifecycle.routeGuard;
            }
        }
        if (result.allowlistTargetCount != 1) {
            result.allowlistClassification = WazePatchEngine.UNSUPPORTED;
            result.reason = "Waze allowlist target count=" + result.allowlistTargetCount;
            result.allowlistDex = "";
        }
        return result;
    }

    private static void copyCurrentSource(Context context,
            NavigatorPatchStore.Profile profile, File target) throws Exception {
        String selectedUri = NavigatorPatchStore.selectedUri(context, profile);
        if (selectedUri != null && !selectedUri.isEmpty()) {
            copyUri(context, Uri.parse(selectedUri), target);
        } else {
            copyInstalled(context, profile, target);
        }
    }

    private static void copyInstalled(Context context,
            NavigatorPatchStore.Profile profile, File target) throws Exception {
        PackageInfo before = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        ApplicationInfo application = before.applicationInfo;
        if (application == null || application.sourceDir == null) {
            throw new IOException("Installed APK path is unavailable");
        }
        if (application.splitSourceDirs != null && application.splitSourceDirs.length > 0) {
            throw new IOException("Split APK is unsupported in this patcher version");
        }
        File source = new File(application.sourceDir);
        rejectOversizedSource(source.length());
        ensureCopySpace(target, source.length());
        Files.copy(source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        PackageInfo after = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        if (before.lastUpdateTime != after.lastUpdateTime
                || before.getLongVersionCode() != after.getLongVersionCode()
                || after.applicationInfo == null
                || !application.sourceDir.equals(after.applicationInfo.sourceDir)) {
            throw new IOException("Installed navigator changed during copy");
        }
        String installedSigner = NavigatorSigningKey.installedCertificateSha256(
                context, profile.packageName);
        String copiedSigner = NavigatorSigningKey.archiveCertificateSha256(
                verifySignature(target).getSignerCertificates());
        if (!installedSigner.equals(copiedSigner)) {
            throw new IOException("Copied APK signer differs from installed package");
        }
    }

    private static void copyUri(Context context, Uri uri, File target) throws IOException {
        long declaredLength = -1L;
        try (AssetFileDescriptor descriptor =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) declaredLength = descriptor.getLength();
        }
        if (declaredLength >= 0L) rejectOversizedSource(declaredLength);
        ensureCopySpace(target, declaredLength);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Selected APK cannot be opened");
            byte[] buffer = new byte[128 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_SOURCE_APK_BYTES) {
                    throw new IOException("Selected APK exceeds 512 MiB limit");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (target.length() <= 0L) throw new IOException("Selected APK is empty");
    }

    private static PackageInfo archiveInfo(Context context, File apk) throws IOException {
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null) throw new IOException("AndroidManifest could not be parsed");
        return info;
    }

    private static void rejectSplitArchive(File apk, PackageInfo info) throws IOException {
        if (info.splitNames != null && info.splitNames.length > 0) {
            throw new IOException("Split APK is unsupported in this patcher version");
        }
        byte[] manifest;
        try (ZipFile zip = new ZipFile(apk)) {
            manifest = readEntry(zip, "AndroidManifest.xml", 8L * 1024L * 1024L);
        }
        try {
            AndroidBinXmlParser parser = new AndroidBinXmlParser(
                    ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN));
            int event = parser.getEventType();
            while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                    if ("uses-split".equals(parser.getName())) {
                        throw new IOException("APK requires an unsupported split");
                    }
                    if ("manifest".equals(parser.getName())) {
                        for (int index = 0; index < parser.getAttributeCount(); index++) {
                            String name = parser.getAttributeName(index);
                            int type = parser.getAttributeValueType(index);
                            if (("split".equals(name) || "configForSplit".equals(name))
                                    && type == AndroidBinXmlParser.VALUE_TYPE_STRING
                                    && !parser.getAttributeStringValue(index).isEmpty()) {
                                throw new IOException(
                                        "Split APK is unsupported in this patcher version");
                            }
                            if ("isSplitRequired".equals(name)
                                    && type == AndroidBinXmlParser.VALUE_TYPE_BOOLEAN
                                    && parser.getAttributeBooleanValue(index)) {
                                throw new IOException("APK declares required splits");
                            }
                        }
                    }
                }
                event = parser.next();
            }
        } catch (AndroidBinXmlParser.XmlParserException error) {
            throw new IOException("Binary AndroidManifest could not be parsed", error);
        }
    }

    private static void validateArchiveLimits(File apk) throws IOException {
        rejectOversizedSource(apk.length());
        int dexCount = 0;
        long totalDexBytes = 0L;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                dexCount++;
                if (dexCount > MAX_DEX_ENTRIES) {
                    throw new IOException("APK contains too many DEX files");
                }
                long expanded = drainEntry(zip, entry, MAX_DEX_BYTES);
                totalDexBytes += expanded;
                if (totalDexBytes > MAX_TOTAL_DEX_BYTES) {
                    throw new IOException("APK DEX payload exceeds 256 MiB limit");
                }
            }
        }
        if (dexCount == 0) throw new IOException("APK contains no DEX files");
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

    private static void rejectContainerName(String displayName) throws IOException {
        String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apks") || lower.endsWith(".apkm")
                || lower.endsWith(".xapk")) {
            throw new IOException("Select one monolithic .apk file, not a split container");
        }
    }

    private static void rejectOversizedSource(long length) throws IOException {
        if (length > MAX_SOURCE_APK_BYTES) {
            throw new IOException("APK exceeds 512 MiB limit");
        }
    }

    private static void ensureCopySpace(File target, long declaredLength) throws IOException {
        long expected = declaredLength < 0L ? MAX_SOURCE_APK_BYTES : declaredLength;
        long reserve = 32L * 1024L * 1024L;
        if (target.getParentFile().getUsableSpace() < expected + reserve) {
            throw new IOException("Insufficient storage to copy selected APK");
        }
    }

    private static ApkVerifier.Result verifySignature(File apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
        if (!result.isVerified()) {
            throw new IOException("APK signature verification failed: " + result.getErrors());
        }
        return result;
    }

    private static void sign(File input, File output) throws Exception {
        new ApkSigner.Builder(Collections.singletonList(
                NavigatorSigningKey.signerConfig()))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .build()
                .sign();
        ApkVerifier.Result result = verifySignature(output);
        String expected = NavigatorSigningKey.localCertificateSha256();
        String actual = NavigatorSigningKey.archiveCertificateSha256(
                result.getSignerCertificates());
        if (!expected.equals(actual)) throw new IOException("Output signer mismatch");
    }

    private static void repack(File input, File output, Map<String, File> replacements,
            Map<String, File> additions) throws IOException {
        Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (ZipArchive archive = new ZipArchive(output)) {
            for (String name : new ArrayList<>(archive.listEntries())) {
                String upper = name.toUpperCase(Locale.ROOT);
                if (replacements.containsKey(name)
                        || additions.containsKey(name)
                        || (upper.startsWith("META-INF/")
                        && (upper.endsWith(".RSA") || upper.endsWith(".DSA")
                        || upper.endsWith(".EC") || upper.endsWith(".SF")
                        || upper.endsWith("MANIFEST.MF")))) {
                    archive.delete(name);
                }
            }
            Map<String, File> changed = new HashMap<>(replacements);
            changed.putAll(additions);
            for (Map.Entry<String, File> entry : changed.entrySet()) {
                BytesSource source = new BytesSource(
                        entry.getValue(), entry.getKey(), Deflater.BEST_SPEED);
                source.align(4);
                archive.add(source);
            }
        }
    }

    private static void stripSignatures(File input, File output) throws IOException {
        repack(input, output, Collections.emptyMap(), Collections.emptyMap());
    }

    private static byte[] readEntry(File apk, String name) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            return readEntry(zip, name);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        return readEntry(zip, name, MAX_DEX_BYTES);
    }

    private static byte[] readEntry(ZipFile zip, String name, long limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing APK entry " + name);
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IOException("APK entry exceeds limit: " + name);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String nextDexEntry(List<String> entries) {
        int maximum = 1;
        for (String entry : entries) {
            if ("classes.dex".equals(entry)) continue;
            String number = entry.substring("classes".length(), entry.length() - 4);
            maximum = Math.max(maximum, Integer.parseInt(number));
        }
        return "classes" + (maximum + 1) + ".dex";
    }

    private static void ensureWorkingSpace(Context context, long inputBytes) throws IOException {
        long required = inputBytes * 4L + 64L * 1024L * 1024L;
        long free = context.getFilesDir().getUsableSpace();
        if (free < required) {
            throw new IOException("Insufficient storage: free=" + free + ", required=" + required);
        }
    }

    private static File temporary(Context context, String prefix, String suffix)
            throws IOException {
        File root = new File(context.getCacheDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create patch cache");
        return File.createTempFile(prefix, suffix, root);
    }

    private static boolean isInstalled(Context context, String packageName) {
        return installedInfo(context, packageName) != null;
    }

    private static PackageInfo installedInfo(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static void assertInstalledTarget(Context context,
            NavigatorPatchStore.Profile profile, long updateTime,
            long versionCode, String signer) throws Exception {
        PackageInfo current = installedInfo(context, profile.packageName);
        if (updateTime < 0L) {
            if (current != null) throw new IOException("Navigator was installed during patching");
            return;
        }
        if (current == null
                || current.lastUpdateTime != updateTime
                || current.getLongVersionCode() != versionCode
                || !signer.equals(NavigatorSigningKey.installedCertificateSha256(
                context, profile.packageName))) {
            throw new IOException("Installed navigator changed during patching");
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder();
        for (byte part : digest.digest()) {
            value.append(String.format(Locale.ROOT, "%02X", part));
        }
        return value.toString();
    }

    private static void saveFailure(Context context, NavigatorPatchStore.Profile profile,
            Exception error) {
        ScanResult failure = new ScanResult(profile, "", "", -1L, "",
                NavigatorPatchStore.FAILED, NavigatorPatchStore.NOT_CHECKED,
                clean(error.getMessage()));
        NavigatorPatchStore.saveScan(context, failure);
        NavigatorPatchStore.transition(
                context, profile, NavigatorPatchStore.FAILED, error.getMessage());
    }

    private static void cleanupInactiveTransactions(File root, File active) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("tx-")
                    && (active == null || !child.equals(active))) deleteTree(child);
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) android.util.Log.w(
                "BYD_NAV_PATCH", "cleanup failed: " + file);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class WazeApkInspection {
        final List<String> dexEntries = new ArrayList<>();
        final List<String> lifecycleDexEntries = new ArrayList<>();
        int allowlistTargetCount;
        String allowlistDex = "";
        String allowlistClassification = WazePatchEngine.UNSUPPORTED;
        String reason = "Waze allowlist target missing";
        int applicationTargetCount;
        int applicationHookCount;
        String applicationGuard = "not found";
        int routeTargetCount;
        int routeHookCount;
        String routeGuard = "not found";
        int bridgeClassCount;

        boolean lifecycleStock() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 0;
        }

        boolean lifecyclePatched() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 1
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 1;
        }
    }
}
