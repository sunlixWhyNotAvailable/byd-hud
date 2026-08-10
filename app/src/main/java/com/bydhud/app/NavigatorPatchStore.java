package com.bydhud.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@android.annotation.SuppressLint("ApplySharedPref")
final class NavigatorPatchStore {
    private static final int SCAN_CACHE_REVISION = 4;
    static final String NOT_CHECKED = "NOT_CHECKED";
    static final String PATCHABLE = "PATCHABLE";
    static final String PATCHED = "PATCHED";
    static final String FAILED = "FAILED";

    static final String IDLE = "IDLE";
    static final String COPYING = "COPYING";
    static final String VERIFYING = "VERIFYING";
    static final String SCANNING = "SCANNING";
    static final String PATCHING = "PATCHING";
    static final String REPACKING = "REPACKING";
    static final String SIGNING = "SIGNING";
    static final String OUTPUT_VERIFY = "OUTPUT_VERIFY";
    static final String READY_TO_INSTALL = "READY_TO_INSTALL";
    static final String AWAITING_PERMISSION = "AWAITING_PERMISSION";
    static final String UNINSTALL_REQUESTED = "UNINSTALL_REQUESTED";
    static final String COMMITTING = "COMMITTING";
    static final String INSTALL_REQUESTED = "INSTALL_REQUESTED";
    static final String VERIFIED = "VERIFIED";
    static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
    static final String OP_SELECT = "SELECT";
    static final String OP_CHECK = "CHECK";
    static final String OP_PATCH = "PATCH";
    static final String OP_RECOVERY = "RECOVERY";

    private static final String PREFS = "navigator_patcher";
    private static final String KEY_OPERATION_PROFILE = "operation_profile";
    private static final String KEY_OPERATION_PHASE = "operation_phase";
    private static final String KEY_OPERATION_DETAIL = "operation_detail";
    private static final String KEY_OPERATION_KIND = "operation_kind";
    private static final String KEY_TRANSACTION_DIR = "transaction_dir";
    private static final String KEY_DESTRUCTIVE = "destructive";
    private static final String KEY_STATE_AT = "state_at";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_TRANSACTION_TOKEN = "transaction_token";
    private static final String KEY_RECOVERY_OWNER = "recovery_owner";
    private static final String KEY_EXPECTED_SHA = "expected_sha";
    private static final String KEY_EXPECTED_VERSION_CODE = "expected_version_code";
    private static final String KEY_EXPECTED_SIGNER = "expected_signer";
    private static final String KEY_EXPECTED_DIRECT = "expected_direct";
    private static final String KEY_EXPECTED_OPTIONAL = "expected_optional";
    private static final String KEY_EXPECTED_ALERT = "expected_alert";
    private static final String KEY_INITIAL_UPDATE_TIME = "initial_update_time";
    private static final String KEY_INITIAL_VERSION_CODE = "initial_version_code";
    private static final String KEY_INITIAL_SIGNER = "initial_signer";
    private static final String KEY_INITIAL_FINGERPRINT = "initial_fingerprint";
    private static final String KEY_EXPECTED_CALLBACK = "expected_callback";
    private static final String KEY_CALLBACK_CONSUMED = "callback_consumed";

    enum Profile {
        WAZE("waze", "com.waze", "Waze", "Stable session", "Waze alerts"),
        GMAPS("gmaps", "app.revanced.android.apps.maps", "Google Maps (ReVanced)",
                "Audio channel", "");

        final String id;
        final String packageName;
        final String fallbackLabel;
        final String optionalLabel;
        final String alertLabel;

        Profile(String id, String packageName, String fallbackLabel,
                String optionalLabel, String alertLabel) {
            this.id = id;
            this.packageName = packageName;
            this.fallbackLabel = fallbackLabel;
            this.optionalLabel = optionalLabel;
            this.alertLabel = alertLabel;
        }

        static Profile fromId(String id) {
            for (Profile profile : values()) if (profile.id.equals(id)) return profile;
            return null;
        }

    }

    static final class ProfileSnapshot {
        final Profile profile;
        final boolean installed;
        final String label;
        final String installedVersion;
        final long installedVersionCode;
        final boolean externalSource;
        final String sourceName;
        final String sourceVersion;
        final long sourceVersionCode;
        final String directState;
        final String optionalState;
        final String alertState;
        final String reason;
        final boolean patchEnabled;

