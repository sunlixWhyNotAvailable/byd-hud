package com.bydhud.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

final class NavPermissionRuntimeProbe {
    enum BindingState { BOUND, NOT_BOUND, UNKNOWN }

    private NavPermissionRuntimeProbe() {
    }

    static BindingState accessibilityBindingState(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) {
            throw new IllegalStateException("AccessibilityManager is unavailable");
        }
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (services == null) {
            throw new IllegalStateException("AccessibilityManager returned a null service list");
        }
        // The public API also returns an empty list when its Binder request fails.
        if (services.isEmpty()) {
            return BindingState.UNKNOWN;
        }
        ComponentName expected = new ComponentName(context, NavAccessibilityService.class);
        for (AccessibilityServiceInfo service : services) {
            String id = service == null ? null : service.getId();
            ComponentName component = id == null ? null : ComponentName.unflattenFromString(id);
            if (component == null) {
                throw new IllegalStateException("AccessibilityManager returned an invalid service id: " + id);
            }
            if (expected.equals(component)) {
                return BindingState.BOUND;
            }
        }
        return BindingState.NOT_BOUND;
    }
}
