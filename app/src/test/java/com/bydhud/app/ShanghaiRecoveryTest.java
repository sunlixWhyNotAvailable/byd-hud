package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.location.LocationManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLocationManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class,
        shadows = {ShanghaiRecoveryTest.Adb.class, ShanghaiRecoveryTest.Gps.class})
public class ShanghaiRecoveryTest {
    private static String provider, appOp;
    private static int removals;

    @Test public void automaticRecoveryRemovesOnlyItsOwnedGpsAndKeepsUnknownPending() {
        Context context = RuntimeEnvironment.getApplication();
        int uid = context.getApplicationInfo().uid;
        for (String owner : new String[]{"foreign.app", "unknown", context.getPackageName()}) {
            context.getSharedPreferences("shanghai-mock-gps-recovery", 0).edit().clear()
                    .putBoolean("cleanup_required", true).putString("session_id", "interrupted")
                    .putString("kind", "owned").putString("package", context.getPackageName())
                    .putInt("uid", uid).putInt("user_id", uid / 100000)
                    .putString("provider", "gps").putString("prior_app_op", "default").commit();
            removals = 0;
            appOp = "allow";
            provider = owner.equals("unknown") ? "unavailable" :
                    "gps provider [mock]:\n  identity=" + uid + "/" + owner + "\n";
            ShanghaiMockGps gps = new ShanghaiMockGps(context);
            ShanghaiMockGps.Result result = gps.recoverOwned();
            assertEquals(owner, owner.equals(context.getPackageName()) ? 1 : 0, removals);
            if (owner.equals("unknown")) {
                assertEquals(ShanghaiMockGps.Code.UNKNOWN_PROVIDER, result.code);
                assertTrue(gps.hasPendingRecovery());
                assertEquals("allow", appOp);
            } else {
                assertTrue(result.detail, result.success());
                assertFalse(gps.hasPendingRecovery());
                assertEquals("default", appOp);
                if (owner.equals("foreign.app")) assertTrue(provider.contains("foreign.app"));
            }
        }
    }

    @Implements(LocalAdbBridge.class)
    public static class Adb {
        @Implementation protected static LocalAdbBridge.ShellResult readGpsProviderState(Context c) {
            return ok(provider);
        }
        @Implementation protected static LocalAdbBridge.ShellResult readOwnMockLocationAppOp(Context c) {
            return ok("MOCK_LOCATION: " + appOp);
        }
        @Implementation protected static LocalAdbBridge.ShellResult setOwnMockLocationAppOp(Context c, String mode) {
            appOp = mode;
            return ok("");
        }
        private static LocalAdbBridge.ShellResult ok(String output) {
            return new LocalAdbBridge.ShellResult(output, 0, output, false, 0, "success", "");
        }
    }

    @Implements(LocationManager.class)
    public static class Gps extends ShadowLocationManager {
        @Implementation protected void removeTestProvider(String name) {
            assertEquals("gps", name);
            removals++;
            provider = "gps provider:\n  identity=1000/android\n";
        }
    }
}
