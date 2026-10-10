package com.bydhud.app;

import static org.junit.Assert.assertSame;

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

    private Display addDisplay(String name) {
        return displays.getDisplay(ShadowDisplayManager.addDisplay("w1920dp-h720dp", name));
    }
}
