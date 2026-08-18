package com.bydhud.app;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed IPC and diagnostic contract shared by the app and its shell helper. */
final class InstrumentProxyContract {
    static final int PROTOCOL_VERSION = 3;
    static final int CAP_SYSTEM_CONTEXT = 1;
    static final int CAP_DIRECT_FID = 1 << 1;
    static final int CAP_INSTRUMENT_SDK = 1 << 2;
    static final int CAP_INSTRUMENT_LANES = 1 << 3;
    static final String ACTION_CONNECTED =
            "com.bydhud.app.action.INSTRUMENT_PROXY_CONNECTED";
    static final String EXTRA_GENERATION = "generation";
    static final String EXTRA_NONCE = "nonce";
    static final String EXTRA_BINDER = "binder";
    private static final String PROCESS_PREFIX = "bydh";

    private static final String KEY_READY = "ready";
    private static final String KEY_ERROR = "error";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_NONCE = "nonce";
    private static final String KEY_PID = "pid";
    private static final String KEY_UID = "uid";
    private static final String KEY_PROTOCOL_VERSION = "protocol_version";
    private static final String KEY_APP_VERSION_CODE = "app_version_code";
    private static final String KEY_LAUNCH_TOKEN = "launch_token";
    private static final String KEY_PROCESS_START_TICKS = "process_start_ticks";
    private static final String KEY_CAPABILITIES = "capabilities";
    private static final String KEY_NAMES = "names";
    private static final String KEY_RESULTS = "results";
    private static final String KEY_DURATIONS = "durations";
    private static final String KEY_ERRORS = "errors";

    private InstrumentProxyContract() {
    }

    static Bundle connectionResult(boolean ready, String error,
            long generation, String nonce, int pid, int uid,
            int appVersionCode, String launchToken, long processStartTicks,
            int capabilities) {
        Bundle result = new Bundle();
        result.putBoolean(KEY_READY, ready);
        result.putString(KEY_ERROR, safe(error));
        result.putLong(KEY_GENERATION, generation);
        result.putString(KEY_NONCE, safe(nonce));
        result.putInt(KEY_PID, pid);
        result.putInt(KEY_UID, uid);
        result.putInt(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION);
        result.putInt(KEY_APP_VERSION_CODE, appVersionCode);
        result.putString(KEY_LAUNCH_TOKEN, safe(launchToken));
        result.putLong(KEY_PROCESS_START_TICKS, processStartTicks);
        result.putInt(KEY_CAPABILITIES, capabilities);
        return result;
    }

    static Bundle operationResult(List<Operation> operations, String error) {
        List<Operation> safeOperations = operations == null
                ? Collections.emptyList() : operations;
        String[] names = new String[safeOperations.size()];
        int[] results = new int[safeOperations.size()];
        long[] durations = new long[safeOperations.size()];
        String[] errors = new String[safeOperations.size()];
        for (int index = 0; index < safeOperations.size(); index++) {
            Operation operation = safeOperations.get(index);
            names[index] = operation.name;
            results[index] = operation.result;
            durations[index] = operation.durationMs;
            errors[index] = operation.error;
        }
        Bundle result = new Bundle();
        result.putStringArray(KEY_NAMES, names);
        result.putIntArray(KEY_RESULTS, results);
        result.putLongArray(KEY_DURATIONS, durations);
        result.putStringArray(KEY_ERRORS, errors);
        result.putString(KEY_ERROR, safe(error));
        return result;
    }

    static boolean isReady(Bundle result) {
        return result != null && result.getBoolean(KEY_READY, false);
    }

    static String error(Bundle result) {
        return result == null ? "no result" : safe(result.getString(KEY_ERROR));
    }

    static boolean hasExpectedConnectionIdentity(Bundle result,
            long generation, String nonce, int uid,
            int appVersionCode, String launchToken) {
        return result != null
                && result.getInt(KEY_PROTOCOL_VERSION, -1) == PROTOCOL_VERSION
                && result.getInt(KEY_APP_VERSION_CODE, -1) == appVersionCode
                && result.getLong(KEY_GENERATION, -1L) == generation
                && safe(nonce).equals(safe(result.getString(KEY_NONCE)))
                && safe(launchToken).equals(safe(result.getString(KEY_LAUNCH_TOKEN)))
                && result.getInt(KEY_PID, -1) > 0
                && result.getInt(KEY_UID, -1) == uid
                && result.getLong(KEY_PROCESS_START_TICKS, -1L) > 0L;
    }

    static int proxyPid(Bundle result) {
        return result == null ? -1 : result.getInt(KEY_PID, -1);
    }

    static int proxyUid(Bundle result) {
        return result == null ? -1 : result.getInt(KEY_UID, -1);
    }

