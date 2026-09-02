package com.bydhud.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;

import org.json.JSONObject;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Export-only, shell-UID, getter-only one-shot. Deliberately does not use InstrumentProxyEntryPoint. */
public final class VehicleConfigurationReadbackEntryPoint {
    private final PrintStream output = System.out;
    private final Map<String, Object> devices = new HashMap<>();
    private final Map<String, Exception> deviceErrors = new HashMap<>();
    private Context context;
    private Exception contextError;

    public static void main(String[] args) {
        if (Process.myUid() != 2000 || (args != null && args.length != 0)) {
            throw new SecurityException("Fixed configuration readback requires shell UID and no arguments");
        }
        //Covers vendor initialization, Binder/native hangs and stdout blockage. Own PID only.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(VehicleConfigurationReadback.OEM_TIMEOUT_MS - 500L); }
            catch (InterruptedException ignored) { return; }
            Process.killProcess(Process.myPid());
        }, "bydhud-readback-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        int exitCode = 0;
        try {
            exitCode = new VehicleConfigurationReadbackEntryPoint().collect();
        } catch (Throwable error) {
            //No exception message or stack dump: framework exceptions can contain unrelated identities.
            System.out.println(VehicleConfigurationReadback.RECORD_PREFIX
                    + "{\"parameter\":\"readback.batch\",\"status\":\"error\",\"errorClass\":\""
                    + error.getClass().getName() + "\"}");
            System.out.flush();
            exitCode = 1;
        } finally {
            //SDKs can create internal threads. They must not keep this diagnostic process alive.
            System.exit(exitCode);
        }
    }

    private int collect() throws Exception {
        unsupported("hud.capabilityMask", "ICarHudManager.getHudConfig(featureMask)",
                "Safe client acquisition is not established; raw variant is not this capability mask");
        unsupported("hud.supportedModes", "ICarHudManager supported-mode getter",
                "Safe client acquisition is not established");
        unsupported("instrument.mileageUnit04", "0x4A504043",
                "Separate family; a typed getter is not established by the reviewed reference");
        unsupported("instrument.brightness", "0x23500030",
                "A target API/type contract is not established by the reviewed reference");
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "bydhud-readback-getter");
            thread.setDaemon(true);
            return thread;
        });
        try {
            boolean timedOut = false;
            for (VehicleConfigurationReadback.Read read : VehicleConfigurationReadback.READS) {
                long startedMs = System.currentTimeMillis();
                long startedNanos = System.nanoTime();
                if (timedOut) {
                    emit(record(read, startedMs, startedNanos, "skipped", null, null,
                            "Earlier getter timed out; isolated batch stopped"));
                    continue;
                }
                Future<Number> pending = worker.submit(() -> read(read));
                try {
                    Number value = pending.get(VehicleConfigurationReadback.GETTER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    boolean sentinel = VehicleConfigurationReadback.isSentinel(value);
                    boolean nonFinite = Double.isNaN(value.doubleValue()) || Double.isInfinite(value.doubleValue());
                    emit(record(read, startedMs, startedNanos, sentinel || nonFinite ? "error" : "success", value,
                            null, sentinel ? "Vendor read-error/unset sentinel; not an observed off state"
                                    : nonFinite ? "Vendor returned a non-finite value" : ""));
                } catch (TimeoutException error) {
                    pending.cancel(true);
                    timedOut = true; //Do not pile up threads behind an uninterruptible vendor getter.
                    emit(record(read, startedMs, startedNanos, "timeout", null, error,
                            "Getter exceeded 1500ms; no further OEM calls"));
                } catch (ExecutionException error) {
                    Throwable cause = rootCause(error);
                    emit(record(read, startedMs, startedNanos, failureStatus(cause), null, cause,
                            safeError(cause)));
                } catch (InterruptedException error) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                    emit(record(read, startedMs, startedNanos, "error", null, error, "Batch interrupted"));
                    return 1;
                }
            }
            return timedOut ? 124 : 0;
        } finally {
            worker.shutdownNow();
        }
    }

    private Number read(VehicleConfigurationReadback.Read read) throws Exception {
        requireDeclaredId(read);
        if (contextError != null) throw contextError;
        if (deviceErrors.containsKey(read.deviceClass)) throw deviceErrors.get(read.deviceClass);
        Object device = devices.get(read.deviceClass);
        if (device == null) {
            if (context == null) {
                try {
                    Looper.prepareMainLooper();
                    Class<?> activityThread = Class.forName("android.app.ActivityThread");
                    Object thread = activityThread.getMethod("systemMain").invoke(null);
                    context = new ReadPermissionContext((Context) activityThread.getMethod("getSystemContext").invoke(thread));
                } catch (Exception | LinkageError error) {
                    contextError = new InvocationTargetException(error);
                    throw contextError;
                }
            }
            try {
                Class<?> type = Class.forName(read.deviceClass);
                device = type.getMethod("getInstance", Context.class).invoke(null, context);
                if (device == null) throw new UnsupportedOperationException("Getter device unavailable");
                devices.put(read.deviceClass, device);
            } catch (Exception | LinkageError error) {
                Exception failure = new InvocationTargetException(error);
                deviceErrors.put(read.deviceClass, failure);
                throw failure;
            }
        }
        if (!read.namedGetter.isEmpty()) {
            Object value = device.getClass().getMethod(read.namedGetter).invoke(device);
            if (!(value instanceof Integer)) throw new UnsupportedOperationException("Unexpected named-getter type");
            return (Integer) value;
        }
        Object event = device.getClass().getMethod("get", int[].class, Class.class)
                .invoke(device, new int[]{read.id}, read.type);
        if (event == null) throw new UnsupportedOperationException("Getter returned no event");
        String valueField = read.type == Double.TYPE ? "doubleValue" : "intValue";
        Object value = event.getClass().getField(valueField).get(event);
        if (!(value instanceof Number)) throw new UnsupportedOperationException("Unexpected event value type");
        return (Number) value;
    }

    //Declarations gate these fixed recipes; they never select extra vehicle reads.
    private static void requireDeclaredId(VehicleConfigurationReadback.Read read) throws Exception {
        Class<?> catalog = Class.forName(read.catalogClass(), false,
                VehicleConfigurationReadbackEntryPoint.class.getClassLoader());
        if (!read.constant.isEmpty()) {
            Field field = catalog.getField(read.constant);
            if (isId(field, read.id)) return;
            throw new UnsupportedOperationException("Target constant differs from audited read identifier");
        }
        //Two reference AR-image getters use numeric constants; require target declaration of that exact ID.
        for (Field field : catalog.getFields()) {
            if (isId(field, read.id)) return;
        }
        throw new UnsupportedOperationException("Audited read identifier is not declared by target framework");
    }

    private static boolean isId(Field field, int id) throws IllegalAccessException {
        return Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                && field.getType() == Integer.TYPE && field.getInt(null) == id;
    }

    private JSONObject record(VehicleConfigurationReadback.Read read, long startedMs,
            long startedNanos, String status, Number raw, Throwable error, String detail) throws Exception {
        JSONObject record = base(read.parameter, read.api(), status, startedMs,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        record.put("id", String.format(java.util.Locale.ROOT, "0x%08X", read.id));
        record.put("constant", read.constant);
        record.put("valueType", read.type.getName());
        putValue(record, read, raw);
        record.put("errorClass", error == null ? JSONObject.NULL : error.getClass().getName());
        record.put("error", detail.isEmpty() ? JSONObject.NULL : detail);
        return record;
    }

    static void putValue(JSONObject record, VehicleConfigurationReadback.Read read, Number raw) throws Exception {
        //JSON cannot encode NaN/infinity as numbers; keep their raw spelling rather than inventing zero.
        record.put("rawValue", raw == null ? JSONObject.NULL
                : Double.isNaN(raw.doubleValue()) || Double.isInfinite(raw.doubleValue()) ? raw.toString() : raw);
        String interpretation = VehicleConfigurationReadback.interpretation(read, raw);
        if (!interpretation.isEmpty()) record.put("interpretation", interpretation);
        String semantics = VehicleConfigurationReadback.semanticStatus(read, raw);
        record.put("semanticStatus", semantics);
        record.put("semanticReason", VehicleConfigurationReadback.semanticReason(semantics));
    }

    private void unsupported(String parameter, String api, String reason) throws Exception {
        JSONObject record = base(parameter, api, "unsupported", System.currentTimeMillis(), 0);
        record.put("id", api.startsWith("0x") ? api : JSONObject.NULL);
        record.put("rawValue", JSONObject.NULL);
        record.put("valueType", "unknown");
        record.put("semanticStatus", "unknown");
        record.put("semanticReason", VehicleConfigurationReadback.semanticReason("unknown"));
        record.put("error", reason);
        emit(record);
    }

    private JSONObject base(String parameter, String api, String status, long timestamp, long duration) throws Exception {
        JSONObject record = new JSONObject();
        record.put("schemaVersion", 1);
        record.put("parameter", parameter);
        record.put("api", api);
        record.put("source", "fixed OEM getter readback; reference-backed, target support conditional");
        record.put("callerContext", "ADB app_process system context; COMMON/GET-only wrapper");
        record.put("uid", Process.myUid());
        record.put("userId", Process.myUid() / 100000);
        record.put("timestampMs", timestamp);
        record.put("durationMs", duration);
        record.put("status", status);
        return record;
    }

    private void emit(JSONObject record) {
        output.println(VehicleConfigurationReadback.RECORD_PREFIX + record);
        output.flush();
    }

    static Throwable rootCause(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    static String failureStatus(Throwable error) {
        if (error instanceof SecurityException) return "denied";
        if (error instanceof ClassNotFoundException || error instanceof LinkageError
                || error instanceof NoSuchMethodException || error instanceof NoSuchFieldException
                || error instanceof UnsupportedOperationException) return "unsupported";
        return "error";
    }

    private static String safeError(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "";
        //No routes, raw Binder parcels or arbitrary object serialization in readback errors.
        String sanitized = message.replaceAll("(?i)(vin|token|password|account|latitude|longitude)[=:][^\\s,;]+", "$1=<redacted>")
                .replaceAll("[\\r\\n\\t]", " ");
        return sanitized.substring(0, Math.min(sanitized.length(), 240));
    }

    static boolean allowedPermission(String permission) {
        return "android.permission.BYDAUTO_INSTRUMENT_COMMON".equals(permission)
                || "android.permission.BYDAUTO_INSTRUMENT_GET".equals(permission)
                || "android.permission.BYDAUTO_SETTING_COMMON".equals(permission)
                || "android.permission.BYDAUTO_SETTING_GET".equals(permission);
    }

    private static final class ReadPermissionContext extends ContextWrapper {
        ReadPermissionContext(Context base) { super(base); }
        @Override public int checkCallingOrSelfPermission(String permission) {
            if (allowedPermission(permission)) return PackageManager.PERMISSION_GRANTED;
            return isBydPermission(permission) ? PackageManager.PERMISSION_DENIED
                    : super.checkCallingOrSelfPermission(permission);
        }
        @Override public int checkCallingPermission(String permission) {
            if (allowedPermission(permission)) return PackageManager.PERMISSION_GRANTED;
            return isBydPermission(permission) ? PackageManager.PERMISSION_DENIED : super.checkCallingPermission(permission);
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            if (allowedPermission(permission)) return PackageManager.PERMISSION_GRANTED;
            return isBydPermission(permission) ? PackageManager.PERMISSION_DENIED : super.checkPermission(permission, pid, uid);
        }
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {
            if (allowedPermission(permission)) return;
            rejectBydPermission(permission);
            super.enforceCallingOrSelfPermission(permission, message);
        }
        @Override public void enforceCallingPermission(String permission, String message) {
            if (allowedPermission(permission)) return;
            rejectBydPermission(permission);
            super.enforceCallingPermission(permission, message);
        }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {
            if (allowedPermission(permission)) return;
            rejectBydPermission(permission);
            super.enforcePermission(permission, pid, uid, message);
        }
        private static boolean isBydPermission(String permission) {
            return permission != null && permission.startsWith("android.permission.BYDAUTO_");
        }
        private static void rejectBydPermission(String permission) {
            if (isBydPermission(permission)) throw new SecurityException("Readback permission rejected: " + permission);
        }
    }
}
