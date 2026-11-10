package com.bydhud.app;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Serialized, off-main-thread mock GPS ownership and recovery boundary. */
final class ShanghaiMockGps {
    private static final String PREFS = "shanghai-mock-gps-recovery";
    private static final String PRIOR_PACKAGE_MODE = "prior_app_op";
    private static final String PRIOR_UID_MODE = "prior_uid_app_op";
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final long OWNERSHIP_CHECK_INTERVAL_MS = 5_000L;
    private static final Pattern APP_OP = Pattern.compile(
            "(?im)^\\h*(?:(Uid mode):\\h*)?(?:android:)?mock_location\\h*:\\h*(\\w+)\\b");
    private static final Pattern DEFAULT_APP_OP = Pattern.compile("(?im)^\\h*Default mode:\\h*(\\w+)\\h*$");
    private static final Pattern GPS_HEADER = Pattern.compile(
            "(?i)^(\\s*)gps\\s+provider\\s*(\\[mock])?\\s*:\\s*$");
    private static final Pattern IDENTITY = Pattern.compile(
            "^\\s*identity\\s*=\\s*(\\d+)\\s*/\\s*([^\\s\\[]+).*$");

    enum Code {
        SUCCESS,
        NO_PENDING_RECOVERY,
        NO_ACTIVE_MOCK,
        FOREIGN_MOCK,
        UNTRACKED_OWN_MOCK,
        UNKNOWN_PROVIDER,
        OWNERSHIP_LOST,
        PENDING_RECOVERY,
        PERSISTENCE_ERROR,
        PERMISSION_ERROR,
        PROVIDER_ERROR
    }

    static final class Result {
        final Code code;
        final String detail;
        final String blockingPackage;

        private Result(Code code, String detail, String blockingPackage) {
            this.code = code;
            this.detail = detail == null ? "" : detail;
            this.blockingPackage = blockingPackage == null ? "" : blockingPackage;
        }

        boolean success() {
            return code == Code.SUCCESS || code == Code.NO_PENDING_RECOVERY
                    || code == Code.NO_ACTIVE_MOCK;
        }

        static Result of(Code code, String detail) {
            return new Result(code, detail, "");
        }

        static Result of(Code code, String detail, String blockingPackage) {
            return new Result(code, detail, blockingPackage);
        }
    }

    enum ProviderKind { REAL, OWNED_MOCK, FOREIGN_MOCK, UNKNOWN }

    static final class ProviderState {
        final ProviderKind kind;
        final int uid;
        final String packageName;
        final String lastMockLocation;
        final boolean active;

        ProviderState(ProviderKind kind, int uid, String packageName) {
            this(kind, uid, packageName, "", kind == ProviderKind.REAL);
        }

        ProviderState(ProviderKind kind, int uid, String packageName,
                String lastMockLocation, boolean active) {
            this.kind = kind;
            this.uid = uid;
            this.packageName = packageName == null ? "" : packageName;
            this.lastMockLocation = lastMockLocation == null ? "" : lastMockLocation;
            this.active = active;
        }
    }

    enum RecoveryAction { REMOVE_OWNED, RESTORE_PERMISSION_ONLY, WAIT_FOR_EVIDENCE }

    static final class AppOpModes {
        final String packageMode;
        final String uidMode;

        AppOpModes(String packageMode, String uidMode) {
            this.packageMode = packageMode;
            this.uidMode = uidMode;
        }

        String mode(boolean uidScoped) {
            return uidScoped ? uidMode : packageMode;
        }

        @Override public String toString() {
            return "packageMode=" + packageMode + " uidMode=" + uidMode;
        }
    }

    private final Context context;
    private final LocationManager locations;
    private final SharedPreferences prefs;
    private final String packageName;
    private final int uid;
    private final int userId;
    private long lastOwnershipCheckMs;
    private long lastInjectedElapsedMs;
    private String lastVerifiedMockLocation = "";
    private boolean firstInjectionPending;

    ShanghaiMockGps(Context context) {
        this.context = context.getApplicationContext();
        locations = this.context.getSystemService(LocationManager.class);
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        packageName = this.context.getPackageName();
        uid = this.context.getApplicationInfo().uid;
        userId = uid / 100000;
    }