        ProfileSnapshot(Profile profile, boolean installed, String label,
                String installedVersion, long installedVersionCode,
                boolean externalSource, String sourceName, String sourceVersion,
                long sourceVersionCode, String directState, String optionalState,
                String alertState,
                String reason, boolean patchEnabled) {
            this.profile = profile;
            this.installed = installed;
            this.label = label;
            this.installedVersion = installedVersion;
            this.installedVersionCode = installedVersionCode;
            this.externalSource = externalSource;
            this.sourceName = sourceName;
            this.sourceVersion = sourceVersion;
            this.sourceVersionCode = sourceVersionCode;
            this.directState = directState;
            this.optionalState = optionalState;
            this.alertState = alertState;
            this.reason = reason;
            this.patchEnabled = patchEnabled;
        }
    }

    static final class OperationSnapshot {
        final Profile profile;
        final String kind;
        final String phase;
        final String detail;
        final boolean destructive;

        OperationSnapshot(Profile profile, String kind, String phase,
                String detail, boolean destructive) {
            this.profile = profile;
            this.kind = kind;
            this.phase = phase;
            this.detail = detail;
            this.destructive = destructive;
        }

        boolean busy() {
            return !IDLE.equals(phase) && !VERIFIED.equals(phase)
                    && !FAILED.equals(phase) && !RECOVERY_REQUIRED.equals(phase);
        }
    }

    private NavigatorPatchStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String selectedUri(Context context, Profile profile) {
        return prefs(context).getString(profile.id + "_selected_uri", "");
    }

    static String selectedName(Context context, Profile profile) {
        return prefs(context).getString(profile.id + "_selected_name", "");
    }

    static void selectExternal(Context context, Profile profile, String uri, String name) {
        selectExternal(context, profile, uri, name, "", -1L, "", "");
    }

    static void selectExternal(Context context, Profile profile, String uri, String name,
            String versionName, long versionCode, String fingerprint, String signer) {
        String previous = selectedUri(context, profile);
        prefs(context).edit()
                .putString(profile.id + "_selected_uri", uri == null ? "" : uri)
                .putString(profile.id + "_selected_name", name == null ? "" : name)
                .putString(profile.id + "_selected_version", versionName == null ? "" : versionName)
                .putLong(profile.id + "_selected_version_code", versionCode)
                .putString(profile.id + "_selected_fingerprint", fingerprint == null ? "" : fingerprint)
                .putString(profile.id + "_selected_signer", signer == null ? "" : signer)
                .remove(profile.id + "_scan_sha")
                .remove(profile.id + "_scan_version")
                .remove(profile.id + "_scan_version_code")
                .remove(profile.id + "_scan_direct")
                .remove(profile.id + "_scan_optional")
                .remove(profile.id + "_scan_alert")
                .remove(profile.id + "_scan_reason")
                .remove(profile.id + "_scan_source_uri")
                .remove(profile.id + "_scan_installed_update")
                .remove(profile.id + "_scan_revision")
                .commit();
        if (previous != null && !previous.isEmpty() && !previous.equals(uri)) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        android.net.Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
    }

    static void clearExternal(Context context, Profile profile) {
        selectExternal(context, profile, "", "");
    }

    static void saveScan(Context context, NavigatorPatchPipeline.ScanResult result) {
        Profile profile = result.profile;
        String sourceUri = selectedUri(context, profile);
        PackageInfo installed = installedInfo(context, profile.packageName);
        prefs(context).edit()
                .putString(profile.id + "_scan_sha", result.sha256)
                .putString(profile.id + "_scan_version", result.versionName)
                .putLong(profile.id + "_scan_version_code", result.versionCode)
                .putString(profile.id + "_scan_direct", result.directState)
                .putString(profile.id + "_scan_optional", result.optionalState)
                .putString(profile.id + "_scan_alert", result.alertState)
                .putString(profile.id + "_scan_reason", result.reason)
                .putString(profile.id + "_scan_source_uri", sourceUri == null ? "" : sourceUri)
                .putLong(profile.id + "_scan_installed_update",
                        installed == null ? -1L : installed.lastUpdateTime)
                .putInt(profile.id + "_scan_revision", SCAN_CACHE_REVISION)
                .commit();
    }

