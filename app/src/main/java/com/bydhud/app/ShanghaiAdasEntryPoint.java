package com.bydhud.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shell-UID, read-only, fixed six-FID ADAS sampler launched from the installed APK. */
public final class ShanghaiAdasEntryPoint {
    static final int[] FIDS = {
            0x2D500020, 0x4320002A, 0x1FF02012,
            0x2370003A, 0x31600025, 0x2D50000F
    };
    private static final String[] NAMES = {
            "speed_limit", "tsr_map_config", "smart_speed_limit_control_mode",
            "speed_limit_preview_state", "sla_state", "isla_switch"
    };
    private static final long INTERVAL_MS = 200L;
    private static final long HARD_LIMIT_MS = ShanghaiDiagnostics.HELPER_HARD_LIMIT_SECONDS * 1000L;

    public static void main(String[] args) {
        if (Process.myUid() != 2000 || (args != null && args.length != 0)) {
            throw new SecurityException("Shanghai ADAS helper requires shell UID and no arguments");
        }
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(HARD_LIMIT_MS); }
            catch (InterruptedException ignored) { return; }
            Process.killProcess(Process.myPid());
        }, "shanghai-adas-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        int exitCode = 0;
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            sample();
        } catch (Throwable error) {
            emit(ShanghaiJson.object("event", "adas_sampler_error",
                    "wallMs", System.currentTimeMillis(),
                    "errorClass", root(error).getClass().getName()));
            exitCode = 1;
        } finally {
            System.out.flush();
            System.exit(exitCode);
        }
    }

    private static void sample() throws Exception {
        Context context = systemContext();
        Class<?> type = Class.forName("android.hardware.bydauto.adas.BYDAutoADASDevice");
        Object device = type.getMethod("getInstance", Context.class).invoke(null, context);
        if (device == null) throw new UnsupportedOperationException("ADAS device unavailable");
        Method get = type.getMethod("get", int[].class, Class.class);
        emit(new JSONObject().put("event", "adas_sampler_ready")
                .put("wallMs", System.currentTimeMillis()).put("fidCount", FIDS.length));
        long sequence = 0L;
        while (!Thread.currentThread().isInterrupted()) {
            long sweepStarted = android.os.SystemClock.elapsedRealtime();
            sequence++;
            for (int index = 0; index < FIDS.length; index++) {
                long readStarted = android.os.SystemClock.elapsedRealtime();
                JSONObject record = new JSONObject().put("event", "adas_sample")
                        .put("sequence", sequence)
                        .put("featureId", String.format(java.util.Locale.ROOT, "0x%08X", FIDS[index]))
                        .put("name", NAMES[index])
                        .put("wallMs", System.currentTimeMillis())
                        .put("elapsedMs", readStarted);
                try {
                    Object event = get.invoke(device, new int[]{FIDS[index]}, int.class);
                    if (event == null) throw new UnsupportedOperationException("ADAS getter returned null");
                    int raw = event.getClass().getField("intValue").getInt(event);
                    record.put("raw", raw).put("success", raw != -999999999);
                    if (raw == -999999999) record.put("error", "ADAS framework no-value sentinel");
                    if (index == 0) {
                        record.put("decodedKmh", raw >= 1 && raw <= 51
                                ? (raw - 1) * 5 : JSONObject.NULL);
                    }
                } catch (Throwable error) {
                    Throwable cause = root(error);
                    record.put("success", false).put("errorClass", cause.getClass().getName())
                            .put("error", safeError(cause));
                }
                record.put("readDurationMs", android.os.SystemClock.elapsedRealtime() - readStarted);
                emit(record);
            }
            long remaining = INTERVAL_MS
                    - (android.os.SystemClock.elapsedRealtime() - sweepStarted);
            if (remaining > 0L) android.os.SystemClock.sleep(remaining);
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context base = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        return new AdasReadContext(base);
    }

    private static void emit(JSONObject record) {
        System.out.println(record.toString());
        System.out.flush();
    }

    private static Throwable root(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static String safeError(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "";
        String safe = message.replaceAll("[\\r\\n\\t]", " ");
        return safe.substring(0, Math.min(240, safe.length()));
    }

    private static final class AdasReadContext extends ContextWrapper {
        AdasReadContext(Context base) { super(base); }
        @Override public int checkCallingOrSelfPermission(String permission) {
            return allowed(permission) ? PackageManager.PERMISSION_GRANTED
                    : deniedByd(permission) ? PackageManager.PERMISSION_DENIED
                    : super.checkCallingOrSelfPermission(permission);
        }
        @Override public int checkCallingPermission(String permission) {
            return allowed(permission) ? PackageManager.PERMISSION_GRANTED
                    : deniedByd(permission) ? PackageManager.PERMISSION_DENIED
                    : super.checkCallingPermission(permission);
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return allowed(permission) ? PackageManager.PERMISSION_GRANTED
                    : deniedByd(permission) ? PackageManager.PERMISSION_DENIED
                    : super.checkPermission(permission, pid, uid);
        }
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {
            if (allowed(permission)) return;
            reject(permission);
            super.enforceCallingOrSelfPermission(permission, message);
        }
        @Override public void enforceCallingPermission(String permission, String message) {
            if (allowed(permission)) return;
            reject(permission);
            super.enforceCallingPermission(permission, message);
        }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {
            if (allowed(permission)) return;
            reject(permission);
            super.enforcePermission(permission, pid, uid, message);
        }
        private static boolean allowed(String permission) {
            return "android.permission.BYDAUTO_ADAS_COMMON".equals(permission)
                    || "android.permission.BYDAUTO_ADAS_GET".equals(permission);
        }
        private static boolean deniedByd(String permission) {
            return permission != null && permission.startsWith("android.permission.BYDAUTO_");
        }
        private static void reject(String permission) {
            if (deniedByd(permission)) throw new SecurityException("ADAS read permission rejected");
        }
    }
}