    Result begin(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Result.of(Code.PROVIDER_ERROR, "Missing Shanghai session ID.");
        }
        if (hasPendingRecovery()) {
            return Result.of(Code.PENDING_RECOVERY, "Previous mock GPS cleanup is pending.");
        }
        try {
            ProviderState before = inspectProvider();
            if (before.kind == ProviderKind.OWNED_MOCK) {
                return Result.of(Code.UNTRACKED_OWN_MOCK,
                        "An untracked mock GPS provider already belongs to this app.");
            }
            if (before.kind == ProviderKind.FOREIGN_MOCK) {
                return Result.of(Code.FOREIGN_MOCK,
                        "Another app owns the active GPS mock: " + before.packageName, before.packageName);
            }
            if (before.kind == ProviderKind.UNKNOWN) {
                return Result.of(Code.UNKNOWN_PROVIDER, "Cannot establish current GPS ownership.");
            }

            if (!writeRecord(sessionId.trim(), "permission")) {
                return Result.of(Code.PERSISTENCE_ERROR, "Cannot persist mock GPS recovery state.");
            }
            Result permission = ensureAllowed();
            if (!permission.success()) return permission;

            ProviderState immediate = inspectProvider();
            if (immediate.kind != ProviderKind.REAL) {
                Result cleanup = cleanupOwned();
                Code code = immediate.kind == ProviderKind.UNKNOWN
                        ? Code.UNKNOWN_PROVIDER
                        : immediate.kind == ProviderKind.OWNED_MOCK
                                ? Code.UNTRACKED_OWN_MOCK : Code.FOREIGN_MOCK;
                return Result.of(code, "GPS provider changed before creation ("
                        + immediate.kind + "). Cleanup: " + cleanup.code + " " + cleanup.detail,
                        immediate.kind == ProviderKind.FOREIGN_MOCK ? immediate.packageName : "");
            }
            if (!prefs.edit().putString("kind", "owned").commit()) {
                prefs.edit().putString("kind", "permission").commit();
                return Result.of(Code.PERSISTENCE_ERROR,
                        "Cannot persist provider-creation recovery intent.");
            }
            addProvider();
            ProviderState after = inspectProvider();
            if (after.kind != ProviderKind.OWNED_MOCK || !after.active) {
                Result cleanup = cleanupOwned();
                return Result.of(Code.PROVIDER_ERROR,
                        "Mock provider creation was not verified (" + after.kind
                                + ", active=" + after.active + "). Cleanup: "
                                + cleanup.code + " " + cleanup.detail);
            }
            lastOwnershipCheckMs = SystemClock.elapsedRealtime();
            lastInjectedElapsedMs = 0L;
            lastVerifiedMockLocation = after.lastMockLocation;
            firstInjectionPending = true;
            return Result.of(Code.SUCCESS, "Mock GPS provider created and ownership verified. "
                    + permission.detail);
        } catch (Exception error) {
            return Result.of(Code.PROVIDER_ERROR, Log.getStackTraceString(error));
        }
    }

    Result inject(ShanghaiRoute.Point point) {
        if (point == null) return Result.of(Code.PROVIDER_ERROR, "Missing route point.");
        if (!hasPendingRecovery() || !"owned".equals(prefs.getString("kind", ""))) {
            return Result.of(Code.PENDING_RECOVERY, "No owned Shanghai mock session is active.");
        }
        try {
            long now = SystemClock.elapsedRealtime();
            boolean verifyReadback = firstInjectionPending || lastOwnershipCheckMs == 0L
                    || now - lastOwnershipCheckMs >= OWNERSHIP_CHECK_INTERVAL_MS;
            if (verifyReadback && !firstInjectionPending) {
                ProviderState state = inspectProvider();
                if (state.kind != ProviderKind.OWNED_MOCK || !state.active) {
                    return Result.of(state.kind == ProviderKind.UNKNOWN
                                    ? Code.UNKNOWN_PROVIDER : Code.OWNERSHIP_LOST,
                            "GPS mock ownership/active state changed: "
                                    + state.kind + "/" + state.active);
                }
            }

            Location location = new Location(GPS);
            location.setLatitude(point.latitude);
            location.setLongitude(point.longitude);
            location.setAccuracy(point.accuracyMeters);
            location.setBearing(point.bearingDegrees);
            location.setSpeed(point.speedMetersPerSecond);
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setBearingAccuracyDegrees(3f);
            location.setSpeedAccuracyMetersPerSecond(1f);
            long injectedElapsedMs = location.getElapsedRealtimeNanos() / 1_000_000L;
            locations.setTestProviderLocation(GPS, location);
            if (verifyReadback) {
                ProviderState after = inspectProvider();
                if (after.kind != ProviderKind.OWNED_MOCK || !after.active) {
                    return Result.of(after.kind == ProviderKind.UNKNOWN
                                    ? Code.UNKNOWN_PROVIDER : Code.OWNERSHIP_LOST,
                            "GPS mock ownership/active state changed after injection: "
                                    + after.kind + "/" + after.active);
                }
                int effective = effectiveMockAppOpMode();
                if (effective != AppOpsManager.MODE_ALLOWED) {
                    return Result.of(Code.PERMISSION_ERROR,
                            "Mock-location app-op changed during route injection: effectiveMode="
                                    + effective + ".");
                }
                if (after.lastMockLocation.isEmpty()
                        || after.lastMockLocation.equals(lastVerifiedMockLocation)) {
                    return Result.of(Code.PROVIDER_ERROR,
                            "Mock location readback did not advance.");
                }
                lastVerifiedMockLocation = after.lastMockLocation;
                lastOwnershipCheckMs = now;
                firstInjectionPending = false;
            }
            lastInjectedElapsedMs = injectedElapsedMs;
            return Result.of(Code.SUCCESS, "Route point injected.");
        } catch (Exception error) {
            return Result.of(Code.PROVIDER_ERROR, Log.getStackTraceString(error));
        }
    }

    Result cleanupOwned() {
        return recoverOwned();
    }

    Result recoverOwned() {
        if (!hasPendingRecovery()) {
            return Result.of(Code.NO_PENDING_RECOVERY, "No mock GPS recovery is pending.");
        }
        if (!recordMatchesInstallation()) {
            return Result.of(Code.PERSISTENCE_ERROR,
                    "Recovery record belongs to a different app installation.");
        }
        return finishRecovery(removeMock(false));
    }

    Result resetAny() {
        if (hasPendingRecovery() && !recordMatchesInstallation()) {
            return Result.of(Code.PERSISTENCE_ERROR,
                    "Pending recovery record belongs to a different app installation.");
        }
        if (!hasPendingRecovery() && !writeRecord("manual-" + System.currentTimeMillis(), "manual")) {
            return Result.of(Code.PERSISTENCE_ERROR, "Cannot persist manual reset recovery state.");
        }
        return finishRecovery(removeMock(true));
    }

    private Result removeMock(boolean anyOwner) {
        try {
            ProviderState current = inspectProvider();
            RecoveryAction action = recoveryAction(prefs.getString("kind", ""), current.kind);
            if (action == RecoveryAction.WAIT_FOR_EVIDENCE) {
                return Result.of(Code.UNKNOWN_PROVIDER,
                        "Cannot establish current GPS ownership; recovery remains pending.");
            }
            if (current.kind == ProviderKind.REAL) {
                return Result.of(Code.NO_ACTIVE_MOCK, "No active GPS mock was present.");
            }
            if (!anyOwner && action != RecoveryAction.REMOVE_OWNED) {
                return Result.of(Code.SUCCESS, "GPS provider outside the recovery record left untouched.");
            }
            Result permission = ensureAllowed();
            if (!permission.success()) return permission;
            current = inspectProvider();
            if (current.kind == ProviderKind.UNKNOWN) {
                return Result.of(Code.UNKNOWN_PROVIDER,
                        "Immediate GPS ownership check failed; recovery remains pending.");
            }
            if (current.kind == ProviderKind.OWNED_MOCK
                    || (anyOwner && current.kind == ProviderKind.FOREIGN_MOCK)) {
                locations.removeTestProvider(GPS);
                ProviderState after = inspectProvider();
                if (after.kind == ProviderKind.UNKNOWN || after.kind == ProviderKind.OWNED_MOCK
                        || (anyOwner && after.kind == ProviderKind.FOREIGN_MOCK)) {
                    return Result.of(after.kind == ProviderKind.UNKNOWN
                                    ? Code.UNKNOWN_PROVIDER : Code.PROVIDER_ERROR,
                            "GPS mock removal was not verified: " + after.kind);
                }
                return Result.of(Code.SUCCESS, "GPS mock removal verified.");
            }
            return Result.of(Code.SUCCESS, "GPS mock removal no longer needed.");
        } catch (Exception error) {
            return Result.of(Code.PROVIDER_ERROR, Log.getStackTraceString(error));
        }
    }

    private Result finishRecovery(Result removal) {
        String packageError = restoreAppOp(false);
        String uidError = restoreAppOp(true);
        if (!packageError.isEmpty() || !uidError.isEmpty()) {
            String restoreError = ("App-op restoration failed. " + packageError + " " + uidError).trim();
            return removal.success() ? Result.of(Code.PERMISSION_ERROR, restoreError)
                    : Result.of(removal.code, removal.detail + "\n" + restoreError, removal.blockingPackage);
        }
        if (!removal.success()) return removal;
        if (!clearRecordDurably()) {
            return Result.of(Code.PERSISTENCE_ERROR, "Cleanup succeeded but recovery state could not be cleared.");
        }
        return Result.of(removal.code, removal.detail + " App-op state restored.");
    }

    boolean hasPendingRecovery() {
        return prefs.getBoolean("cleanup_required", false);
    }

    String pendingSessionId() {
        return hasPendingRecovery() ? prefs.getString("session_id", "") : "";
    }

    long lastInjectedElapsedMs() {
        return lastInjectedElapsedMs;
    }

    static RecoveryAction recoveryAction(String recordKind, ProviderKind providerKind) {
        if (providerKind == ProviderKind.UNKNOWN) return RecoveryAction.WAIT_FOR_EVIDENCE;
        if ("owned".equals(recordKind) && providerKind == ProviderKind.OWNED_MOCK) {
            return RecoveryAction.REMOVE_OWNED;
        }
        return RecoveryAction.RESTORE_PERMISSION_ONLY;
    }

    static boolean recordIdentityMatches(String recordedPackage, int recordedUid,
            int recordedUserId, String recordedProvider,
            String currentPackage, int currentUid, int currentUserId) {
        return currentPackage.equals(recordedPackage)
                && currentUid == recordedUid
                && currentUserId == recordedUserId
                && GPS.equals(recordedProvider);
    }

    static AppOpModes parseAppOpModes(String output) {
        String safe = output == null ? "" : output;
        Matcher matcher = APP_OP.matcher(safe);
        String packageMode = null;
        String uidMode = null;
        while (matcher.find()) {
            String mode = matcher.group(2).toLowerCase(Locale.ROOT);
            if (!isAppOpMode(mode)) return null;
            if (matcher.group(1) == null) {
                if (packageMode != null) return null;
                packageMode = mode;
            } else {
                if (uidMode != null) return null;
                uidMode = mode;
            }
        }
        if (packageMode != null || uidMode != null) return new AppOpModes(packageMode, uidMode);
        if (!safe.toLowerCase(Locale.ROOT).contains("no operations")) return null;
        Matcher fallback = DEFAULT_APP_OP.matcher(safe);
        String mode = fallback.find() ? fallback.group(1).toLowerCase(Locale.ROOT) : "default";
        return isAppOpMode(mode) ? new AppOpModes(mode, null) : null;
    }

    static boolean isAppOpMode(String mode) {
        return "allow".equals(mode) || "deny".equals(mode) || "ignore".equals(mode)
                || "default".equals(mode) || "foreground".equals(mode);
    }

    static ProviderState parseProviderState(String dump, String ownPackage, int ownUid) {
        if (dump == null || dump.trim().isEmpty()) {
            return new ProviderState(ProviderKind.UNKNOWN, -1, "");
        }
        String[] lines = dump.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher header = GPS_HEADER.matcher(lines[i]);
            if (!header.matches()) continue;
            if (header.group(2) == null) {
                return new ProviderState(ProviderKind.REAL, -1, "");
            }
            int headerIndent = header.group(1).length();
            int providerUid = -1;
            String providerPackage = "";
            String lastMockLocation = "";
            boolean enabled = false;
            boolean allowed = false;
            for (int j = i + 1; j < lines.length; j++) {
                String line = lines[j];
                if (!line.trim().isEmpty() && leadingSpaces(line) <= headerIndent) break;
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("last mock location=")) {
                    lastMockLocation = trimmed.substring("last mock location=".length()).trim();
                }
                if ("enabled=true".equalsIgnoreCase(trimmed)) enabled = true;
                if ("allowed=true".equalsIgnoreCase(trimmed)) allowed = true;
                Matcher identity = IDENTITY.matcher(line);
                if (!identity.matches()) continue;
                providerUid = Integer.parseInt(identity.group(1));
                providerPackage = identity.group(2);
            }
            if (providerUid >= 0) {
                ProviderKind kind = providerUid == ownUid && providerPackage.equals(ownPackage)
                        ? ProviderKind.OWNED_MOCK : ProviderKind.FOREIGN_MOCK;
                return new ProviderState(kind, providerUid, providerPackage,
                        lastMockLocation, enabled && allowed);
            }
            return new ProviderState(ProviderKind.UNKNOWN, -1, "");
        }
        return new ProviderState(ProviderKind.UNKNOWN, -1, "");
    }

    @SuppressLint("WrongConstant")
    @SuppressWarnings("deprecation")
    private void addProvider() {
        locations.addTestProvider(GPS,
                false, true, false, false, true, true, true,
                Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
        locations.setTestProviderEnabled(GPS, true);
    }

    private ProviderState inspectProvider() throws IOException {
        LocalAdbBridge.ShellResult result = LocalAdbBridge.readGpsProviderState(context);
        if (result == null || !result.success() || result.truncated) {
            return new ProviderState(ProviderKind.UNKNOWN, -1, "");
        }
        return parseProviderState(result.output, packageName, uid);
    }

    private AppOpModes readOwnAppOps() throws IOException {
        requireCallerIdentity();
        LocalAdbBridge.ShellResult result = LocalAdbBridge.readOwnMockLocationAppOp(context);
        requireAppOpCommand("GET", result);
        AppOpModes modes = parseAppOpModes(result.output);
        if (modes == null) throw new IOException("Unrecognized mock-location app-op GET: " + result.output);
        return modes;
    }

    private void requireCallerIdentity() {
        if (Process.myUid() != uid || !packageName.equals(context.getOpPackageName())) {
            throw new SecurityException("Mock-location caller identity mismatch: callerUid="
                    + Process.myUid() + " packageUid=" + uid + " package=" + packageName
                    + " opPackage=" + context.getOpPackageName());
        }
    }

    @SuppressWarnings("deprecation")
    private int effectiveMockAppOpMode() {
        requireCallerIdentity();
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) throw new IllegalStateException("Mock-location AppOps service is unavailable.");
        // Use the same AppOps operation as LocationManager's mock-provider calls.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return appOps.noteOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, packageName,
                    context.getAttributionTag(), null);
        }
        return appOps.noteOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, packageName);
    }

    private Result ensureAllowed() {
        try {
            AppOpModes before = readOwnAppOps();
            int effective = effectiveMockAppOpMode();
            if (effective == AppOpsManager.MODE_ALLOWED) {
                return Result.of(Code.SUCCESS, "Mock-location app-op already allowed: " + before);
            }
            boolean uidScoped = before.uidMode != null;
            if (uidScoped && (!"default".equals(before.uidMode) || effective != AppOpsManager.MODE_DEFAULT
                    || uid != 2000 || !"com.android.shell".equals(packageName)
                    || ShellWorkEntryPoint.currentJob == null)) {
                return Result.of(Code.PERMISSION_ERROR,
                        "Mock-location UID policy prevents access: " + before + " effectiveMode=" + effective);
            }
            if ("foreground".equals(before.mode(uidScoped))) {
                return Result.of(Code.PERMISSION_ERROR,
                        "Mock-location requires foreground access: " + before + " effectiveMode=" + effective);
            }
            if ("allow".equals(before.mode(uidScoped))) {
                return Result.of(Code.PERMISSION_ERROR,
                        "Mock-location app-op is blocked despite the grant: " + before
                                + " effectiveMode=" + effective);
            }
            String priorKey = uidScoped ? PRIOR_UID_MODE : PRIOR_PACKAGE_MODE;
            if (!prefs.contains(priorKey) && !prefs.edit().putString(priorKey, before.mode(uidScoped)).commit()) {
                return Result.of(Code.PERSISTENCE_ERROR, "Cannot persist prior mock-location app-op: " + before);
            }
            AppOpModes after = setAppOp("allow", uidScoped);
            effective = effectiveMockAppOpMode();
            if (effective != AppOpsManager.MODE_ALLOWED) {
                return Result.of(Code.PERMISSION_ERROR,
                        "Mock-location app-op is still blocked: " + after + " effectiveMode=" + effective);
            }
            return Result.of(Code.SUCCESS, "Mock-location app-op allowed: " + before + " -> " + after);
        } catch (Exception error) {
            return Result.of(Code.PERMISSION_ERROR, Log.getStackTraceString(error));
        }
    }

    private AppOpModes setAppOp(String mode, boolean uidScoped) throws IOException {
        requireCallerIdentity();
        String scope = uidScoped ? "UID" : "package";
        LocalAdbBridge.ShellResult set = LocalAdbBridge.setOwnMockLocationAppOp(context, mode, uidScoped);
        requireAppOpCommand("SET " + scope + " " + mode, set);
        AppOpModes after;
        try {
            after = readOwnAppOps();
        } catch (IOException error) {
            throw new IOException("Mock-location SET " + scope + " " + mode + " succeeded. GET failed.", error);
        }
        if (!mode.equals(after.mode(uidScoped))) {
            throw new IOException("Mock-location SET " + scope + " " + mode
                    + " succeeded but readback was " + after + ".");
        }
        return after;
    }

    private static void requireAppOpCommand(String stage, LocalAdbBridge.ShellResult result) throws IOException {
        if (result == null) throw new IOException("Mock-location " + stage + " returned no command result.");
        if (!result.success() || result.truncated) {
            throw new IOException("Mock-location " + stage + " exit=" + result.exitCode
                    + " status=" + result.status + " truncated=" + result.truncated
                    + " error=" + result.error + " output=" + result.output);
        }
    }

    private String restoreAppOp(boolean uidScoped) {
        String prior = prefs.getString(uidScoped ? PRIOR_UID_MODE : PRIOR_PACKAGE_MODE, null);
        if (prior == null) return "";
        try {
            setAppOp(prior, uidScoped);
            return "";
        } catch (Exception error) {
            return Log.getStackTraceString(error);
        }
    }

    private boolean writeRecord(String sessionId, String kind) {
        return prefs.edit()
                .putBoolean("cleanup_required", true)
                .putString("session_id", sessionId)
                .putString("kind", kind)
                .putString("package", packageName)
                .putInt("uid", uid)
                .putInt("user_id", userId)
                .putString("provider", GPS)
                .remove(PRIOR_PACKAGE_MODE)
                .remove(PRIOR_UID_MODE)
                .commit();
    }

    private boolean recordMatchesInstallation() {
        return recordIdentityMatches(
                prefs.getString("package", ""),
                prefs.getInt("uid", -1),
                prefs.getInt("user_id", -1),
                prefs.getString("provider", ""),
                packageName, uid, userId);
    }

    private boolean clearRecordDurably() {
        String sessionId = prefs.getString("session_id", "");
        String kind = prefs.getString("kind", "");
        String recordedPackage = prefs.getString("package", "");
        int recordedUid = prefs.getInt("uid", -1);
        int recordedUserId = prefs.getInt("user_id", -1);
        String provider = prefs.getString("provider", "");
        String priorAppOp = prefs.getString(PRIOR_PACKAGE_MODE, null);
        String priorUidAppOp = prefs.getString(PRIOR_UID_MODE, null);
        if (prefs.edit().clear().commit()) return true;
        prefs.edit()
                .putBoolean("cleanup_required", true)
                .putString("session_id", sessionId)
                .putString("kind", kind)
                .putString("package", recordedPackage)
                .putInt("uid", recordedUid)
                .putInt("user_id", recordedUserId)
                .putString("provider", provider)
                .putString(PRIOR_PACKAGE_MODE, priorAppOp)
                .putString(PRIOR_UID_MODE, priorUidAppOp)
                .commit();
        return false;
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) count++;
        return count;
    }

}
