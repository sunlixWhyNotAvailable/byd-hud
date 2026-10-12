package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.*;
import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public final class NavigatorMapLifecycleJournalTest {
    private Context context;
    private Class<?> spoolClass;
    private final List<JSONObject> stored = new ArrayList<>();
    private final NavigatorMapLifecycleJournal.Store sink = (owner, producer, event) -> {
        stored.add(event);
        return true;
    };

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("navigator_map_lifecycle_journal", Context.MODE_PRIVATE)
                .edit().clear().commit();
        spoolClass = Class.forName("com.bydhud.mapcapture.LifecycleJournalSpool");
    }

    @Test public void queuedEventsSurviveLostAckAndProducerRestartWithoutDuplicates() throws Exception {
        Object source = source("original");
        for (int i = 1; i <= 300; i++) append(source, detail(i), i);
        Object first = batch(source);
        assertTrue(events(first).length() > 1);
        assertTrue(events(first).length() < 300);
        assertFalse(accept(source, first, "foreign.package", sink).accepted);
        assertTrue(stored.isEmpty());
        assertTrue(accept(source, first, NavigatorMapCapture.WAZE_PACKAGE, sink).accepted);
        int firstCount = stored.size();
        source = source("restarted"); // The first ACK never reached the producer.
        Object retry = batch(source);
        assertEquals(events(first).toString(), events(retry).toString());
        ack(source, accept(source, retry, NavigatorMapCapture.WAZE_PACKAGE, sink));
        assertEquals(firstCount, stored.size());
        drain(source);
        assertEquals(300, stored.size());
        for (int i = 1; i <= 300; i++) {
            assertEquals(i, stored.get(i - 1).getLong("sequence"));
            assertEquals(detail(i), stored.get(i - 1).getString("detail"));
        }
        assertEquals(0, events(batch(source("next-session"))).length());
    }

    @Test public void largeUtf8EventResumesAfterRestartAndFailedSinkWithoutAdvancingAck() throws Exception {
        String detail = "🌐navigation-state-".repeat(2400);
        Object source = source("large");
        append(source, detail, 1);
        NavigatorMapLifecycleJournal.Result accepted = accept(source, batch(source),
                NavigatorMapCapture.WAZE_PACKAGE, sink);
        assertEquals(0, accepted.ackSequence);
        assertEquals(1, accepted.ackChunkSequence);
        assertTrue(accepted.ackChunkIndex > 0);
        ack(source, accepted);
        source = source("restarted");
        Object tail = batch(source);
        int beforeFailure = stored.size();
        NavigatorMapLifecycleJournal.Result refused = accept(source, tail,
                NavigatorMapCapture.WAZE_PACKAGE, (owner, producer, event) -> false);
        assertFalse(refused.accepted);
        assertEquals(accepted.ackSequence, refused.ackSequence);
        assertEquals(accepted.ackChunkIndex, refused.ackChunkIndex);
        ack(source, refused);
        assertEquals(events(tail).toString(), events(batch(source)).toString());
        assertEquals(beforeFailure, stored.size());
        drain(source);
        StringBuilder delivered = new StringBuilder();
        for (int i = 0; i < stored.size(); i++) {
            JSONObject fragment = stored.get(i);
            assertEquals(1, fragment.getLong("sequence"));
            assertEquals(i + 1, fragment.getInt("detailChunkIndex"));
            delivered.append(fragment.getString("detail"));
        }
        assertArrayEquals(detail.getBytes(StandardCharsets.UTF_8),
                delivered.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String detail(int sequence) {
        return sequence == 1 ? "" : sequence == 2 ? "one" : "event-" + sequence;
    }
    private Object source(String session) {
        return callConstructor(spoolClass, ClassParameter.from(Context.class, context),
                ClassParameter.from(int.class, 8123), ClassParameter.from(String.class, session));
    }
    private void append(Object source, String detail, long sequence) {
        callInstanceMethod(source, "append", ClassParameter.from(String.class, "state"),
                ClassParameter.from(String.class, "renderer"), ClassParameter.from(String.class, detail),
                ClassParameter.from(long.class, sequence));
    }
    private Object batch(Object source) {
        Object result = callInstanceMethod(source, "batch", ClassParameter.from(boolean.class, true));
        assertTrue(events(result).toString().getBytes(StandardCharsets.UTF_8).length <= 24 * 1024);
        return result;
    }
    private JSONArray events(Object batch) { return getField(batch, "events"); }
    private NavigatorMapLifecycleJournal.Result accept(Object source, Object batch, String owner,
            NavigatorMapLifecycleJournal.Store target) {
        return NavigatorMapLifecycleJournal.accept(context, owner, callInstanceMethod(source, "sessionId"),
                events(batch), getField(batch, "completeThrough"), getField(batch, "partialSequence"),
                getField(batch, "partialChunkIndex"), target);
    }
    private void ack(Object source, NavigatorMapLifecycleJournal.Result result) {
        assertTrue(callInstanceMethod(source, "acknowledge", ClassParameter.from(long.class, result.ackSequence),
                ClassParameter.from(long.class, result.ackChunkSequence),
                ClassParameter.from(int.class, result.ackChunkIndex)));
    }
    private void drain(Object source) {
        for (int polls = 0; polls < 20; polls++) {
            Object batch = batch(source);
            if (events(batch).length() == 0) return;
            NavigatorMapLifecycleJournal.Result result = accept(source, batch, NavigatorMapCapture.WAZE_PACKAGE, sink);
            assertTrue(result.accepted);
            ack(source, result);
        }
        fail("producer did not finish the pending journal");
    }
}
