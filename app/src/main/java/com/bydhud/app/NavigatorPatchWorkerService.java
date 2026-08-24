package com.bydhud.app;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Process boundary for DEX/APK work. It deliberately has no store or installer access.
 */
public final class NavigatorPatchWorkerService extends Service {
    static final int MSG_SCAN = 1;
    static final int MSG_PREPARE = 2;
    static final int MSG_CANCEL = 3;
    static final int MSG_RESULT = 4;
    static final int MSG_ABORT_PROCESS = 5;

    static final String KEY_OPERATION = "operation";
    static final String KEY_PROFILE = "profile";
    static final String KEY_SOURCE = "source";
    static final String KEY_OUTPUT = "output";
    static final String KEY_DIRECTORY = "directory";
    static final String KEY_TRANSACTION = "transaction";
    static final String KEY_EXPECTED = "expected";
    static final String KEY_STATUS = "status";
    static final String KEY_ERROR = "error";
    static final String KEY_SCAN = "scan";
    static final String KEY_INPUT = "input";
    static final String KEY_OUTPUT_RESULT = "output_result";
    static final String KEY_OPTIONAL_APPLIED = "optional_applied";
    static final String STATUS_OK = "OK";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_FAILED = "FAILED";

    private static final class Task {
        final Messenger reply;
        Future<?> future;
        volatile boolean started;
        volatile boolean cancelled;
        IBinder replyBinder;
        IBinder.DeathRecipient replyDeath;

        Task(Messenger reply) {
            this.reply = reply;
        }
    }

