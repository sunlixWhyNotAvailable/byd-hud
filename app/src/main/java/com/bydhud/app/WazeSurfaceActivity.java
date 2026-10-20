package com.bydhud.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Button;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class WazeSurfaceActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "BYD_HUD_WAZE_SURFACE";
    private static final long SURFACE_DESTROY_ACK_TIMEOUT_MS = 250L;
    interface HostBridge {
        void onSurfaceReady(Surface surface, int width, int height, int dpi, Rect visibleArea,
                long activityInstanceId, int displayId, long surfaceEpoch);
        void onSurfaceDestroyed(Runnable completion);
        void onVisibleAreaChanged(Rect visibleArea);
        void onClick(float x, float y);
        void onScroll(float distanceX, float distanceY);
        void onFling(float velocityX, float velocityY);
        void onScale(float focusX, float focusY, float scaleFactor);
        void onRecenter();
    }

    private static final Object BRIDGE_LOCK = new Object();
    private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong(1L);
    private static WeakReference<WazeSurfaceActivity> active = new WeakReference<>(null);
    private static HostBridge hostBridge;

    static boolean launch(Context context, int displayId) {
        Intent intent = new Intent(context, WazeSurfaceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            if (displayId >= 0) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                context.startActivity(intent, options.toBundle());
            } else {
                context.startActivity(intent);
            }
            SurfaceLog.event("activity_launch_requested", "display", displayId);
            return true;
        } catch (RuntimeException error) {
            SurfaceLog.error("activity_launch_failed", error);
            return false;
        }
    }

    static void finishActive(String reason) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) {
            activity = active.get();
        }
        SurfaceLog.event("activity_finish_requested", "reason", reason,
                "active", activity != null);
        if (activity != null) activity.runOnUiThread(() -> {
            activity.closeReason = reason == null ? "requested-close" : reason;
            activity.finish();
        });
    }

    static int activeTaskId() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            return activity == null ? -1 : activity.getTaskId();
        }
    }

    static int activeDisplayId() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            return activity == null
                    ? -1 : activity.getWindowManager().getDefaultDisplay().getDisplayId();
        }
    }

    static long activeInstanceId() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            return activity == null ? 0L : activity.instanceId;
        }
    }

    static long activeSurfaceEpoch() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            return activity == null ? 0L : activity.surfaceEpoch;
        }
    }

    static boolean hasValidSurface() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            if (activity == null) return false;
            Surface currentSurface = activity.surface;
            return currentSurface != null && currentSurface.isValid();
        }
    }

    static boolean isActive() {
        return activeTaskId() >= 0;
    }

    static void refreshSurfaceAfterMove(int taskId) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) { activity = active.get(); }
        if (activity == null || activity.getTaskId() != taskId) return;
        activity.runOnUiThread(() -> {
            synchronized (BRIDGE_LOCK) {
                if (active.get() != activity || !activity.visible) return;
            }
            activity.dispatchSurfaceState();
        });
    }

    static boolean isVisible() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            return activity != null && activity.visible;
        }
    }

    private SurfaceView surfaceView;
    private Button recenterButton;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleDetector;
    private volatile Surface surface;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile int surfaceDpi;
    private volatile Rect visibleArea = new Rect();
    private final long instanceId = NEXT_INSTANCE_ID.getAndIncrement();
    private volatile long surfaceEpoch;
    private boolean suppressGestureUntilNextDown;
    private volatile boolean visible;
    private String closeReason;

    static void attachHostBridge(HostBridge bridge) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) {
            hostBridge = bridge;
            activity = active.get();
        }
        if (activity != null) activity.runOnUiThread(activity::dispatchSurfaceState);
    }

    static void detachHostBridge(HostBridge bridge) {
        synchronized (BRIDGE_LOCK) {
            if (hostBridge == bridge) hostBridge = null;
        }
    }

    static void setRecenterAvailable(boolean available) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) { activity = active.get(); }
        if (activity != null) activity.runOnUiThread(
                () -> activity.recenterButton.setEnabled(available));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle("Waze Surface");
        setTaskDescription(new ActivityManager.TaskDescription("Waze Surface"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        recenterButton = new Button(this);
        String language = HudPrefs.uiLanguage(this);
        recenterButton.setText("uk".equals(language) ? "Центрувати"
                : "ru".equals(language) ? "Центрировать" : "Center");
        recenterButton.setAllCaps(false);
        recenterButton.setTextSize(14f);
        recenterButton.setEnabled(false);
        boolean dark = HudPrefs.isDarkTheme(this);
        recenterButton.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{dark ? 0xffaab8ca : 0xff526274, dark ? 0xfff1f6ff : 0xff121a23}));
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xff172231 : 0xfff0f5fb);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), dark ? 0xff40536a : 0xff6d7d8f);
        recenterButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x402f86f6 : 0x242f86f6), background, null));
        recenterButton.setPadding(dp(16), 0, dp(16), 0);
        recenterButton.setOnClickListener(view -> {
            HostBridge bridge = bridge();
            if (bridge != null) bridge.onRecenter();
        });
        FrameLayout.LayoutParams buttonLayout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.BOTTOM | Gravity.LEFT);
        buttonLayout.setMargins(dp(14), dp(14), dp(14), dp(14));
        root.addView(recenterButton, buttonLayout);

        configureGestures();
        setContentView(root);
        synchronized (BRIDGE_LOCK) {
            active = new WeakReference<>(this);
        }
        NavHudLiveSender.get(this).onWazeSurfaceActivityCreated(getTaskId(), instanceId);
        NavAppDisplayController.get(this).onSurfaceWindowChanged();
        SurfaceLog.event("visible_surface_activity_created");
    }

    @Override
    protected void onDestroy() {
        SurfaceLog.event("visible_surface_activity_destroyed",
                "task", getTaskId(), "display", getWindowManager().getDefaultDisplay().getDisplayId(),
                "changingConfigurations", isChangingConfigurations(),
                "finishing", isFinishing(),
                "closeReason", closeReason == null ? "system" : closeReason);
        synchronized (BRIDGE_LOCK) {
            if (active.get() == this) active = new WeakReference<>(null);
        }
        NavHudLiveSender.get(this).onWazeSurfaceActivityDestroyed(
                getTaskId(), instanceId, isChangingConfigurations());
        // System task trimming can also set isFinishing(); it must not retire projection intent.
        if (closeReason != null && !isChangingConfigurations()) {
            NavAppDisplayController.get(this).onSurfaceTaskFinished(getTaskId());
        }
        NavAppDisplayController.get(this).onSurfaceWindowChanged();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        NavHudLiveSender.get(this).onWazeSurfaceActivityVisibilityChanged(instanceId, true);
        NavAppDisplayController.get(this).onSurfaceWindowChanged();
        dispatchSurfaceState();
    }

    @Override
    protected void onStop() {
        visible = false;
        NavHudLiveSender.get(this).onWazeSurfaceActivityVisibilityChanged(instanceId, false);
        NavAppDisplayController.get(this).onSurfaceWindowChanged();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // A paused window can remain visible on the cluster while Routes owns tablet focus.
        SurfaceLog.event("surface_focus", "focused", hasFocus, "visible", visible,
                "task", getTaskId(), "display", activeDisplayId());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        SurfaceLog.event("visible_surface_activity_reused");
        dispatchSurfaceState();
    }

    @Override
    public void onBackPressed() {
        // Keep the independent root task available in Recents, just like minimizing a window.
        SurfaceLog.event("surface_background_requested", "task", getTaskId());
        moveTaskToBack(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceEpoch++;
        SurfaceLog.event("visible_surface_created");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        int dpi = Math.max(1, getResources().getDisplayMetrics().densityDpi);
        if (surfaceWidth != width || surfaceHeight != height || surfaceDpi != dpi) surfaceEpoch++;
        surface = holder.getSurface();
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        surfaceDpi = dpi;
        updateVisibleArea();
        SurfaceLog.event("visible_surface_changed", "width", surfaceWidth,
                "height", surfaceHeight, "dpi", surfaceDpi,
                "valid", surface != null && surface.isValid());
        SurfaceLog.milestone("visibleSurfaceReady", surface != null && surface.isValid());
        dispatchSurfaceState();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) recenterButton.getLayoutParams();
        layout.height = dp(44);
        layout.setMargins(dp(14), dp(14), dp(14), dp(14));
        recenterButton.setLayoutParams(layout);
        recenterButton.setPadding(dp(16), 0, dp(16), 0);
        recenterButton.setTextSize(14f);
        if (surface == null || !surface.isValid()) return;
        Rect frame = surfaceView.getHolder().getSurfaceFrame();
        surfaceChanged(surfaceView.getHolder(), 0, frame.width(), frame.height());
        NavAppDisplayController.get(this).onSurfaceWindowChanged();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        CountDownLatch destroyed = new CountDownLatch(1);
        HostBridge bridge = bridge();
        if (bridge == null) {
            destroyed.countDown();
        } else {
            bridge.onSurfaceDestroyed(destroyed::countDown);
        }
        try {
            if (!destroyed.await(SURFACE_DESTROY_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                SurfaceLog.event("surface_destroy_ack_timeout");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        SurfaceLog.event("visible_surface_destroyed");
        SurfaceLog.milestone("visibleSurfaceReady", false);
        surface = null;
        surfaceWidth = 0;
        surfaceHeight = 0;
        surfaceDpi = 0;
        visibleArea = new Rect();
    }

    private void configureGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                HostBridge bridge = bridge();
                if (bridge != null) bridge.onClick(surfaceX(event.getX()), surfaceY(event.getY()));
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current,
                                    float distanceX, float distanceY) {
                HostBridge bridge = bridge();
                if (bridge != null) bridge.onScroll(surfaceX(distanceX), surfaceY(distanceY));
                return true;
            }

            @Override
            public boolean onFling(MotionEvent first, MotionEvent current,
                                   float velocityX, float velocityY) {
                HostBridge bridge = bridge();
                if (bridge != null) bridge.onFling(surfaceX(velocityX), surfaceY(velocityY));
                return true;
            }
        });
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        HostBridge bridge = bridge();
                        if (bridge != null) bridge.onScale(
                                surfaceX(detector.getFocusX()), surfaceY(detector.getFocusY()),
                                detector.getScaleFactor());
                        return true;
                    }
                });
        surfaceView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                suppressGestureUntilNextDown = false;
            }
            boolean wasScaling = scaleDetector.isInProgress();
            scaleDetector.onTouchEvent(event);
            boolean scaling = scaleDetector.isInProgress();
            if (!wasScaling && scaling) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                gestureDetector.onTouchEvent(cancel);
                cancel.recycle();
            }
            if (wasScaling || scaling || event.getPointerCount() > 1) {
                suppressGestureUntilNextDown = true;
            } else if (!suppressGestureUntilNextDown) {
                gestureDetector.onTouchEvent(event);
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                suppressGestureUntilNextDown = false;
            }
            return true;
        });
    }

    private void updateVisibleArea() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return;
        Rect next = new Rect(0, 0, surfaceWidth, surfaceHeight);
        if (next.equals(visibleArea)) return;
        visibleArea = next;
        SurfaceLog.event("surface_visible_area", "rect", visibleArea.toShortString());
        HostBridge bridge = bridge();
        if (bridge != null) bridge.onVisibleAreaChanged(new Rect(visibleArea));
    }

    private void dispatchSurfaceState() {
        HostBridge bridge = bridge();
        if (bridge == null || surface == null || !surface.isValid()) return;
        bridge.onSurfaceReady(surface, surfaceWidth, surfaceHeight, surfaceDpi,
                new Rect(visibleArea), instanceId, activeDisplayId(), surfaceEpoch);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float surfaceX(float value) {
        return surfaceCoordinate(value, surfaceView.getWidth(), surfaceWidth);
    }

    private float surfaceY(float value) {
        return surfaceCoordinate(value, surfaceView.getHeight(), surfaceHeight);
    }

    static float surfaceCoordinate(float value, int viewExtent, int bufferExtent) {
        return viewExtent > 0 && bufferExtent > 0 ? value * bufferExtent / viewExtent : value;
    }

    private static HostBridge bridge() {
        synchronized (BRIDGE_LOCK) {
            return hostBridge;
        }
    }

    private static final class SurfaceLog {
        private SurfaceLog() {
        }

        static void event(String name, Object... values) {
            StringBuilder line = new StringBuilder(name == null ? "" : name);
            if (values != null) {
                for (int index = 0; index + 1 < values.length; index += 2) {
                    line.append(' ').append(values[index]).append('=').append(values[index + 1]);
                }
            }
            String text = line.toString();
            Log.i(TAG, text);
            WazeSurfaceActivity activity;
            synchronized (BRIDGE_LOCK) {
                activity = active.get();
            }
            if (activity != null) AppEventLogger.event(activity, "waze_surface " + text);
        }

        static void milestone(String name, boolean value) {
            event("milestone", name, value);
        }

        static void error(String name, Throwable error) {
            event(name, "error", error == null ? "unknown" : error.toString());
        }

    }
}
