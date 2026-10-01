package com.bydhud.mapcapture;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Selected Maps 26.30 camera and its upstream UI-state source; main thread only. */
final class MapsNavigationCamera {
    final WeakReference<Object> owner, map, tracker, dispatcher, sink;
    boolean stopped, owned, uiStopped, uiOwned;
    private Object state, previousState;
    private Method update;
    private long states, dispatches, applications;

    MapsNavigationCamera(Object controller, Object following) throws Exception {
        owner = new WeakReference<>(controller);
        map = new WeakReference<>(MapsFollowing.get(MapsFollowing.get(following, null, "d"), null, "aq"));
        Object ui = MapsFollowing.get(controller, null, "c");
        tracker = new WeakReference<>(ui);
        dispatcher = new WeakReference<>(MapsFollowing.get(ui, null, "p"));
        sink = new WeakReference<>(MapsFollowing.get(controller, null, "q"));
        uiStopped = !(Boolean)MapsFollowing.get(ui, null, "e");
    }
    void state(Object current, Object previous) {
        state = current; previousState = previous; states++;
    }
    boolean stopping(boolean active, Object selectedMap) {
        stopped = true;
        owned = active && selectedMap != null && map.get() == selectedMap;
        return owned;
    }
    boolean uiStopping(boolean active, Object selectedMap) {
        uiStopped = true;
        uiOwned = active && selectedMap != null && map.get() == selectedMap;
        return uiOwned;
    }
    void starting() throws Exception {
        releaseCamera(); // UI and camera OEM starts can arrive in either order.
        stopped = false;
    }
    void uiStarting() throws Exception {
        releaseUi();
        uiStopped = false;
    }
    boolean resume() throws Exception {
        Object controller = owner.get(), ui = tracker.get();
        if (!stopped || controller == null || ui == null || map.get() == null) return false;
        try {
            if (!owned) {
                owned = true;
                MapsFollowing.call(controller, "e");
                if (state != null) applyState(state, previousState);
            }
            if (uiStopped) {
                if (!uiOwned) {
                    uiOwned = true;
                    MapsFollowing.call(ui, "e");
                }
                // Reapply fresh state after the offscreen scene rebound its camera driver.
                MapsFollowing.call(ui, "v");
            }
            return true;
        } catch (Exception error) {
            try { release(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    boolean dispatch(Object current, Object previous) throws Exception {
        if (!uiOwned || !owned || !stopped) return false;
        dispatches++;
        applyState(current, previous);
        return true;
    }
    private void applyState(Object current, Object previous) throws Exception {
        Object controller = owner.get();
        if (controller == null || current == null) return;
        if (update == null) {
            for (Method method : controller.getClass().getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals("pF") && types.length == 2 && types[0].isInstance(current)) {
                    update = method; update.setAccessible(true); break;
                }
            }
            if (update == null) throw new NoSuchMethodException("navigation camera pF(state,previous)");
        }
        update.invoke(controller, current, previous);
    }
    void applied() { applications++; }
    boolean needsRelease() { return owned || uiOwned; }
    void release() throws Exception {
        // Shut off upstream delivery before the camera and location subscriptions.
        try { releaseUi(); }
        finally { releaseCamera(); }
    }
    private void releaseUi() throws Exception {
        if (!uiOwned) return;
        uiOwned = false;
        Object ui = tracker.get();
        if (ui != null) MapsFollowing.call(ui, "f");
    }
    private void releaseCamera() throws Exception {
        if (!owned) return;
        owned = false;
        Object controller = owner.get();
        if (controller != null) MapsFollowing.call(controller, "f");
    }
    String info() {
        return "navigationCameraOwned=" + owned + " navigationCameraStopped=" + stopped
                + " navigationUiOwned=" + uiOwned + " navigationUiStopped=" + uiStopped
                + " navigationStateSaved=" + (state != null) + " states=" + states
                + " backgroundDispatches=" + dispatches + " cameraApplications=" + applications;
    }
}