    private final Map<String, Task> operations = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private Messenger endpoint;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "navigator-patcher-worker");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return endpoint.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopWhenIdle();
        return false;
    }

    @Override
    public void onDestroy() {
        for (Task operation : operations.values()) {
            if (operation.future != null) operation.future.cancel(true);
        }
        operations.clear();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private boolean handleMessage(Message message) {
        Bundle data = message.getData();
        if (message.what == MSG_CANCEL) {
            cancel(data.getString(KEY_OPERATION));
            return true;
        }
        if (message.what == MSG_ABORT_PROCESS) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if (message.what != MSG_SCAN && message.what != MSG_PREPARE) return false;
        final String operation = data.getString(KEY_OPERATION, "");
        if (operation.isEmpty() || message.replyTo == null || operations.containsKey(operation)) {
            sendResult(message.replyTo, operation, STATUS_FAILED, "Invalid or duplicate operation", null);
            return true;
        }
        final Messenger reply = message.replyTo;
        final Bundle request = new Bundle(data);
        Task task = new Task(reply);
        synchronized (operations) {
            if (operations.containsKey(operation)) {
                sendResult(reply, operation, STATUS_FAILED, "Duplicate operation", null);
                return true;
            }
            operations.put(operation, task);
            task.replyBinder = reply.getBinder();
            task.replyDeath = () -> {
                if (operations.containsKey(operation)) {
                    Log.w("BYDHUD_PATCHER", "client_died operation=" + operation);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            };
            try {
                task.replyBinder.linkToDeath(task.replyDeath, 0);
            } catch (RemoteException deadClient) {
                operations.remove(operation, task);
                sendResult(reply, operation, STATUS_CANCELLED, "Client process died", null);
                stopWhenIdle();
                return true;
            }
            FutureTask<Void> future = new FutureTask<>(() -> {
                task.started = true;
                run(message.what, request, task);
                return null;
            });
            task.future = future;
            executor.execute(future);
        }
        return true;
    }

    private void run(int command, Bundle request, Task task) {
        String operation = request.getString(KEY_OPERATION, "");
        Messenger reply = task.reply;
        try {
            checkCancelled(task);
            String profile = request.getString(KEY_PROFILE, "");
            if (profile.isEmpty()) throw new IllegalArgumentException("Unknown patch profile");
            File source = file(request.getString(KEY_SOURCE, ""));
            if (command == MSG_SCAN) {
                File output = request.getBoolean(KEY_DIRECTORY, false)
                        ? null : requireFile(request, KEY_OUTPUT);
                NavigatorPatchPipeline.ScanResult result = output == null
                        ? NavigatorPatchPipeline.workerInspectDirectory(this, profile, source)
                        : NavigatorPatchPipeline.workerScan(this, profile, source, output);
                checkCancelled(task);
                sendResult(reply, operation, STATUS_OK, "", NavigatorPatchPipeline.workerBundle(result));
            } else {
                File transaction = requireFile(request, KEY_TRANSACTION);
                NavigatorPatchPipeline.ScanResult expected = NavigatorPatchPipeline.workerUnbundle(
                        request.getBundle(KEY_EXPECTED));
                NavigatorPatchPipeline.WorkerPatchResult result =
                        NavigatorPatchPipeline.workerPrepare(
                                this, profile, source, transaction, expected);
                checkCancelled(task);
                Bundle payload = new Bundle();
                payload.putBundle(KEY_INPUT, NavigatorPatchPipeline.workerBundle(result.input));
                payload.putBundle(KEY_OUTPUT_RESULT, NavigatorPatchPipeline.workerBundle(result.output));
                payload.putString(KEY_TRANSACTION, result.transaction.getAbsolutePath());
                payload.putBoolean(KEY_OPTIONAL_APPLIED, result.optionalApplied);
                sendResult(reply, operation, STATUS_OK, "", payload);
            }
        } catch (NavigatorPatchPipeline.OperationCancelledException cancelled) {
            sendResult(reply, operation, STATUS_CANCELLED, cancelled.getMessage(), null);
        } catch (Throwable error) {
            if (task.cancelled || Thread.currentThread().isInterrupted()) {
                sendResult(reply, operation, STATUS_CANCELLED, "Operation cancelled", null);
            } else {
                Log.w("BYDHUD_PATCHER", "worker_failed operation=" + operation, error);
                sendResult(reply, operation, STATUS_FAILED,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage(), null);
            }
        } finally {
            operations.remove(operation, task);
            unlinkReplyDeath(task);
            stopWhenIdle();
        }
    }

    private void cancel(String operation) {
        if (operation == null || operation.isEmpty()) return;
        Task task;
        boolean cancelledBeforeStart = false;
        synchronized (operations) {
            task = operations.get(operation);
            if (task == null) return;
            task.cancelled = true;
            if (task.future != null) task.future.cancel(true);
            if (!task.started && operations.remove(operation, task)) {
                cancelledBeforeStart = true;
            }
        }
        if (cancelledBeforeStart) {
            unlinkReplyDeath(task);
            sendResult(task.reply, operation, STATUS_CANCELLED, "Operation cancelled", null);
        }
        stopWhenIdle();
    }

    private static void checkCancelled(Task task)
            throws NavigatorPatchPipeline.OperationCancelledException {
        if (task.cancelled || Thread.currentThread().isInterrupted()) {
            throw new NavigatorPatchPipeline.OperationCancelledException();
        }
    }

    private static void unlinkReplyDeath(Task task) {
        if (task.replyBinder == null || task.replyDeath == null) return;
        task.replyBinder.unlinkToDeath(task.replyDeath, 0);
        task.replyBinder = null;
        task.replyDeath = null;
    }

    private void stopWhenIdle() {
        if (operations.isEmpty()) stopSelf();
    }

    private static File file(String path) {
        return path == null || path.isEmpty() ? null : new File(path);
    }

    private static File requireFile(Bundle data, String key) throws Exception {
        File file = file(data.getString(key, ""));
        if (file == null) throw new IllegalArgumentException("Missing " + key);
        return file;
    }

    private void sendResult(Messenger reply, String operation, String status, String error,
            Bundle payload) {
        if (reply == null) return;
        Message message = Message.obtain(null, MSG_RESULT);
        Bundle data = new Bundle();
        data.putString(KEY_OPERATION, operation);
        data.putString(KEY_STATUS, status);
        data.putString(KEY_ERROR, error == null ? "" : error);
        if (payload != null) data.putBundle(KEY_SCAN, payload);
        message.setData(data);
        try {
            reply.send(message);
        } catch (RemoteException ignored) {
            // The main process will treat binder death as a bounded operation failure.
        }
    }

}
