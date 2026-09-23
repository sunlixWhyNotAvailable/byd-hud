package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.app.Application;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ShanghaiSomeIpClientBehaviorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void refusalDoesNotAbortLaterTopicsAndLateCallbackIsNotJournaled() throws Exception {
        ShanghaiBehaviorSupport.reset();
        Context base = RuntimeEnvironment.getApplication();
        long refused = ShanghaiTopics.ALL.get(0);
        ShanghaiBehaviorSupport.ControlledSomeIpBinder binder =
                new ShanghaiBehaviorSupport.ControlledSomeIpBinder(refused);
        Context context = new ShanghaiBehaviorSupport.BoundContext(base, binder);
        File journalFile = new File(temporary.getRoot(), "someip-events.jsonl");
        ShanghaiEventJournal journal = new ShanghaiEventJournal(
                journalFile, ShanghaiTopics.ALL, System::nanoTime);
        ShanghaiSomeIpClient client = new ShanghaiSomeIpClient(
                context, "behavior-test", journal);
        boolean started = false;
        try {
            started = client.start(100L, () -> false);
            assertTrue(started);
            assertEquals(ShanghaiTopics.ALL, binder.requestedTopics);
            assertEquals(ShanghaiTopics.ALL.size() - 1, client.subscribedCount());

            JSONArray results = client.subscriptionResults();
            assertEquals(ShanghaiTopics.ALL.size(), results.length());
            for (int index = 0; index < ShanghaiTopics.ALL.size(); index++) {
                JSONObject item = results.getJSONObject(index);
                assertEquals(ShanghaiTopics.hex(ShanghaiTopics.ALL.get(index)), item.getString("topic"));
                assertEquals(index != 0, item.getBoolean("accepted"));
            }

            long acceptedTopic = ShanghaiTopics.ALL.get(1);
            byte[] payload = new byte[]{0, 1, (byte) 0xfe, 0x22, 0};
            IBinder callback = binder.callback;
            assertNotNull(callback);
            ShanghaiBehaviorSupport.sendEvent(callback, acceptedTopic, 873L, payload);
            assertTrue(await(() -> journal.persistedCount() == 1L, 3_000L));
            assertEquals(1L, journal.receivedCount());

            List<Long> accepted = new ArrayList<>(ShanghaiTopics.ALL.subList(1, ShanghaiTopics.ALL.size()));
            List<Long> expectedUnsubscribe = new ArrayList<>(accepted);
            Collections.reverse(expectedUnsubscribe);
            client.close();
            assertEquals(expectedUnsubscribe, binder.unsubscribedTopics);
            assertTrue(binder.callbackUnregistered);
            assertTrue(client.cleanupResult().getBoolean("unbound"));

            ShanghaiBehaviorSupport.sendEvent(callback, acceptedTopic, 874L,
                    new byte[]{9, 8, 7});
            assertEquals(1L, journal.persistedCount());
            assertEquals(2L, journal.receivedCount());
            assertEquals(1L, journal.droppedCount());
        } finally {
            if (started) client.close();
            journal.close();
        }

        List<String> lines = Files.readAllLines(journalFile.toPath(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        JSONObject event = new JSONObject(lines.get(0));
        assertEquals(acceptedTopicId(), event.getString("topic"));
        assertEquals(873L, event.getLong("oemTimestamp"));
        assertEquals(5, event.getInt("declaredPayloadLength"));
        assertArrayEquals(new byte[]{0, 1, (byte) 0xfe, 0x22, 0},
                Base64.getDecoder().decode(event.getString("payloadBase64")));
        assertTrue(journal.finalized());
        assertFalse(client.healthy());
    }

    private static String acceptedTopicId() { return ShanghaiTopics.hex(ShanghaiTopics.ALL.get(1)); }

    private static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(5L);
        }
        return condition.getAsBoolean();
    }
}
