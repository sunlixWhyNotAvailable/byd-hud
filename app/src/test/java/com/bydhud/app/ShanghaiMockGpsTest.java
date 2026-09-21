package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ShanghaiMockGpsTest {
    @Test public void appOpParserAcceptsVehicleAndCmdFormats() {
        assertEquals("default", ShanghaiMockGps.parseAppOpMode(
                "MOCK_LOCATION: default; time=+5s945ms ago"));
        assertEquals("allow", ShanghaiMockGps.parseAppOpMode(
                "Uid mode: android:mock_location: allow"));
        assertEquals("deny", ShanghaiMockGps.parseAppOpMode("MOCK_LOCATION: deny"));
        assertEquals("ignore", ShanghaiMockGps.parseAppOpMode("MOCK_LOCATION: ignore"));
        assertEquals("default", ShanghaiMockGps.parseAppOpMode("No operations."));
        assertNull(ShanghaiMockGps.parseAppOpMode("permission controller unavailable"));
    }

    @Test public void providerParserUsesOnlyGpsHeaderAndItsIdentity() {
        String owned = "Provider:\n"
                + " gps provider [mock]:\n"
                + "   listeners:\n"
                + "     10039/com.byd.launchermap/ABC Request[]\n"
                + "   enabled=true\n"
                + "   allowed=true\n"
                + "   identity=10103/com.bydhud.app\n"
                + "   last mock location=Location[gps 31.2304,121.4737 et=+1h2m3s mock]\n"
                + "Historical Aggregate Location Provider Data:\n"
                + " gps:\n"
                + "   10017/com.foreign.mock: locations=5\n";
        assertState(ShanghaiMockGps.ProviderKind.OWNED_MOCK, 10103, "com.bydhud.app",
                ShanghaiMockGps.parseProviderState(owned, "com.bydhud.app", 10103));
        assertEquals("Location[gps 31.2304,121.4737 et=+1h2m3s mock]",
                ShanghaiMockGps.parseProviderState(
                        owned, "com.bydhud.app", 10103).lastMockLocation);
        org.junit.Assert.assertTrue(ShanghaiMockGps.parseProviderState(
                owned, "com.bydhud.app", 10103).active);
        org.junit.Assert.assertFalse(ShanghaiMockGps.parseProviderState(
                owned.replace("enabled=true", "enabled=false"),
                "com.bydhud.app", 10103).active);

        String foreign = owned.replace("10103/com.bydhud.app", "10179/com.example.mock");
        assertState(ShanghaiMockGps.ProviderKind.FOREIGN_MOCK, 10179, "com.example.mock",
                ShanghaiMockGps.parseProviderState(foreign, "com.bydhud.app", 10103));

        String real = "  gps provider:\n"
                + "    listeners:\n"
                + "      10103/com.bydhud.app/ABC Request[]\n"
                + "    identity=1000/android[GnssService]\n";
        assertState(ShanghaiMockGps.ProviderKind.REAL, -1, "",
                ShanghaiMockGps.parseProviderState(real, "com.bydhud.app", 10103));
    }

    @Test public void providerParserFailsClosedForMissingOrAmbiguousGpsIdentity() {
        String listenerOnly = "passive provider:\n"
                + "  listeners:\n"
                + "    10103/com.bydhud.app/ABC Request[]\n"
                + "  identity=10103/com.bydhud.app\n";
        assertEquals(ShanghaiMockGps.ProviderKind.UNKNOWN,
                ShanghaiMockGps.parseProviderState(
                        listenerOnly, "com.bydhud.app", 10103).kind);
        assertEquals(ShanghaiMockGps.ProviderKind.UNKNOWN,
                ShanghaiMockGps.parseProviderState(
                        "gps provider [mock]:\n  listeners:\n", "com.bydhud.app", 10103).kind);
        assertEquals(ShanghaiMockGps.ProviderKind.UNKNOWN,
                ShanghaiMockGps.parseProviderState("", "com.bydhud.app", 10103).kind);
    }

    @Test public void crashWindowsNeverAuthorizeForeignOrManualRecoveryRemoval() {
        assertEquals(ShanghaiMockGps.RecoveryAction.RESTORE_PERMISSION_ONLY,
                ShanghaiMockGps.recoveryAction("owned", ShanghaiMockGps.ProviderKind.REAL));
        assertEquals(ShanghaiMockGps.RecoveryAction.REMOVE_OWNED,
                ShanghaiMockGps.recoveryAction("owned", ShanghaiMockGps.ProviderKind.OWNED_MOCK));
        assertEquals(ShanghaiMockGps.RecoveryAction.RESTORE_PERMISSION_ONLY,
                ShanghaiMockGps.recoveryAction("owned", ShanghaiMockGps.ProviderKind.FOREIGN_MOCK));
        assertEquals(ShanghaiMockGps.RecoveryAction.RESTORE_PERMISSION_ONLY,
                ShanghaiMockGps.recoveryAction("manual", ShanghaiMockGps.ProviderKind.OWNED_MOCK));
        assertEquals(ShanghaiMockGps.RecoveryAction.RESTORE_PERMISSION_ONLY,
                ShanghaiMockGps.recoveryAction("permission",
                        ShanghaiMockGps.ProviderKind.OWNED_MOCK));
        assertEquals(ShanghaiMockGps.RecoveryAction.WAIT_FOR_EVIDENCE,
                ShanghaiMockGps.recoveryAction("owned", ShanghaiMockGps.ProviderKind.UNKNOWN));
    }

    @Test public void recoveryRecordIsBoundToInstallationAndGpsProvider() {
        org.junit.Assert.assertTrue(ShanghaiMockGps.recordIdentityMatches(
                "com.bydhud.app", 10103, 0, "gps", "com.bydhud.app", 10103, 0));
        org.junit.Assert.assertFalse(ShanghaiMockGps.recordIdentityMatches(
                "com.bydhud.app", 10103, 0, "gps", "com.bydhud.app", 10104, 0));
        org.junit.Assert.assertFalse(ShanghaiMockGps.recordIdentityMatches(
                "com.bydhud.app", 10103, 0, "gps", "com.bydhud.app", 10103, 10));
        org.junit.Assert.assertFalse(ShanghaiMockGps.recordIdentityMatches(
                "com.bydhud.app", 10103, 0, "network", "com.bydhud.app", 10103, 0));
    }

    private static void assertState(ShanghaiMockGps.ProviderKind kind, int uid,
            String packageName, ShanghaiMockGps.ProviderState actual) {
        assertEquals(kind, actual.kind);
        assertEquals(uid, actual.uid);
        assertEquals(packageName, actual.packageName);
    }
}
