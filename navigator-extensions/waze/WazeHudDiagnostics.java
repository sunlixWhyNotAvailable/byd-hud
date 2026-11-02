package com.bydhud.mapcapture;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** Observes stock alert boundaries without changing delivery or retaining their owners. */
public final class WazeHudDiagnostics {
    private static final Map<String, String> LAST = new LinkedHashMap<>();

    public static void producer(Object owner, Object context) { record("producer", owner, context, null); }
    public static void input(Object owner, Object alert) { record("input", owner, null, alert); }
    public static void publish(Object owner, Object alert) { record("publish", owner, null, alert); }
    public static void collect(Object owner, Object alert) { record("collect", owner, null, alert); }
    public static void resume(Object owner, Object lifecycle) { record("resume", owner, lifecycle, null); }
    public static void pause(Object owner, Object lifecycle) { record("pause", owner, lifecycle, null); }

    private static synchronized void record(String stage, Object owner, Object context, Object alert) {
        if (!LifecycleJournalTransport.detailed()) return;
        try {
            // Same observer key for resume/pause preserves each transition across repeated resumes.
            String key = (stage.equals("resume") || stage.equals("pause") ? "lifecycle" : stage)
                    + ":" + identity(owner);
            String detail = "stage=" + stage + " owner=" + identity(owner);
            if (stage.equals("collect")) context = field(owner, "d");
            if (stage.equals("resume") || stage.equals("pause")) context = field(owner, "c");
            if (context != null) detail += " context=" + identity(context);
            if (alert == null) detail += " alert=null";
            else if (alert.getClass().getName().equals("com.waze.k")) {
                detail += " alert=native type=" + enumName(field(alert, "o"))
                        + " key=" + alertKey(field(alert, "a"));
            } else if (alert.getClass().getName().equals("com.waze.car_lib.a.q")) {
                detail += " alert=car id=" + field(alert, "a") + " type=" + enumName(field(alert, "j"))
                        + " key=" + alertKey(field(alert, "b"));
            } else detail += " alert=" + identity(alert);
            if (detail.equals(LAST.get(key))) return;
            if (LAST.size() >= 64 && !LAST.containsKey(key)) LAST.remove(LAST.keySet().iterator().next());
            LAST.put(key, detail);
            BackgroundCapture.event("hud_alert_boundary", null, detail);
        } catch (Throwable error) {
            BackgroundCapture.failure("hud_alert_diagnostic_" + stage, error);
        }
    }

    private static String identity(Object value) {
        return value == null ? "null" : value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }
    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : identity(value);
    }
    private static String alertKey(Object value) {
        // Stock com.waze.l hashes its provider/object/type identifiers; no caption or address is logged.
        return value == null ? "null" : Integer.toHexString(value.hashCode());
    }
    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
}
