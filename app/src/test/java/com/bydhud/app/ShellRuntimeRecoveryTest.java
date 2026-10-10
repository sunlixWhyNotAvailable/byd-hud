package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShellRuntimeRecoveryTest {

    @Test public void lostClientCanReattachWithoutRestartingShellAndOldDeathCannotDetachNewClient() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        InstrumentNavigationProxyService runtime = new InstrumentNavigationProxyService(context, 7,
                "0123456789abcdef0123456789abcdef", Binder.getCallingUid(), "0123456789abcdef", 108);
        Client first = new Client();
        runtime.connect(7, "0123456789abcdef0123456789abcdef", first);
        assertTrue(runtime.isConnected());
        runtime.setRecoveryEnabled(7, true);
        runtime.suspendOutput(7);
        java.lang.reflect.Method death = InstrumentNavigationProxyService.class.getDeclaredMethod(
                "onClientDied", IInstrumentNavigationClient.class);
        death.setAccessible(true);
        death.invoke(runtime, first);
        assertFalse(runtime.isConnected());
        Client replacement = new Client();
        runtime.connect(7, "0123456789abcdef0123456789abcdef", replacement);
        assertTrue(runtime.isConnected());
        java.lang.reflect.Field suspension = InstrumentNavigationProxyService.class.getDeclaredField("outputSuspended");
        suspension.setAccessible(true);
        assertTrue(suspension.getBoolean(runtime));
        runtime.resumeOutput(7);
        assertFalse(suspension.getBoolean(runtime));
        death.invoke(runtime, first);
        assertTrue("stale death must not detach the replacement", runtime.isConnected());
        assertTrue(InstrumentProxyContract.hasCapability(replacement.result, InstrumentProxyContract.CAP_SHELL_RUNTIME));
    }

    private static final class Client extends IInstrumentNavigationClient.Stub {
        Bundle result;
        @Override public void onProxyConnected(long generation, Bundle result) { this.result = result; }
        @Override public void onProxyPong(long generation, long token) { }
        @Override public void onProxyStopping(long generation, String reason) { }
    }
}
