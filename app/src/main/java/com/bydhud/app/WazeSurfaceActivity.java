package com.bydhud.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;

import androidx.car.app.FailureResponse;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Alert;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Item;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.OnClickDelegate;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchCallbackDelegate;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.SectionedItemList;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapController;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PanModeDelegate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate;
import androidx.car.app.serialization.Bundleable;
import androidx.core.graphics.drawable.IconCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
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
        void onBackPressedByHost();
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
        if (activity != null) activity.runOnUiThread(activity::finish);
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

    static boolean isVisible() {
        synchronized (BRIDGE_LOCK) {
            WazeSurfaceActivity activity = active.get();
            return activity != null && activity.visible;
        }
    }

    private SurfaceView surfaceView;
    private LinearLayout overlay;
    private TextView templateStatus;
    private LinearLayout appActions;
    private LinearLayout mapActions;
    private LinearLayout contentActions;
    private TextView contentStatus;
    private TextView alertStatus;
    private LinearLayout alertActions;
    private LinearLayout searchControls;
    private EditText searchInput;
    private TextWatcher searchWatcher;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleDetector;
    private volatile Surface surface;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile int surfaceDpi;
    private volatile Rect visibleArea = new Rect();
    private final long instanceId = NEXT_INSTANCE_ID.getAndIncrement();
    private volatile long surfaceEpoch;
    private boolean suppressSearchCallback;
    private boolean suppressGestureUntilNextDown;
    private boolean panMode;
    private int visibleAlertId = -1;
    private volatile boolean visible;
    private boolean templateRendered;
    private boolean templateRenderPosted;
    private Template pendingTemplate;
    private Template lastRenderedTemplate;

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

    static void showTemplate(Template template) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) {
            activity = active.get();
        }
        if (activity == null) {
            SurfaceLog.event("surface_template_without_activity",
                    "type", template == null ? "null" : template.getClass().getName());
            return;
        }
        activity.enqueueTemplate(template);
    }

    static void showAlert(Alert alert) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) {
            activity = active.get();
        }
        if (activity != null) activity.runOnUiThread(() -> activity.renderAlert(alert));
    }

    static void dismissAlert(int alertId) {
        WazeSurfaceActivity activity;
        synchronized (BRIDGE_LOCK) {
            activity = active.get();
        }
        if (activity != null) activity.runOnUiThread(() -> activity.clearAlert(alertId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
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

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(12), dp(8), dp(12), dp(8));
        overlay.setBackgroundColor(0xcc101214);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(overlay, overlayParams);

        templateStatus = new TextView(this);
        templateStatus.setText("Waiting for Waze template");
        templateStatus.setTextColor(Color.WHITE);
        templateStatus.setTextSize(16f);
        overlay.addView(templateStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        appActions = addActionRow();
        mapActions = addActionRow();
        contentActions = addActionRow();

        contentStatus = new TextView(this);
        contentStatus.setTextColor(Color.WHITE);
        contentStatus.setTextSize(15f);
        contentStatus.setVisibility(View.GONE);
        overlay.addView(contentStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        alertStatus = new TextView(this);
        alertStatus.setTextColor(0xffffd54f);
        alertStatus.setTextSize(16f);
        alertStatus.setGravity(Gravity.CENTER_VERTICAL);
        alertStatus.setVisibility(View.GONE);
        overlay.addView(alertStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        alertActions = addActionRow();

        searchControls = new LinearLayout(this);
        searchControls.setOrientation(LinearLayout.HORIZONTAL);
        searchControls.setVisibility(View.GONE);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(0xffb0b4b8);
        searchControls.addView(searchInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        overlay.addView(searchControls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        overlay.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) ->
                updateVisibleArea());
        configureGestures();
        setContentView(root);
        synchronized (BRIDGE_LOCK) {
            active = new WeakReference<>(this);
        }
        NavHudLiveSender.get(this).onWazeSurfaceActivityCreated(getTaskId(), instanceId);
        SurfaceLog.event("visible_surface_activity_created");
    }

    @Override
    protected void onDestroy() {
        synchronized (BRIDGE_LOCK) {
            if (active.get() == this) active = new WeakReference<>(null);
        }
        NavHudLiveSender.get(this).onWazeSurfaceActivityDestroyed(
                getTaskId(), instanceId, isChangingConfigurations());
        SurfaceLog.event("visible_surface_activity_destroyed");
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        NavHudLiveSender.get(this).onWazeSurfaceActivityVisibilityChanged(instanceId, true);
        dispatchSurfaceState();
    }

    @Override
    protected void onPause() {
        visible = false;
        NavHudLiveSender.get(this).onWazeSurfaceActivityVisibilityChanged(instanceId, false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        SurfaceLog.event("visible_surface_activity_reused");
        dispatchSurfaceState();
    }

    @Override
    public void onBackPressed() {
        NavHudLiveSender.get(this).onWazeSurfaceBackPressed(instanceId);
        finish();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceEpoch++;
        SurfaceLog.event("visible_surface_created");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        surfaceDpi = Math.max(1, getResources().getDisplayMetrics().densityDpi);
        updateVisibleArea();
        SurfaceLog.event("visible_surface_changed", "width", surfaceWidth,
                "height", surfaceHeight, "dpi", surfaceDpi,
                "valid", surface != null && surface.isValid());
        SurfaceLog.milestone("visibleSurfaceReady", surface != null && surface.isValid());
        dispatchSurfaceState();
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
                if (bridge != null) bridge.onClick(event.getX(), event.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current,
                                    float distanceX, float distanceY) {
                HostBridge bridge = bridge();
                if (bridge != null) bridge.onScroll(distanceX, distanceY);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent first, MotionEvent current,
                                   float velocityX, float velocityY) {
                HostBridge bridge = bridge();
                if (bridge != null) bridge.onFling(velocityX, velocityY);
                return true;
            }
        });
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        HostBridge bridge = bridge();
                        if (bridge != null) bridge.onScale(detector.getFocusX(), detector.getFocusY(),
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

    private void enqueueTemplate(Template template) {
        synchronized (this) {
            pendingTemplate = template;
            if (templateRenderPosted) return;
            templateRenderPosted = true;
        }
        runOnUiThread(() -> {
            Template latest;
            synchronized (WazeSurfaceActivity.this) {
                latest = pendingTemplate;
                pendingTemplate = null;
                templateRenderPosted = false;
            }
            renderTemplate(latest);
        });
    }

    private void renderTemplate(Template template) {
        if (templateRendered && (template == lastRenderedTemplate
                || template != null && template.equals(lastRenderedTemplate))) {
            return;
        }
        templateRendered = true;
        lastRenderedTemplate = template;
        clearActionRow(appActions);
        clearActionRow(mapActions);
        clearActionRow(contentActions);
        contentStatus.setText("");
        contentStatus.setCompoundDrawables(null, null, null, null);
        contentStatus.setVisibility(View.GONE);
        searchControls.setVisibility(View.GONE);
        if (searchWatcher != null) searchInput.removeTextChangedListener(searchWatcher);
        searchWatcher = null;
        searchControls.removeAllViews();
        searchControls.addView(searchInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        panMode = false;
        if (template == null) {
            templateStatus.setText("No Waze template");
            SurfaceLog.event("template_inventory", "type", "null");
            updateVisibleArea();
            return;
        }

        String type = template.getClass().getSimpleName();
        templateStatus.setText(type);
        List<String> actionInventory = new ArrayList<>();
        List<String> rowInventory = new ArrayList<>();
        addHostBackButton();

        if (template instanceof PlaceListNavigationTemplate) {
            PlaceListNavigationTemplate value = (PlaceListNavigationTemplate) template;
            templateStatus.setText(type + titleSuffix(value.getTitle()));
            addActionStrip(value.getActionStrip(), "action", actionInventory, null);
            addActionStrip(value.getMapActionStrip(), "map", actionInventory,
                    value.getPanModeDelegate());
            addRows(value.getItemList(), rowInventory);
            markPhase("phasePlaceList", "place_list");
        } else if (template instanceof RoutePreviewNavigationTemplate) {
            RoutePreviewNavigationTemplate value = (RoutePreviewNavigationTemplate) template;
            templateStatus.setText(type + titleSuffix(value.getTitle()));
            addAction(value.getNavigateAction(), "navigate", actionInventory, null);
            addActionStrip(value.getActionStrip(), "action", actionInventory, null);
            addActionStrip(value.getMapActionStrip(), "map", actionInventory,
                    value.getPanModeDelegate());
            addRows(value.getItemList(), rowInventory);
            markPhase("phaseRoutePreview", "route_preview");
        } else if (template instanceof NavigationTemplate) {
            NavigationTemplate value = (NavigationTemplate) template;
            addActionStrip(value.getActionStrip(), "action", actionInventory, null);
            addActionStrip(value.getMapActionStrip(), "map", actionInventory,
                    value.getPanModeDelegate());
            markPhase("phaseActiveNavigation", "active_navigation");
        } else if (template instanceof SearchTemplate) {
            SearchTemplate value = (SearchTemplate) template;
            addActionStrip(value.getActionStrip(), "action", actionInventory, null);
            addRows(value.getItemList(), rowInventory);
            configureSearch(value);
            markPhase("phaseSearch", "search");
        } else if (template instanceof MapWithContentTemplate) {
            MapWithContentTemplate value = (MapWithContentTemplate) template;
            addActionStrip(value.getActionStrip(), "action", actionInventory, null);
            MapController controller = value.getMapController();
            if (controller != null) {
                addActionStrip(controller.getMapActionStrip(), "map", actionInventory,
                        controller.getPanModeDelegate());
            }
            renderContentTemplate(value.getContentTemplate(), actionInventory, rowInventory);
            markPhase("phaseMapWithContent", "map_with_content");
        } else {
            renderContentTemplate(template, actionInventory, rowInventory);
        }

        SurfaceLog.event("template_inventory", "type", template.getClass().getName(),
                "actions", actionInventory.toString(), "rows", rowInventory.toString(),
                "actionCount", actionInventory.size(), "rowCount", rowInventory.size());
        SurfaceLog.milestone("templateReceived", true);
        updateVisibleArea();
    }

    private void addHostBackButton() {
        addButton(appActions, "Back", null, true, "host:back", () -> {
            HostBridge bridge = bridge();
            if (bridge != null) bridge.onBackPressedByHost();
        });
    }

    private void addActionStrip(ActionStrip strip, String source,
                                List<String> inventory, PanModeDelegate panDelegate) {
        if (strip == null) return;
        int index = 0;
        for (Action action : strip.getActions()) {
            addAction(action, source + ':' + index++, inventory, panDelegate);
        }
    }

    private void addAction(Action action, String source,
                           List<String> inventory, PanModeDelegate panDelegate) {
        if (action == null) return;
        String title = text(action.getTitle());
        String label = title.isEmpty() ? Action.typeToString(action.getType()) : title;
        OnClickDelegate click = action.getOnClickDelegate();
        inventory.add(source + "{type=" + action.getType() + ",title=" + title
                + ",click=" + (click != null) + ",icon=" + iconMarker(action.getIcon()) + "}");
        if (action.getType() == Action.TYPE_BACK) {
            addButton(appActions, label, action.getIcon(), true, source, () -> {
                HostBridge bridge = bridge();
                if (bridge != null) bridge.onBackPressedByHost();
            });
        } else if (action.getType() == Action.TYPE_PAN && panDelegate != null) {
            addButton(mapActions, label, action.getIcon(), true, source, () -> {
                panMode = !panMode;
                invokePan(panDelegate, panMode, source);
            });
        } else if (click != null) {
            LinearLayout target = source.startsWith("map:") ? mapActions
                    : source.startsWith("content:") || source.startsWith("row:")
                    ? contentActions : appActions;
            String visibleLabel = title.isEmpty() && action.getIcon() != null ? "" : label;
            addButton(target, visibleLabel, action.getIcon(), safeEnabled(action), source,
                    () -> invokeClick(click, source));
        }
    }

    private void addRows(ItemList itemList, List<String> inventory) {
        if (itemList == null) return;
        addRows(itemList.getItems(), inventory);
    }

    private void addRows(List<? extends Item> items, List<String> inventory) {
        int index = 0;
        for (Item item : items) {
            if (!(item instanceof Row)) {
                inventory.add(index++ + "{type=" + item.getClass().getName() + "}");
                continue;
            }
            Row row = (Row) item;
            String title = text(row.getTitle());
            String detail = joinText(row.getTexts());
            OnClickDelegate click = row.getOnClickDelegate();
            String source = "row:" + index++;
            inventory.add(source + "{title=" + title + ",text=" + detail
                    + ",click=" + (click != null) + "}");
            String label = title.isEmpty() ? detail : title;
            if (!detail.isEmpty() && !detail.equals(label)) label += " | " + detail;
            String finalLabel = label.isEmpty() ? source : label;
            addButton(contentActions, finalLabel, row.getImage(), click != null && safeEnabled(row),
                    source, click == null ? () -> { } : () -> invokeClick(click, source));
            int actionIndex = 0;
            for (Action action : row.getActions()) {
                addAction(action, source + ":action:" + actionIndex++, inventory, null);
            }
        }
    }

    private void renderContentTemplate(Template template, List<String> actionInventory,
                                       List<String> rowInventory) {
        if (template == null) return;
        if (template instanceof ListTemplate) {
            ListTemplate value = (ListTemplate) template;
            appendContentTitle(value.getTitle());
            addAction(value.getHeaderAction(), "content:header", actionInventory, null);
            addActionStrip(value.getActionStrip(), "content:action", actionInventory, null);
            addRows(value.getSingleList(), rowInventory);
            for (SectionedItemList section : value.getSectionedLists()) {
                appendContentTitle(section.getHeader());
                addRows(section.getItemList(), rowInventory);
            }
            int index = 0;
            for (Action action : value.getActions()) {
                addContentAction(action, "content:button:" + index++, actionInventory);
            }
        } else if (template instanceof PaneTemplate) {
            PaneTemplate value = (PaneTemplate) template;
            appendContentTitle(value.getTitle());
            addAction(value.getHeaderAction(), "content:header", actionInventory, null);
            addActionStrip(value.getActionStrip(), "content:action", actionInventory, null);
            Pane pane = value.getPane();
            if (pane != null) {
                addRows(pane.getRows(), rowInventory);
                int index = 0;
                for (Action action : pane.getActions()) {
                    addContentAction(action, "content:pane:" + index++, actionInventory);
                }
            }
        } else if (template instanceof MessageTemplate) {
            MessageTemplate value = (MessageTemplate) template;
            appendContentTitle(value.getTitle());
            appendContentTitle(value.getMessage());
            setContentIcon(value.getIcon());
            addAction(value.getHeaderAction(), "content:header", actionInventory, null);
            addActionStrip(value.getActionStrip(), "content:action", actionInventory, null);
            int index = 0;
            for (Action action : value.getActions()) {
                addContentAction(action, "content:button:" + index++, actionInventory);
            }
        }
    }

    private void addContentAction(Action action, String source, List<String> inventory) {
        if (action == null) return;
        String title = text(action.getTitle());
        OnClickDelegate click = action.getOnClickDelegate();
        inventory.add(source + "{type=" + action.getType() + ",title=" + title
                + ",click=" + (click != null) + ",icon=" + iconMarker(action.getIcon()) + "}");
        String label = title.isEmpty() && action.getIcon() != null
                ? "" : (title.isEmpty() ? Action.typeToString(action.getType()) : title);
        addButton(contentActions, label, action.getIcon(), click != null && safeEnabled(action),
                source, click == null ? () -> { } : () -> invokeClick(click, source));
    }

    private void appendContentTitle(CarText value) {
        String text = text(value);
        if (text.isEmpty()) return;
        String current = contentStatus.getText().toString();
        contentStatus.setText(current.isEmpty() ? text : current + " | " + text);
        contentStatus.setVisibility(View.VISIBLE);
    }

    private void setContentIcon(CarIcon icon) {
        Drawable drawable = loadIcon(icon, 32);
        if (drawable == null) return;
        contentStatus.setCompoundDrawablePadding(dp(6));
        contentStatus.setCompoundDrawables(drawable, null, null, null);
        contentStatus.setVisibility(View.VISIBLE);
    }

    private void renderAlert(Alert alert) {
        if (alert == null) return;
        visibleAlertId = alert.getId();
        String title = text(alert.getTitle());
        String subtitle = text(alert.getSubtitle());
        alertStatus.setText(subtitle.isEmpty() ? title : title + " | " + subtitle);
        alertStatus.setCompoundDrawablePadding(dp(6));
        alertStatus.setCompoundDrawables(loadIcon(alert.getIcon(), 36), null, null, null);
        alertStatus.setVisibility(View.VISIBLE);
        clearActionRow(alertActions);
        List<String> inventory = new ArrayList<>();
        int index = 0;
        for (Action action : alert.getActions()) {
            if (action == null) continue;
            String source = "alert:" + alert.getId() + ':' + index++;
            String titleText = text(action.getTitle());
            OnClickDelegate click = action.getOnClickDelegate();
            inventory.add(source + "{title=" + titleText + ",click=" + (click != null) + "}");
            String label = titleText.isEmpty() && action.getIcon() != null ? "" : titleText;
            addButton(alertActions, label, action.getIcon(), click != null && safeEnabled(action),
                    source, click == null ? () -> { } : () -> invokeClick(click, source));
        }
        SurfaceLog.event("visible_alert_show", "id", alert.getId(), "actions", inventory.toString());
        updateVisibleArea();
    }

    private void clearAlert(int alertId) {
        if (visibleAlertId != alertId) return;
        visibleAlertId = -1;
        alertStatus.setText("");
        alertStatus.setCompoundDrawables(null, null, null, null);
        alertStatus.setVisibility(View.GONE);
        clearActionRow(alertActions);
        SurfaceLog.event("visible_alert_dismiss", "id", alertId);
        updateVisibleArea();
    }

    private void configureSearch(SearchTemplate template) {
        SearchCallbackDelegate delegate = template.getSearchCallbackDelegate();
        searchControls.setVisibility(View.VISIBLE);
        searchInput.setHint(template.getSearchHint());
        suppressSearchCallback = true;
        searchInput.setText(template.getInitialSearchText());
        searchInput.setSelection(searchInput.length());
        suppressSearchCallback = false;
        searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressSearchCallback) return;
                invokeSearchChanged(delegate, s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        searchInput.addTextChangedListener(searchWatcher);
        Button submit = new Button(this);
        submit.setText("Search");
        submit.setOnClickListener(view -> invokeSearchSubmitted(delegate,
                searchInput.getText().toString()));
        searchControls.addView(submit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
    }

    private LinearLayout addActionRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void clearActionRow(LinearLayout row) {
        row.removeAllViews();
        ((View) row.getParent()).setVisibility(View.GONE);
    }

    private void addButton(LinearLayout row, String label, CarIcon icon, boolean enabled,
                           String description, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(label.isEmpty() ? description : label);
        button.setEnabled(enabled);
        button.setMinWidth(dp(label.isEmpty() ? 56 : 96));
        Drawable drawable = loadIcon(icon, 32);
        if (drawable != null) {
            button.setCompoundDrawablePadding(dp(6));
            button.setCompoundDrawables(drawable, null, null, null);
        }
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        ((View) row.getParent()).setVisibility(View.VISIBLE);
    }

    private void invokeClick(OnClickDelegate delegate, String source) {
        SurfaceLog.event("template_delegate_invoked", "kind", "click", "source", source);
        try {
            delegate.sendClick(new UiDoneCallback("click:" + source));
        } catch (Throwable t) {
            SurfaceLog.error("template_click_failed", t);
        }
    }

    private void invokePan(PanModeDelegate delegate, boolean enabled, String source) {
        SurfaceLog.event("template_delegate_invoked", "kind", "pan", "source", source,
                "enabled", enabled);
        try {
            delegate.sendPanModeChanged(enabled, new UiDoneCallback("pan:" + source));
        } catch (Throwable t) {
            SurfaceLog.error("template_pan_failed", t);
        }
    }

    private void invokeSearchChanged(SearchCallbackDelegate delegate, String value) {
        SurfaceLog.event("template_delegate_invoked", "kind", "search_changed",
                "length", value.length());
        try {
            delegate.sendSearchTextChanged(value, new UiDoneCallback("search_changed"));
        } catch (Throwable t) {
            SurfaceLog.error("template_search_changed_failed", t);
        }
    }

    private void invokeSearchSubmitted(SearchCallbackDelegate delegate, String value) {
        SurfaceLog.event("template_delegate_invoked", "kind", "search_submitted",
                "length", value.length());
        try {
            delegate.sendSearchSubmitted(value, new UiDoneCallback("search_submitted"));
        } catch (Throwable t) {
            SurfaceLog.error("template_search_submit_failed", t);
        }
    }

    private void markPhase(String milestone, String phase) {
        SurfaceLog.milestone(milestone, true);
        SurfaceLog.event("phase_marker", "phase", phase);
    }

    private void updateVisibleArea() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return;
        int overlayBottom = Math.min(surfaceHeight, Math.max(0, overlay.getBottom()));
        Rect next = new Rect(0, overlayBottom, surfaceWidth, surfaceHeight);
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

    private static HostBridge bridge() {
        synchronized (BRIDGE_LOCK) {
            return hostBridge;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String titleSuffix(CarText title) {
        String value = text(title);
        return value.isEmpty() ? "" : " | " + value;
    }

    private static String text(CarText value) {
        return value == null ? "" : value.toString();
    }

    private static String joinText(List<CarText> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (CarText value : values) {
            if (out.length() > 0) out.append(" | ");
            out.append(text(value));
        }
        return out.toString();
    }

    private boolean safeEnabled(Action action) {
        try {
            return action.isEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean safeEnabled(Row row) {
        try {
            return row.isEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String iconMarker(CarIcon icon) {
        return icon == null ? "" : "type:" + icon.getType();
    }

    private Drawable loadIcon(CarIcon icon, int sizeDp) {
        if (icon == null) return null;
        try {
            IconCompat compat = icon.getIcon();
            Drawable drawable = compat == null ? null : compat.loadDrawable(this);
            if (drawable != null) drawable.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
            return drawable;
        } catch (Throwable t) {
            SurfaceLog.error("template_icon_render_failed", t);
            return null;
        }
    }

    private static final class UiDoneCallback implements OnDoneCallback {
        private final String name;

        UiDoneCallback(String name) {
            this.name = name;
        }

        @Override
        public void onSuccess(Bundleable response) {
            SurfaceLog.event("template_delegate_result", "name", name, "success", true);
        }

        @Override
        public void onFailure(Bundleable response) {
            String value;
            try {
                Object decoded = response == null ? null : response.get();
                value = decoded instanceof FailureResponse
                        ? ((FailureResponse) decoded).getStackTrace() : String.valueOf(decoded);
            } catch (Throwable t) {
                value = t.toString();
            }
            SurfaceLog.event("template_delegate_result", "name", name,
                    "success", false, "failure", value);
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
