package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Sends the stock map's raw dashboard-layout command without adding a generated AIDL dependency.
final class StockMapProtocol30011 {
    private static final ComponentName SERVICE = new ComponentName(
            "com.byd.launchermap",
            "com.autosdk.protocol.service.ProtocolService");
    private static final String SERVICE_ACTION = "action.com.autosdk.protocol.ProtocolService";
    private static final String DESCRIPTOR = "com.autosdk.protocol.IProtocolAidlInterface";
    private static final int TRANSACTION_SET_PROTOCOL_MODEL = 1;
    private static final int PROTOCOL_INSTRUMENT_NAVIGATION_TYPE = 30011;
    private static final int ACTION_SET = 1;
    private static final long TIMEOUT_MS = 3000L;
    private static final AtomicBoolean TRANSACTION_IN_FLIGHT = new AtomicBoolean(false);

    private StockMapProtocol30011() {
    }

    // Returns an empty string on success and a short failure detail otherwise.
    static String dispatch(Context context, boolean fullscreen) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IBinder> binder = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder.set(service);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder.set(null);
                connected.countDown();
            }
        };
        boolean bound = false;
        try {
            Intent intent = new Intent(SERVICE_ACTION).setComponent(SERVICE);
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                return "bind rejected";
            }
            if (!await(connected, deadlineNanos)) {
                return "bind timeout after " + TIMEOUT_MS + "ms";
            }
            IBinder service = binder.get();
            if (service == null) {
                return "service disconnected";
            }
            if (!TRANSACTION_IN_FLIGHT.compareAndSet(false, true)) {
                return "previous transaction still in flight";
            }
            CountDownLatch transacted = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("transaction failed");
            try {
                new Thread(() -> {
                    try {
                        result.set(transact(
                                service,
                                context.getPackageName(),
                                fullscreen ? 4 : 3));
                    } finally {
                        TRANSACTION_IN_FLIGHT.set(false);
                        transacted.countDown();
                    }
                }, "BydHudProtocol30011").start();
            } catch (RuntimeException e) {
                TRANSACTION_IN_FLIGHT.set(false);
                throw e;
            }
            if (!await(transacted, deadlineNanos)) {
                return "transaction outcome indeterminate after " + TIMEOUT_MS + "ms";
            }
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + safe(e.getMessage());
        } finally {
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static boolean await(CountDownLatch latch, long deadlineNanos)
            throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS);
    }

    private static String transact(IBinder binder, String packageName, int operation) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(1);
            writeProtocolModel(request, packageName, operation);
            if (!binder.transact(TRANSACTION_SET_PROTOCOL_MODEL, request, reply, 0)) {
                return "transaction rejected";
            }
            reply.readException();
            return "";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + safe(e.getMessage());
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    // Field order and values intentionally match navigation-type2-probe's ProtocolModel parcel.
    private static void writeProtocolModel(Parcel parcel, String packageName, int operation) {
        parcel.writeInt(PROTOCOL_INSTRUMENT_NAVIGATION_TYPE);
        parcel.writeLong(System.currentTimeMillis());
        parcel.writeInt(0);
        parcel.writeString("0");
        parcel.writeString(packageName);
        parcel.writeString("");
        parcel.writeInt(ACTION_SET);
        parcel.writeInt(operation);
        parcel.writeString("");
        parcel.writeString("");
        parcel.writeInt(0);
        parcel.writeString(null);
        parcel.writeString(null);
        parcel.writeString("");
        parcel.writeString(null);
        parcel.writeString(null);
        parcel.writeInt(1);
        parcel.writeInt(0);
        parcel.writeInt(0);
        parcel.writeInt(1);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