    static long proxyStartTimeTicks(Bundle result) {
        return result == null ? -1L : result.getLong(KEY_PROCESS_START_TICKS, -1L);
    }

    static int capabilities(Bundle result) {
        return result == null ? 0 : result.getInt(KEY_CAPABILITIES, 0);
    }

    static boolean hasCapability(Bundle result, int capability) {
        return (capabilities(result) & capability) != 0;
    }

    static boolean hasUsableNavigationCapability(Bundle result) {
        int capabilities = capabilities(result);
        return (capabilities & CAP_SYSTEM_CONTEXT) != 0
                && (capabilities & (CAP_DIRECT_FID | CAP_INSTRUMENT_SDK)) != 0;
    }

    static List<Operation> operations(Bundle result) {
        if (result == null) return Collections.emptyList();
        String[] names = result.getStringArray(KEY_NAMES);
        int[] results = result.getIntArray(KEY_RESULTS);
        long[] durations = result.getLongArray(KEY_DURATIONS);
        String[] errors = result.getStringArray(KEY_ERRORS);
        if (names == null || results == null || durations == null || errors == null) {
            return Collections.emptyList();
        }
        int count = Math.min(Math.min(names.length, results.length),
                Math.min(durations.length, errors.length));
        List<Operation> parsed = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            parsed.add(new Operation(names[index], results[index],
                    durations[index], errors[index]));
        }
        return parsed;
    }

    static boolean validStatus(int status) {
        return status == VehicleTbtPublisher.STATUS_IDLE
                || status == VehicleTbtPublisher.STATUS_ACTIVE
                || status == VehicleTbtPublisher.STATUS_TEARDOWN;
    }

    static String legacyProcessName(int appUid) {
        if (appUid < 10_000) {
            throw new IllegalArgumentException("invalid app uid");
        }
        return PROCESS_PREFIX + appUid;
    }

    static String processName(int appUid, String launchToken) {
        String token = safe(launchToken);
        if (!validLaunchToken(token)) {
            throw new IllegalArgumentException("invalid launch token");
        }
        String uid = Integer.toString(appUid);
        if (appUid < 10_000) throw new IllegalArgumentException("invalid app uid");
        int tokenLength = Math.max(2, 15 - 3 - uid.length());
        return "bh" + uid + "_" + token.substring(0, tokenLength);
    }

    static boolean validLaunchToken(String launchToken) {
        return safe(launchToken).matches("[0-9a-f]{16}");
    }

    static long processStartTimeTicks(String statLine) {
        String value = statLine == null ? "" : statLine.trim();
        int commandEnd = value.lastIndexOf(") ");
        if (commandEnd < 0 || commandEnd + 2 >= value.length()) return -1L;
        String[] fields = value.substring(commandEnd + 2).trim().split("\\s+");
        if (fields.length <= 19) return -1L;
        try {
            long ticks = Long.parseLong(fields[19]);
            return ticks > 0L ? ticks : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static boolean validGuidance(int icon, int distanceMeters, String road) {
        return icon >= 0 && icon <= 49
                && distanceMeters >= -1 && distanceMeters <= 2_000_000
                && preserveText(road).length() <= 512;
    }

    static boolean validGuidance(int icon, int distanceMeters, String road,
            int[] laneDirections, int[] laneRecommendations) {
        return validGuidance(icon, distanceMeters, road)
                && validLanes(laneDirections, laneRecommendations);
    }

    static boolean validLanes(int[] laneDirections, int[] laneRecommendations) {
        if (laneDirections == null || laneRecommendations == null
                || laneDirections.length != laneRecommendations.length
                || laneDirections.length > HudLaneModel.MAX_LANES) {
            return false;
        }
        for (int index = 0; index < laneDirections.length; index++) {
            int direction = laneDirections[index];
            int recommendation = laneRecommendations[index];
            if (direction < 0 || direction >= 255
                    || (recommendation != 255
                    && (recommendation < 0 || recommendation >= 255))) {
                return false;
            }
        }
        return true;
    }

    static boolean requiredOperationsSucceeded(
            List<Operation> operations, int requiredCount) {
        if (operations == null || requiredCount <= 0 || requiredCount > operations.size()) {
            return false;
        }
        for (int index = 0; index < requiredCount; index++) {
            Operation operation = operations.get(index);
            if (operation == null || operation.result != 0 || !operation.error.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static final class Operation {
        final String name;
        final int result;
        final long durationMs;
        final String error;

        Operation(String name, int result, long durationMs, String error) {
            this.name = safe(name);
            this.result = result;
            this.durationMs = Math.max(0L, durationMs);
            this.error = safe(error);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String preserveText(String value) {
        return value == null ? "" : value;
    }
}
