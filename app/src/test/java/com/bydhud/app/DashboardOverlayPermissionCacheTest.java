package com.bydhud.app;

import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Permission.DENIED;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Permission.GRANTED;
import static com.bydhud.app.DashboardWidgetLifecyclePolicy.Permission.UNKNOWN;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DashboardOverlayPermissionCacheTest {
    private Object savedStatus;

    @Before public void saveStatus() throws Exception {
        savedStatus = statusField().get(null);
    }

    @After public void restoreStatus() throws Exception {
        statusField().set(null, savedStatus);
    }

    @Test public void emptyDeniedAndGrantedCacheValuesStayDistinct() throws Exception {
        statusField().set(null, null);
        assertEquals(UNKNOWN, MainActivity.cachedDashboardOverlayPermission());

        statusField().set(null, permissionStatus(false));
        assertEquals(DENIED, MainActivity.cachedDashboardOverlayPermission());

        statusField().set(null, permissionStatus(true));
        assertEquals(GRANTED, MainActivity.cachedDashboardOverlayPermission());
    }

    private static NavRuntimePermissionStatus permissionStatus(boolean overlayGranted) {
        NavPermissionStatus settings = NavPermissionStatus.forTest(
                true, true, true, overlayGranted, "notification", "accessibility");
        return NavRuntimePermissionStatus.fromSettingsForTest(
                settings, true, true, false, "notification", "accessibility");
    }

    private static Field statusField() throws Exception {
        Field field = MainActivity.class.getDeclaredField("cachedNavRuntimePermissionStatus");
        field.setAccessible(true);
        return field;
    }
}
