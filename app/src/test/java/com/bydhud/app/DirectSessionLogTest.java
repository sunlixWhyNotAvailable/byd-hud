package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.util.ReflectionHelpers.setStaticField;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class,
        shadows = DirectSessionLogTest.DebugWriter.class)
public final class DirectSessionLogTest {
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        DebugWriter.directRawLines.clear();
        DebugWriter.fallbackEvents.clear();
        DebugWriter.acceptDirectRaw = true;
        Method invalidate = NavigationLogStorage.class.getDeclaredMethod(
                "invalidatePublicRootCache");
        invalidate.setAccessible(true);
        invalidate.invoke(null);
        setStaticField(WazeCaptureDebugWriter.class, "instance", null);
    }

    @Test
    public void acceptedDirectFrameIsStoredOnceWithCanonicalSessionFields() throws Exception {
        DirectSessionLog session = DirectSessionLog.start(
                context, NavigationLogStorage.WAZE_DIRECT_DIR, "test");

        assertTrue(NavCaptureStore.directRawEvent(context, session, "waze_direct",
                "com.waze", "frame=data", 123L, 456L));

        assertEquals(1, DebugWriter.directRawLines.size());
        assertTrue(DebugWriter.fallbackEvents.isEmpty());
        JSONObject raw = new JSONObject(DebugWriter.directRawLines.get(0));
        assertEquals(2, raw.getInt("schemaVersion"));
        assertEquals(123L, raw.getLong("t"));
        assertEquals(456L, raw.getLong("ts"));
        assertFalse(raw.getString("localTs").isEmpty());
        assertEquals("waze-direct", raw.getString("source"));
        assertFalse(raw.getString("session").isEmpty());
        assertEquals("frame=data", raw.getString("raw"));
        assertEquals("waze_direct", raw.getString("channel"));
        assertEquals("com.waze", raw.getString("package"));
        assertFalse(raw.has("payload"));
        assertTrue(session.end("test"));
    }

    @Test
    public void identicalDirectOccurrencesAreBothRetained() throws Exception {
        DirectSessionLog session = DirectSessionLog.start(
                context, NavigationLogStorage.GMAPS_DIRECT_DIR, "test");

        assertTrue(NavCaptureStore.directRawEvent(context, session, "gmaps_direct",
                "com.google.android.apps.maps", "same-frame", 123L, 456L));
        assertTrue(NavCaptureStore.directRawEvent(context, session, "gmaps_direct",
                "com.google.android.apps.maps", "same-frame", 123L, 456L));

        assertEquals(2, DebugWriter.directRawLines.size());
        assertEquals(DebugWriter.directRawLines.get(0), DebugWriter.directRawLines.get(1));
        assertTrue(DebugWriter.fallbackEvents.isEmpty());
        assertTrue(session.end("test"));
    }

    @Test
    public void missingEndedOrRejectedSessionFallsBackWithTheCapturedTimestamp() throws Exception {
        assertTrue(NavCaptureStore.directRawEvent(context, null, "gmaps_direct",
                "com.google.android.apps.maps", "no-session", 10L, 20L));

        DirectSessionLog ended = DirectSessionLog.start(
                context, NavigationLogStorage.GMAPS_DIRECT_DIR, "test");
        assertTrue(ended.end("test"));
        assertTrue(NavCaptureStore.directRawEvent(context, ended, "gmaps_direct",
                "com.google.android.apps.maps", "ended-session", 30L, 40L));

        DirectSessionLog rejected = DirectSessionLog.start(
                context, NavigationLogStorage.GMAPS_DIRECT_DIR, "test");
        DebugWriter.acceptDirectRaw = false;
        assertTrue(NavCaptureStore.directRawEvent(context, rejected, "gmaps_direct",
                "com.google.android.apps.maps", "queue-rejected", 50L, 60L));

        assertEquals(0, DebugWriter.directRawLines.size());
        assertEquals(3, DebugWriter.fallbackEvents.size());
        assertEquals("no-session", DebugWriter.fallbackEvents.get(0).payload);
        assertEquals(10L, DebugWriter.fallbackEvents.get(0).elapsedMs);
        assertEquals(20L, DebugWriter.fallbackEvents.get(0).wallMs);
        assertEquals("ended-session", DebugWriter.fallbackEvents.get(1).payload);
        assertEquals(30L, DebugWriter.fallbackEvents.get(1).elapsedMs);
        assertEquals(40L, DebugWriter.fallbackEvents.get(1).wallMs);
        assertEquals("queue-rejected", DebugWriter.fallbackEvents.get(2).payload);
        assertEquals(50L, DebugWriter.fallbackEvents.get(2).elapsedMs);
        assertEquals(60L, DebugWriter.fallbackEvents.get(2).wallMs);
        assertTrue(rejected.end("test"));
    }

    @Implements(WazeCaptureDebugWriter.class)
    public static final class DebugWriter {
        private static final List<String> directRawLines = new ArrayList<>();
        private static final List<FallbackEvent> fallbackEvents = new ArrayList<>();
        private static boolean acceptDirectRaw;

        @Implementation
        protected static WazeCaptureDebugWriter get() {
            return Shadow.newInstanceOf(WazeCaptureDebugWriter.class);
        }

        @Implementation
        protected boolean appendDirectLine(File dir, String fileName, String line) {
            return true;
        }

        @Implementation
        protected boolean appendDirectRaw(File dir, String fileName, String line) {
            if (acceptDirectRaw) {
                directRawLines.add(line);
                return true;
            }
            return false;
        }

        @Implementation
        protected boolean endDirectSession(File dir, String fileName, String line) {
            NavigationLogStorage.closeDirectSession(dir);
            return true;
        }

        @Implementation
        protected boolean rawEvent(Context context, String channel, String packageName,
                String payload, long elapsedMs, long wallMs) {
            fallbackEvents.add(new FallbackEvent(payload, elapsedMs, wallMs));
            return true;
        }
    }

    private static final class FallbackEvent {
        final String payload;
        final long elapsedMs;
        final long wallMs;

        FallbackEvent(String payload, long elapsedMs, long wallMs) {
            this.payload = payload;
            this.elapsedMs = elapsedMs;
            this.wallMs = wallMs;
        }
    }
}
