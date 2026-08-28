package com.bydhud.app;

//sends SOME/IP HUD payloads so app-level route decisions reach the BYD cluster protocol.

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Transport boundary for BYD SOME/IP HUD output.
 */
final class SomeIpHudClient {
    //defines the Listener module boundary so related behavior stays readable inside one unit.
    interface Listener {
        void onClientLog(String line);

        void onTransportConnected();

        void onTransportUnavailable(String reason);
    }

    private static final String SOMEIP_DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final String SOMEIP_PACKAGE = "com.ts.car.someip.service";
    private static final String SOMEIP_SERVER_SERVICE =
            "com.ts.car.someip.service.manager.SomeIpServerService";

    static final long HUD_NAVI_INFO_SERVICE_ID = 3097367205183488L;
    static final long HUD_TOPIC_8001 = 0x4010a00018001L;
    static final long HUD_TOPIC_8002 = 0x4010a00018002L;
    static final long HUD_TOPIC_8003 = 0x4010a00018003L;
    static final long HUD_ROAD_INFO_TOPIC = HUD_TOPIC_8001;

    private static final int TRANSACTION_IS_SERVICE_READY = 3;
    private static final int TRANSACTION_START_SERVICE = 4;
    private static final int TRANSACTION_STOP_SERVICE = 5;
    private static final int TRANSACTION_FIRE_EVENT = 6;

    private final Context context;
    private final Listener listener;
    private volatile IBinder binder;
    private volatile boolean bound;
    private ServiceConnection connection;
    private int bindingGeneration;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    SomeIpHudClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isBound() {
        return binder != null;
    }

    //keeps pending bind state visible so package-replace reset can release stale connections.
    boolean hasBinding() {
        return bound || binder != null;
    }

    //opens the external boundary here so connection setup remains observable and retryable.
    synchronized void bind() {
        if (bound) return;
        int generation = ++bindingGeneration;
        ServiceConnection candidate = connectionFor(generation);
        connection = candidate;
        Intent intent = new Intent();
        intent.setClassName(SOMEIP_PACKAGE, SOMEIP_SERVER_SERVICE);
        intent.setType(context.getPackageName());
        bound = context.bindService(intent, candidate, Context.BIND_AUTO_CREATE);
        if (!bound && connection == candidate) connection = null;
        log("bindService=" + bound + " generation=" + generation);
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    int start() throws RemoteException {
        int ret = transactLong(TRANSACTION_START_SERVICE, HUD_NAVI_INFO_SERVICE_ID);
        log("startSomeIpService ret=" + ret);
        return ret;
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    int stop() throws RemoteException {
        int ret = transactLong(TRANSACTION_STOP_SERVICE, HUD_NAVI_INFO_SERVICE_ID);
        log("stopSomeIpService ret=" + ret);
        return ret;
    }

    int startAuxiliaryService(long serviceId) throws RemoteException {
        return transactLong(TRANSACTION_START_SERVICE, serviceId);
    }

    int stopAuxiliaryService(long serviceId) throws RemoteException {
        return transactLong(TRANSACTION_STOP_SERVICE, serviceId);
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    int send(byte[] payload) throws RemoteException {
        return sendToTopic(HUD_ROAD_INFO_TOPIC, payload);
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    int sendToTopic(long topic, byte[] payload) throws RemoteException {
        if (binder == null) {
            throw new RemoteException("SomeIpServerService is not connected");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SOMEIP_DESCRIPTOR);
            data.writeInt(1);
            data.writeLong(topic);
            data.writeLong(System.currentTimeMillis());
            data.writeInt(payload.length);
            data.writeByteArray(payload);
            binder.transact(TRANSACTION_FIRE_EVENT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void unbind() {
        ServiceConnection current;
        boolean wasBound;
        synchronized (this) {
            ++bindingGeneration;
            current = connection;
            wasBound = bound;
            connection = null;
            binder = null;
            bound = false;
        }
        if (wasBound && current != null) {
            try {
                context.unbindService(current);
            } catch (IllegalArgumentException ignored) {
                // The platform may already have removed this generation.
            }
        }
    }

    private ServiceConnection connectionFor(int generation) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (SomeIpHudClient.this) {
                    if (generation != bindingGeneration || connection != this || !bound) {
                        log("stale connected ignored generation=" + generation);
                        return;
                    }
                    binder = service;
                }
                listener.onTransportConnected();
                log("connected: " + name.flattenToShortString()
                        + " generation=" + generation);
                try {
                    log("ready=" + isReady() + " generation=" + generation);
                } catch (RemoteException e) {
                    log("ready error: " + e.getMessage());
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                synchronized (SomeIpHudClient.this) {
                    if (generation != bindingGeneration || connection != this) return;
                    binder = null;
                }
                listener.onTransportUnavailable("service_disconnected");
                log("disconnected: " + name.flattenToShortString()
                        + " generation=" + generation);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                if (!resetBinding(generation, this)) return;
                listener.onTransportUnavailable("binding_died");
                log("binding died: " + name.flattenToShortString()
                        + " generation=" + generation);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                if (!resetBinding(generation, this)) return;
                listener.onTransportUnavailable("null_binding");
                log("null binding: " + name.flattenToShortString()
                        + " generation=" + generation);
            }
        };
    }

    private boolean resetBinding(int generation, ServiceConnection candidate) {
        synchronized (this) {
            if (generation != bindingGeneration || connection != candidate) return false;
        }
        unbind();
        return true;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean isReady() throws RemoteException {
        if (binder == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SOMEIP_DESCRIPTOR);
            binder.transact(TRANSACTION_IS_SERVICE_READY, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private int transactLong(int transaction, long value) throws RemoteException {
        if (binder == null) {
            throw new RemoteException("SomeIpServerService is not connected");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SOMEIP_DESCRIPTOR);
            data.writeLong(value);
            binder.transact(transaction, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private void log(String line) {
        listener.onClientLog(line);
    }
}
