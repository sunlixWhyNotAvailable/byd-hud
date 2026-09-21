package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Read-only client for the stock SOME/IP event service. */
final class ShanghaiSomeIpClient implements Closeable {
    private static final String CLIENT_DESCRIPTOR = "ts.car.someip.sdk.ISomeIpClientInterface";
    private static final String CALLBACK_DESCRIPTOR = "ts.car.someip.sdk.ISomeIpCallback";
    private static final ComponentName COMPONENT = new ComponentName(
            "com.ts.car.someip.service",
            "com.ts.car.someip.service.manager.SomeIpClientService");
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    private final Context context;
    private final String clientType;
    private final ShanghaiEventJournal journal;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final List<Long> subscribed = new ArrayList<>();
    private final List<Long> startedServices = new ArrayList<>();
    private final Callback callback;
    private final JSONArray results = new JSONArray();
    private final JSONArray clientResults = new JSONArray();
    private final JSONObject cleanup = ShanghaiJson.object("status", "pending");
    private volatile IBinder service;
    private volatile boolean disconnected;
    private boolean bound;
    private boolean callbackRegistered;
    private boolean closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder;
            connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            disconnected = true;
            service = null;
        }
    };

    ShanghaiSomeIpClient(Context context, String sessionId, ShanghaiEventJournal journal) {
        this.context = context.getApplicationContext();
        this.clientType = "bydhud-shanghai-" + sessionId;
        this.journal = journal;
        this.callback = new Callback(journal);
    }

    boolean start(long bindTimeoutMs, BooleanSupplier cancelled) throws Exception {
        Intent intent = new Intent().setComponent(COMPONENT).setType(clientType);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound || !connected.await(bindTimeoutMs, TimeUnit.MILLISECONDS) || service == null) {
            return false;
        }
        if (cancelled.getAsBoolean()) return true;
        registerCallback();
        startRequestedClients(cancelled);
        for (long topic : ShanghaiTopics.ALL) {
            JSONObject item = new JSONObject().put("topic", ShanghaiTopics.hex(topic));
            if (cancelled.getAsBoolean()) {
                item.put("accepted", false).put("skipped", "stop_requested");
                results.put(item);
                continue;
            }
            try {
                int result = transactInt(8, topic);
                item.put("result", result).put("accepted", result == 0);
                if (result == 0) subscribed.add(topic);
            } catch (Throwable error) {
                item.put("accepted", false).put("error", error.toString());
            }
            results.put(item);
        }
        return true;
    }

    private void startRequestedClients(BooleanSupplier cancelled) throws Exception {
        Set<Long> services = new LinkedHashSet<>();
        for (long topic : ShanghaiTopics.ALL) {
            services.add(0x000B000000000000L | (topic & 0x0000FFFFFFFF0000L));
        }
        for (long serviceId : services) {
            JSONObject item = new JSONObject().put("service", ShanghaiTopics.hex(serviceId));
            if (cancelled.getAsBoolean()) {
                item.put("accepted", false).put("skipped", "stop_requested");
                clientResults.put(item);
                continue;
            }
            int result;
            try { result = transactInt(4, serviceId); }
            catch (Throwable error) {
                item.put("accepted", false).put("error", error.toString());
                clientResults.put(item);
                continue;
            }
            boolean accepted = result == 0 || result == 13;
            item.put("result", result).put("accepted", accepted);
            if (accepted) startedServices.add(serviceId);
            clientResults.put(item);
        }
    }

    private void registerCallback() throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT_DESCRIPTOR);
            data.writeStrongBinder(callback);
            requireService().transact(1, data, reply, 0);
            reply.readException();
            callbackRegistered = true;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    int subscribedCount() { return subscribed.size(); }
    boolean live() { return !disconnected && service != null && journal.receivedCount() > 0; }
    boolean healthy() { return !disconnected && service != null && journal.healthy(); }
    JSONArray subscriptionResults() { return results; }
    JSONArray clientResults() { return clientResults; }
    JSONObject cleanupResult() { return cleanup; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        JSONArray unsubscribe = new JSONArray();
        for (int index = subscribed.size() - 1; index >= 0; index--) {
            long topic = subscribed.get(index);
            JSONObject item = ShanghaiJson.object("topic", ShanghaiTopics.hex(topic));
            try { ShanghaiJson.put(item, "result", transactInt(9, topic)); }
            catch (Throwable error) { ShanghaiJson.put(item, "error", error.toString()); }
            unsubscribe.put(item);
        }
        ShanghaiJson.put(cleanup, "unsubscribeResults", unsubscribe);
        JSONArray stoppedClients = new JSONArray();
        for (int index = startedServices.size() - 1; index >= 0; index--) {
            long serviceId = startedServices.get(index);
            JSONObject item = ShanghaiJson.object("service", ShanghaiTopics.hex(serviceId));
            try { ShanghaiJson.put(item, "result", transactInt(5, serviceId)); }
            catch (Throwable error) { ShanghaiJson.put(item, "error", error.toString()); }
            stoppedClients.put(item);
        }
        ShanghaiJson.put(cleanup, "stopClientResults", stoppedClients);
        if (callbackRegistered && service != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CLIENT_DESCRIPTOR);
                data.writeStrongBinder(callback);
                service.transact(2, data, reply, 0);
                reply.readException();
                ShanghaiJson.put(cleanup, "callbackUnregistered", true);
            } catch (Throwable ignored) {
                ShanghaiJson.put(cleanup, "callbackUnregisterError", ignored.toString());
            } finally {
                reply.recycle();
                data.recycle();
            }
        } else if (callbackRegistered) {
            ShanghaiJson.put(cleanup, "callbackUnregisterError", "unconfirmed: service disconnected");
        }
        callbackRegistered = false;
        callback.seal(2_000L);
        if (bound) {
            try {
                context.unbindService(connection);
                ShanghaiJson.put(cleanup, "unbound", true);
            } catch (Throwable error) {
                ShanghaiJson.put(cleanup, "unbindError", error.toString());
            }
        }
        bound = false;
        service = null;
        ShanghaiJson.put(cleanup, "status", "attempted");
    }

    private int transactInt(int code, Long value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT_DESCRIPTOR);
            if (value != null) data.writeLong(value);
            requireService().transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private IBinder requireService() {
        IBinder current = service;
        if (current == null) throw new IllegalStateException("SOME/IP service disconnected");
        return current;
    }

    private static final class Callback extends Binder {
        private final ShanghaiEventJournal journal;
        private boolean sealed;
        private int inFlight;

        Callback(ShanghaiEventJournal journal) {
            this.journal = journal;
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                boolean admitted = begin();
                Frame frame = null;
                try {
                    frame = readFrame(data);
                    if (admitted && frame != null) {
                        journal.offer(frame.topic, frame.timestamp, frame.declaredLength, frame.payload);
                    } else if (frame != null) {
                        journal.rejectAfterSeal(frame.topic, frame.payload);
                    }
                } finally {
                    if (admitted) end();
                }
                if (reply != null) {
                    reply.writeNoException();
                    writeFrame(reply, frame);
                }
                return true;
            }
            if (code == 2) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                data.readInt();
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == 3) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                Frame request = readFrame(data);
                if (reply != null) {
                    reply.writeNoException();
                    writeFrame(reply, null);
                    writeFrame(reply, request);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        synchronized boolean begin() {
            if (sealed) return false;
            inFlight++;
            return true;
        }
        synchronized void end() {
            inFlight--;
            if (inFlight == 0) notifyAll();
        }
        synchronized void seal(long timeoutMs) {
            sealed = true;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (inFlight > 0) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0L) return;
                try { wait(remaining); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static Frame readFrame(Parcel parcel) {
            if (parcel.readInt() == 0) return null;
            long topic = parcel.readLong();
            long timestamp = parcel.readLong();
            int declaredLength = parcel.readInt();
            if (declaredLength < 0) return new Frame(topic, timestamp, declaredLength, new byte[0]);
            if (declaredLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("SOME/IP payload exceeds cap");
            }
            byte[] payload = parcel.createByteArray();
            if (payload == null) payload = new byte[0];
            if (payload.length != declaredLength) {
                throw new IllegalArgumentException("SOME/IP payload length mismatch");
            }
            return new Frame(topic, timestamp, declaredLength, payload);
        }

        private static void writeFrame(Parcel parcel, Frame frame) {
            if (frame == null) {
                parcel.writeInt(0);
                return;
            }
            parcel.writeInt(1);
            parcel.writeLong(frame.topic);
            parcel.writeLong(frame.timestamp);
            parcel.writeInt(frame.payload.length);
            parcel.writeByteArray(frame.payload);
        }
    }

    private static final class Frame {
        final long topic;
        final long timestamp;
        final int declaredLength;
        final byte[] payload;
        Frame(long topic, long timestamp, int declaredLength, byte[] payload) {
            this.topic = topic;
            this.timestamp = timestamp;
            this.declaredLength = declaredLength;
            this.payload = payload;
        }
    }
}
