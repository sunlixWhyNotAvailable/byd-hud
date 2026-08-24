package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Synchronous IO-thread client for the isolated patcher process. */
final class NavigatorPatchWorkerClient {
    private static final long BIND_TIMEOUT_MS = 10_000L;
    private static final long OPERATION_TIMEOUT_MS = 20 * 60_000L;

    private NavigatorPatchWorkerClient() {
    }

    static NavigatorPatchPipeline.ScanResult scan(Context context,
            NavigatorPatchStore.Profile profile, String operation, File source, File output)
            throws Exception {
        Bundle request = request(context, NavigatorPatchWorkerService.MSG_SCAN, operation,
                profile, source, output, null, null, false);
        return NavigatorPatchPipeline.workerUnbundle(
                request.getBundle(NavigatorPatchWorkerService.KEY_SCAN));
    }

    static NavigatorPatchPipeline.WorkerPatchResult prepare(Context context,
            NavigatorPatchStore.Profile profile, String operation, File source,
            File transaction, NavigatorPatchPipeline.ScanResult expected) throws Exception {
        Bundle request = request(context, NavigatorPatchWorkerService.MSG_PREPARE, operation,
                profile, source, null, transaction, expected, false);
        Bundle payload = request.getBundle(NavigatorPatchWorkerService.KEY_SCAN);
        if (payload == null) throw new IOException("Patcher returned no result");
        NavigatorPatchPipeline.ScanResult input = NavigatorPatchPipeline.workerUnbundle(
                payload.getBundle(NavigatorPatchWorkerService.KEY_INPUT));
        NavigatorPatchPipeline.ScanResult output = NavigatorPatchPipeline.workerUnbundle(
                payload.getBundle(NavigatorPatchWorkerService.KEY_OUTPUT_RESULT));
        File resultTransaction = new File(payload.getString(
                NavigatorPatchWorkerService.KEY_TRANSACTION, transaction.getAbsolutePath()));
        return new NavigatorPatchPipeline.WorkerPatchResult(input, output, resultTransaction,
                payload.getBoolean(NavigatorPatchWorkerService.KEY_OPTIONAL_APPLIED, false));
    }

    static NavigatorPatchPipeline.ScanResult inspectInstalled(Context context,
            NavigatorPatchStore.Profile profile, String operation, File scratch)
            throws Exception {
        Bundle response = request(context, NavigatorPatchWorkerService.MSG_SCAN, operation,
                profile, null, scratch, null, null, false);
        return NavigatorPatchPipeline.workerUnbundle(
                response.getBundle(NavigatorPatchWorkerService.KEY_SCAN));
    }

    static NavigatorPatchPipeline.ScanResult inspectDirectory(Context context,
            NavigatorPatchStore.Profile profile, String operation, File directory)
            throws Exception {
        Bundle response = request(context, NavigatorPatchWorkerService.MSG_SCAN, operation,
                profile, directory, null, null, null, true);
        return NavigatorPatchPipeline.workerUnbundle(
                response.getBundle(NavigatorPatchWorkerService.KEY_SCAN));
    }

    private static Bundle request(Context context, int command, String operation,
            NavigatorPatchStore.Profile profile, File source, File output, File transaction,
            NavigatorPatchPipeline.ScanResult expected, boolean directory) throws Exception {
        Bundle request = call(context, command, operation, data -> {
            data.putString(NavigatorPatchWorkerService.KEY_PROFILE, profile.id);
            data.putString(NavigatorPatchWorkerService.KEY_SOURCE,
                    source == null ? "" : source.getAbsolutePath());
            data.putString(NavigatorPatchWorkerService.KEY_OUTPUT,
                    output == null ? "" : output.getAbsolutePath());
            data.putString(NavigatorPatchWorkerService.KEY_TRANSACTION,
                    transaction == null ? "" : transaction.getAbsolutePath());
            data.putBoolean(NavigatorPatchWorkerService.KEY_DIRECTORY, directory);
            if (expected != null) data.putBundle(NavigatorPatchWorkerService.KEY_EXPECTED,
                    NavigatorPatchPipeline.workerBundle(expected));
        }, true);
        String status = request.getString(NavigatorPatchWorkerService.KEY_STATUS, "");
        if (NavigatorPatchWorkerService.STATUS_CANCELLED.equals(status)) {
            throw new NavigatorPatchPipeline.OperationCancelledException();
        }
        if (!NavigatorPatchWorkerService.STATUS_OK.equals(status)) {
            throw new IOException(request.getString(NavigatorPatchWorkerService.KEY_ERROR,
                    "Navigator patcher failed"));
        }
        return request;
    }

