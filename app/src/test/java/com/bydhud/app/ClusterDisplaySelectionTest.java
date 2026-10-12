package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class ClusterDisplaySelectionTest {
    private DisplayManager displays;

    @Before public void setUp() {
        displays = (DisplayManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.DISPLAY_SERVICE);
    }

    @Test public void fullSharedHostWinsOverParentAndCardRegardlessOfIdOrOrder() {
        Display parent = addDisplay("fission_bg_XDJAScreenProjection");
        Display card = addDisplay("shared_fission_bg_XDJAScreenProjection_1");
        Display similar = addDisplay("shared_fission_bg_XDJAScreenProjection_0_extra");
        Display full = addDisplay("shared_fission_bg_XDJAScreenProjection_0");

        assertSame(full, ClusterProjectionService.chooseClusterDisplay(
                new Display[]{parent, card, similar, full}));
        assertSame(full, ClusterProjectionService.chooseClusterDisplay(
                new Display[]{full, similar, card, parent}));
    }

    @Test public void absentFullSharedHostPreservesLegacyFirstMatch() {
        Display parent = addDisplay("fission_bg_XDJAScreenProjection");
        Display card = addDisplay("shared_fission_bg_XDJAScreenProjection_1");
        Display remote = addDisplay("remote_dashboard");

        assertSame(parent, ClusterProjectionService.chooseClusterDisplay(
                new Display[]{remote, parent, card}));
        assertSame(card, ClusterProjectionService.chooseClusterDisplay(
                new Display[]{card, parent, remote}));
    }

    @Test public void detectedHostPersistsAndPercentagesUseItsAreaOncePerProcess() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Display host = addDisplay("shared_fission_bg_XDJAScreenProjection_0");
        android.graphics.Point actual = new android.graphics.Point();
        host.getRealSize(actual);
        DashboardProjectionPolicy.initialize(context);
        org.json.JSONObject saved = new org.json.JSONObject(HudPrefs.dashboardHostGeometry(context));
        assertEquals(actual.x, saved.getInt("width"));
        assertEquals(actual.y, saved.getInt("height"));
        assertTrue(DashboardProjectionPolicy.matchesHost(host));
        assertFalse(DashboardProjectionPolicy.matchesHost(displays.getDisplay(0)));
        ShadowDisplayManager.changeDisplay(host.getDisplayId(), "w1000dp-h500dp");
        DashboardProjectionPolicy.initialize(context);
        assertEquals(saved.toString(), HudPrefs.dashboardHostGeometry(context));
        DashboardProjectionPolicy.Geometry full = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(100, 75, 50, 100));
        assertEquals(actual.x, full.width);
        assertEquals(actual.y * 75 / 100, full.height);
        assertEquals(0, full.left);
        DashboardProjectionPolicy.Geometry half = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(50, 75, 50, 50));
        assertEquals(actual.x / 2, half.width);
        assertEquals((actual.x - half.width) / 2, half.left);
        assertEquals(half.width * 2, half.bufferWidth);
        assertEquals(half.height * 2, half.bufferHeight);
        // A fresh process initially has only persisted geometry, before a host appears.
        ShadowDisplayManager.removeDisplay(host.getDisplayId());
        org.robolectric.util.ReflectionHelpers.setStaticField(DashboardProjectionPolicy.class, "host", null);
        org.robolectric.util.ReflectionHelpers.setStaticField(DashboardProjectionPolicy.class, "initialized", false);
        org.robolectric.util.ReflectionHelpers.setStaticField(DashboardProjectionPolicy.class, "hostChecked", false);
        DashboardProjectionPolicy.initialize(context);
        DashboardProjectionPolicy.Geometry restored = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(50, 75, 50, 50));
        assertEquals(half.width, restored.width);
        assertEquals(half.height, restored.height);
        assertEquals(half.left, restored.left);
    }

    private Display addDisplay(String name) {
        return displays.getDisplay(ShadowDisplayManager.addDisplay("w1920dp-h720dp", name));
    }

    @Test public void newExternalIntentCanRepairDisplayAgainWithoutLoopingItsOwnLaunch() {
        Display dashboard = addDisplay("shared_fission_bg_XDJAScreenProjection_0");
        DisplayActivity activity = org.robolectric.Robolectric.buildActivity(DisplayActivity.class).get();
        activity.manager = activity.createDisplayContext(dashboard).getSystemService(android.view.WindowManager.class);
        activity.setIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN));
        MainActivityDisplayGuard guard = new MainActivityDisplayGuard();
        guard.check(activity, "resume");
        assertEquals(1, activity.launches);
        guard.check(activity, "resume");
        activity.setIntent(activity.lastLaunch);
        guard.check(activity, "new-intent");
        assertEquals("self-repair must not loop", 1, activity.launches);
        activity.setIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN));
        guard.check(activity, "new-intent");
        assertEquals("a genuine new launch gets its own repair", 2, activity.launches);
    }

    public static final class DisplayActivity extends android.app.Activity {
        android.view.WindowManager manager;
        int launches;
        android.content.Intent lastLaunch;
        @Override public android.view.WindowManager getWindowManager() { return manager; }
        @Override public void startActivity(android.content.Intent intent, android.os.Bundle options) {
            launches++;
            lastLaunch = intent;
        }
    }
}
