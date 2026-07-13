package com.bydhud.app;

//sends SOME/IP HUD payloads so app-level route decisions reach the BYD cluster protocol.

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

//contains the SomeIpHudClient transport boundary so external communication is isolated from app logic.
final class SomeIpHudClient {
    //defines the Listener module boundary so related behavior stays readable inside one unit.
    interface Listener {
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        void onClientLog(String line);
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

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            log("connected: " + name.flattenToShortString());
            try {
                log("ready=" + isReady());
            } catch (RemoteException e) {
                log("ready error: " + e.getMessage());
            }
        }

        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void onServiceDisconnected(ComponentName name) {
            log("disconnected: " + name.flattenToShortString());
            binder = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            log("binding died: " + name.flattenToShortString());
            resetBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            log("null binding: " + name.flattenToShortString());
            resetBinding();
        }
    };

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
    void bind() {
        if (bound) {
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(SOMEIP_PACKAGE, SOMEIP_SERVER_SERVICE);
        intent.setType(context.getPackageName());
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        log("bindService=" + bound);
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
        if (!bound) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            //returns early because the service connection has already been unbound.
        }
        binder = null;
        bound = false;
    }

    private void resetBinding() {
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The platform may have already removed a dead binding.
            }
        }
        binder = null;
        bound = false;
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