    private interface RequestBuilder {
        void apply(Bundle request);
    }

    private static Bundle call(Context context, int command, String operation,
            RequestBuilder builder, boolean awaitResult) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("Navigator patcher request cannot block the main thread");
        }
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Bundle> response = new AtomicReference<>();
        Messenger callback = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == NavigatorPatchWorkerService.MSG_RESULT) {
                response.set(message.getData());
                completed.countDown();
            }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
                remote.set(new Messenger(binder));
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                failure.compareAndSet(null, new IOException("Navigator patcher process died"));
                connected.countDown();
                completed.countDown();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                failure.compareAndSet(null, new IOException("Navigator patcher binding died"));
                connected.countDown();
                completed.countDown();
            }
        };
        Intent intent = new Intent(context, NavigatorPatchWorkerService.class);
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw new IOException("Cannot bind navigator patcher");
        }
        boolean requestSent = false;
        try {
            if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("Navigator patcher bind timeout");
            }
            Throwable bindFailure = failure.get();
            if (bindFailure != null) throw asException(bindFailure);
            Message request = Message.obtain(null, command);
            Bundle data = new Bundle();
            data.putString(NavigatorPatchWorkerService.KEY_OPERATION, operation);
            if (builder != null) builder.apply(data);
            request.setData(data);
            if (awaitResult) request.replyTo = callback;
            remote.get().send(request);
            requestSent = true;
            if (!awaitResult) return new Bundle();
            if (!completed.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancelAndFence(remote.get(), completed, operation);
                throw new IOException("Navigator patcher operation timeout");
            }
            Throwable processFailure = failure.get();
            if (processFailure != null) throw asException(processFailure);
            Bundle result = response.get();
            if (result == null) throw new IOException("Navigator patcher returned no result");
            return result;
        } catch (InterruptedException cancelled) {
            if (requestSent) cancelAndFence(remote.get(), completed, operation);
            throw new NavigatorPatchPipeline.OperationCancelledException();
        } catch (RemoteException error) {
            throw new IOException("Navigator patcher process died", error);
        } finally {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Bind failed or was already torn down by the system.
            }
        }
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception
                ? (Exception) error : new IOException(error.getMessage(), error);
    }

    private static void sendCancel(Messenger remote, String operation) {
        if (remote == null || operation == null || operation.isEmpty()) return;
        try {
            Message cancel = Message.obtain(null, NavigatorPatchWorkerService.MSG_CANCEL);
            Bundle data = new Bundle();
            data.putString(NavigatorPatchWorkerService.KEY_OPERATION, operation);
            cancel.setData(data);
            remote.send(cancel);
        } catch (RemoteException ignored) {
        }
    }

    private static void cancelAndFence(Messenger remote, CountDownLatch completed,
            String operation) throws IOException {
        sendCancel(remote, operation);
        if (awaitUninterruptibly(completed, BIND_TIMEOUT_MS)) return;
        try {
            remote.send(Message.obtain(null, NavigatorPatchWorkerService.MSG_ABORT_PROCESS));
        } catch (RemoteException processAlreadyDead) {
            return;
        }
        if (!awaitUninterruptibly(completed, BIND_TIMEOUT_MS)) {
            throw new IOException("Navigator patcher cancellation fence timeout");
        }
    }

    private static boolean awaitUninterruptibly(CountDownLatch latch, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) return latch.getCount() == 0L;
            try {
                return latch.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                // Cancellation is already represented by the worker protocol and Store state.
            }
        }
    }
}