    static synchronized void claim(Context context, Profile profile, String kind,
            String phase, String detail)
            throws IOException {
        OperationSnapshot current = operation(context);
        if (current.busy() || RECOVERY_REQUIRED.equals(current.phase)) {
            throw new IOException("Another patch or recovery transaction is active");
        }
        prefs(context).edit().putString(KEY_OPERATION_KIND, kind).commit();
        transition(context, profile, phase, detail);
    }

    static synchronized void claimRecovery(
            Context context, Profile profile, String phase, String detail) throws IOException {
        OperationSnapshot current = operation(context);
        if (!RECOVERY_REQUIRED.equals(current.phase) || current.profile != profile) {
            throw new IOException("No matching recovery transaction is available");
        }
        prefs(context).edit().putString(KEY_OPERATION_KIND, OP_RECOVERY).commit();
        transition(context, profile, phase, detail);
    }

    static synchronized void claimAssetRecoveryTransaction(
            Context context, Profile profile, String recoveryOwner, File directory,
            NavigatorPatchPipeline.ScanResult expected,
            long initialUpdateTime, long initialVersionCode, String initialSigner,
            String initialFingerprint, String detail) throws IOException {
        SharedPreferences preferences = prefs(context);
        OperationSnapshot current = operation(context);
        String transactionName = directory.getName();
        boolean exactRecovery = matchesRecoveryTransaction(
                current, profile, recoveryOwner, transactionName, expected.sha256,
                preferences.getString(KEY_RECOVERY_OWNER, ""),
                preferences.getString(KEY_TRANSACTION_DIR, ""),
                preferences.getString(KEY_EXPECTED_SHA, ""));
        if (current.busy()
                || (RECOVERY_REQUIRED.equals(current.phase) && !exactRecovery)) {
            throw new IOException("Another recovery transaction is active");
        }
        boolean committed = preferences.edit()
                .putString(KEY_OPERATION_KIND, OP_RECOVERY)
                .putString(KEY_OPERATION_PROFILE, profile.id)
                .putString(KEY_OPERATION_PHASE, RECOVERY_REQUIRED)
                .putString(KEY_OPERATION_DETAIL, detail == null ? "" : detail)
                .putLong(KEY_STATE_AT, System.currentTimeMillis())
                .putString(KEY_TRANSACTION_DIR, transactionName)
                .putBoolean(KEY_DESTRUCTIVE, true)
                .putString(KEY_TRANSACTION_TOKEN, transactionName)
                .putInt(KEY_SESSION_ID, -1)
                .putString(KEY_RECOVERY_OWNER, recoveryOwner == null ? "" : recoveryOwner)
                .putString(KEY_EXPECTED_SHA, expected.sha256)
                .putLong(KEY_EXPECTED_VERSION_CODE, expected.versionCode)
                .putString(KEY_EXPECTED_SIGNER, expected.signerSha256)
                .putString(KEY_EXPECTED_DIRECT, expected.directState)
                .putString(KEY_EXPECTED_OPTIONAL, expected.optionalState)
                .putString(KEY_EXPECTED_ALERT, expected.alertState)
                .putLong(KEY_INITIAL_UPDATE_TIME, initialUpdateTime)
                .putLong(KEY_INITIAL_VERSION_CODE, initialVersionCode)
                .putString(KEY_INITIAL_SIGNER, initialSigner == null ? "" : initialSigner)
                .putString(KEY_INITIAL_FINGERPRINT,
                        initialFingerprint == null ? "" : initialFingerprint)
                .putString(KEY_EXPECTED_CALLBACK, "")
                .putBoolean(KEY_CALLBACK_CONSUMED, false)
                .commit();
        if (!committed) throw new IOException("Cannot retain navigator recovery transaction");
        AppEventLogger.event(context, "navigator_patch operation=" + OP_RECOVERY
                + " profile=" + profile.id
                + " stage=" + RECOVERY_REQUIRED + " code=RECOVERY_REQUIRED"
                + " detail=" + clean(detail));
    }

