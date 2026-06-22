package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

final class SomeIpHudClient {
    interface Listener {
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
        public void onServiceDisconnected(ComponentName name) {
            log("disconnected: " + name.flattenToShortString());
            binder = null;
            bound = false;
        }
    };

    SomeIpHudClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isBound() {
        return binder != null;
    }

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

    int start() throws RemoteException {
        int ret = transactLong(TRANSACTION_START_SERVICE, HUD_NAVI_INFO_SERVICE_ID);
        log("startSomeIpService ret=" + ret);
        return ret;
    }

    int stop() throws RemoteException {
        int ret = transactLong(TRANSACTION_STOP_SERVICE, HUD_NAVI_INFO_SERVICE_ID);
        log("stopSomeIpService ret=" + ret);
        return ret;
    }

    int send(byte[] payload) throws RemoteException {
        return sendToTopic(HUD_ROAD_INFO_TOPIC, payload);
    }

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

    void unbind() {
        if (!bound) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Already unbound.
        }
        binder = null;
        bound = false;
    }

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

    private void log(String line) {
        listener.onClientLog(line);
    }
}
