package com.bydhud.mapcapture;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Maps 26.30 controller ownership; called only on the navigator's main thread. */
final class MapsFollowing {
    private static Object resuming;
    final WeakReference<Object> owner, map;
    boolean stopped, owned;
    private boolean stopPrepared;
    private int cameraRestores;
    private Object savedMode, savedRelative, deferredOff;
    private boolean savedAnimated;

    MapsFollowing(Object controller) throws Exception {
        owner = new WeakReference<>(controller);
        map = new WeakReference<>(get(get(controller, null, "d"), null, "aq"));
    }
    void rememberMode() throws Exception {
        if (stopPrepared) return;
        Object controller = owner.get();
        if (controller == null) return;
        Object mode = call(controller, "b");
        Object driver = get(controller, null, (Boolean)get(controller, null, "g") ? "m" : "l");
        savedRelative = driver == null ? null : get(driver, null, "c");
        savedAnimated = driver != null && (Boolean)get(driver, null, "e");
        savedMode = mode;
    }
    void prepareStop() throws Exception {
        rememberMode();
        stopPrepared = true;
    }
    boolean stopping(boolean active, Object selectedMap) throws Exception {
        prepareStop();
        stopped = true;
        owned = active && selectedMap != null && map.get() == selectedMap;
        if (!owned) applyDeferredOff();
        return owned;
    }
    void starting() throws Exception {
        release(); // Balance our retained start before the real OEM start registers again.
        sceneStarted();
    }
    void sceneStarted() {
        // The map scene can enter foreground before the OEM location start hook.
        stopped = false;
        stopPrepared = false;
        savedMode = savedRelative = null;
    }
    boolean resume() throws Exception {
        Object controller = owner.get();
        if (!stopped || controller == null || map.get() == null) return false;
        Object previous = resuming;
        try {
            // Reuse the complete OEM registration path. Its camera-mode reset alone
            // is suppressed by the j() hook during this capture-owned start.
            if (!owned) {
                owned = true;
                resuming = controller;
                try { call(controller, "g", new Class<?>[]{boolean.class}, false); }
                finally { resuming = previous; }
            }
            // The OEM UI may have set OFF before our first lease. Reattach the
            // saved camera through its setter; return that OFF when capture ends.
            Object current = call(controller, "b");
            if (savedMode != null && !isOff(savedMode) && isOff(current)) deferredOff = current;
            // A new scene needs its camera driver rebound even when location
            // subscriptions survived onStop and the mode still says TRACKING.
            if (savedMode != null && !isOff(savedMode)) {
                setMode(savedMode, savedRelative, savedAnimated);
                cameraRestores++;
            }
            return true;
        } catch (Exception error) {
            try { release(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    boolean needsRelease() { return owned || deferredOff != null; }
    void release() throws Exception {
        try { applyDeferredOff(); }
        finally {
            if (owned) {
                owned = false;
                Object controller = owner.get();
                if (controller != null) call(controller, "h");
            }
        }
    }
    static boolean preserveMode(Object controller) { return resuming == controller; }
    static Object requestedMode(Object request) throws Exception {
        return request == null || request instanceof Enum<?> ? request : get(get(request, null, "e"), null, "c");
    }
    boolean suppressOff(boolean selectedBackground, Object request) throws Exception {
        Object requested = requestedMode(request);
        if (!selectedBackground || !isOff(requested)) return false;
        prepareStop();
        if (savedMode == null || isOff(savedMode)) return false;
        deferredOff = requested;
        return true;
    }
    private static boolean isOff(Object mode) { return mode instanceof Enum<?> && ((Enum<?>)mode).name().equals("OFF"); }
    private void applyDeferredOff() throws Exception {
        Object mode = deferredOff;
        deferredOff = null;
        if (mode != null) setMode(mode, null, true);
    }
    private void setMode(Object mode, Object relative, boolean animated) throws Exception {
        Object controller = owner.get();
        if (controller == null) return;
        for (Method method : controller.getClass().getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("j") && types.length == 3 && types[0].isInstance(mode)
                    && types[2] == boolean.class) {
                method.setAccessible(true); method.invoke(controller, mode, relative, animated); return;
            }
        }
        throw new NoSuchMethodException("auto-pan setter j(mode,relative,animated)");
    }
    String info() throws Exception {
        Object controller = owner.get();
        if (controller == null) return "following=gone";
        Object location = get(controller, null, "d");
        return "following=" + controller.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(controller))
                + " owned=" + owned + " stopped=" + stopped
                + " locStarted=" + get(location, null, "r")
                + " locEntities=" + get(location, null, "v")
                + " locationAgeMs=" + call(location, "f") + " location=" + brief(call(location, "i"),64)
                + " inputBearing=" + call(location, "b")
                + " mode=" + brief(call(controller, "b"),24) + " savedMode=" + brief(savedMode,24)
                + " deferredOff=" + (deferredOff != null) + " locMode=" + brief(call(location, "h"),24)
                + " cameraRestores=" + cameraRestores
                + " camera=" + brief(call(get(controller, null, "q"), "a"),128);
    }
    private static String brief(Object value, int limit) {
        String text = String.valueOf(value); return text.length() <= limit ? text : text.substring(0,limit);
    }
    static Object get(Object object, String owner, String name) throws Exception {
        Class<?> cls = owner == null ? object.getClass() : Class.forName(owner, false, object.getClass().getClassLoader());
        while (cls != null) {
            try { Field field = cls.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException missing) { cls = cls.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> cls = object.getClass();
        while (cls != null) {
            try { Method method = cls.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args); }
            catch (NoSuchMethodException missing) { cls = cls.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }
    static Object call(Object object, String name) throws Exception { return call(object, name, new Class<?>[0]); }
}