    static boolean matchesRecoveryTransaction(
            OperationSnapshot current, Profile profile,
            String recoveryOwner, String transactionName, String expectedFingerprint,
            String storedOwner, String storedTransaction, String storedFingerprint) {
        return current != null
                && RECOVERY_REQUIRED.equals(current.phase)
                && current.profile == profile
                && same(recoveryOwner, storedOwner)
                && same(transactionName, storedTransaction)
                && same(expectedFingerprint, storedFingerprint);
    }

    static synchronized void transition(
            Context context, Profile profile, String phase, String detail) {
        prefs(context).edit()
                .putString(KEY_OPERATION_PROFILE, profile == null ? "" : profile.id)
                .putString(KEY_OPERATION_PHASE, phase)
                .putString(KEY_OPERATION_DETAIL, detail == null ? "" : detail)
                .putLong(KEY_STATE_AT, System.currentTimeMillis())
                .commit();
        String operation = prefs(context).getString(KEY_OPERATION_KIND, "");
        AppEventLogger.event(context, "navigator_patch operation=" + operation
                + " profile=" + (profile == null ? "" : profile.id)
                + " stage=" + phase + " code=" + eventCode(operation, phase, detail)
                + " detail=" + clean(detail));
    }

    static synchronized boolean transitionIfPhase(Context context, Profile profile,
            String expectedPhase, String phase, String detail) {
        if (!expectedPhase.equals(prefs(context).getString(KEY_OPERATION_PHASE, IDLE))) {
            return false;
        }
        transition(context, profile, phase, detail);
        return true;
    }

    static synchronized OperationSnapshot operation(Context context) {
        SharedPreferences preferences = prefs(context);
        return new OperationSnapshot(
                Profile.fromId(preferences.getString(KEY_OPERATION_PROFILE, "")),
                preferences.getString(KEY_OPERATION_KIND, ""),
                preferences.getString(KEY_OPERATION_PHASE, IDLE),
                preferences.getString(KEY_OPERATION_DETAIL, ""),
                preferences.getBoolean(KEY_DESTRUCTIVE, false));
    }

    static synchronized void setTransaction(Context context, Profile profile, File directory,
            boolean destructive, NavigatorPatchPipeline.ScanResult expected,
            long initialUpdateTime, long initialVersionCode, String initialSigner,
            String initialFingerprint) {
        prefs(context).edit()
                .putString(KEY_OPERATION_PROFILE, profile.id)
                .putString(KEY_TRANSACTION_DIR, directory.getName())
                .putBoolean(KEY_DESTRUCTIVE, destructive)
                .putString(KEY_TRANSACTION_TOKEN, directory.getName())
                .putInt(KEY_SESSION_ID, -1)
                .putString(KEY_EXPECTED_SHA, expected.sha256)
                .putLong(KEY_EXPECTED_VERSION_CODE, expected.versionCode)
                .putString(KEY_EXPECTED_SIGNER, expected.signerSha256)
                .putString(KEY_EXPECTED_DIRECT, expected.directState)
                .putString(KEY_EXPECTED_OPTIONAL, expected.optionalState)
                .putString(KEY_EXPECTED_ALERT, expected.alertState)
                .putLong(KEY_INITIAL_UPDATE_TIME, initialUpdateTime)
                .putLong(KEY_INITIAL_VERSION_CODE, initialVersionCode)
                .putString(KEY_INITIAL_SIGNER, initialSigner == null ? "" : initialSigner)
                .putString(KEY_INITIAL_FINGERPRINT,
                        initialFingerprint == null ? "" : initialFingerprint)
                .putString(KEY_EXPECTED_CALLBACK, "")
                .putBoolean(KEY_CALLBACK_CONSUMED, false)
                .remove(KEY_RECOVERY_OWNER)
                .commit();
    }

