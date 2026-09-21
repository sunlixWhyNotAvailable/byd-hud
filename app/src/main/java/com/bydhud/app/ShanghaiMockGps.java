package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Serialized, off-main-thread mock GPS ownership and recovery boundary. */
final class ShanghaiMockGps {
    private static final String PREFS = "shanghai-mock-gps-recovery";
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final long OWNERSHIP_CHECK_INTERVAL_MS = 5_000L;
    private static final Pattern APP_OP = Pattern.compile(
            "(?i)(?:android:)?mock_location\\s*:\\s*(allow|deny|ignore|default)");
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

        private Result(Code code, String detail) {
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }

        boolean success() {
            return code == Code.SUCCESS || code == Code.NO_PENDING_RECOVERY
                    || code == Code.NO_ACTIVE_MOCK;
        }

        static Result of(Code code, String detail) {
            return new Result(code, detail);
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
                        "Another app owns the active GPS mock: " + before.packageName);
            }
            if (before.kind == ProviderKind.UNKNOWN) {
                return Result.of(Code.UNKNOWN_PROVIDER, "Cannot establish current GPS ownership.");
            }

            String priorMode = readOwnAppOp();
            if (priorMode == null) {
                return Result.of(Code.PERMISSION_ERROR, "Cannot read the current mock-location app-op.");
            }
            if (!writeRecord(sessionId.trim(), "permission", priorMode)) {
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
                        + immediate.kind + "). Cleanup: " + cleanup.code + " " + cleanup.detail);
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
            return Result.of(Code.SUCCESS, "Mock GPS provider created and ownership verified.");
        } catch (Exception error) {
            return Result.of(Code.PROVIDER_ERROR, describe(error));
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
                String appOp = readOwnAppOp();
                if (after.kind != ProviderKind.OWNED_MOCK || !after.active) {
                    return Result.of(after.kind == ProviderKind.UNKNOWN
                                    ? Code.UNKNOWN_PROVIDER : Code.OWNERSHIP_LOST,
                            "GPS mock ownership/active state changed after injection: "
                                    + after.kind + "/" + after.active);
                }
                if (!"allow".equals(appOp)) {
                    return Result.of(Code.PERMISSION_ERROR,
                            "Mock-location app-op changed during route injection.");
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
            return Result.of(Code.PROVIDER_ERROR, describe(error));
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
        String kind = prefs.getString("kind", "");
        try {
            ProviderState state = inspectProvider();
            RecoveryAction action = recoveryAction(kind, state.kind);
            if (action == RecoveryAction.WAIT_FOR_EVIDENCE) {
                return Result.of(Code.UNKNOWN_PROVIDER,
                        "GPS ownership is unknown; recovery remains pending.");
            }
            if (action == RecoveryAction.REMOVE_OWNED) {
                Result permission = ensureAllowed();
                if (!permission.success()) return permission;
                ProviderState immediate = inspectProvider();
                if (immediate.kind == ProviderKind.UNKNOWN) {
                    return Result.of(Code.UNKNOWN_PROVIDER,
                            "Immediate GPS ownership check failed; recovery remains pending.");
                }
                if (immediate.kind == ProviderKind.OWNED_MOCK) {
                    locations.removeTestProvider(GPS);
                    ProviderState after = inspectProvider();
                    if (after.kind == ProviderKind.OWNED_MOCK
                            || after.kind == ProviderKind.UNKNOWN) {
                        return Result.of(Code.PROVIDER_ERROR,
                                "Owned GPS mock removal was not verified: " + after.kind);
                    }
                }
            }
            Result restored = restorePriorAppOp();
            if (!restored.success()) return restored;
            if (!clearRecordDurably()) {
                return Result.of(Code.PERSISTENCE_ERROR,
                        "Cleanup succeeded but the recovery record could not be cleared.");
            }
            return Result.of(state.kind == ProviderKind.REAL
                            ? Code.NO_ACTIVE_MOCK : Code.SUCCESS,
                    state.kind == ProviderKind.FOREIGN_MOCK
                            ? "Foreign GPS mock left untouched; own app-op restored."
                            : "Owned mock GPS cleanup verified.");
        } catch (Exception error) {
            return Result.of(Code.PROVIDER_ERROR, describe(error));
        }
    }

    Result resetAny() {
        try {
            if (hasPendingRecovery() && !recordMatchesInstallation()) {
                return Result.of(Code.PERSISTENCE_ERROR,
                        "Pending recovery record belongs to a different app installation.");
            }
            if (!hasPendingRecovery()) {
                String priorMode = readOwnAppOp();
                if (priorMode == null) {
                    return Result.of(Code.PERMISSION_ERROR,
                            "Cannot read the current mock-location app-op.");
                }
                if (!writeRecord("manual-" + System.currentTimeMillis(), "manual", priorMode)) {
                    return Result.of(Code.PERSISTENCE_ERROR,
                            "Cannot persist manual reset recovery state.");
                }
            }
            Result permission = ensureAllowed();
            if (!permission.success()) return permission;
            ProviderState current = inspectProvider();
            if (current.kind == ProviderKind.UNKNOWN) {
                return Result.of(Code.UNKNOWN_PROVIDER,
                        "Cannot establish the current GPS provider; reset remains pending.");
            }
            if (current.kind == ProviderKind.OWNED_MOCK
                    || current.kind == ProviderKind.FOREIGN_MOCK) {
                locations.removeTestProvider(GPS);
                ProviderState after = inspectProvider();
                if (after.kind != ProviderKind.REAL) {
                    return Result.of(after.kind == ProviderKind.UNKNOWN
                                    ? Code.UNKNOWN_PROVIDER : Code.PROVIDER_ERROR,
                            "GPS mock removal was not verified: " + after.kind);
                }
            }
            Result restored = restorePriorAppOp();
            if (!restored.success()) return restored;
            if (!clearRecordDurably()) {
                return Result.of(Code.PERSISTENCE_ERROR,
                        "Reset succeeded but the recovery record could not be cleared.");
            }
            return Result.of(current.kind == ProviderKind.REAL
                            ? Code.NO_ACTIVE_MOCK : Code.SUCCESS,
                    current.kind == ProviderKind.REAL
                            ? "No active GPS mock was present."
                            : "Current GPS mock removed and own app-op restored.");
        } catch (Exception error) {
            return Result.of(Code.PROVIDER_ERROR, describe(error));
        }
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

    static String parseAppOpMode(String output) {
        String safe = output == null ? "" : output;
        Matcher matcher = APP_OP.matcher(safe);
        if (matcher.find()) return matcher.group(1).toLowerCase(Locale.ROOT);
        String lower = safe.toLowerCase(Locale.ROOT);
        return lower.contains("no operations") ? "default" : null;
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

    private String readOwnAppOp() throws IOException {
        LocalAdbBridge.ShellResult result = LocalAdbBridge.readOwnMockLocationAppOp(context);
        return result != null && result.success() && !result.truncated
                ? parseAppOpMode(result.output) : null;
    }

    private Result ensureAllowed() throws IOException {
        String current = readOwnAppOp();
        if (current == null) {
            return Result.of(Code.PERMISSION_ERROR, "Cannot read mock-location app-op.");
        }
        if (!"allow".equals(current)) {
            LocalAdbBridge.ShellResult set =
                    LocalAdbBridge.setOwnMockLocationAppOp(context, "allow");
            if (set == null || !set.success() || !"allow".equals(readOwnAppOp())) {
                return Result.of(Code.PERMISSION_ERROR,
                        "Mock-location app-op grant was not verified.");
            }
        }
        return Result.of(Code.SUCCESS, "Mock-location app-op is allowed.");
    }

    private Result restorePriorAppOp() throws IOException {
        String prior = prefs.getString("prior_app_op", null);
        if (prior == null) {
            return Result.of(Code.PERSISTENCE_ERROR, "Recovery record has no prior app-op.");
        }
        LocalAdbBridge.ShellResult set = LocalAdbBridge.setOwnMockLocationAppOp(context, prior);
        String after = set != null && set.success() ? readOwnAppOp() : null;
        return prior.equals(after)
                ? Result.of(Code.SUCCESS, "Prior mock-location app-op restored.")
                : Result.of(Code.PERMISSION_ERROR,
                        "Prior mock-location app-op restoration was not verified.");
    }

    private boolean writeRecord(String sessionId, String kind, String priorMode) {
        return prefs.edit()
                .putBoolean("cleanup_required", true)
                .putString("session_id", sessionId)
                .putString("kind", kind)
                .putString("package", packageName)
                .putInt("uid", uid)
                .putInt("user_id", userId)
                .putString("provider", GPS)
                .putString("prior_app_op", priorMode)
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
        String priorAppOp = prefs.getString("prior_app_op", "");
        if (prefs.edit().clear().commit()) return true;
        prefs.edit()
                .putBoolean("cleanup_required", true)
                .putString("session_id", sessionId)
                .putString("kind", kind)
                .putString("package", recordedPackage)
                .putInt("uid", recordedUid)
                .putInt("user_id", recordedUserId)
                .putString("provider", provider)
                .putString("prior_app_op", priorAppOp)
                .commit();
        return false;
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) count++;
        return count;
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
