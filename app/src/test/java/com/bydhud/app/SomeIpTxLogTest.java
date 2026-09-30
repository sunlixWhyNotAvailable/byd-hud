package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.util.ReflectionHelpers.setStaticField;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class,
        shadows = {SomeIpTxLogTest.DebugWriter.class, SomeIpTxLogTest.Store.class})
public final class SomeIpTxLogTest {
    private static final List<String> lines = new ArrayList<>();

    private Context context;
    private SomeIpTxLog log;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .clear().commit();
        lines.clear();
        setStaticField(SomeIpTxLog.class, "instance", null);
        HudPrefs.setDetailedDebugArtifactsEnabled(context, true);
        log = SomeIpTxLog.get(context);
    }

    @After
    public void tearDown() {
        HudPrefs.setDetailedDebugArtifactsEnabled(context, false);
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .clear().commit();
        setStaticField(SomeIpTxLog.class, "instance", null);
    }

    @Test
    public void omittedLiveMapDoesNotPolluteFullPayloadDeduplication() throws Exception {
        byte[] payload = new byte[]{1, 2, 3};
        log.recordSend("direct", "navigator_map", 7L, "frame", "live_map",
                payload, payload, 0, null, 4L, false);
        log.recordSend("direct", "navigator_map", 7L, "frame", "live_map",
                payload, payload, 0, null, 5L, false);
        log.recordSend("direct", "navigator_map", 7L, "frame", "live_map",
                payload, payload, 0, null, 6L, true);
        log.recordSend("direct", "navigator_map", 7L, "frame_retry", "live_map",
                payload, payload, 0, null, 7L, true);

        assertEquals(4, lines.size());
        JSONObject omittedSend = new JSONObject(lines.get(0));
        assertEquals("send", omittedSend.getString("event"));
        assertEquals(payload.length, omittedSend.getInt("payloadLength"));
        assertEquals(0, omittedSend.getInt("result"));
        assertEquals(4L, omittedSend.getLong("durationMs"));
        assertTrue(omittedSend.has("payloadSha256"));
        assertEquals("live_map", omittedSend.getString("payloadOmitted"));
        assertFalse(omittedSend.has("payloadBase64"));
        assertFalse(omittedSend.has("payloadRefSha256"));

        JSONObject omittedRepeat = new JSONObject(lines.get(1));
        assertEquals("repeat", omittedRepeat.getString("event"));
        assertEquals("live_map", omittedRepeat.getString("payloadOmitted"));
        assertFalse(omittedRepeat.has("payloadRefSha256"));

        JSONObject firstFullSend = new JSONObject(lines.get(2));
        assertEquals("send", firstFullSend.getString("event"));
        assertEquals("AQID", firstFullSend.getString("payloadBase64"));
        assertFalse(firstFullSend.has("payloadRefSha256"));

        JSONObject deduplicatedFullSend = new JSONObject(lines.get(3));
        assertEquals("send", deduplicatedFullSend.getString("event"));
        assertEquals(firstFullSend.getString("payloadSha256"),
                deduplicatedFullSend.getString("payloadRefSha256"));
        assertFalse(deduplicatedFullSend.has("payloadBase64"));
        assertFalse(deduplicatedFullSend.has("payloadOmitted"));
    }

    @Test
    public void legacyRecordSendRetainsPayloadByDefault() throws Exception {
        byte[] payload = new byte[]{9, 8};
        log.recordSend("manual", "check", 3L, "check", "manual", payload,
                payload, 0, null, 2L);

        assertEquals(1, lines.size());
        JSONObject json = new JSONObject(lines.get(0));
        assertEquals("CQg=", json.getString("payloadBase64"));
        assertFalse(json.has("payloadOmitted"));
    }

    @Implements(WazeCaptureDebugWriter.class)
    public static final class DebugWriter {
        @Implementation
        protected static WazeCaptureDebugWriter get() {
            return Shadow.newInstanceOf(WazeCaptureDebugWriter.class);
        }

        @Implementation
        protected boolean someIpTx(Runnable work) {
            work.run();
            return true;
        }
    }

    @Implements(NavCaptureStore.class)
    public static final class Store {
        @Implementation
        protected static void writeSomeIpTx(Context context, String targetDay, String line) {
            lines.add(line);
        }
    }
}