    static synchronized void beginRestoreTransaction(
            Context context, Profile profile,
            NavigatorPatchPipeline.ScanResult expected,
            long initialUpdateTime, long initialVersionCode,
            String initialSigner, String initialFingerprint) {
        prefs(context).edit()
                .putString(KEY_OPERATION_PROFILE, profile.id)
                .putString(KEY_TRANSACTION_TOKEN, "restore-" + UUID.randomUUID())
                .putInt(KEY_SESSION_ID, -1)
                .putBoolean(KEY_DESTRUCTIVE, true)
                .putString(KEY_EXPECTED_SHA, expected.sha256)
                .putLong(KEY_EXPECTED_VERSION_CODE, expected.versionCode)
                .putString(KEY_EXPECTED_SIGNER, expected.signerSha256)
                .putString(KEY_EXPECTED_DIRECT, "")
                .putString(KEY_EXPECTED_OPTIONAL, "")
                .putString(KEY_EXPECTED_ALERT, "")
                .putLong(KEY_INITIAL_UPDATE_TIME, initialUpdateTime)
                .putLong(KEY_INITIAL_VERSION_CODE, initialVersionCode)
                .putString(KEY_INITIAL_SIGNER, initialSigner == null ? "" : initialSigner)
                .putString(KEY_INITIAL_FINGERPRINT,
                        initialFingerprint == null ? "" : initialFingerprint)
                .putString(KEY_EXPECTED_CALLBACK, "")
                .putBoolean(KEY_CALLBACK_CONSUMED, false)
                .commit();
    }

    static String transactionToken(Context context) {
        return prefs(context).getString(KEY_TRANSACTION_TOKEN, "");
    }

    static synchronized void setSessionId(Context context, int sessionId) {
        prefs(context).edit().putInt(KEY_SESSION_ID, sessionId).commit();
    }

    static int sessionId(Context context) {
        return prefs(context).getInt(KEY_SESSION_ID, -1);
    }

    static String expectedSha(Context context) {
        return prefs(context).getString(KEY_EXPECTED_SHA, "");
    }

    static long expectedVersionCode(Context context) {
        return prefs(context).getLong(KEY_EXPECTED_VERSION_CODE, -1L);
    }

    static String expectedSigner(Context context) {
        return prefs(context).getString(KEY_EXPECTED_SIGNER, "");
    }

    static String expectedDirect(Context context) {
        return prefs(context).getString(KEY_EXPECTED_DIRECT, "");
    }

    static String expectedOptional(Context context) {
        return prefs(context).getString(KEY_EXPECTED_OPTIONAL, "");
    }

    static String expectedAlert(Context context) {
        return prefs(context).getString(KEY_EXPECTED_ALERT, "");
    }

    static long initialUpdateTime(Context context) {
        return prefs(context).getLong(KEY_INITIAL_UPDATE_TIME, -1L);
    }

    static long initialVersionCode(Context context) {
        return prefs(context).getLong(KEY_INITIAL_VERSION_CODE, -1L);
    }

    static String initialSigner(Context context) {
        return prefs(context).getString(KEY_INITIAL_SIGNER, "");
    }

    static String initialFingerprint(Context context) {
        return prefs(context).getString(KEY_INITIAL_FINGERPRINT, "");
    }

    static long stateAt(Context context) {
        return prefs(context).getLong(KEY_STATE_AT, 0L);
    }

    static synchronized void expectCallback(Context context, String operation) {
        prefs(context).edit()
                .putString(KEY_EXPECTED_CALLBACK, operation)
                .putBoolean(KEY_CALLBACK_CONSUMED, false)
                .commit();
    }

    static synchronized boolean acceptCallback(Context context, Profile profile,
            String token, String operation, int callbackSessionId, boolean terminal) {
        SharedPreferences preferences = prefs(context);
        if (profile == null || token == null || operation == null
                || !token.equals(preferences.getString(KEY_TRANSACTION_TOKEN, ""))
                || profile != Profile.fromId(preferences.getString(KEY_OPERATION_PROFILE, ""))
                || !operation.equals(preferences.getString(KEY_EXPECTED_CALLBACK, ""))
                || (terminal && preferences.getBoolean(KEY_CALLBACK_CONSUMED, false))) {
            return false;
        }
        boolean install = "install_patched".equals(operation)
                || "install_original".equals(operation);
        if (install && callbackSessionId != preferences.getInt(KEY_SESSION_ID, -1)) {
            return false;
        }
        String phase = preferences.getString(KEY_OPERATION_PHASE, IDLE);
        boolean phaseMatches = install
                ? COMMITTING.equals(phase) || INSTALL_REQUESTED.equals(phase)
                : UNINSTALL_REQUESTED.equals(phase);
        if (!phaseMatches) return false;
        if (terminal) preferences.edit().putBoolean(KEY_CALLBACK_CONSUMED, true).commit();
        return true;
    }

