package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the exact shell helper ownership across app process and APK replacement. */
final class InstrumentProxyStore {
    private static final String PREFS = "byd_hud_instrument_proxy";
    private static final String KEY_PID = "pid";
    private static final String KEY_UID = "uid";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_NONCE = "nonce";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_PROCESS_NAME = "process_name";
    private static final String KEY_START_TICKS = "start_ticks";
    private static final String KEY_VERSION_CODE = "version_code";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_LEGACY_MIGRATED_UID = "legacy_migrated_uid";

    private InstrumentProxyStore() {
    }

    static Identity load(Context context) {
        SharedPreferences prefs = prefs(context);
        Identity identity = new Identity(
                prefs.getInt(KEY_PID, -1),
                prefs.getInt(KEY_UID, -1),
                prefs.getLong(KEY_GENERATION, -1L),
                prefs.getString(KEY_NONCE, ""),
                prefs.getString(KEY_TOKEN, ""),
                prefs.getString(KEY_PROCESS_NAME, ""),
                prefs.getLong(KEY_START_TICKS, -1L),
                prefs.getInt(KEY_VERSION_CODE, -1),
                prefs.getBoolean(KEY_CONNECTED, false));
        return identity.isValid() ? identity : Identity.none();
    }

    static boolean save(Context context, Identity identity) {
        if (identity == null || !identity.isValid()) return false;
        return prefs(context).edit()
                .putInt(KEY_PID, identity.pid)
                .putInt(KEY_UID, identity.uid)
                .putLong(KEY_GENERATION, identity.generation)
                .putString(KEY_NONCE, identity.nonce)
                .putString(KEY_TOKEN, identity.token)
                .putString(KEY_PROCESS_NAME, identity.processName)
                .putLong(KEY_START_TICKS, identity.startTimeTicks)
                .putInt(KEY_VERSION_CODE, identity.versionCode)
                .putBoolean(KEY_CONNECTED, identity.connected)
                .commit();
    }

    static boolean clear(Context context, Identity expected) {
        Identity stored = load(context);
        if (!stored.isValid()) return true;
        if (expected != null && expected.isValid() && !stored.sameLaunch(expected)) return false;
        return prefs(context).edit()
                .remove(KEY_PID)
                .remove(KEY_UID)
                .remove(KEY_GENERATION)
                .remove(KEY_NONCE)
                .remove(KEY_TOKEN)
                .remove(KEY_PROCESS_NAME)
                .remove(KEY_START_TICKS)
                .remove(KEY_VERSION_CODE)
                .remove(KEY_CONNECTED)
                .commit();
    }

    static boolean legacyMigrationComplete(Context context, int appUid) {
        return prefs(context).getInt(KEY_LEGACY_MIGRATED_UID, -1) == appUid;
    }

    static boolean markLegacyMigrationComplete(Context context, int appUid) {
        return prefs(context).edit().putInt(KEY_LEGACY_MIGRATED_UID, appUid).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Identity {
        final int pid;
        final int uid;
        final long generation;
        final String nonce;
        final String token;
        final String processName;
        final long startTimeTicks;
        final int versionCode;
        final boolean connected;

        Identity(int pid, int uid, long generation, String nonce,
                String token, String processName, long startTimeTicks,
                int versionCode, boolean connected) {
            this.pid = pid;
            this.uid = uid;
            this.generation = generation;
            this.nonce = safe(nonce);
            this.token = safe(token);
            this.processName = safe(processName);
            this.startTimeTicks = startTimeTicks;
            this.versionCode = versionCode;
            this.connected = connected;
        }

        static Identity pending(int uid, long generation, String nonce,
                String token, int versionCode) {
            return new Identity(-1, uid, generation, nonce, token,
                    InstrumentProxyContract.processName(uid, token),
                    -1L, versionCode, false);
        }

        Identity withPid(int pid) {
            return new Identity(pid, uid, generation, nonce, token,
                    processName, startTimeTicks, versionCode, connected);
        }

        Identity connected(int pid, long startTimeTicks) {
            return new Identity(pid, uid, generation, nonce, token,
                    processName, startTimeTicks, versionCode, true);
        }

        boolean isValid() {
            return uid >= 10_000
                    && generation > 0L
                    && nonce.matches("[0-9a-f]{32}")
                    && InstrumentProxyContract.validLaunchToken(token)
                    && processName.equals(InstrumentProxyContract.processName(uid, token))
                    && versionCode > 0;
        }

        boolean sameLaunch(Identity other) {
            return other != null
                    && generation == other.generation
                    && uid == other.uid
                    && nonce.equals(other.nonce)
                    && token.equals(other.token)
                    && processName.equals(other.processName);
        }

        static Identity none() {
            return new Identity(-1, -1, -1L, "", "", "", -1L, -1, false);
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
