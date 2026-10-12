package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = ShellShanghaiRouteTokenTest.Transport.class)
public final class ShellShanghaiRouteTokenTest {
    @Test public void generatedSessionTokensAreAcceptedByActualDiagnosticCommand() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String before = context.getApplicationInfo().sourceDir;
        context.getApplicationInfo().sourceDir = "/data/app/com.bydhud.app-test/base.apk";
        Transport.commands.clear();
        try {
            String first = ShellShanghaiRoute.tokenFromSessionId("shanghai_session-with-nonhex-prefix");
            String second = ShellShanghaiRoute.tokenFromSessionId("another-session");
            assertNotEquals(first, second);
            assertEquals(first, ShellShanghaiRoute.tokenFromSessionId("shanghai_session-with-nonhex-prefix"));
            LocalAdbBridge.openShanghaiAdasStream(context, first);
            LocalAdbBridge.openShanghaiAdasStream(context, second);
            assertEquals(2, Transport.commands.size());
            assertTrue(Transport.commands.get(0).contains("--nice-name=bydhud-sh-" + first + "-adas"));
            assertTrue(Transport.commands.get(1).contains("--nice-name=bydhud-sh-" + second + "-adas"));
            assertThrows(SecurityException.class, () -> LocalAdbBridge.openShanghaiAdasStream(context, "shanghai_bad-token"));
            assertEquals(2, Transport.commands.size());
        } finally { context.getApplicationInfo().sourceDir = before; }
    }
    @Implements(LocalAdbBridge.class)
    public static class Transport {
        static final List<String> commands = new ArrayList<>();
        @Implementation protected static LocalAdbBridge.ShanghaiStreamSession openShanghaiStream(Context context, String command) {
            commands.add(command);
            return null;
        }
    }
}
