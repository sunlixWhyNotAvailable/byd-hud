package com.bydhud.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        final String alertState;
        final String reason;

        ScanResult(NavigatorPatchStore.Profile profile, String sha256,
                String versionName, long versionCode, String signerSha256,
                String directState, String optionalState, String alertState, String reason) {
            this.profile = profile;
            this.sha256 = sha256;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.directState = directState;
            this.optionalState = optionalState;
            this.alertState = alertState;
            this.reason = reason;
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
        final String installedFingerprint;

        PreparedPatch(NavigatorPatchStore.Profile profile, ScanResult input, ScanResult output,
                File directory, boolean destructive, boolean optionalApplied,
                long installedUpdateTime, long installedVersionCode,
                String installedSignerSha256, String installedFingerprint) {
            this.profile = profile;
            this.input = input;
            this.output = output;
            this.directory = directory;
            this.destructive = destructive;
            this.optionalApplied = optionalApplied;
            this.installedUpdateTime = installedUpdateTime;
            this.installedVersionCode = installedVersionCode;
            this.installedSignerSha256 = installedSignerSha256;
            this.installedFingerprint = installedFingerprint;
        }
    }

    private NavigatorPatchPipeline() {
    }

    static NavigatorPatchStore.Profile inspectSelectedPackage(
            Context context, NavigatorPatchStore.Profile expectedProfile,
            Uri uri, String displayName) throws Exception {
        if (expectedProfile == null) throw new IllegalArgumentException("Patch profile is required");
        NavigatorPatchStore.claim(
                context, expectedProfile, NavigatorPatchStore.OP_SELECT,
                NavigatorPatchStore.COPYING, displayName);
        File input = temporary(context, "selected-", suffix(displayName));
        File setDirectory = temporaryDirectory(context, "selected-set-");
        try {
            copyUri(context, uri, input);
            NavigatorApkSet.SetInfo set = NavigatorApkSet.materializeSource(
                    context, expectedProfile, input, setDirectory);
            requireTrustedSource(context, expectedProfile, set);
            NavigatorPatchStore.Profile profile = expectedProfile;
            PackageInfo installed = installedInfo(context, profile.packageName);
            if (installed != null && set.versionCode < installed.getLongVersionCode()) {
                throw new IOException("Selected APK-set is older than installed version");
            }
            context.getContentResolver().takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            NavigatorPatchStore.selectExternal(
                    context, profile, uri.toString(), displayName,
                    set.versionName, set.versionCode, set.fingerprint, set.signerSha256);
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.IDLE, "");
            return profile;
        } catch (Exception error) {
            NavigatorPatchStore.transition(
                    context, expectedProfile, NavigatorPatchStore.FAILED, error.getMessage());
            throw error;
        } finally {
            input.delete();
            deleteTree(setDirectory);
        }
    }

    static ScanResult scan(Context context, NavigatorPatchStore.Profile profile) throws Exception {
        NavigatorPatchStore.claim(
                context, profile, NavigatorPatchStore.OP_CHECK,
                NavigatorPatchStore.COPYING, "Copying APK");
        File setDirectory = temporaryDirectory(context, profile.id + "-scan-");
        try {
            NavigatorApkSet.SetInfo set = materializeCurrentSource(context, profile, setDirectory);
            requireTrustedSource(context, profile, set);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.VERIFYING, "Verifying APK");
            ScanResult preflight = metadata(set, profile);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.SCANNING, "Scanning DEX");
            ScanResult result = inspectComponents(context, set, preflight);
            NavigatorPatchStore.saveScan(context, result);
            NavigatorPatchStore.transition(context, profile, NavigatorPatchStore.IDLE, "");
            return result;
        } catch (Exception error) {
            saveFailure(context, profile, error);
            throw error;
        } finally {
            deleteTree(setDirectory);
        }
    }

    static PreparedPatch prepare(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        NavigatorPatchStore.claim(
                context, profile, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.COPYING, "Copying APK");
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
            File source = new File(transaction, "source-set");
            File patched = new File(transaction, "patched-set");
            NavigatorApkSet.SetInfo sourceSet = materializeCurrentSource(context, profile, source);
            requireTrustedSource(context, profile, sourceSet);
            String initialFingerprint = initialInstalled == null ? ""
                    : NavigatorPatchStore.selectedUri(context, profile).isEmpty()
                    ? sourceSet.fingerprint
                    : NavigatorApkSet.inspectInstalled(context, profile).fingerprint;
            ensureWorkingSpace(context, sourceSet);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.VERIFYING, "Verifying source");
            ScanResult metadata = metadata(sourceSet, profile);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.SCANNING, "Rechecking DEX");
            ScanResult input = inspectComponents(context, sourceSet, metadata);
            String previousSha = NavigatorPatchStore.prefs(context)
                    .getString(profile.id + "_scan_sha", "");
            if (previousSha == null || !previousSha.equals(input.sha256)) {
                throw new IOException("APK changed after compatibility check");
            }
            // Recheck the staged source immediately before creating a destructive transaction.
            requireTrustedSource(context, profile, sourceSet);
            if (NavigatorPatchStore.FAILED.equals(input.directState)
                    || (!NavigatorPatchStore.PATCHABLE.equals(input.directState)
                    && !NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                    && !NavigatorPatchStore.PATCHABLE.equals(input.alertState))) {
                throw new IOException("No compatible patch is available: " + input.reason);
            }

            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.PATCHING, "Patching direct channel");
            boolean optionalFailed = buildUnsignedSet(
                    context, profile, sourceSet, patched, transaction, input);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.SIGNING, "Signing APK");
            signSet(patched);
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.OUTPUT_VERIFY, "Verifying output");
            NavigatorApkSet.SetInfo outputSet = NavigatorApkSet.readDirectory(
                    context, profile, patched);
            ScanResult outputMetadata = metadata(outputSet, profile);
            ScanResult output = inspectComponents(context, outputSet, outputMetadata);
            if (!NavigatorPatchStore.PATCHED.equals(output.directState)) {
                throw new IOException("Direct channel post-verification failed");
            }
            if (profile == NavigatorPatchStore.Profile.WAZE
                    && NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                    && !NavigatorPatchStore.PATCHED.equals(output.optionalState)) {
                throw new IOException("Stable session post-verification failed");
            }
            if (profile == NavigatorPatchStore.Profile.WAZE
                    && NavigatorPatchStore.PATCHABLE.equals(input.alertState)
                    && !NavigatorPatchStore.PATCHED.equals(output.alertState)) {
                throw new IOException("Waze alert-hook post-verification failed");
            }
            if (optionalFailed) {
                output = copyStates(output, output.directState, NavigatorPatchStore.FAILED,
                        output.alertState,
                        "Optional patch attempt failed");
            }
            boolean optionalApplied = (NavigatorPatchStore.PATCHABLE.equals(input.optionalState)
                    && NavigatorPatchStore.PATCHED.equals(output.optionalState))
                    || (NavigatorPatchStore.PATCHABLE.equals(input.alertState)
                    && NavigatorPatchStore.PATCHED.equals(output.alertState));
            assertInstalledTarget(context, profile, initialUpdateTime,
                    initialVersionCode, initialSigner, initialFingerprint);
            boolean installed = initialInstalled != null;
            boolean destructive = installed
                    && !NavigatorSigningKey.installedUsesLocalKey(context, profile.packageName);
            NavigatorPatchStore.setTransaction(
                    context, profile, transaction, destructive, output,
                    initialUpdateTime, initialVersionCode, initialSigner,
                    initialFingerprint);
            NavigatorPatchStore.transition(context, profile,
                    NavigatorPatchStore.READY_TO_INSTALL,
                    destructive ? "Replacement requires data removal" : "Ready to install");
            return new PreparedPatch(
                    profile, input, output, transaction, destructive, optionalApplied,
                    initialUpdateTime, initialVersionCode, initialSigner,
                    initialFingerprint);
        } catch (Exception error) {
            NavigatorPatchStore.transition(
                    context, profile, NavigatorPatchStore.FAILED, error.getMessage());
            deleteTree(transaction);
            throw error;
        }
    }

    static ScanResult inspectInstalled(Context context, NavigatorPatchStore.Profile profile)
            throws Exception {
        File setDirectory = temporaryDirectory(context, profile.id + "-installed-");
        try {
            NavigatorApkSet.SetInfo set = NavigatorApkSet.materializeInstalled(
                    context, profile, setDirectory);
            requireTrustedSource(context, profile, set);
            ScanResult metadata = metadata(set, profile);
            return inspectComponents(context, set, metadata);
        } finally {
            deleteTree(setDirectory);
        }
    }

    static ScanResult verifyRecoverySource(Context context,
            NavigatorPatchStore.Profile profile, File source) throws Exception {
        NavigatorApkSet.SetInfo set = NavigatorApkSet.readDirectory(context, profile, source);
        requireTrustedSource(context, profile, set);
        return new ScanResult(profile, set.fingerprint, set.versionName,
                set.versionCode, set.signerSha256,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED, "");
    }

    static void discardPrepared(Context context, PreparedPatch prepared, String reason) {
        if (prepared != null) deleteTree(prepared.directory);
        NavigatorPatchStore.clearTransactionMetadata(context);
        NavigatorPatchStore.transition(context,
                prepared == null ? null : prepared.profile,
                NavigatorPatchStore.FAILED, reason);
    }

    private static NavigatorApkSet.SetInfo materializeCurrentSource(
            Context context, NavigatorPatchStore.Profile profile, File outputDirectory)
            throws Exception {
        String selected = NavigatorPatchStore.selectedUri(context, profile);
        if (selected != null && !selected.isEmpty()) {
            String name = NavigatorPatchStore.selectedName(context, profile);
            File input = temporary(context, profile.id + "-source-", suffix(name));
            try {
                copyUri(context, Uri.parse(selected), input);
                return NavigatorApkSet.materializeSource(
                        context, profile, input, outputDirectory);
            } finally {
                input.delete();
            }
        }
        return NavigatorApkSet.materializeInstalled(context, profile, outputDirectory);
    }

    private static void requireTrustedSource(Context context,
            NavigatorPatchStore.Profile profile, NavigatorApkSet.SetInfo set)
            throws Exception {
        boolean localSigner = NavigatorSigningKey.certificateMatchesLocalIfPresent(
                set.signerSha256);
        boolean mandatoryPatchMarker = false;
        if (localSigner) {
            ScanResult markerScan = inspectComponents(context, set, metadata(set, profile));
            mandatoryPatchMarker = NavigatorPatchStore.PATCHED.equals(
                    markerScan.directState);
        }
        NavigatorPatchTrustPolicy.require(profile, set.signerSha256,
                mandatoryPatchMarker, localSigner);
    }

    private static ScanResult metadata(NavigatorApkSet.SetInfo set,
            NavigatorPatchStore.Profile profile) {
        return new ScanResult(profile, set.fingerprint, set.versionName,
                set.versionCode, set.signerSha256,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED, "");
    }

    private static ScanResult inspectComponents(Context context,
            NavigatorApkSet.SetInfo set, ScanResult metadata) throws Exception {
        if (metadata.profile == NavigatorPatchStore.Profile.GMAPS) {
            String direct = "";
            String audio = "";
            int directTargets = 0;
            int audioTargets = 0;
            for (NavigatorApkSet.Member member : set.members) {
                String memberDirect = GmapsDiagnosticPatcher.inspectDirectClassification(member.file);
                String memberAudio = GmapsDiagnosticPatcher.inspectAudioClassification(member.file);
                if (GMAPS_PATCHABLE.equals(memberDirect) || GMAPS_DIRECT.equals(memberDirect)) {
                    direct = memberDirect;
                    directTargets++;
                }
                if (GMAPS_PATCHABLE.equals(memberAudio) || GMAPS_AUDIO.equals(memberAudio)) {
                    audio = memberAudio;
                    audioTargets++;
                }
            }
            if (directTargets != 1 || (!GMAPS_PATCHABLE.equals(direct)
                    && !GMAPS_DIRECT.equals(direct))) {
                return copyStates(metadata, NavigatorPatchStore.FAILED,
                        NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED,
                        "Mandatory Google Maps anchors are incompatible");
            }
            String directState = GMAPS_PATCHABLE.equals(direct)
                    ? NavigatorPatchStore.PATCHABLE : NavigatorPatchStore.PATCHED;
            String optionalState = audioTargets == 1 && GMAPS_PATCHABLE.equals(audio)
                    ? NavigatorPatchStore.PATCHABLE
                    : audioTargets == 1 && GMAPS_AUDIO.equals(audio)
                    ? NavigatorPatchStore.PATCHED : NavigatorPatchStore.FAILED;
            String reason = NavigatorPatchStore.FAILED.equals(optionalState)
                    ? "Audio channel anchors are incompatible" : "Compatible";
            return copyStates(metadata, directState, optionalState,
                    NavigatorPatchStore.NOT_CHECKED, reason);
        }
        int stock = 0;
        int patched = 0;
        int lifecycleTargets = 0;
        boolean lifecycleStock = false;
        boolean lifecyclePatched = false;
        int alertTargets = 0;
        boolean alertStock = false;
        boolean alertPatched = false;
        String reason = "Waze allowlist target missing";
        for (NavigatorApkSet.Member member : set.members) {
            WazeApkInspection inspection = inspectWaze(member.file);
            if (WazePatchEngine.PATCHABLE_STOCK.equals(inspection.allowlistClassification)) {
                stock++;
            } else if (WazePatchEngine.ALREADY_PATCHED.equals(inspection.allowlistClassification)) {
                patched++;
            }
            lifecycleTargets += inspection.applicationTargetCount + inspection.routeTargetCount;
            lifecycleStock |= inspection.lifecycleStock();
            lifecyclePatched |= inspection.lifecyclePatched();
            alertTargets += inspection.alertClassCount;
            alertStock |= inspection.alertStock();
            alertPatched |= inspection.alertPatched();
            if (!inspection.reason.isEmpty()) reason = inspection.reason;
        }
        String directState;
        if (stock == 1 && patched == 0) directState = NavigatorPatchStore.PATCHABLE;
        else if (stock == 0 && patched == 1) directState = NavigatorPatchStore.PATCHED;
        else return copyStates(metadata, NavigatorPatchStore.FAILED,
                NavigatorPatchStore.NOT_CHECKED, NavigatorPatchStore.NOT_CHECKED, reason);
        String optional = lifecycleTargets == 0 ? NavigatorPatchStore.FAILED
                : lifecycleStock ? NavigatorPatchStore.PATCHABLE
                : lifecyclePatched ? NavigatorPatchStore.PATCHED
                : NavigatorPatchStore.FAILED;
        String alert = alertTargets != 1 ? NavigatorPatchStore.FAILED
                : alertStock ? NavigatorPatchStore.PATCHABLE
                : alertPatched ? NavigatorPatchStore.PATCHED
                : NavigatorPatchStore.FAILED;
        String compatibilityReason = NavigatorPatchStore.FAILED.equals(optional)
                ? "Stable session anchors are incompatible"
                : NavigatorPatchStore.FAILED.equals(alert)
                ? "Waze alert anchors are incompatible" : "Compatible";
        return copyStates(metadata, directState, optional, alert, compatibilityReason);
    }

    private static boolean buildUnsignedSet(Context context, NavigatorPatchStore.Profile profile,
            NavigatorApkSet.SetInfo sourceSet, File outputDirectory, File transaction,
            ScanResult input) throws Exception {
        File sourceDirectory = sourceSet.members.get(0).file.getParentFile().getParentFile();
        copySetDirectory(sourceDirectory, outputDirectory);
        if (profile == NavigatorPatchStore.Profile.WAZE) {
            return patchWazeSet(context, sourceSet, outputDirectory, transaction, input);
        }
        return patchGmapsSet(context, sourceSet, outputDirectory, transaction, input);
    }

    private static boolean patchGmapsSet(Context context, NavigatorApkSet.SetInfo sourceSet,
            File outputDirectory, File transaction, ScanResult input) throws Exception {
        File directMember = null;
        File audioMember = null;
        for (NavigatorApkSet.Member member : sourceSet.members) {
            String direct = GmapsDiagnosticPatcher.inspectDirectClassification(member.file);
            String audio = GmapsDiagnosticPatcher.inspectAudioClassification(member.file);
            if (GMAPS_PATCHABLE.equals(direct)) {
                if (directMember != null) throw new IOException("Multiple Google Maps direct targets");
                directMember = outputMember(outputDirectory, member.installName);
            } else if (GMAPS_DIRECT.equals(direct)) {
                if (directMember != null) throw new IOException("Multiple Google Maps direct targets");
                directMember = null;
            }
            if (GMAPS_PATCHABLE.equals(audio)) {
                if (audioMember != null) throw new IOException("Multiple Google Maps audio targets");
                audioMember = outputMember(outputDirectory, member.installName);
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.directState)) {
            if (directMember == null) throw new IOException("Google Maps direct target member missing");
            File rewritten = new File(transaction, "gmaps-direct-unsigned.apk");
            File loggerDex = new File(transaction, "gmaps-bridge.dex");
            File report = new File(transaction, "gmaps-direct-report.json");
            PatchPayloadDex.extract(
                    context, "Lcom/bydhud/gmapsdiag/NavInfoLogger", loggerDex);
            GmapsDiagnosticPatcher.patchDirect(directMember, rewritten, loggerDex, report);
            replaceFile(rewritten, directMember);
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.optionalState)) {
            if (audioMember == null) {
                AppEventLogger.event(context,
                        "navigator_patch operation=patch profile=gmaps stage=optional code=OPTIONAL_INCOMPATIBLE");
                return true;
            }
            try {
                File optional = new File(transaction, "gmaps-audio-unsigned.apk");
                NavigatorPatchStore.transition(context, NavigatorPatchStore.Profile.GMAPS,
                        NavigatorPatchStore.PATCHING, "Patching audio channel");
                GmapsDiagnosticPatcher.patchNavigationAudio(
                        audioMember, optional, new File(transaction, "gmaps-audio-report.json"));
                replaceFile(optional, audioMember);
            } catch (Exception optionalError) {
                AppEventLogger.event(context, "navigator_patch operation=patch profile=gmaps"
                        + " stage=optional code=OPTIONAL_PATCH_FAILED detail="
                        + clean(optionalError.getMessage()));
                return true;
            }
        }
        return false;
    }

    private static boolean patchWazeSet(Context context, NavigatorApkSet.SetInfo sourceSet,
            File outputDirectory, File transaction, ScanResult input) throws Exception {
        List<WazeApkInspection> inspections = new ArrayList<>();
        WazeApkInspection allowlist = null;
        int allowlistCount = 0;
        for (NavigatorApkSet.Member member : sourceSet.members) {
            WazeApkInspection inspection = inspectWaze(member.file);
            inspections.add(inspection);
            if (inspection.allowlistTargetCount == 1) {
                allowlist = inspection;
                allowlistCount++;
            }
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.directState)) {
            if (allowlistCount != 1 || allowlist == null) {
                throw new IOException("Waze allowlist target member is ambiguous");
            }
            File target = outputMember(outputDirectory, allowlist.fileName);
            File rewrittenDex = new File(transaction, "waze-direct.dex");
            WazePatchEngine.patchWazeAllowlist(
                    readEntry(target, allowlist.allowlistDex), rewrittenDex);
            File rewrittenApk = new File(transaction, "waze-direct-unsigned.apk");
            Map<String, File> replacements = new HashMap<>();
            replacements.put(allowlist.allowlistDex, rewrittenDex);
            repack(target, rewrittenApk, replacements, Collections.emptyMap());
            replaceFile(rewrittenApk, target);
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.optionalState)) {
            NavigatorPatchStore.transition(context, NavigatorPatchStore.Profile.WAZE,
                    NavigatorPatchStore.PATCHING, "Patching stable session");
            WazeApkInspection lifecycleOwner = null;
            for (WazeApkInspection inspection : inspections) {
                if (inspection.applicationTargetCount > 0 || inspection.routeTargetCount > 0) {
                    if (lifecycleOwner != null) {
                        throw new IOException("Waze stable-session target member is ambiguous");
                    }
                    lifecycleOwner = inspection;
                }
            }
            if (lifecycleOwner == null || lifecycleOwner.routeTargetCount != 1
                    || lifecycleOwner.applicationTargetCount != 1) {
                throw new IOException("Waze stable-session target missing");
            }
            File target = outputMember(outputDirectory, lifecycleOwner.fileName);
            Map<String, File> replacements = new HashMap<>();
            for (String dexEntry : lifecycleOwner.lifecycleDexEntries) {
                File rewritten = new File(transaction,
                        "lifecycle-" + lifecycleOwner.fileName + "-"
                                + dexEntry.replace('/', '_'));
                WazePatchEngine.patchLifecycle(readEntry(target, dexEntry), rewritten);
                replacements.put(dexEntry, rewritten);
            }
            File lifecycleApk = new File(transaction, "waze-lifecycle-unsigned.apk");
            repack(target, lifecycleApk, replacements, Collections.emptyMap());
            File bridgeDex = new File(transaction, "waze-route-v2.dex");
            PatchPayloadDex.extract(
                    context, "Lcom/waze/bydhud/RouteStateBridgeV2", bridgeDex);
            File optionalApk = new File(transaction, "waze-optional-unsigned.apk");
            Map<String, File> additions = new HashMap<>();
            List<String> dexEntries = dexEntries(lifecycleApk);
            additions.put(nextDexEntry(dexEntries), bridgeDex);
            repack(lifecycleApk, optionalApk, Collections.emptyMap(), additions);
            replaceFile(optionalApk, target);
        }
        if (NavigatorPatchStore.PATCHABLE.equals(input.alertState)) {
            NavigatorPatchStore.transition(context, NavigatorPatchStore.Profile.WAZE,
                    NavigatorPatchStore.PATCHING, "Patching Waze alerts");
            WazeApkInspection alertOwner = null;
            for (WazeApkInspection inspection : inspections) {
                if (inspection.alertClassCount <= 0) continue;
                if (alertOwner != null) {
                    throw new IOException("Waze alert-hook target member is ambiguous");
                }
                alertOwner = inspection;
            }
            if (alertOwner == null || !alertOwner.alertStock()
                    || alertOwner.alertDexEntries.size() != 1) {
                throw new IOException("Waze alert-hook target missing");
            }
            File target = outputMember(outputDirectory, alertOwner.fileName);
            String dexEntry = alertOwner.alertDexEntries.get(0);
            File rewritten = new File(transaction, "waze-alert-hook.dex");
            WazePatchEngine.patchAlertHook(readEntry(target, dexEntry), rewritten);
            File alertApk = new File(transaction, "waze-alert-hook-unsigned.apk");
            Map<String, File> replacements = new HashMap<>();
            replacements.put(dexEntry, rewritten);
            repack(target, alertApk, replacements, Collections.emptyMap());
            replaceFile(alertApk, target);
        }
        return false;
    }

    private static void signSet(File directory) throws Exception {
        List<File> members = new ArrayList<>();
        File memberDirectory = new File(directory, "members");
        File[] files = memberDirectory.listFiles((file, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("APK-set is empty");
        Collections.addAll(members, files);
        for (File input : members) {
            File signed = new File(input.getParentFile(), input.getName() + ".signed");
            sign(input, signed);
            replaceFile(signed, input);
        }
    }

    private static void copySetDirectory(File source, File target) throws IOException {
        deleteTree(target);
        if (!target.mkdirs()) throw new IOException("Cannot create patched APK-set");
        File sourceMembers = new File(source, "members");
        File targetMembers = new File(target, "members");
        if (!targetMembers.mkdirs()) throw new IOException("Cannot create patched APK members");
        File[] files = sourceMembers.listFiles((file, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("Source APK-set is empty");
        for (File file : files) {
            Files.copy(file.toPath(), new File(targetMembers, file.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File outputMember(File directory, String installName) throws IOException {
        File member = new File(new File(directory, "members"), installName);
        if (!member.isFile()) throw new IOException("Missing APK-set member: " + installName);
        return member;
    }

    private static void replaceFile(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static List<String> dexEntries(File apk) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes(\\d*)\\.dex")) result.add(name);
            }
        }
        return result;
    }

    private static String suffix(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apkm")) return ".apkm";
        if (lower.endsWith(".apks")) return ".apks";
        if (lower.endsWith(".xapk")) return ".xapk";
        return ".apk";
    }

    private static File temporaryDirectory(Context context, String prefix) throws IOException {
        File root = new File(context.getCacheDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create patch cache");
        File directory = new File(root, prefix + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot create temporary APK-set");
        return directory;
    }

    private static ScanResult copyStates(ScanResult source, String direct,
            String optional, String alert, String reason) {
        return new ScanResult(source.profile, source.sha256, source.versionName,
                source.versionCode, source.signerSha256, direct, optional, alert, reason);
    }

    private static WazeApkInspection inspectWaze(File apk) throws IOException {
        WazeApkInspection result = new WazeApkInspection();
        result.fileName = apk.getName();
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
                result.legacyApplicationHookCount += lifecycle.legacyApplicationHookCount;
                result.routeTargetCount += lifecycle.routeTargetCount;
                result.routeHookCount += lifecycle.routeHookCount;
                result.legacyRouteHookCount += lifecycle.legacyRouteHookCount;
                result.bridgeClassCount += lifecycle.bridgeClassCount;
                result.legacyBridgeClassCount += lifecycle.legacyBridgeClassCount;
                if (lifecycle.applicationTargetCount > 0
                        || lifecycle.routeTargetCount > 0) {
                    result.lifecycleDexEntries.add(entry.getName());
                }
                if (lifecycle.applicationTargetCount > 0) {
                    result.applicationGuard = lifecycle.applicationGuard;
                }
                if (lifecycle.routeTargetCount > 0) result.routeGuard = lifecycle.routeGuard;
                WazePatchEngine.AlertInspection alert =
                        WazePatchEngine.inspectAlertHook(bytes);
                result.alertClassCount += alert.classCount;
                result.alertFieldAnchorCount += alert.fieldAnchorCount;
                result.alertGuardFieldCount += alert.guardFieldCount;
                result.alertTargetMethodCount += alert.targetMethodCount;
                result.alertAnchorCount += alert.anchorCount;
                result.alertHookCallCount += alert.hookCallCount;
                result.alertHookAfterAnchorCount += alert.hookAfterAnchorCount;
                result.alertHelperMethodCount += alert.helperMethodCount;
                result.alertProducerCallCount += alert.producerCallCount;
                result.alertCollectorCallCount += alert.collectorCallCount;
                result.alertGuardReadCount += alert.guardReadCount;
                result.alertGuardWriteCount += alert.guardWriteCount;
                result.alertLogMarkerCount += alert.logMarkerCount;
                if (alert.classCount > 0) result.alertDexEntries.add(entry.getName());
            }
        }
        if (result.allowlistTargetCount != 1) {
            result.allowlistClassification = WazePatchEngine.UNSUPPORTED;
            result.reason = "Waze allowlist target count=" + result.allowlistTargetCount;
            result.allowlistDex = "";
        }
        return result;
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

    private static void ensureWorkingSpace(Context context, NavigatorApkSet.SetInfo set)
            throws IOException {
        long total = 0L;
        for (NavigatorApkSet.Member member : set.members) total += member.file.length();
        ensureWorkingSpace(context, total);
    }

    private static File temporary(Context context, String prefix, String suffix)
            throws IOException {
        File root = new File(context.getCacheDir(), "navigator-patcher");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create patch cache");
        return File.createTempFile(prefix, suffix, root);
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
            long versionCode, String signer, String fingerprint) throws Exception {
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
        if (!fingerprint.equals(
                NavigatorApkSet.inspectInstalled(context, profile).fingerprint)) {
            throw new IOException("Installed APK-set changed during patching");
        }
    }

    private static void saveFailure(Context context, NavigatorPatchStore.Profile profile,
            Exception error) {
        ScanResult failure = new ScanResult(profile, "", "", -1L, "",
                NavigatorPatchStore.FAILED, NavigatorPatchStore.NOT_CHECKED,
                NavigatorPatchStore.NOT_CHECKED,
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
        String fileName = "";
        final List<String> dexEntries = new ArrayList<>();
        final List<String> lifecycleDexEntries = new ArrayList<>();
        final List<String> alertDexEntries = new ArrayList<>();
        int allowlistTargetCount;
        String allowlistDex = "";
        String allowlistClassification = WazePatchEngine.UNSUPPORTED;
        String reason = "Waze allowlist target missing";
        int applicationTargetCount;
        int applicationHookCount;
        int legacyApplicationHookCount;
        String applicationGuard = "not found";
        int routeTargetCount;
        int routeHookCount;
        int legacyRouteHookCount;
        String routeGuard = "not found";
        int bridgeClassCount;
        int legacyBridgeClassCount;
        int alertClassCount;
        int alertFieldAnchorCount;
        int alertGuardFieldCount;
        int alertTargetMethodCount;
        int alertAnchorCount;
        int alertHookCallCount;
        int alertHookAfterAnchorCount;
        int alertHelperMethodCount;
        int alertProducerCallCount;
        int alertCollectorCallCount;
        int alertGuardReadCount;
        int alertGuardWriteCount;
        int alertLogMarkerCount;

        boolean lifecycleStock() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && legacyApplicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 0 && legacyBridgeClassCount == 0;
        }

        boolean lifecycleV2Patched() {
            return applicationTargetCount == 1 && applicationHookCount == 1
                    && legacyApplicationHookCount == 0
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 1
                    && legacyRouteHookCount == 0
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 1 && legacyBridgeClassCount == 0;
        }

        boolean lifecycleLegacyPatched() {
            return applicationTargetCount == 1 && applicationHookCount == 0
                    && legacyApplicationHookCount == 1
                    && "ok".equals(applicationGuard)
                    && routeTargetCount == 1 && routeHookCount == 0
                    && legacyRouteHookCount == 1
                    && "ok".equals(routeGuard)
                    && bridgeClassCount == 0 && legacyBridgeClassCount == 1;
        }

        boolean lifecyclePatched() {
            return lifecycleV2Patched() || lifecycleLegacyPatched();
        }

        boolean alertStock() {
            return alertClassCount == 1 && alertFieldAnchorCount == 1
                    && alertGuardFieldCount == 0 && alertTargetMethodCount == 1
                    && alertAnchorCount == 1 && alertHookCallCount == 0
                    && alertHelperMethodCount == 0;
        }

        boolean alertPatched() {
            return alertClassCount == 1 && alertFieldAnchorCount == 1
                    && alertGuardFieldCount == 1 && alertTargetMethodCount == 1
                    && alertAnchorCount == 1 && alertHookCallCount == 1
                    && alertHookAfterAnchorCount == 1 && alertHelperMethodCount == 1
                    && alertProducerCallCount == 1 && alertCollectorCallCount == 1
                    && alertGuardReadCount == 1 && alertGuardWriteCount == 1
                    && alertLogMarkerCount == 1;
        }
    }
}
