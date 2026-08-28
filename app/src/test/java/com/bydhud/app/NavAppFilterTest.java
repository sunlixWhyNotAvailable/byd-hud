package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavAppFilterTest {
    @Test
    public void abrpRemainsVisibleButIsNotASelectedHudNavigator() {
        assertFalse(NavAppFilter.isCuratedNavigationPackage("com.iternio.abrpapp"));
        assertFalse(NavAppFilter.shouldHideFromCaptureList("com.iternio.abrpapp"));
        assertTrue(NavAppFilter.isCuratedNavigationPackage("com.waze"));
        assertTrue(NavAppFilter.isCuratedNavigationPackage("app.revanced.android.apps.maps"));
        assertTrue(NavAppFilter.isCuratedNavigationPackage("com.google.android.apps.maps"));
    }
}
