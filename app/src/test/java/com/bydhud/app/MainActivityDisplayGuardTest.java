package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 29,
        shadows = MainActivityDisplayGuardTest.EventLog.class)
public final class MainActivityDisplayGuardTest {
    private RecordingActivity activity;
    private MainActivityDisplayGuard guard;

    @Before public void setUp() {
        EventLog.lines.clear();
        activity = new RecordingActivity();
        activity.setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        guard = new MainActivityDisplayGuard();
    }

    @Test public void tabletLaunchDoesNotRestartOrFinishTheActivity() {
        guard.check(activity, "create");
        guard.check(activity, "resume");
        assertEquals(0, activity.launches);
        assertFalse(activity.isFinishing());
        assertTrue(EventLog.lines.get(0).contains("display=0 target=0 launcher=true"));
    }

    @Test public void secondaryDisplayRedirectsOnlyMainAndBoundsUnconfirmedRetries() {
        activity.displayId = ShadowDisplayManager.addDisplay("1920x540");
        guard.check(activity, "create");
        assertEquals(MainActivity.class.getName(), activity.launched.getComponent().getClassName());
        ActivityOptions options = ReflectionHelpers.callConstructor(ActivityOptions.class,
                ClassParameter.from(Bundle.class, activity.options));
        assertEquals(0, options.getLaunchDisplayId());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP, activity.launched.getFlags());
        assertFalse(activity.isFinishing());
        guard.check(activity, "resume");
        activity.setIntent(activity.launched);
        new MainActivityDisplayGuard().check(activity, "recreated");
        assertEquals(1, activity.launches);
        assertTrue(EventLog.lines.get(EventLog.lines.size() - 1).endsWith("unconfirmed_no_retry"));
        activity.displayId = 0;
        guard.check(activity, "configuration");
        assertTrue(EventLog.lines.get(EventLog.lines.size() - 1).endsWith("confirmed"));
    }

    @Test public void rejectedLaunchIsLoggedAndNotRepeatedAcrossRecreation() {
        activity.displayId = ShadowDisplayManager.addDisplay("1920x540");
        activity.reject = true;
        guard.check(activity, "create");
        assertTrue(EventLog.lines.get(0).endsWith("failed error=SecurityException"));
        new MainActivityDisplayGuard().check(activity, "recreated");
        assertEquals(1, activity.launches);
    }

    @Test public void finishingActivityIsNotReopened() {
        activity.displayId = ShadowDisplayManager.addDisplay("1920x540");
        activity.finish();
        guard.check(activity, "resume");
        assertEquals(0, activity.launches);
    }

    public static final class RecordingActivity extends Activity {
        int displayId;
        int launches;
        boolean reject;
        Intent launched;
        Bundle options;

        RecordingActivity() { attachBaseContext(RuntimeEnvironment.getApplication()); }

        @Override public WindowManager getWindowManager() {
            return createDisplayContext(getSystemService(DisplayManager.class).getDisplay(displayId))
                    .getSystemService(WindowManager.class);
        }

        @Override public void startActivity(Intent intent, Bundle bundle) {
            launches++;
            if (reject) throw new SecurityException("injected denial");
            launched = intent;
            options = bundle;
        }
    }

    @Implements(AppEventLogger.class)
    public static class EventLog {
        static final List<String> lines = new ArrayList<>();
        @Implementation protected static void event(Context context, String line) { lines.add(line); }
    }
}
