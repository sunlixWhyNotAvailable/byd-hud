package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.HandlerThread;
import com.bydhud.gmapswire.GmapsWireParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Feed the real parser/channel with bounded protobuf fixtures, without a navigator or car. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class GMapsGuidanceReliabilityTest {
    private GMapsDirectChannel channel;
    private final List<DirectTbtFrame> frames = new ArrayList<>();
    private int clears;
    private int stops;

    @Before public void setup() throws Exception {
        Method reset = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        reset.setAccessible(true);
        reset.invoke(null);
        GMapsDirectChannel.Listener listener = (GMapsDirectChannel.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{GMapsDirectChannel.Listener.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onFrame")) frames.add((DirectTbtFrame) args[2]);
                    if (method.getName().equals("onGuidanceUnavailable")) clears++;
                    if (method.getName().equals("onNavigationEnded")) stops++;
                    return null;
                });
        channel = new GMapsDirectChannel(RuntimeEnvironment.getApplication(), listener);
    }

    @After public void cleanup() throws Exception {
        ((HandlerThread) field("thread")).quitSafely();
    }

    @Test public void reroutingClearsOldTurnOnceAndNextStepRecoversInSameSession() throws Exception {
        deliver(1, route(0, step(7, scalar(1, 100), "Turn left")));
        assertEquals(1, frames.size());
        deliver(2, route(1, step(7, scalar(1, 100), "Turn left")));
        assertNull(field("currentFrame"));
        assertEquals(1, clears);
        assertEquals(0, stops);
        deliver(3, route(1, step(7, scalar(1, 100), "Turn left")));
        assertEquals(1, clears);
        deliver(4, route(0, step(8, scalar(1, 80), "Turn right")));
        assertEquals(2, frames.size());
        assertEquals(8, frames.get(1).getRawManeuverType());
    }

    @Test public void malformedFrameClearsButOlderFrameCannotEraseCurrentTurn() throws Exception {
        deliver(5, route(0, step(7, scalar(1, 50), "Left")));
        deliver(4, new byte[]{26, 127});
        assertNotNull(field("currentFrame"));
        assertEquals(0, clears);
        deliver(6, new byte[]{26, 127});
        assertNull(field("currentFrame"));
        assertEquals(1, clears);
    }

    @Test public void emptyNewFrameAlsoFencesOlderOtherwiseValidFrame() throws Exception {
        deliver(5, route(0, step(7, scalar(1, 50), "Left")));
        deliver(10, new byte[0]);
        deliver(8, route(0, step(8, scalar(1, 60), "Right")));
        assertNull(field("currentFrame"));
        assertEquals(1, frames.size());
        assertEquals(1, clears);
    }

    @Test public void distanceUnavailableClearsInsteadOfInventingZero() throws Exception {
        deliver(1, route(0, step(7, scalar(1, 50), "Left")));
        deliver(2, route(0, step(7, null, "Left")));
        assertEquals(1, clears);
        assertEquals(1, frames.size());
    }

    @Test public void presentEmptyProtobufDistanceMeansValidZero() throws Exception {
        deliver(1, route(0, step(7, new byte[0], "Left now")));
        assertEquals(1, frames.size());
        assertEquals(0, frames.get(0).getDistanceMeters());
        assertEquals(0, clears);
    }

    @Test public void incompleteRouteDistanceIsUnavailableInsteadOfPartialTotal() throws Exception {
        Map<String,Object> summary = GmapsWireParser.summarize(route(0,
                step(7, scalar(1, 100), "Left"), step(8, null, "Right")));
        assertEquals(100L, summary.get("distanceMeters"));
        assertFalse(summary.containsKey("nextStopRemainingDistanceMeters"));
        assertFalse(summary.containsKey("wholeRouteRemainingDistanceMeters"));
    }

    @Test public void cueSurvivesWhenGoogleDoesNotSupplyStreetName() throws Exception {
        deliver(1, route(0, step(7, scalar(1, 100), "Take the second exit")));
        assertEquals("Take the second exit", frames.get(0).getCueText());
        assertEquals("Take the second exit", VehicleTbtPublisher.roadTextForTest(frames.get(0)));
    }

    @Test public void explicitInactiveLanesClearEvenIfOldLanePayloadRemains() throws Exception {
        byte[] arrow = concat(scalar(1, 1), scalar(3, 1));
        byte[] lane = bytes(1, arrow);
        byte[] extras = concat(scalar(6, 0), bytes(7, bytes(1, lane)));
        deliver(1, route(0, concat(step(7, scalar(1, 100), "Left"), extras)));
        assertTrue(frames.get(0).getLanes().isEmpty());
    }

    @Test public void unknownPhysicalLaneCannotShiftRecommendedLaneToLeft() {
        List<Object> lanes = Arrays.asList(lane(1, "", false), lane(99, "", false), lane(3, "RIGHT", true));
        assertTrue(GMapsDirectChannel.mapLanesForTest(lanes).isEmpty());
        List<DirectTbtFrame.Lane> mapped = GMapsDirectChannel.mapLanesForTest(
                Arrays.asList(lane(1, "", false), lane(3, "RIGHT", true)));
        assertEquals(2, mapped.size());
        assertFalse(mapped.get(0).isRecommended());
        assertTrue(mapped.get(1).isRecommended());
    }

    @Test public void stepIdentitySurvivesPresentationAndBitmapCopies() throws Exception {
        deliver(1, route(1, step(7, scalar(1, 50), "Left"), step(7, scalar(1, 80), "Left")));
        DirectTbtFrame frame = frames.get(0).withDistanceMeters(100)
                .withNavigationText("Road", "Left").withManeuverPng(new byte[]{1})
                .withSpeedLimit(DirectTbtFrame.SpeedLimit.inactive());
        assertEquals(1, frame.getSourceStepIndex());
    }

    @Test public void nextRoundaboutCannotReuseBitmapOfPreviousExit() throws Exception {
        byte[] first = step(55, scalar(1, 100), "First roundabout");
        byte[] next = step(55, scalar(1, 100), "Next roundabout");
        @SuppressWarnings("unchecked")
        Map<String,GMapsDirectChannel.ManeuverBitmap> cache =
                (Map<String,GMapsDirectChannel.ManeuverBitmap>) field("maneuverBitmaps");
        byte[] firstExit = new byte[]{1, 2, 3};
        cache.put("ROUNDABOUT_ENTER_AND_EXIT_CW", new GMapsDirectChannel.ManeuverBitmap(
                "ROUNDABOUT_ENTER_AND_EXIT_CW", "test", firstExit, 1, 1, 1, 1, 1, 1, 10));
        deliver(10, route(0, first, next));
        assertArrayEquals(firstExit, frames.get(0).getManeuverPng());
        deliver(11, route(1, first, next));
        assertFalse(Arrays.equals(firstExit, frames.get(1).getManeuverPng()));
        byte[] secondExit = new byte[]{4, 5, 6};
        cache.put("ROUNDABOUT_ENTER_AND_EXIT_CW", new GMapsDirectChannel.ManeuverBitmap(
                "ROUNDABOUT_ENTER_AND_EXIT_CW", "test", secondExit, 1, 1, 2, 1, 2, 1, 12));
        deliver(12, route(1, first, next));
        assertArrayEquals(secondExit, frames.get(2).getManeuverPng());
    }

    @Test public void maps25WrappedManeuverSelectsLateGoogleBitmap() throws Exception {
        deliver(1, route(0, step(1, new byte[0], "Head northwest")));
        byte[] png = bitmap(Color.WHITE);
        deliverBitmap("{DEPART}", png, 1, 10);
        assertEquals(2, frames.size());
        assertArrayEquals(png, frames.get(1).getManeuverPng());
    }

    @Test public void wrappedBitmapBeforeStructuredFrameIsSelectedOnMatchingSequence() throws Exception {
        deliver(1, route(0, step(7, scalar(1, 80), "Left")));
        byte[] png = bitmap(Color.WHITE);
        deliverBitmap("{TURN_NORMAL_RIGHT}", png, 2, 10);
        assertEquals(1, frames.size());
        deliver(2, route(0, step(8, scalar(1, 70), "Right")));
        assertArrayEquals(png, frames.get(1).getManeuverPng());
    }

    @Test public void wrappedAndPlainNamesShareOrderingAndStepFences() throws Exception {
        byte[] turn = step(7, scalar(1, 80), "Left");
        deliver(10, route(0, turn, turn));
        byte[] current = bitmap(Color.WHITE);
        deliverBitmap("{TURN_NORMAL_LEFT}", current, 10, 20);
        deliverBitmap("TURN_NORMAL_LEFT", bitmap(Color.RED), 10, 10);
        assertArrayEquals(current, ((DirectTbtFrame) field("currentFrame")).getManeuverPng());
        deliver(11, route(1, turn, turn));
        byte[] stale = bitmap(Color.BLUE);
        deliverBitmap("{TURN_NORMAL_LEFT}", stale, 10, 30);
        assertFalse(Arrays.equals(stale, ((DirectTbtFrame) field("currentFrame")).getManeuverPng()));
    }

    @Test public void malformedNamesCannotBeMistakenForCurrentManeuver() throws Exception {
        deliver(1, route(0, step(1, new byte[0], "Head northwest")));
        for (String name : Arrays.asList("{{DEPART}}", "{depart}", "{DEPART, LEFT}", "DEPART}")) {
            deliverBitmap(name, bitmap(Color.WHITE), 1, 10);
        }
        assertEquals(1, frames.size());
    }

    private static byte[] bitmap(int color) {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private void deliverBitmap(String maneuver, byte[] png, long sequence, long atMs) throws Exception {
        Bundle b = new Bundle();
        b.putString("maneuver", maneuver); b.putString("viewId", "maneuver_image");
        b.putByteArray("png", png); b.putInt("width", 2); b.putInt("height", 2);
        b.putLong("sourceSequence", sequence); b.putLong("bridgeElapsedMs", atMs);
        Method m = GMapsDirectChannel.class.getDeclaredMethod("handleManeuverBitmap", Bundle.class);
        m.setAccessible(true); m.invoke(channel, b);
    }

    private static Map<String,Object> lane(int shape, String side, boolean recommended) {
        Map<String,Object> arrow = new HashMap<>();
        arrow.put("shapeEnum", shape); arrow.put("side", side); arrow.put("recommended", recommended);
        return Collections.singletonMap("arrows", Collections.singletonList(arrow));
    }
    private Object field(String name) throws Exception {
        Field f = GMapsDirectChannel.class.getDeclaredField(name);
        f.setAccessible(true); return f.get(channel);
    }
    private void deliver(long sequence, byte[] payload) throws Exception {
        Bundle b = new Bundle(); b.putLong("sequence", sequence); b.putByteArray("payload", payload);
        Method m = GMapsDirectChannel.class.getDeclaredMethod("handleFrame", Bundle.class, GMapsTimingDiagnostics.Frame.class);
        m.setAccessible(true);
        m.invoke(channel, b, GMapsTimingDiagnostics.frame(3, "test", 3, 1, 1, sequence, 0, 0, 0));
    }
    static byte[] step(int maneuver, byte[] distance, String cue) {
        return concat(bytes(2, scalar(1, maneuver)), distance == null ? new byte[0] : bytes(3, distance),
                bytes(8, cue.getBytes(StandardCharsets.UTF_8)));
    }
    static byte[] route(int index, byte[]... steps) {
        ByteArrayOutputStream leg = new ByteArrayOutputStream();
        for (byte[] step : steps) leg.writeBytes(bytes(2, step));
        leg.writeBytes(scalar(3, index));
        return bytes(3, bytes(2, bytes(3, bytes(2, leg.toByteArray()))));
    }
    static byte[] bytes(int tag, byte[] value) {
        return concat(varint(tag * 8L + 2), varint(value.length), value);
    }
    static byte[] scalar(int tag, long value) { return concat(varint(tag * 8L), varint(value)); }
    static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~127L) != 0) { out.write((int) (value & 127) | 128); value >>>= 7; }
        out.write((int) value); return out.toByteArray();
    }
    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
