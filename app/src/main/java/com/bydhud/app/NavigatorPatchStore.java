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
    // Invalidate cached results whenever component structural classification changes.
    private static final int SCAN_CACHE_REVISION = 10;
    static final String NOT_CHECKED = "NOT_CHECKED";
    static final String PATCHABLE = "PATCHABLE";
    static final String PATCHED = "PATCHED";
    static final String FAILED = "FAILED";

    static final String IDLE = "IDLE";
    static final String COPYING = "COPYING";
    static final String WAITING_FOR_PATCHER = "WAITING_FOR_PATCHER";
    static final String VERIFYING = "VERIFYING";
    static final String SCANNING = "SCANNING";
    static final String PATCHING = "PATCHING";
    static final String REPACKING = "REPACKING";
    static final String SIGNING = "SIGNING";
    static final String OUTPUT_VERIFY = "OUTPUT_VERIFY";
    static final String INSTALLED_VERIFY = "INSTALLED_VERIFY";
    static final String READY_TO_INSTALL = "READY_TO_INSTALL";
    static final String AWAITING_PERMISSION = "AWAITING_PERMISSION";
    static final String INSTALL_PREPARING = "INSTALL_PREPARING";
    static final String UNINSTALL_REQUESTED = "UNINSTALL_REQUESTED";
    static final String COMMITTING = "COMMITTING";
    static final String INSTALL_REQUESTED = "INSTALL_REQUESTED";
    static final String VERIFIED = "VERIFIED";
    static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
    static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    static final String CANCELLED = "CANCELLED";
    static final String OP_SELECT = "SELECT";
    static final String OP_CHECK = "CHECK";
    static final String OP_PATCH = "PATCH";
    static final String OP_RECOVERY = "RECOVERY";

    private static final String PREFS = "navigator_patcher";
    private static final String KEY_OPERATION_PROFILE = "operation_profile";
    private static final String KEY_OPERATION_PHASE = "operation_phase";
    private static final String KEY_OPERATION_DETAIL = "operation_detail";
    private static final String KEY_OPERATION_KIND = "operation_kind";
    private static final String KEY_OPERATION_TOKEN = "operation_token";
    private static final String KEY_OPERATION_PROGRESS = "operation_progress";
    private static final String KEY_OPERATION_ERROR = "operation_error";
    private static final String KEY_OPERATION_STARTED_AT = "operation_started_at";
    private static final String KEY_READY_AT = "ready_at";
    private static final String KEY_CANCEL_REQUESTED = "cancel_requested";
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
    private static final String KEY_EXPECTED_GMS_CORE = "expected_gms_core";
    private static final String KEY_EXPECTED_OPTIONAL = "expected_optional";
    private static final String KEY_EXPECTED_ALERT = "expected_alert";
    private static final String KEY_INITIAL_UPDATE_TIME = "initial_update_time";
    private static final String KEY_INITIAL_VERSION_CODE = "initial_version_code";
    private static final String KEY_INITIAL_SIGNER = "initial_signer";
    private static final String KEY_INITIAL_FINGERPRINT = "initial_fingerprint";
    private static final String KEY_EXPECTED_CALLBACK = "expected_callback";
    private static final String KEY_CALLBACK_CONSUMED = "callback_consumed";
    private static final String KEY_INSTALL_OWNER = "install_owner";
    private static final String KEY_INSTALL_OWNER_TOKEN = "install_owner_token";

    enum Profile {
        WAZE("waze", "com.waze", "Waze", "Lanes", "Stable session", "Waze alerts"),
        GMAPS("gmaps", "app.revanced.android.apps.maps", "Google Maps (ReVanced)",
                "GmsCore", "Audio channel", "PiP");

        final String id;
        final String packageName;
        final String fallbackLabel;
        // Shared second component: Waze lanes or Google Maps GmsCore.
        final String gmsCoreLabel;
        final String optionalLabel;
        final String alertLabel;

        Profile(String id, String packageName, String fallbackLabel,
                String gmsCoreLabel, String optionalLabel, String alertLabel) {
            this.id = id;
            this.packageName = packageName;
            this.fallbackLabel = fallbackLabel;
            this.gmsCoreLabel = gmsCoreLabel;
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
        final String gmsCoreState;
        final String optionalState;
        final String alertState;
        final String reason;
        final boolean patchEnabled;

        ProfileSnapshot(Profile profile, boolean installed, String label,
                String installedVersion, long installedVersionCode,
                boolean externalSource, String sourceName, String sourceVersion,
                long sourceVersionCode, String directState, String gmsCoreState,
                String optionalState, String alertState,
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
            this.gmsCoreState = gmsCoreState;
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
        final String operationToken;
        final long startedAt;
        final int progress;
        final String error;
        final long readyAt;
        final boolean cancelRequested;
        final boolean destructive;

        OperationSnapshot(Profile profile, String kind, String phase,
                String detail, String operationToken, long startedAt, int progress, String error,
                long readyAt, boolean cancelRequested, boolean destructive) {
            this.profile = profile;
            this.kind = kind;
            this.phase = phase;
            this.detail = detail;
            this.operationToken = operationToken;
            this.startedAt = startedAt;
            this.progress = progress;
            this.error = error;
            this.readyAt = readyAt;
            this.cancelRequested = cancelRequested;
            this.destructive = destructive;
        }

        OperationSnapshot(Profile profile, String kind, String phase,
                String detail, boolean destructive) {
            this(profile, kind, phase, detail, "", 0L, 0, "", 0L, false, destructive);
        }

        boolean busy() {
            return !IDLE.equals(phase) && !VERIFIED.equals(phase)
                    && !FAILED.equals(phase) && !CANCELLED.equals(phase)
                    && !RECOVERY_REQUIRED.equals(phase);
        }

        boolean terminal() {
            return IDLE.equals(phase) || VERIFIED.equals(phase)
                    || FAILED.equals(phase) || CANCELLED.equals(phase)
                    || RECOVERY_REQUIRED.equals(phase);
        }
    }

    private NavigatorPatchStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String profileKey(Profile profile, String key) {
        return profile.id + "_" + key;
    }

    private static boolean localKind(String kind) {
        return OP_SELECT.equals(kind) || OP_CHECK.equals(kind) || OP_PATCH.equals(kind);
    }

    private static String operationKind(Context context, Profile profile) {
        return prefs(context).getString(profileKey(profile, KEY_OPERATION_KIND), "");
    }

    private static boolean localOperation(Context context, Profile profile) {
        return profile != null && localKind(operationKind(context, profile));
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
                .remove(profile.id + "_scan_gms_core")
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
                .putString(profile.id + "_scan_gms_core", result.gmsCoreState)
                .putString(profile.id + "_scan_optional", result.optionalState)
                .putString(profile.id + "_scan_alert", result.alertState)
                .putString(profile.id + "_scan_reason", result.reason)
                .putString(profile.id + "_scan_source_uri", sourceUri == null ? "" : sourceUri)
                .putLong(profile.id + "_scan_installed_update",
                        installed == null ? -1L : installed.lastUpdateTime)
                .putInt(profile.id + "_scan_revision", SCAN_CACHE_REVISION)
                .commit();
    }

    static synchronized boolean completeScanUnlessCancelled(Context context,
            NavigatorPatchPipeline.ScanResult result, String phase, String detail) {
        Profile profile = result.profile;
        if (isCancellationRequested(context, profile)
                || CANCEL_REQUESTED.equals(operation(context, profile).phase)) {
            return false;
        }
        saveScan(context, result);
        transitionLocal(context, profile, phase, detail, -1);
        return true;
    }

    static synchronized void claim(Context context, Profile profile, String kind,
            String phase, String detail)
            throws IOException {
        if (profile == null) throw new IOException("Patch profile is required");
        if (localKind(kind)) {
            OperationSnapshot current = operation(context, profile);
            if (current.busy() || RECOVERY_REQUIRED.equals(current.phase)) {
                throw new IOException("Another operation for this navigator is active");
            }
            File previousTransaction = localOperation(context, profile)
                    ? transactionDirectory(context, profile) : null;
            clearTransactionMetadata(context, profile);
            deleteTreeQuietly(previousTransaction);
            long startedAt = nextStartedAt(context);
            prefs(context).edit()
                    .putString(profileKey(profile, KEY_OPERATION_KIND), kind)
                    .putString(profileKey(profile, KEY_OPERATION_TOKEN), UUID.randomUUID().toString())
                    .putLong(profileKey(profile, KEY_OPERATION_STARTED_AT), startedAt)
                    .putInt(profileKey(profile, KEY_OPERATION_PROGRESS), 0)
                    .putString(profileKey(profile, KEY_OPERATION_ERROR), "")
                    .putLong(profileKey(profile, KEY_READY_AT), 0L)
                    .putBoolean(profileKey(profile, KEY_CANCEL_REQUESTED), false)
                    .putBoolean(profileKey(profile, KEY_DESTRUCTIVE), false)
                    .commit();
            transition(context, profile, phase, detail);
            return;
        }
        OperationSnapshot current = globalOperation(context);
        if (current.busy() || RECOVERY_REQUIRED.equals(current.phase)) {
            throw new IOException("Another patch or recovery transaction is active");
        }
        prefs(context).edit().putString(KEY_OPERATION_KIND, kind).commit();
        transition(context, profile, phase, detail);
    }

    static synchronized void claimRecovery(
            Context context, Profile profile, String phase, String detail) throws IOException {
        OperationSnapshot current = globalOperation(context);
        OperationSnapshot local = operation(context, profile);
        if (RECOVERY_REQUIRED.equals(local.phase) && current.profile == null) {
            if (!prefs(context).getString(KEY_INSTALL_OWNER, "").isEmpty()) {
                throw new IOException("Another navigator install is active");
            }
            promoteProfileRecovery(context, profile, local);
            current = globalOperation(context);
        }
        if (!RECOVERY_REQUIRED.equals(current.phase) || current.profile != profile) {
            throw new IOException("No matching recovery transaction is available");
        }
        prefs(context).edit()
                .putString(KEY_OPERATION_KIND, OP_RECOVERY)
                .putLong(KEY_OPERATION_STARTED_AT, Math.max(System.currentTimeMillis(),
                        prefs(context).getLong(KEY_OPERATION_STARTED_AT, 0L) + 1L))
                .commit();
        transition(context, profile, phase, detail);
    }

    private static void promoteProfileRecovery(Context context, Profile profile,
            OperationSnapshot local) {
        SharedPreferences preferences = prefs(context);
        preferences.edit()
                .putString(KEY_OPERATION_KIND, OP_RECOVERY)
                .putString(KEY_OPERATION_PROFILE, profile.id)
                .putString(KEY_OPERATION_PHASE, RECOVERY_REQUIRED)
                .putString(KEY_OPERATION_DETAIL, local.error)
                .putString(KEY_TRANSACTION_DIR,
                        preferences.getString(profileKey(profile, KEY_TRANSACTION_DIR), ""))
                .putBoolean(KEY_DESTRUCTIVE,
                        preferences.getBoolean(profileKey(profile, KEY_DESTRUCTIVE), true))
                .putString(KEY_TRANSACTION_TOKEN,
                        preferences.getString(profileKey(profile, KEY_TRANSACTION_TOKEN), ""))
                .putInt(KEY_SESSION_ID,
                        preferences.getInt(profileKey(profile, KEY_SESSION_ID), -1))
                .putString(KEY_EXPECTED_SHA,
                        preferences.getString(profileKey(profile, KEY_EXPECTED_SHA), ""))
                .putLong(KEY_EXPECTED_VERSION_CODE,
                        preferences.getLong(profileKey(profile, KEY_EXPECTED_VERSION_CODE), -1L))
                .putString(KEY_EXPECTED_SIGNER,
                        preferences.getString(profileKey(profile, KEY_EXPECTED_SIGNER), ""))
                .putString(KEY_EXPECTED_DIRECT,
                        preferences.getString(profileKey(profile, KEY_EXPECTED_DIRECT), ""))
                .putString(KEY_EXPECTED_GMS_CORE,
                        preferences.getString(profileKey(profile, KEY_EXPECTED_GMS_CORE), ""))
                .putString(KEY_EXPECTED_OPTIONAL,
                        preferences.getString(profileKey(profile, KEY_EXPECTED_OPTIONAL), ""))
                .putString(KEY_EXPECTED_ALERT,
                        preferences.getString(profileKey(profile, KEY_EXPECTED_ALERT), ""))
                .putLong(KEY_INITIAL_UPDATE_TIME,
                        preferences.getLong(profileKey(profile, KEY_INITIAL_UPDATE_TIME), -1L))
                .putLong(KEY_INITIAL_VERSION_CODE,
                        preferences.getLong(profileKey(profile, KEY_INITIAL_VERSION_CODE), -1L))
                .putString(KEY_INITIAL_SIGNER,
                        preferences.getString(profileKey(profile, KEY_INITIAL_SIGNER), ""))
                .putString(KEY_INITIAL_FINGERPRINT,
                        preferences.getString(profileKey(profile, KEY_INITIAL_FINGERPRINT), ""))
                .putLong(KEY_OPERATION_STARTED_AT, local.startedAt)
                .putString(profileKey(profile, KEY_OPERATION_KIND), OP_RECOVERY)
                .putLong(KEY_STATE_AT, System.currentTimeMillis())
                .commit();
    }

    static synchronized void claimAssetRecoveryTransaction(
            Context context, Profile profile, String recoveryOwner, File directory,
            NavigatorPatchPipeline.ScanResult expected,
            long initialUpdateTime, long initialVersionCode, String initialSigner,
            String initialFingerprint, String detail) throws IOException {
        SharedPreferences preferences = prefs(context);
        OperationSnapshot current = globalOperation(context);
        String transactionName = directory.getName();
        boolean exactRecovery = matchesRecoveryTransaction(
                current, profile, recoveryOwner, transactionName, expected.sha256,
                preferences.getString(KEY_RECOVERY_OWNER, ""),
                preferences.getString(KEY_TRANSACTION_DIR, ""),
                preferences.getString(KEY_EXPECTED_SHA, ""));
        if (current.busy()
                || (RECOVERY_REQUIRED.equals(current.phase) && !exactRecovery)
                || (!preferences.getString(KEY_INSTALL_OWNER, "").isEmpty()
                && !exactRecovery)) {
            throw new IOException("Another recovery transaction is active");
        }
        boolean committed = preferences.edit()
                .putString(KEY_OPERATION_KIND, OP_RECOVERY)
                .putString(KEY_OPERATION_PROFILE, profile.id)
                .putString(KEY_OPERATION_PHASE, RECOVERY_REQUIRED)
                .putString(KEY_OPERATION_DETAIL, detail == null ? "" : detail)
                .putLong(KEY_STATE_AT, System.currentTimeMillis())
                .putLong(KEY_OPERATION_STARTED_AT, Math.max(System.currentTimeMillis(),
                        preferences.getLong(KEY_OPERATION_STARTED_AT, 0L) + 1L))
                .putString(KEY_TRANSACTION_DIR, transactionName)
                .putBoolean(KEY_DESTRUCTIVE, true)
                .putString(KEY_TRANSACTION_TOKEN, transactionName)
                .putInt(KEY_SESSION_ID, -1)
                .putString(KEY_RECOVERY_OWNER, recoveryOwner == null ? "" : recoveryOwner)
                .putString(KEY_EXPECTED_SHA, expected.sha256)
                .putLong(KEY_EXPECTED_VERSION_CODE, expected.versionCode)
                .putString(KEY_EXPECTED_SIGNER, expected.signerSha256)
                .putString(KEY_EXPECTED_DIRECT, expected.directState)
                .putString(KEY_EXPECTED_GMS_CORE, expected.gmsCoreState)
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
        if (profile != null && localOperation(context, profile)) {
            transitionLocal(context, profile, phase, detail, -1);
            return;
        }
        prefs(context).edit()
                .putString(KEY_OPERATION_PROFILE, profile == null ? "" : profile.id)
                .putString(KEY_OPERATION_PHASE, phase)
                .putString(KEY_OPERATION_DETAIL, detail == null ? "" : detail)
                .putString(KEY_OPERATION_ERROR,
                        FAILED.equals(phase) || CANCELLED.equals(phase)
                                || RECOVERY_REQUIRED.equals(phase)
                                ? (detail == null ? "" : detail) : "")
                .putLong(KEY_STATE_AT, System.currentTimeMillis())
                .commit();
        String operation = prefs(context).getString(KEY_OPERATION_KIND, "");
        AppEventLogger.event(context, "navigator_patch operation=" + operation
                + " profile=" + (profile == null ? "" : profile.id)
                + " stage=" + phase + " code=" + eventCode(operation, phase, detail)
                + " detail=" + clean(detail));
        MainActivity.publishSharedUiStateChange();
    }

    static synchronized void transitionProgress(Context context, Profile profile,
            String phase, int progress, String detail) {
        if (profile != null && localOperation(context, profile)) {
            transitionLocal(context, profile, phase, detail, progress);
        } else {
            transition(context, profile, phase, detail);
        }
    }

    private static void transitionLocal(Context context, Profile profile,
            String phase, String detail, int progress) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(profileKey(profile, KEY_OPERATION_PHASE), phase)
                .putString(profileKey(profile, KEY_OPERATION_DETAIL), detail == null ? "" : detail)
                .putString(profileKey(profile, KEY_OPERATION_ERROR),
                        FAILED.equals(phase) || CANCELLED.equals(phase)
                                || RECOVERY_REQUIRED.equals(phase)
                                ? (detail == null ? "" : detail) : "")
                .putLong(profileKey(profile, KEY_STATE_AT), System.currentTimeMillis());
        if (progress >= 0) editor.putInt(profileKey(profile, KEY_OPERATION_PROGRESS),
                Math.max(0, Math.min(100, progress)));
        if (READY_TO_INSTALL.equals(phase)
                && preferences.getLong(profileKey(profile, KEY_READY_AT), 0L) <= 0L) {
            editor.putLong(profileKey(profile, KEY_READY_AT), nextReadyAt(context));
        }
        if (!CANCEL_REQUESTED.equals(phase)) {
            editor.putBoolean(profileKey(profile, KEY_CANCEL_REQUESTED), false);
        }
        editor.commit();
        String operation = operationKind(context, profile);
        AppEventLogger.event(context, "navigator_patch operation=" + operation
                + " profile=" + profile.id + " stage=" + phase
                + " code=" + eventCode(operation, phase, detail)
                + " detail=" + clean(detail));
        MainActivity.publishSharedUiStateChange();
        if (FAILED.equals(phase) || CANCELLED.equals(phase)
                || RECOVERY_REQUIRED.equals(phase)) {
            requestInstallDrain(context);
        }
    }

    static synchronized boolean transitionIfPhase(Context context, Profile profile,
            String expectedPhase, String phase, String detail) {
        if (!expectedPhase.equals(operation(context, profile).phase)) {
            return false;
        }
        transition(context, profile, phase, detail);
        return true;
    }

    static synchronized OperationSnapshot operation(Context context) {
        OperationSnapshot global = globalOperation(context);
        if (!global.terminal() || !global.kind.isEmpty()) return global;
        OperationSnapshot newest = null;
        for (Profile profile : Profile.values()) {
            OperationSnapshot candidate = operation(context, profile);
            if (candidate.kind.isEmpty()) continue;
            if (newest == null || candidate.startedAt > newest.startedAt) newest = candidate;
        }
        return newest == null ? global : newest;
    }

    static synchronized OperationSnapshot operation(Context context, Profile profile) {
        if (profile == null) return globalOperation(context);
        SharedPreferences preferences = prefs(context);
        String kind = operationKind(context, profile);
        if (localKind(kind)) {
            return new OperationSnapshot(profile, kind,
                    preferences.getString(profileKey(profile, KEY_OPERATION_PHASE), IDLE),
                    preferences.getString(profileKey(profile, KEY_OPERATION_DETAIL), ""),
                    preferences.getString(profileKey(profile, KEY_OPERATION_TOKEN), ""),
                    preferences.getLong(profileKey(profile, KEY_OPERATION_STARTED_AT), 0L),
                    preferences.getInt(profileKey(profile, KEY_OPERATION_PROGRESS), 0),
                    preferences.getString(profileKey(profile, KEY_OPERATION_ERROR), ""),
                    preferences.getLong(profileKey(profile, KEY_READY_AT), 0L),
                    preferences.getBoolean(profileKey(profile, KEY_CANCEL_REQUESTED), false),
                    preferences.getBoolean(profileKey(profile, KEY_DESTRUCTIVE), false));
        }
        OperationSnapshot global = globalOperation(context);
        return global.profile == profile ? global : new OperationSnapshot(
                profile, "", IDLE, "", "", 0L, 0, "", 0L, false, false);
    }

    static synchronized OperationSnapshot[] operations(Context context) {
        OperationSnapshot[] result = new OperationSnapshot[Profile.values().length];
        for (int i = 0; i < Profile.values().length; i++) {
            result[i] = operation(context, Profile.values()[i]);
        }
        return result;
    }

    private static synchronized OperationSnapshot globalOperation(Context context) {
        SharedPreferences preferences = prefs(context);
        return new OperationSnapshot(
                Profile.fromId(preferences.getString(KEY_OPERATION_PROFILE, "")),
                preferences.getString(KEY_OPERATION_KIND, ""),
                preferences.getString(KEY_OPERATION_PHASE, IDLE),
                preferences.getString(KEY_OPERATION_DETAIL, ""),
                preferences.getString(KEY_TRANSACTION_TOKEN, ""),
                preferences.getLong(KEY_OPERATION_STARTED_AT, 0L),
                preferences.getInt(KEY_OPERATION_PROGRESS, 0),
                preferences.getString(KEY_OPERATION_ERROR, ""),
                preferences.getLong(KEY_READY_AT, 0L),
                preferences.getBoolean(KEY_CANCEL_REQUESTED, false),
                preferences.getBoolean(KEY_DESTRUCTIVE, false));
    }

    private static long nextStartedAt(Context context) {
        SharedPreferences preferences = prefs(context);
        long next = Math.max(System.currentTimeMillis(),
                preferences.getLong(KEY_OPERATION_STARTED_AT, 0L) + 1L);
        for (Profile candidate : Profile.values()) {
            next = Math.max(next, preferences.getLong(
                    profileKey(candidate, KEY_OPERATION_STARTED_AT), 0L) + 1L);
        }
        return next;
    }

    private static long nextReadyAt(Context context) {
        SharedPreferences preferences = prefs(context);
        long next = System.currentTimeMillis();
        for (Profile candidate : Profile.values()) {
            next = Math.max(next,
                    preferences.getLong(profileKey(candidate, KEY_READY_AT), 0L) + 1L);
        }
        return next;
    }

    static synchronized boolean setTransaction(Context context, Profile profile, File directory,
            boolean destructive, NavigatorPatchPipeline.ScanResult expected,
            long initialUpdateTime, long initialVersionCode, String initialSigner,
            String initialFingerprint, String readyDetail) throws IOException {
        if (isCancellationRequested(context, profile)
                || CANCEL_REQUESTED.equals(operation(context, profile).phase)) {
            return false;
        }
        boolean committed = prefs(context).edit()
                .putString(profileKey(profile, KEY_TRANSACTION_DIR), directory.getName())
                .putBoolean(profileKey(profile, KEY_DESTRUCTIVE), destructive)
                .putString(profileKey(profile, KEY_TRANSACTION_TOKEN), directory.getName())
                .putInt(profileKey(profile, KEY_SESSION_ID), -1)
                .putString(profileKey(profile, KEY_EXPECTED_SHA), expected.sha256)
                .putLong(profileKey(profile, KEY_EXPECTED_VERSION_CODE), expected.versionCode)
                .putString(profileKey(profile, KEY_EXPECTED_SIGNER), expected.signerSha256)
                .putString(profileKey(profile, KEY_EXPECTED_DIRECT), expected.directState)
                .putString(profileKey(profile, KEY_EXPECTED_GMS_CORE), expected.gmsCoreState)
                .putString(profileKey(profile, KEY_EXPECTED_OPTIONAL), expected.optionalState)
                .putString(profileKey(profile, KEY_EXPECTED_ALERT), expected.alertState)
                .putLong(profileKey(profile, KEY_INITIAL_UPDATE_TIME), initialUpdateTime)
                .putLong(profileKey(profile, KEY_INITIAL_VERSION_CODE), initialVersionCode)
                .putString(profileKey(profile, KEY_INITIAL_SIGNER), initialSigner == null ? "" : initialSigner)
                .putString(profileKey(profile, KEY_INITIAL_FINGERPRINT),
                        initialFingerprint == null ? "" : initialFingerprint)
                .putString(profileKey(profile, KEY_EXPECTED_CALLBACK), "")
                .putBoolean(profileKey(profile, KEY_CALLBACK_CONSUMED), false)
                .commit();
        if (!committed) throw new IOException("Cannot publish navigator patch transaction");
        transitionLocal(context, profile, READY_TO_INSTALL, readyDetail, 100);
        return true;
    }

    static synchronized void setTransactionDirectory(Context context, Profile profile,
            File directory) {
        if (profile == null || directory == null) return;
        prefs(context).edit()
                .putString(profileKey(profile, KEY_TRANSACTION_DIR), directory.getName())
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
                .putString(KEY_EXPECTED_GMS_CORE, "")
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

    static String transactionToken(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? transactionToken(context)
                : prefs(context).getString(profileKey(profile, KEY_TRANSACTION_TOKEN), "");
    }

    static synchronized void setSessionId(Context context, int sessionId) {
        prefs(context).edit().putInt(KEY_SESSION_ID, sessionId).commit();
    }

    static synchronized void setSessionId(Context context, Profile profile, int sessionId) {
        if (profile == null || !localOperation(context, profile)) setSessionId(context, sessionId);
        else prefs(context).edit().putInt(profileKey(profile, KEY_SESSION_ID), sessionId).commit();
    }

    static int sessionId(Context context) {
        return prefs(context).getInt(KEY_SESSION_ID, -1);
    }

    static int sessionId(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? sessionId(context)
                : prefs(context).getInt(profileKey(profile, KEY_SESSION_ID), -1);
    }

    static String expectedSha(Context context) {
        return prefs(context).getString(KEY_EXPECTED_SHA, "");
    }

    static String expectedSha(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedSha(context)
                : prefs(context).getString(profileKey(profile, KEY_EXPECTED_SHA), "");
    }

    static long expectedVersionCode(Context context) {
        return prefs(context).getLong(KEY_EXPECTED_VERSION_CODE, -1L);
    }

    static long expectedVersionCode(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedVersionCode(context)
                : prefs(context).getLong(profileKey(profile, KEY_EXPECTED_VERSION_CODE), -1L);
    }

    static String expectedSigner(Context context) {
        return prefs(context).getString(KEY_EXPECTED_SIGNER, "");
    }

    static String expectedSigner(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedSigner(context)
                : prefs(context).getString(profileKey(profile, KEY_EXPECTED_SIGNER), "");
    }

    static String expectedDirect(Context context) {
        return prefs(context).getString(KEY_EXPECTED_DIRECT, "");
    }

    static String expectedDirect(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedDirect(context)
                : prefs(context).getString(profileKey(profile, KEY_EXPECTED_DIRECT), "");
    }

    static String expectedGmsCore(Context context) {
        return prefs(context).getString(KEY_EXPECTED_GMS_CORE, "");
    }

    static String expectedGmsCore(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedGmsCore(context)
                : prefs(context).getString(profileKey(profile, KEY_EXPECTED_GMS_CORE), "");
    }

    static String expectedOptional(Context context) {
        return prefs(context).getString(KEY_EXPECTED_OPTIONAL, "");
    }

    static String expectedOptional(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedOptional(context)
                : prefs(context).getString(profileKey(profile, KEY_EXPECTED_OPTIONAL), "");
    }

    static String expectedAlert(Context context) {
        return prefs(context).getString(KEY_EXPECTED_ALERT, "");
    }

    static String expectedAlert(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? expectedAlert(context)
                : prefs(context).getString(profileKey(profile, KEY_EXPECTED_ALERT), "");
    }

    static long initialUpdateTime(Context context) {
        return prefs(context).getLong(KEY_INITIAL_UPDATE_TIME, -1L);
    }

    static long initialUpdateTime(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? initialUpdateTime(context)
                : prefs(context).getLong(profileKey(profile, KEY_INITIAL_UPDATE_TIME), -1L);
    }

    static long initialVersionCode(Context context) {
        return prefs(context).getLong(KEY_INITIAL_VERSION_CODE, -1L);
    }

    static long initialVersionCode(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? initialVersionCode(context)
                : prefs(context).getLong(profileKey(profile, KEY_INITIAL_VERSION_CODE), -1L);
    }

    static String initialSigner(Context context) {
        return prefs(context).getString(KEY_INITIAL_SIGNER, "");
    }

    static String initialSigner(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? initialSigner(context)
                : prefs(context).getString(profileKey(profile, KEY_INITIAL_SIGNER), "");
    }

    static String initialFingerprint(Context context) {
        return prefs(context).getString(KEY_INITIAL_FINGERPRINT, "");
    }

    static String initialFingerprint(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? initialFingerprint(context)
                : prefs(context).getString(profileKey(profile, KEY_INITIAL_FINGERPRINT), "");
    }

    static long stateAt(Context context) {
        return prefs(context).getLong(KEY_STATE_AT, 0L);
    }

    static long stateAt(Context context, Profile profile) {
        return profile == null || !localOperation(context, profile) ? stateAt(context)
                : prefs(context).getLong(profileKey(profile, KEY_STATE_AT), 0L);
    }

    static synchronized void expectCallback(Context context, String operation) {
        prefs(context).edit()
                .putString(KEY_EXPECTED_CALLBACK, operation)
                .putBoolean(KEY_CALLBACK_CONSUMED, false)
                .commit();
    }

    static synchronized void expectCallback(Context context, Profile profile, String operation) {
        if (profile == null) {
            expectCallback(context, operation);
            return;
        }
        prefs(context).edit()
                .putString(profileKey(profile, KEY_EXPECTED_CALLBACK), operation)
                .putBoolean(profileKey(profile, KEY_CALLBACK_CONSUMED), false)
                .commit();
    }

    static synchronized boolean acceptCallback(Context context, Profile profile,
            String token, String operation, int callbackSessionId, boolean terminal) {
        if (profile != null && localOperation(context, profile)) {
            SharedPreferences local = prefs(context);
            if (token == null || operation == null
                    || !token.equals(local.getString(profileKey(profile, KEY_TRANSACTION_TOKEN), ""))
                    || !operation.equals(local.getString(profileKey(profile, KEY_EXPECTED_CALLBACK), ""))
                    || (terminal && local.getBoolean(profileKey(profile, KEY_CALLBACK_CONSUMED), false))) {
                return false;
            }
            boolean install = "install_patched".equals(operation)
                    || "install_original".equals(operation);
            if (install && callbackSessionId != local.getInt(profileKey(profile, KEY_SESSION_ID), -1)) {
                return false;
            }
            String phase = local.getString(profileKey(profile, KEY_OPERATION_PHASE), IDLE);
            boolean phaseMatches = install
                    ? COMMITTING.equals(phase) || INSTALL_REQUESTED.equals(phase)
                    : UNINSTALL_REQUESTED.equals(phase);
            if (!phaseMatches) return false;
            if (terminal) local.edit()
                    .putBoolean(profileKey(profile, KEY_CALLBACK_CONSUMED), true).commit();
            return true;
        }
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

    static File transactionDirectory(Context context, Profile profile) {
        if (profile == null || !localOperation(context, profile)) return transactionDirectory(context);
        String name = prefs(context).getString(profileKey(profile, KEY_TRANSACTION_DIR), "");
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

    static File originalSet(Context context, Profile profile) {
        return new File(transactionDirectory(context, profile), "source-set");
    }

    static boolean isCancellationRequested(Context context, Profile profile) {
        return profile != null && prefs(context)
                .getBoolean(profileKey(profile, KEY_CANCEL_REQUESTED), false);
    }

    static boolean canCancel(OperationSnapshot operation) {
        if (operation == null || operation.profile == null || !operation.busy()) return false;
        if (!OP_CHECK.equals(operation.kind) && !OP_PATCH.equals(operation.kind)) return false;
        return COPYING.equals(operation.phase) || WAITING_FOR_PATCHER.equals(operation.phase)
                || VERIFYING.equals(operation.phase)
                || SCANNING.equals(operation.phase) || PATCHING.equals(operation.phase)
                || REPACKING.equals(operation.phase) || SIGNING.equals(operation.phase)
                || OUTPUT_VERIFY.equals(operation.phase) || READY_TO_INSTALL.equals(operation.phase)
                || AWAITING_PERMISSION.equals(operation.phase);
    }

    static synchronized boolean requestCancel(Context context, Profile profile) {
        OperationSnapshot current = operation(context, profile);
        if (!canCancel(current)) return false;
        prefs(context).edit()
                .putBoolean(profileKey(profile, KEY_CANCEL_REQUESTED), true)
                .putString(profileKey(profile, KEY_OPERATION_PHASE), CANCEL_REQUESTED)
                .putString(profileKey(profile, KEY_OPERATION_DETAIL), "Cancellation requested")
                .putLong(profileKey(profile, KEY_STATE_AT), System.currentTimeMillis())
                .commit();
        AppEventLogger.event(context, "navigator_patch operation=" + current.kind
                + " profile=" + profile.id + " stage=" + CANCEL_REQUESTED
                + " code=CANCEL_REQUESTED detail=Cancellation requested");
        MainActivity.publishSharedUiStateChange();
        return true;
    }

    static synchronized void markCancelled(Context context, Profile profile, String detail) {
        if (profile == null || !localOperation(context, profile)) return;
        transitionLocal(context, profile, CANCELLED,
                detail == null || detail.isEmpty() ? "Cancelled" : detail, 0);
    }

    static synchronized boolean dismiss(Context context, Profile profile) {
        if (profile == null) return false;
        OperationSnapshot current = operation(context, profile);
        if (!(FAILED.equals(current.phase) || CANCELLED.equals(current.phase)
                || VERIFIED.equals(current.phase))) return false;
        File transaction = transactionDirectory(context, profile);
        clearTransactionMetadata(context, profile);
        releaseInstall(context, profile);
        prefs(context).edit()
                .putString(profileKey(profile, KEY_OPERATION_PHASE), IDLE)
                .putString(profileKey(profile, KEY_OPERATION_DETAIL), "")
                .putString(profileKey(profile, KEY_OPERATION_ERROR), "")
                .putInt(profileKey(profile, KEY_OPERATION_PROGRESS), 0)
                .putLong(profileKey(profile, KEY_READY_AT), 0L)
                .putBoolean(profileKey(profile, KEY_CANCEL_REQUESTED), false)
                .commit();
        MainActivity.publishSharedUiStateChange();
        deleteTreeQuietly(transaction);
        requestInstallDrain(context);
        return true;
    }

    static synchronized boolean claimInstall(Context context, Profile profile) throws IOException {
        if (profile == null) throw new IOException("Patch profile is required");
        OperationSnapshot recovery = globalOperation(context);
        if (OP_RECOVERY.equals(recovery.kind) && recovery.busy()) {
            return false;
        }
        for (Profile candidate : Profile.values()) {
            if (RECOVERY_REQUIRED.equals(operation(context, candidate).phase)) {
                return false;
            }
        }
        SharedPreferences preferences = prefs(context);
        String ownerId = preferences.getString(KEY_INSTALL_OWNER, "");
        Profile owner = Profile.fromId(ownerId);
        if (owner != null && operation(context, owner).terminal()) {
            preferences.edit().remove(KEY_INSTALL_OWNER).remove(KEY_INSTALL_OWNER_TOKEN).commit();
            owner = null;
        }
        if (owner != null && !owner.equals(profile)) return false;
        OperationSnapshot current = operation(context, profile);
        if (!READY_TO_INSTALL.equals(current.phase) && !AWAITING_PERMISSION.equals(current.phase)) {
            throw new IOException("Navigator patch is not ready to install");
        }
        long candidateAt = current.readyAt <= 0L ? Long.MAX_VALUE : current.readyAt;
        for (Profile other : Profile.values()) {
            if (other == profile) continue;
            OperationSnapshot queued = operation(context, other);
            if (readyBefore(queued, candidateAt)) return false;
        }
        preferences.edit()
                .putString(KEY_INSTALL_OWNER, profile.id)
                .putString(KEY_INSTALL_OWNER_TOKEN, current.operationToken)
                .commit();
        transitionLocal(context, profile, INSTALL_PREPARING,
                "Preparing Android installer", current.progress);
        return true;
    }

    static boolean readyBefore(OperationSnapshot operation, long readyAt) {
        return operation != null && READY_TO_INSTALL.equals(operation.phase)
                && operation.readyAt > 0L && operation.readyAt < readyAt;
    }

    static boolean isInstalledVerificationPhase(String phase) {
        return INSTALLED_VERIFY.equals(phase);
    }

    static synchronized void releaseInstall(Context context, Profile profile) {
        SharedPreferences preferences = prefs(context);
        if (profile == null || profile.id.equals(preferences.getString(KEY_INSTALL_OWNER, ""))) {
            preferences.edit().remove(KEY_INSTALL_OWNER).remove(KEY_INSTALL_OWNER_TOKEN).commit();
        }
    }

    static boolean isInstallOwner(Context context, Profile profile) {
        return profile != null && profile.id.equals(
                prefs(context).getString(KEY_INSTALL_OWNER, ""));
    }

    private static void deleteTreeQuietly(File root) {
        if (root == null || !root.exists()) return;
        try {
            NavigatorPatchPipeline.deleteTree(root);
        } catch (RuntimeException ignored) {
        }
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
                .remove(KEY_EXPECTED_GMS_CORE)
                .remove(KEY_EXPECTED_OPTIONAL)
                .remove(KEY_EXPECTED_ALERT)
                .remove(KEY_INITIAL_UPDATE_TIME)
                .remove(KEY_INITIAL_VERSION_CODE)
                .remove(KEY_INITIAL_SIGNER)
                .remove(KEY_INITIAL_FINGERPRINT)
                .remove(KEY_EXPECTED_CALLBACK)
                .remove(KEY_CALLBACK_CONSUMED)
                .remove(KEY_OPERATION_KIND)
                .remove(KEY_OPERATION_STARTED_AT)
                .remove(KEY_OPERATION_PROGRESS)
                .remove(KEY_OPERATION_ERROR)
                .remove(KEY_READY_AT)
                .remove(KEY_CANCEL_REQUESTED)
                .commit();
    }

    static synchronized void clearTransactionMetadata(Context context, Profile profile) {
        if (profile == null) {
            clearTransactionMetadata(context);
            return;
        }
        prefs(context).edit()
                .remove(profileKey(profile, KEY_TRANSACTION_DIR))
                .remove(profileKey(profile, KEY_DESTRUCTIVE))
                .remove(profileKey(profile, KEY_SESSION_ID))
                .remove(profileKey(profile, KEY_TRANSACTION_TOKEN))
                .remove(profileKey(profile, KEY_RECOVERY_OWNER))
                .remove(profileKey(profile, KEY_EXPECTED_SHA))
                .remove(profileKey(profile, KEY_EXPECTED_VERSION_CODE))
                .remove(profileKey(profile, KEY_EXPECTED_SIGNER))
                .remove(profileKey(profile, KEY_EXPECTED_DIRECT))
                .remove(profileKey(profile, KEY_EXPECTED_GMS_CORE))
                .remove(profileKey(profile, KEY_EXPECTED_OPTIONAL))
                .remove(profileKey(profile, KEY_EXPECTED_ALERT))
                .remove(profileKey(profile, KEY_INITIAL_UPDATE_TIME))
                .remove(profileKey(profile, KEY_INITIAL_VERSION_CODE))
                .remove(profileKey(profile, KEY_INITIAL_SIGNER))
                .remove(profileKey(profile, KEY_INITIAL_FINGERPRINT))
                .remove(profileKey(profile, KEY_EXPECTED_CALLBACK))
                .remove(profileKey(profile, KEY_CALLBACK_CONSUMED))
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
                .remove(KEY_EXPECTED_GMS_CORE)
                .remove(KEY_EXPECTED_OPTIONAL)
                .remove(KEY_EXPECTED_ALERT)
                .remove(KEY_INITIAL_UPDATE_TIME)
                .remove(KEY_INITIAL_VERSION_CODE)
                .remove(KEY_INITIAL_SIGNER)
                .remove(KEY_INITIAL_FINGERPRINT)
                .remove(KEY_EXPECTED_CALLBACK)
                .remove(KEY_CALLBACK_CONSUMED)
                .remove(KEY_OPERATION_KIND)
                .remove(profileKey(profile, KEY_OPERATION_KIND))
                .remove(profileKey(profile, KEY_OPERATION_PHASE))
                .remove(profileKey(profile, KEY_OPERATION_DETAIL))
                .remove(profileKey(profile, KEY_OPERATION_TOKEN))
                .remove(profileKey(profile, KEY_OPERATION_STARTED_AT))
                .remove(profileKey(profile, KEY_OPERATION_PROGRESS))
                .remove(profileKey(profile, KEY_OPERATION_ERROR))
                .remove(profileKey(profile, KEY_READY_AT))
                .remove(profileKey(profile, KEY_CANCEL_REQUESTED))
                .commit();
        if (!committed) throw new IOException("Cannot complete navigator recovery transaction");
        AppEventLogger.event(context, "navigator_patch operation=" + OP_RECOVERY
                + " profile=" + profile.id + " stage=" + IDLE
                + " code=" + eventCode(OP_RECOVERY, IDLE, detail)
                + " detail=" + clean(detail));
        MainActivity.publishSharedUiStateChange();
        requestInstallDrain(context);
    }

    private static void requestInstallDrain(Context context) {
        NavigatorPackageInstaller.drainInstallQueue(context);
    }

    static void recordInstalledVerification(
            Context context, Profile profile, long updateTime, long versionCode,
            String sha256, String directState, String gmsCoreState,
            String optionalState, String alertState) {
        prefs(context).edit()
                .putLong(profile.id + "_installed_update", updateTime)
                .putLong(profile.id + "_installed_version_code", versionCode)
                .putString(profile.id + "_installed_sha", sha256)
                .putString(profile.id + "_installed_direct", directState)
                .putString(profile.id + "_installed_gms_core", gmsCoreState)
                .putString(profile.id + "_installed_optional", optionalState)
                .putString(profile.id + "_installed_alert", alertState)
                .commit();
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
        String gmsCore = preferences.getString(profile.id + "_scan_gms_core", NOT_CHECKED);
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
            gmsCore = NOT_CHECKED;
            optional = NOT_CHECKED;
            alert = NOT_CHECKED;
            reason = "";
        }
        if (!external && !isInstalled) {
            direct = NOT_CHECKED;
            gmsCore = NOT_CHECKED;
            optional = NOT_CHECKED;
            alert = NOT_CHECKED;
            reason = "";
        }
        boolean patchEnabled = isPatchEnabled(profile, direct, gmsCore, optional, alert);
        return new ProfileSnapshot(profile, isInstalled, label, installedVersion, installedCode,
                external, sourceName == null ? "" : sourceName,
                sourceVersion == null ? "" : sourceVersion, sourceCode,
                direct, gmsCore, optional, alert, reason == null ? "" : reason, patchEnabled);
    }

    static boolean isPatchEnabled(Profile profile, String direct, String optional,
            String auxiliary) {
        return isPatchEnabled(profile, direct, NOT_CHECKED, optional, auxiliary);
    }

    static boolean isPatchEnabled(Profile profile, String direct, String gmsCore,
            String optional, String auxiliary) {
        if (profile == Profile.WAZE) {
            boolean coreCompatible = (PATCHABLE.equals(direct) || PATCHED.equals(direct))
                    && (PATCHABLE.equals(gmsCore) || PATCHED.equals(gmsCore));
            return coreCompatible && (PATCHABLE.equals(direct) || PATCHABLE.equals(gmsCore)
                    || PATCHABLE.equals(optional) || PATCHABLE.equals(auxiliary));
        }
        return PATCHABLE.equals(direct)
                || PATCHABLE.equals(gmsCore)
                || PATCHABLE.equals(optional)
                || PATCHABLE.equals(auxiliary);
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