    static File transactionDirectory(Context context) {
        String name = prefs(context).getString(KEY_TRANSACTION_DIR, "");
        return new File(new File(context.getFilesDir(), "navigator-patcher"),
                name == null || name.isEmpty() ? "unset" : name);
    }

    static String recoveryOwner(Context context) {
        return prefs(context).getString(KEY_RECOVERY_OWNER, "");
    }

    static String installedIdentity(Context context, Profile profile) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(
                profile.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        ApplicationInfo applicationInfo = info.applicationInfo;
        if (applicationInfo == null || applicationInfo.sourceDir == null) {
            throw new IOException("Installed APK path is unavailable");
        }
        StringBuilder identity = new StringBuilder()
                .append(info.lastUpdateTime).append('|')
                .append(info.getLongVersionCode()).append('|')
                .append(info.versionName == null ? "" : info.versionName).append('|')
                .append(NavigatorSigningKey.installedCertificateSha256(
                        context, profile.packageName));
        appendFileIdentity(identity, applicationInfo.sourceDir);
        if (applicationInfo.splitSourceDirs != null) {
            for (String path : applicationInfo.splitSourceDirs) {
                appendFileIdentity(identity, path);
            }
        }
        return identity.toString();
    }

    static File originalSet(Context context) {
        return new File(transactionDirectory(context), "source-set");
    }

    static synchronized void clearTransactionMetadata(Context context) {
        prefs(context).edit()
                .remove(KEY_TRANSACTION_DIR)
                .remove(KEY_DESTRUCTIVE)
                .remove(KEY_SESSION_ID)
                .remove(KEY_TRANSACTION_TOKEN)
                .remove(KEY_RECOVERY_OWNER)
                .remove(KEY_EXPECTED_SHA)
                .remove(KEY_EXPECTED_VERSION_CODE)
                .remove(KEY_EXPECTED_SIGNER)
                .remove(KEY_EXPECTED_DIRECT)
                .remove(KEY_EXPECTED_OPTIONAL)
                .remove(KEY_EXPECTED_ALERT)
                .remove(KEY_INITIAL_UPDATE_TIME)
                .remove(KEY_INITIAL_VERSION_CODE)
                .remove(KEY_INITIAL_SIGNER)
                .remove(KEY_INITIAL_FINGERPRINT)
                .remove(KEY_EXPECTED_CALLBACK)
                .remove(KEY_CALLBACK_CONSUMED)
                .remove(KEY_OPERATION_KIND)
                .commit();
    }

    static synchronized void completeRestoreTransaction(
            Context context, Profile profile, String detail) throws IOException {
        boolean committed = prefs(context).edit()
                .putString(KEY_OPERATION_PROFILE, profile.id)
                .putString(KEY_OPERATION_PHASE, IDLE)
                .putString(KEY_OPERATION_DETAIL, detail == null ? "" : detail)
                .putLong(KEY_STATE_AT, System.currentTimeMillis())
                .remove(KEY_TRANSACTION_DIR)
                .remove(KEY_DESTRUCTIVE)
                .remove(KEY_SESSION_ID)
                .remove(KEY_TRANSACTION_TOKEN)
                .remove(KEY_RECOVERY_OWNER)
                .remove(KEY_EXPECTED_SHA)
                .remove(KEY_EXPECTED_VERSION_CODE)
                .remove(KEY_EXPECTED_SIGNER)
                .remove(KEY_EXPECTED_DIRECT)
                .remove(KEY_EXPECTED_OPTIONAL)
                .remove(KEY_EXPECTED_ALERT)
                .remove(KEY_INITIAL_UPDATE_TIME)
                .remove(KEY_INITIAL_VERSION_CODE)
                .remove(KEY_INITIAL_SIGNER)
                .remove(KEY_INITIAL_FINGERPRINT)
                .remove(KEY_EXPECTED_CALLBACK)
                .remove(KEY_CALLBACK_CONSUMED)
                .remove(KEY_OPERATION_KIND)
                .commit();
        if (!committed) throw new IOException("Cannot complete navigator recovery transaction");
        AppEventLogger.event(context, "navigator_patch operation=" + OP_RECOVERY
                + " profile=" + profile.id + " stage=" + IDLE
                + " code=" + eventCode(OP_RECOVERY, IDLE, detail)
                + " detail=" + clean(detail));
    }

