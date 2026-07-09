package com.bydhud.app;

//owns the main-screen MediaProjection mirror used by Waze visual parsing.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

//keeps capture lifecycle separate from WazeCropCapture so parser loops do not own Android consent.
public final class WazeMediaProjectionService extends Service {
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "data";

    private static final int NOTIFICATION_ID = 4401;
    private static final String CHANNEL_ID = "byd_hud_waze_projection";
    private static final String VIRTUAL_DISPLAY_NAME = "bydhud_waze_main_capture";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile MediaProjection projection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;

    @Override
    //registers the foreground service before MediaProjection work so Android keeps capture alive.
    public void onCreate() {
        super.onCreate();
        startProjectionForeground();
        WazeMediaProjectionController.registerService(this);
    }

    @Override
    //accepts the one-use consent token and creates the mirrored ImageReader display.
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (code != android.app.Activity.RESULT_OK || data == null) {
            WazeMediaProjectionController.clearConsent();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startProjection(code, data);
        return START_STICKY;
    }

    @Override
    //keeps the service unbound because capture is accessed through the local controller.
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    //cleans all native capture resources when Android stops the service.
    public void onDestroy() {
        releaseProjection("destroy");
        WazeMediaProjectionController.unregisterService(this);
        super.onDestroy();
    }

    //reports whether ImageReader frames can be consumed by the Waze parser.
    boolean isReady() {
        return projection != null && virtualDisplay != null && imageReader != null;
    }

    //copies the latest mirrored frame into a standalone bitmap owned by the caller.
    Bitmap copyLatestFrame() {
        ImageReader reader = imageReader;
        if (reader == null) {
            return null;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / Math.max(1, pixelStride),
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap frame = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            padded.recycle();
            return frame;
        } catch (RuntimeException e) {
            AppEventLogger.event(this, "waze_frame_capture image_reader_error "
                    + e.getClass().getSimpleName());
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    //creates the MediaProjection virtual display that feeds ImageReader.
    private void startProjection(int code, Intent data) {
        if (isReady()) {
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            stopSelf();
            return;
        }
        try {
            projection = manager.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            WazeMediaProjectionController.clearConsent();
            AppEventLogger.event(this, "waze_frame_capture projection_start_failed "
                    + e.getClass().getSimpleName());
            stopSelf();
            return;
        }
        if (projection == null) {
            WazeMediaProjectionController.clearConsent();
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            //releases mirrors when Android revokes screen capture.
            public void onStop() {
                releaseProjection("projection-stop", false);
                stopSelf();
            }
        }, handler);
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        }
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        int densityDpi = Math.max(1, metrics.densityDpi);
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        try {
            virtualDisplay = projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler);
        } catch (RuntimeException e) {
            AppEventLogger.event(this, "waze_frame_capture virtual_display_failed "
                    + e.getClass().getSimpleName());
            releaseProjection("virtual-display-failed");
            stopSelf();
            return;
        }
        if (virtualDisplay == null) {
            AppEventLogger.event(this, "waze_frame_capture virtual_display_failed null");
            releaseProjection("virtual-display-null");
            stopSelf();
            return;
        }
        AppEventLogger.event(this, "waze_frame_capture mediaprojection_ready "
                + width + "x" + height + " dpi=" + densityDpi);
        NavHudLiveSender.get(this).onWazeMediaProjectionReady(width + "x" + height);
    }

    //releases native mirrors in reverse ownership order.
    private void releaseProjection(String reason) {
        releaseProjection(reason, true);
    }

    //releases native mirrors in reverse ownership order and avoids recursive stop callbacks.
    private void releaseProjection(String reason, boolean stopProjection) {
        VirtualDisplay display = virtualDisplay;
        ImageReader reader = imageReader;
        MediaProjection activeProjection = projection;
        virtualDisplay = null;
        imageReader = null;
        projection = null;
        if (display != null) {
            display.release();
        }
        if (reader != null) {
            reader.close();
        }
        if (activeProjection != null && stopProjection) {
            activeProjection.stop();
        }
        WazeMediaProjectionController.clearConsent();
        AppEventLogger.event(this, "waze_frame_capture projection_released reason=" + reason);
    }

    //uses the mediaProjection foreground-service type so Android allows screen capture.
    private void startProjectionForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    //builds a quiet persistent notification matching existing runtime services.
    private Notification buildNotification() {
        createNotificationChannel();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                4401,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_hud_notification)
                .setContentTitle("BYD HUD")
                .setContentText("Waze frame capture active")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    //creates the notification channel lazily to avoid duplicate Android channel work.
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "BYD HUD Waze capture",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }
}