    static void recordInstalledVerification(
            Context context, Profile profile, long updateTime, long versionCode,
            String sha256, String directState, String optionalState, String alertState) {
        prefs(context).edit()
                .putLong(profile.id + "_installed_update", updateTime)
                .putLong(profile.id + "_installed_version_code", versionCode)
                .putString(profile.id + "_installed_sha", sha256)
                .putString(profile.id + "_installed_direct", directState)
                .putString(profile.id + "_installed_optional", optionalState)
                .putString(profile.id + "_installed_alert", alertState)
                .commit();
    }

    static boolean isInstalledWazeLifecycleV2(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    Profile.WAZE.packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SharedPreferences preferences = prefs(context);
            String signer = NavigatorSigningKey.installedCertificateSha256(
                    context, Profile.WAZE.packageName);
            boolean localVerificationCurrent =
                    PATCHED.equals(preferences.getString("waze_installed_optional", ""))
                    && preferences.getLong("waze_installed_update", -1L) == info.lastUpdateTime
                    && preferences.getLong("waze_installed_version_code", -1L)
                    == info.getLongVersionCode();
            return NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                    signer,
                    NavigatorAssetManager.isInstalledCanonicalWazeCached(context),
                    NavigatorSigningKey.certificateMatchesLocalIfPresent(signer),
                    localVerificationCurrent).accepted;
        } catch (Exception ignored) {
            return false;
        }
    }

    static ProfileSnapshot snapshot(Context context, Profile profile) {
        PackageInfo installed = installedInfo(context, profile.packageName);
        boolean isInstalled = installed != null;
        String label = profile.fallbackLabel;
        String installedVersion = "";
        long installedCode = -1L;
        if (installed != null) {
            installedVersion = installed.versionName == null ? "" : installed.versionName;
            installedCode = installed.getLongVersionCode();
            ApplicationInfo application = installed.applicationInfo;
            if (application != null) {
                CharSequence value = context.getPackageManager().getApplicationLabel(application);
                if (value != null && value.length() > 0) label = value.toString();
            }
        }
        SharedPreferences preferences = prefs(context);
        String uri = selectedUri(context, profile);
        boolean external = uri != null && !uri.isEmpty();
        String sourceName = external
                ? preferences.getString(profile.id + "_selected_name", "")
                : isInstalled ? label : "";
        String sourceVersion = preferences.getString(profile.id + "_scan_version", "");
        long sourceCode = preferences.getLong(profile.id + "_scan_version_code", -1L);
        if (external && sourceVersion.isEmpty()) {
            sourceVersion = preferences.getString(profile.id + "_selected_version", "");
            sourceCode = preferences.getLong(profile.id + "_selected_version_code", -1L);
        }
        String direct = preferences.getString(profile.id + "_scan_direct", NOT_CHECKED);
        String optional = preferences.getString(profile.id + "_scan_optional", NOT_CHECKED);
        String alert = preferences.getString(profile.id + "_scan_alert", NOT_CHECKED);
        String reason = preferences.getString(profile.id + "_scan_reason", "");
        String scannedUri = preferences.getString(profile.id + "_scan_source_uri", "");
        long scannedInstalledUpdate = preferences.getLong(
                profile.id + "_scan_installed_update", -1L);
        boolean currentScan = external
                ? uri.equals(scannedUri)
                : isInstalled && (scannedUri == null || scannedUri.isEmpty())
                && scannedInstalledUpdate == installed.lastUpdateTime;
        currentScan &= preferences.getInt(profile.id + "_scan_revision", 0)
                == SCAN_CACHE_REVISION;
        if (!currentScan) {
            sourceVersion = "";
            sourceCode = -1L;
            direct = NOT_CHECKED;
            optional = NOT_CHECKED;
            alert = NOT_CHECKED;
            reason = "";
        }
        if (!external && !isInstalled) {
            direct = NOT_CHECKED;
            optional = NOT_CHECKED;
            alert = NOT_CHECKED;
            reason = "";
        }
        boolean patchEnabled = PATCHABLE.equals(direct)
                || (PATCHED.equals(direct)
                && (PATCHABLE.equals(optional) || PATCHABLE.equals(alert)));
        return new ProfileSnapshot(profile, isInstalled, label, installedVersion, installedCode,
                external, sourceName == null ? "" : sourceName,
                sourceVersion == null ? "" : sourceVersion, sourceCode,
                direct, optional, alert, reason == null ? "" : reason, patchEnabled);
    }

    private static PackageInfo installedInfo(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ');
        return result.length() > 320 ? result.substring(0, 320) : result;
    }

    private static boolean same(String first, String second) {
        return (first == null ? "" : first).equals(second == null ? "" : second);
    }

    private static void appendFileIdentity(StringBuilder identity, String path) {
        File file = new File(path == null ? "" : path);
        identity.append('|').append(path == null ? "" : path)
                .append('|').append(file.length())
                .append('|').append(file.lastModified());
    }

    private static String eventCode(String operation, String phase, String detail) {
        String value = clean(detail).toLowerCase(java.util.Locale.ROOT);
        if (RECOVERY_REQUIRED.equals(phase)) return "RECOVERY_REQUIRED";
        if (value.contains("trust_profile_required")) return "TRUST_PROFILE_REQUIRED";
        if (value.contains("trust_signer_missing")) return "TRUST_SIGNER_MISSING";
        if (value.contains("trust_unknown_signer")) return "TRUST_UNKNOWN_SIGNER";
        if (value.contains("trust_local_signer_unpatched")) {
            return "TRUST_LOCAL_SIGNER_UNPATCHED";
        }
        if (value.contains("trust_gmaps_morphe_artifact_mismatch")) {
            return "TRUST_GMAPS_MORPHE_ARTIFACT_MISMATCH";
        }
        if (value.contains("trust_")) return "TRUST_REJECTED";
        if (value.contains("unsupported format")) return "UNSUPPORTED_SOURCE_FORMAT";
        if (value.contains("package") && (value.contains("mismatch")
                || value.contains("expected="))) return "PACKAGE_MISMATCH";
        if (value.contains("older") || value.contains("downgrade")) return "VERSION_DOWNGRADE";
        if (value.contains("signer") || value.contains("signature")) return "SIGNATURE_INVALID";
        if (value.contains("insufficient storage")) return "INSUFFICIENT_STORAGE";
        if (value.contains("too many") || value.contains("exceeds")
                || value.contains("too large") || value.contains("unsafe path")) {
            return "ARCHIVE_LIMIT_EXCEEDED";
        }
        if (value.contains("feature") || value.contains("unknown split")
                || value.contains("unknown configuration")) {
            return "UNSUPPORTED_SPLIT_TOPOLOGY";
        }
        if (value.contains("ambiguous") || value.contains("multiple base")
                || value.contains("duplicate split")) return "APK_SET_AMBIGUOUS";
        if (value.contains("manifest") || value.contains("no apk")
                || value.contains("empty")) return "ARCHIVE_INVALID";
        if (value.contains("mandatory") || value.contains("direct channel")) {
            return "MANDATORY_INCOMPATIBLE";
        }
        if (value.contains("audio channel") || value.contains("stable session")
                || value.contains("waze alert")
                || value.contains("optional")) return "OPTIONAL_INCOMPATIBLE";
        if (OUTPUT_VERIFY.equals(phase)) return "OUTPUT_VERIFY_FAILED";
        if (FAILED.equals(phase)) {
            if (OP_SELECT.equals(operation)) return "SOURCE_SELECTION_FAILED";
            if (OP_CHECK.equals(operation)) return "CHECK_FAILED";
            if (OP_PATCH.equals(operation)) return "PATCH_FAILED";
            if (OP_RECOVERY.equals(operation)) return "RECOVERY_FAILED";
            return "OPERATION_FAILED";
        }
        return "STATE";
    }
}
