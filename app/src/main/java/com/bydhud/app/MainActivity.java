package com.bydhud.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends ComponentActivity {
    private static final String TAG = "BydHudTest";
    private static final long SEND_INTERVAL_MS = 1000L;
    private static final int LOOP_LOG_EVERY_SENDS = 30;
    private static final int START_BIND_RETRY_LIMIT = 30;
    private static final long START_BIND_RETRY_MS = 200L;
    private static final int CLEAR_FRAME_COUNT = 5;
    private static final long CLEAR_FRAME_INTERVAL_MS = 120L;
    private static final long CLEAR_STOP_DELAY_MS = 300L;
    private static final int STATUS_LOG_MAX_LINES = 35;
    private static final int NAV_RUNTIME_RECONNECT_RETRY_LIMIT = 2;
    private static final long NAV_RUNTIME_RECHECK_DELAY_MS = 1500L;
    private static final long NAV_PERMISSION_SELF_CHECK_DELAY_MS = 600L;
    private static final long APP_SCAN_REFRESH_INTERVAL_MS = 5000L;
    private static final boolean BACKGROUND_MODE = true;
    private static final String PNG_HIDE_CANDIDATE_LINE = "PNG hide: OEM S72 blank";
    private static final String CLOSE_ACTION_LINE = "CloseAction: Back/Home keeps sender";
    private static final String BACKGROUND_MODE_LINE = "BackgroundMode: Boot controls runtime service";
    private static final String KILL_ON_STOP_DISABLED_LINE = "KillOnStop: disabled";
    private static final String BG_SETTINGS_LINE =
            "BG settings: Settings-DiLink-Apps-Disable background apps";
    private static final String THEME_WARNING_TAG = "theme_warning";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HudState state = new HudState();
    private final ArrayDeque<String> statusLines = new ArrayDeque<>();
    private final AtomicBoolean appScanInProgress = new AtomicBoolean(false);
    private SomeIpHudClient hudClient;

    private TextView statusView;
    private TextView currentStateView;
    private TextView logcatRecorderStatusView;
    private TextView logPathsView;
    private LinearLayout rootView;
    private ScrollView mainTabView;
    private ScrollView appsTabView;
    private ScrollView logTabView;
    private ScrollView manualTabView;
    private LinearLayout appsCuratedListRoot;
    private LinearLayout appsRawListRoot;
    private TextView capturePackagesView;
    private TextView adbBridgeStatusView;
    private EditText distanceEdit;
    private EditText laneCountEdit;
    private EditText roadNameEdit;
    private EditText laneEdit;
    private EditText guidePointEdit;
    private EditText rawPngIdEdit;
    private EditText rawNativeIdEdit;
    private Button connectButton;
    private Button startButton;
    private Button stopButton;
    private Button disconnectButton;
    private Switch bootSwitch;
    private Switch smallDistanceClampSwitch;
    private Switch roundaboutLeftHandSwitch;
    private Switch pngOutputSwitch;
    private Switch nativeOutputSwitch;
    private Switch laneOutputSwitch;
    private Switch distanceOutputSwitch;
    private Switch streetOutputSwitch;
    private Switch manualModeSwitch;
    private int selectedTabIndex = 0;
    private CompoundButton.OnCheckedChangeListener bootSwitchListener;
    private CompoundButton.OnCheckedChangeListener smallDistanceClampSwitchListener;
    private CompoundButton.OnCheckedChangeListener roundaboutLeftHandSwitchListener;
    private CompoundButton.OnCheckedChangeListener pngOutputSwitchListener;
    private CompoundButton.OnCheckedChangeListener nativeOutputSwitchListener;
    private CompoundButton.OnCheckedChangeListener laneOutputSwitchListener;
    private CompoundButton.OnCheckedChangeListener distanceOutputSwitchListener;
    private CompoundButton.OnCheckedChangeListener streetOutputSwitchListener;
    private CompoundButton.OnCheckedChangeListener manualModeSwitchListener;
    private Button mainTabButton;
    private Button appsTabButton;
    private Button logTabButton;
    private Button manualTabButton;
    private Button languageUaButton;
    private Button languageEngButton;
    private Button themeDarkButton;
    private Button themeLightButton;
    private Button appsRefreshButton;
    private Button adbBridgeGrantButton;
    private Button bgSettingsButton;
    private Button logcatStartButton;
    private Button logcatStopButton;
    private Button arrowsCuratedButton;
    private Button arrowsRawButton;
    private Button curatedPrevButton;
    private Button curatedNextButton;
    private Button rawPngPrevButton;
    private Button rawPngNextButton;
    private Button rawNativePrevButton;
    private Button rawNativeNextButton;
    private Button rawApplyButton;
    private Button pngShowButton;
    private Button pngHideButton;
    private Button nativeShowButton;
    private Button nativeHideButton;
    private Button laneShowButton;
    private Button laneHideButton;
    private Button laneApplyButton;
    private Button laneRandomizeButton;
    private Button distanceApplyButton;
    private boolean sending;
    private boolean destroyed;
    private boolean manualModeEnabled;
    private boolean adbGrantInProgress;
    private boolean autoAdbGrantAttemptedThisLaunch;
    private int navRuntimeReconnectAttemptsThisLaunch;
    private boolean navPermissionSelfCheckPending;
    private boolean clearSequenceActive;
    private boolean exitRequested;
    private boolean arrowCuratedMode = true;
    private boolean pngVisible = true;
    private boolean nativeVisible = true;
    private int lastVisiblePngSourceId = 9;
    private int lastVisibleNativeId = 11;
    private boolean startAfterBindPending;
    private int startBindAttempts;
    private int sendCount;
    private int curatedIndex = HudArrowComboCatalog.defaultIndex();
    private String cachedPayloadKey = "";
    private byte[] cachedPayload = new byte[0];
    private int cachedLaneBytes;
    private int cachedTurnBytes;
    private String cachedTurnFieldStatus = "";
    private String cachedTurnFieldMagic = "";
    private String cachedTurnFieldDescriptor = "";
    private String cachedTurnResource = "";
    private int cachedDisplayDistance;
    private Runnable pendingClearFrameRunnable;
    private Runnable pendingClearStopRunnable;
    private Runnable pendingStartAfterBindRunnable;
    private Runnable pendingNavPermissionSelfCheckRunnable;
    private final Random random = new Random();

    private final Runnable sendLoop = new Runnable() {
        @Override
        public void run() {
            if (!sending) {
                return;
            }
            sendCurrentState("loop");
            handler.postDelayed(this, SEND_INTERVAL_MS);
        }
    };

    private final Runnable startAfterBindRunnable = new Runnable() {
        @Override
        public void run() {
            pendingStartAfterBindRunnable = null;
            if (!startAfterBindPending) {
                return;
            }
            if (hudClient.isBound()) {
                startAfterBindPending = false;
                appendStatus("start pending: SomeIP connected");
                startSending();
                return;
            }
            if (startBindAttempts >= START_BIND_RETRY_LIMIT) {
                startAfterBindPending = false;
                appendStatus("start pending failed: SomeIP bind timeout");
                return;
            }
            startBindAttempts++;
            hudClient.bind();
            scheduleStartAfterBind();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        HudGraphicPayload.setContext(this);
        if (HudPrefs.isBootEnabled(this)) {
            HudRuntimeSupervisor.ensureStarted(this, "activity-create");
        } else {
            HudRuntimeWatchdog.cancel(this);
        }
        hudClient = new SomeIpHudClient(this, line -> {
            appendStatus(line);
            refreshControls();
        });
        NavAppDisplayController.get(this).setListener(() -> runOnUiThread(() -> {
            refreshControls();
            refreshActiveAppsList();
        }));
        BydHudRuntimeCompose.install(this);
        appendStatus(buildSafetyBanner());
        appendStatus("idle: Boot controls runtime; Apps tab controls Start HUD / Start only log");
        refreshStateView();
        refreshControls();
        refreshLogcatControls();
        if (showBackgroundReminderIfNeeded()) {
            navPermissionSelfCheckPending = true;
        } else {
            scheduleNavPermissionSelfCheck(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (HudPrefs.isBootEnabled(this)) {
            HudRuntimeSupervisor.ensureStarted(this, "activity-start");
        }
        maybeRunPendingNavPermissionSelfCheck();
        refreshControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeRunPendingNavPermissionSelfCheck();
        refreshControls();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (exitRequested || isFinishing()) {
            appendStatus("onStop after explicit exit");
            return;
        }
        appendStatus("onStop background keepalive sending=" + sending);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (pendingNavPermissionSelfCheckRunnable != null) {
            handler.removeCallbacks(pendingNavPermissionSelfCheckRunnable);
            pendingNavPermissionSelfCheckRunnable = null;
        }
        NavAppDisplayController.get(this).setListener(null);
        if (isFinishing()) {
            stopImmediately("destroy", false, true);
        } else {
            appendStatus("destroy without finish: keep sender state");
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        appendStatus("back pressed: moving task to background");
        moveTaskToBack(true);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        root.setBackgroundColor(uiBackgroundColor());
        rootView = root;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(buildSafetyBanner(), 18, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        languageUaButton = button("Укр", v -> setUiLanguage(true));
        languageEngButton = button("Eng", v -> setUiLanguage(false));
        themeDarkButton = button("Темна", v -> setUiTheme(true));
        themeLightButton = button("Light", v -> setUiTheme(false));
        header.addView(languageUaButton);
        header.addView(languageEngButton);
        header.addView(themeDarkButton);
        header.addView(themeLightButton);
        root.addView(header);

        LinearLayout contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        mainTabView = new ScrollView(this);
        LinearLayout mainRoot = tabContentRoot();
        mainTabView.addView(mainRoot);
        contentHost.addView(mainTabView, tabLayoutParams());

        appsTabView = new ScrollView(this);
        LinearLayout appsRoot = tabContentRoot();
        appsTabView.addView(appsRoot);
        contentHost.addView(appsTabView, tabLayoutParams());

        logTabView = new ScrollView(this);
        LinearLayout logRoot = tabContentRoot();
        logTabView.addView(logRoot);
        contentHost.addView(logTabView, tabLayoutParams());

        manualTabView = new ScrollView(this);
        LinearLayout manualRoot = tabContentRoot();
        manualTabView.addView(manualRoot);
        contentHost.addView(manualTabView, tabLayoutParams());

        mainTabButton = button("Main", v -> selectTab(0));
        appsTabButton = button("Apps", v -> selectTab(1));
        logTabButton = button("Logs", v -> selectTab(2));
        manualTabButton = button("Manual", v -> selectTab(3));
        root.addView(row(mainTabButton, appsTabButton, logTabButton, manualTabButton));

        currentStateView = label("", 16, false);
        mainRoot.addView(currentStateView);

        mainRoot.addView(section("Permissions and Runtime"));
        adbBridgeStatusView = label("nav permission self-check pending", 14, false);
        mainRoot.addView(adbBridgeStatusView);
        adbBridgeGrantButton = button("Grant ADB",
                v -> requestAdbPermissionGrant(
                        "ui-grant",
                        LocalAdbBridge.AuthorizationPromptMode.FORCE));
        bgSettingsButton = button("Open \"Disable background apps\" menu",
                v -> BgSettingsLauncher.open(this));
        mainRoot.addView(row(adbBridgeGrantButton, bgSettingsButton));

        mainRoot.addView(section("Navigation Output"));
        pngOutputSwitchListener = (buttonView, checked) ->
                setPngOutputEnabled(checked);
        nativeOutputSwitchListener = (buttonView, checked) ->
                setNativeOutputEnabled(checked);
        laneOutputSwitchListener = (buttonView, checked) ->
                setLaneOutputEnabled(checked);
        distanceOutputSwitchListener = (buttonView, checked) ->
                setDistanceOutputEnabled(checked);
        streetOutputSwitchListener = (buttonView, checked) ->
                setStreetOutputEnabled(checked);
        pngOutputSwitch = switchRow(mainRoot, "PNG output",
                HudPrefs.isPngOutputEnabled(this), pngOutputSwitchListener);
        nativeOutputSwitch = switchRow(mainRoot, "Native output",
                HudPrefs.isNativeOutputEnabled(this), nativeOutputSwitchListener);
        laneOutputSwitch = switchRow(mainRoot, "Lane output",
                HudPrefs.isLaneOutputEnabled(this), laneOutputSwitchListener);
        distanceOutputSwitch = switchRow(mainRoot, "Distance output",
                HudPrefs.isDistanceOutputEnabled(this), distanceOutputSwitchListener);
        streetOutputSwitch = switchRow(mainRoot, "Street output",
                HudPrefs.isStreetOutputEnabled(this), streetOutputSwitchListener);

        mainRoot.addView(section("Navigation Preferences"));
        bootSwitchListener = (buttonView, checked) -> setBootMode(checked);
        bootSwitch = switchRow(mainRoot, "Boot runtime service", HudPrefs.isBootEnabled(this),
                bootSwitchListener);
        smallDistanceClampSwitchListener = (buttonView, checked) ->
                setSmallDistanceClamp(checked);
        smallDistanceClampSwitch = switchRow(mainRoot, "Small distance clamp",
                HudPrefs.isSmallDistanceClampEnabled(this), smallDistanceClampSwitchListener);
        roundaboutLeftHandSwitchListener = (buttonView, checked) ->
                setRoundaboutLeftHandTraffic(checked);
        roundaboutLeftHandSwitch = switchRow(mainRoot, "Roundabout left-hand traffic",
                HudPrefs.isRoundaboutLeftHandTraffic(this), roundaboutLeftHandSwitchListener);
        mainRoot.addView(label("Roundabout left-hand traffic: Changes roundabout assets for PNG output",
                13, false));

        logRoot.addView(section("Logcat"));
        logcatRecorderStatusView = label(LogcatRecorder.STATUS_WAITING, 14, false);
        logRoot.addView(logcatRecorderStatusView);
        logcatStartButton = button("Start Logcat", v -> startLogcatRecording());
        logcatStopButton = button("Stop Logcat", v -> stopLogcatRecording());
        logRoot.addView(row(logcatStartButton, logcatStopButton));

        logRoot.addView(section("Navigation Logs"));
        logPathsView = label(logPathsText(), 13, false);
        logRoot.addView(logPathsView);

        logRoot.addView(section("Status Log"));
        statusView = label("", 13, false);
        logRoot.addView(statusView);

        manualRoot.addView(section("Manual HUD Output"));
        manualModeSwitchListener = (buttonView, checked) -> setManualMode(checked);
        manualModeSwitch = switchRow(manualRoot, "Manual mode", manualModeEnabled,
                manualModeSwitchListener);

        manualRoot.addView(section("Connection"));
        connectButton = button("Connect", v -> connectSomeIp());
        startButton = button("Start", v -> startSending());
        stopButton = button("Stop", v -> stopSending(true));
        disconnectButton = button("Disconnect", v -> disconnectHud());
        manualRoot.addView(row(
                connectButton,
                startButton,
                stopButton,
                disconnectButton));
        manualRoot.addView(row(
                button("Background", v -> moveTaskToBack(true)),
                button("Stop Exit", v -> exitAndFinish())));

        manualRoot.addView(section("Distance"));
        distanceEdit = numberEditText(state.distanceToIntersection, "Distance m");
        distanceApplyButton = button("Apply Distance", v -> applyDistanceAndSend());
        manualRoot.addView(row(distanceEdit, distanceApplyButton));
        roadNameEdit = editText(state.roadName);
        manualRoot.addView(roadNameEdit);

        manualRoot.addView(section("Lanes"));
        laneCountEdit = numberEditText(state.numOfLanes, "Count");
        laneEdit = editText(state.laneString);
        laneShowButton = button("Lanes Show", v -> setLaneBitmap(true));
        laneHideButton = button("Lanes Hide", v -> setLaneBitmap(false));
        manualRoot.addView(row(laneShowButton, laneHideButton));
        manualRoot.addView(laneEdit);
        laneApplyButton = button("Apply Lanes", v -> applyLaneCountAndSend());
        laneRandomizeButton = button("Next Lanes", v -> randomizeLanesAndSend());
        manualRoot.addView(row(laneCountEdit, laneApplyButton, laneRandomizeButton));

        manualRoot.addView(section("Arrow Visibility"));
        pngShowButton = button("PNG Show", v -> setPngVisible(true));
        pngHideButton = button("PNG Hide", v -> setPngVisible(false));
        nativeShowButton = button("Native Show", v -> setNativeVisible(true));
        nativeHideButton = button("Native Hide", v -> setNativeVisible(false));
        manualRoot.addView(row(pngShowButton, pngHideButton));
        manualRoot.addView(row(nativeShowButton, nativeHideButton));

        manualRoot.addView(section("Supported Arrows"));
        arrowsCuratedButton = button("Supported", v -> switchArrowMode(true));
        arrowsRawButton = button("Raw", v -> switchArrowMode(false));
        manualRoot.addView(row(arrowsCuratedButton, arrowsRawButton));
        curatedPrevButton = button("Prev", v -> stepCurated(-1));
        curatedNextButton = button("Next", v -> stepCurated(1));
        manualRoot.addView(row(curatedPrevButton, curatedNextButton));

        manualRoot.addView(section("Raw Arrows"));
        rawPngIdEdit = numberEditText(state.turnBitmapId, "PNG/S ID");
        rawNativeIdEdit = numberEditText(state.maneuverId, "Native/N ID");
        manualRoot.addView(row(rawPngIdEdit, rawNativeIdEdit));
        rawPngPrevButton = button("PNG Prev", v -> stepRawPng(-1));
        rawPngNextButton = button("PNG Next", v -> stepRawPng(1));
        manualRoot.addView(row(rawPngPrevButton, rawPngNextButton));
        rawNativePrevButton = button("Native Prev", v -> stepRawNative(-1));
        rawNativeNextButton = button("Native Next", v -> stepRawNative(1));
        manualRoot.addView(row(rawNativePrevButton, rawNativeNextButton));
        rawApplyButton = button("Apply Raw", v -> applyRawArrowsAndSend());
        manualRoot.addView(row(rawApplyButton));

        manualRoot.addView(section("Quick Hide"));
        manualRoot.addView(row(
                button("Hide PNG + Native", v -> setBothArrowsHidden())));

        manualRoot.addView(section("Guide Point"));
        guidePointEdit = editText(state.guidePoint);
        guidePointEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        manualRoot.addView(guidePointEdit);
        manualRoot.addView(row(button("Apply Guide", v -> applyNavigationFieldsAndSend())));

        appsRoot.addView(section("Supported apps"));
        appsRoot.addView(warningLabel("SUPPORTED APPS: Google Maps, Waze, ABRP"));
        appsRoot.addView(warningLabel("Unsupported apps are logging only"));
        capturePackagesView = label("", 14, false);
        appsRoot.addView(capturePackagesView);
        appsRefreshButton = button("Оновити застосунки", v -> composeRefreshApps());
        appsRoot.addView(row(appsRefreshButton));
        appsCuratedListRoot = tabContentRoot();
        appsRoot.addView(appsCuratedListRoot);
        appsRoot.addView(section("All apps"));
        appsRawListRoot = tabContentRoot();
        appsRoot.addView(appsRawListRoot);

        refreshActiveAppsList();
        refreshUiModeButtons();
        selectTab(0);
        applyUiLanguage();
        applyUiTheme();
        return root;
    }

    private LinearLayout tabContentRoot() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(8, 8, 8, 8);
        return content;
    }

    private LinearLayout.LayoutParams tabLayoutParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
    }

    private void selectTab(int tabIndex) {
        selectedTabIndex = tabIndex;
        boolean showMain = tabIndex == 0;
        boolean showApps = tabIndex == 1;
        boolean showLog = tabIndex == 2;
        boolean showManual = tabIndex == 3;
        if (mainTabView != null) {
            mainTabView.setVisibility(showMain ? View.VISIBLE : View.GONE);
        }
        if (appsTabView != null) {
            appsTabView.setVisibility(showApps ? View.VISIBLE : View.GONE);
        }
        if (logTabView != null) {
            logTabView.setVisibility(showLog ? View.VISIBLE : View.GONE);
        }
        if (manualTabView != null) {
            manualTabView.setVisibility(showManual ? View.VISIBLE : View.GONE);
        }
        refreshTabButtons();
        if (showApps) {
            refreshActiveAppsList();
        }
    }

    private void startLogcatRecording() {
        setLogcatRecorderStatus(LogcatRecorder.STATUS_RECORDING);
        LogcatRecorder.Result result = LogcatRecorder.start(this);
        appendStatus("logcat start " + result.detail
                + " file=" + filePath(result.file));
        refreshLogcatControls();
    }

    private void stopLogcatRecording() {
        setLogcatRecorderStatus(LogcatRecorder.STATUS_SAVING);
        if (logcatStartButton != null) {
            logcatStartButton.setEnabled(false);
        }
        if (logcatStopButton != null) {
            logcatStopButton.setEnabled(false);
        }
        handler.post(() -> {
            LogcatRecorder.Result result = LogcatRecorder.stop(this);
            appendStatus("logcat stop " + result.detail
                    + " file=" + filePath(result.file));
            refreshLogcatControls();
        });
    }

    private void setLogcatRecorderStatus(String status) {
        if (logcatRecorderStatusView != null) {
            logcatRecorderStatusView.setText(status);
        }
    }

    private void refreshLogcatControls() {
        boolean recording = LogcatRecorder.isRecording();
        if (logcatStartButton != null) {
            logcatStartButton.setEnabled(!recording);
        }
        if (logcatStopButton != null) {
            logcatStopButton.setEnabled(recording);
        }
        if (logcatRecorderStatusView != null) {
            logcatRecorderStatusView.setText(LogcatRecorder.statusText());
        }
        if (logPathsView != null) {
            logPathsView.setText(logPathsText());
        }
    }

    private String logPathsText() {
        return "Logcat:\n"
                + NavigationLogStorage.publicLogcatPath()
                + "\nFiles: logcat_*.txt, events.log"
                + "\n\nNav capture:\n"
                + NavigationLogStorage.publicNavCapturePath() + "/<yyyymmdd>"
                + "\nFiles: raw_nav_events.jsonl, nav_snapshots.jsonl"
                + "\n\nWaze crop:\n"
                + NavigationLogStorage.publicWazeCropPath()
                + "\n\nPath to navigation logs on tablet."
                + "\nProbe channels: notification_large_icon, unsupported_start_hud, waze_crop, nav_app_display";
    }

    private List<ComposeStorageDay> composeStorageDays() {
        List<ComposeStorageDay> days = new ArrayList<>();
        File root = NavigationLogStorage.navCaptureDir(this);
        File[] files = root.listFiles();
        if (files == null) {
            return days;
        }
        SimpleDateFormat labelFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (File file : files) {
            if (file == null || !file.isDirectory() || !file.getName().matches("\\d{8}")) {
                continue;
            }
            days.add(new ComposeStorageDay(
                    file.getName(),
                    labelFormat.format(new Date(file.lastModified())),
                    countSessionDirs(file),
                    folderSizeBytes(file),
                    NavigationLogStorage.isActiveNavCaptureDay(file.getName())));
        }
        Collections.sort(days, (a, b) -> b.name.compareTo(a.name));
        return days;
    }

    private long navCaptureFolderSizeBytes() {
        return folderSizeBytes(NavigationLogStorage.navCaptureDir(this));
    }

    private int countSessionDirs(File dayDir) {
        int count = 0;
        File[] children = dayDir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            if (child != null && child.isDirectory()) {
                File[] sessions = child.listFiles();
                if (sessions != null) {
                    for (File session : sessions) {
                        if (session != null && session.isDirectory()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private long folderSizeBytes(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            total += folderSizeBytes(child);
        }
        return total;
    }

    public ComposeSnapshot composeSnapshot() {
        NavRuntimePermissionStatus permissionStatus = NavRuntimePermissionStatus.check(this);
        NavAppDisplayController displayController = NavAppDisplayController.get(this);
        Set<String> capturePackages = NavCapturePrefs.getCapturePackages(this);
        Set<String> observedPackages = NavCapturePrefs.getObservedPackages(this);
        String hudPackage = NavCapturePrefs.getHudPackage(this);
        Set<String> logOnlyPackages = NavCapturePrefs.getLogOnlyPackages(this);
        NavAppTaskScanner.Snapshot appScan = NavAppTaskScanner.get(this).currentSnapshot();
        List<ActiveAppRow> rawApps = activeRowsFromScan(appScan.rows);
        List<ActiveAppRow> supportedApps = loadCuratedApps(rawApps, capturePackages, observedPackages);
        List<ComposeAppRow> supportedRows = composeRows(
                supportedApps, hudPackage, logOnlyPackages, observedPackages, true);
        List<ComposeAppRow> allRows = new ArrayList<>();
        for (ActiveAppRow app : rawApps) {
            if (!NavAppFilter.isCuratedNavigationPackage(app.packageName)) {
                allRows.add(composeRow(app, hudPackage, logOnlyPackages, observedPackages, false));
            }
        }
        return new ComposeSnapshot(
                HudPrefs.isUaLanguage(this),
                HudPrefs.isDarkTheme(this),
                HudPrefs.isBootEnabled(this),
                HudPrefs.isPngOutputEnabled(this),
                HudPrefs.isNativeOutputEnabled(this),
                HudPrefs.isLaneOutputEnabled(this),
                HudPrefs.isDistanceOutputEnabled(this),
                HudPrefs.isStreetOutputEnabled(this),
                HudPrefs.isSmallDistanceClampEnabled(this),
                HudPrefs.isRoundaboutLeftHandTraffic(this),
                permissionStatus.settingsGranted(),
                permissionStatus.readyForCapture(),
                permissionStatus.summary(),
                LocalAdbBridge.adbKeyFingerprint(this),
                hudStatus(permissionStatus),
                hudPackage,
                formatCapturePackages(logOnlyPackages),
                formatCapturePackages(observedPackages),
                displayController.activeDashboardPackage(),
                displayController.isMoveInProgress(),
                LogcatRecorder.isRecording(),
                LogcatRecorder.statusText(),
                logPathsText(),
                composeApplicationState(permissionStatus),
                manualModeEnabled,
                arrowCuratedMode,
                curatedIndex,
                HudArrowComboCatalog.size(),
                state.turnBitmapId,
                state.maneuverId,
                state.distanceToIntersection,
                state.roadName == null ? "" : state.roadName,
                state.laneString == null ? "" : state.laneString,
                appScan.lastScanText,
                HudPrefs.storageLimitGb(this),
                navCaptureFolderSizeBytes(),
                composeStorageDays(),
                supportedRows,
                allRows);
    }

    private List<ActiveAppRow> activeRowsFromScan(List<NavAppTaskScanner.Row> scanRows) {
        List<ActiveAppRow> rows = new ArrayList<>();
        if (scanRows == null) {
            return rows;
        }
        for (NavAppTaskScanner.Row row : scanRows) {
            if (row == null || row.packageName == null || row.packageName.isEmpty()) {
                continue;
            }
            rows.add(new ActiveAppRow(
                    appLabel(row.packageName),
                    row.packageName,
                    row.processName,
                    row.importance,
                    row.hasProcess,
                    row.hasTask,
                    row.taskId,
                    row.displayId,
                    row.visible));
        }
        return rows;
    }

    private List<ComposeAppRow> composeRows(List<ActiveAppRow> apps, String hudPackage,
            Set<String> logOnlyPackages, Set<String> observedPackages, boolean supportedSection) {
        List<ComposeAppRow> rows = new ArrayList<>();
        for (ActiveAppRow app : apps) {
            rows.add(composeRow(app, hudPackage, logOnlyPackages, observedPackages, supportedSection));
        }
        return rows;
    }

    private ComposeAppRow composeRow(ActiveAppRow app, String hudPackage,
            Set<String> logOnlyPackages, Set<String> observedPackages, boolean supportedSection) {
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        NavAppDisplayState displayState = controller.lastState(app.packageName);
        String activeDashboardPackage = controller.activeDashboardPackage();
        boolean runtimeBacked = app.isRuntimeBacked();
        boolean observed = observedPackages.contains(app.packageName);
        boolean onDashboard = app.packageName.equals(activeDashboardPackage)
                || (runtimeBacked && app.displayId > 0)
                || (runtimeBacked && displayState.taskId >= 0 && displayState.isOnDashboardDisplay());
        boolean supportedHud = isSupportedHudPackage(app.packageName);
        boolean installed = isPackageInstalled(app.packageName);
        return new ComposeAppRow(
                app.label,
                app.packageName,
                displayPackageLine(app.packageName),
                installed,
                runtimeBacked,
                observed,
                supportedHud,
                supportedSection,
                app.packageName.equals(hudPackage),
                logOnlyPackages.contains(app.packageName),
                onDashboard,
                controller.isMoveInProgress(),
                app.processName,
                app.importance);
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private String hudStatus(NavRuntimePermissionStatus permissionStatus) {
        if (!permissionStatus.readyForCapture()) {
            return "failed";
        }
        if (NavHudLiveSender.get(this).isRunning() || sending) {
            return "running";
        }
        return "idle";
    }

    private String composeApplicationState(NavRuntimePermissionStatus permissionStatus) {
        String hudPackage = NavCapturePrefs.getHudPackage(this);
        return "HUD package: " + (hudPackage.isEmpty() ? "(none)" : hudPackage)
                + "\nLog-only: " + formatCapturePackages(NavCapturePrefs.getLogOnlyPackages(this))
                + "\nObserved: " + formatCapturePackages(NavCapturePrefs.getObservedPackages(this))
                + "\nWaze crop: " + (WazeCropCapture.get(this).isRunning() ? "active session" : "idle")
                + "\nRuntime service: "
                + HudRuntimeState.summary(this, SystemClock.elapsedRealtime())
                + "\nPermissions: " + permissionStatus.summary();
    }

    public void composeGrantAdb() {
        requestAdbPermissionGrant(
                "manual-grant-button",
                LocalAdbBridge.AuthorizationPromptMode.FORCE);
    }

    public void composeOpenBackgroundSettings() {
        composeOpenBackgroundSettingsFromReminder();
    }

    public boolean composeShouldShowBackgroundReminder() {
        return HudPrefs.shouldShowBackgroundReminder(this);
    }

    public void composeDismissBackgroundReminder() {
        HudPrefs.markBackgroundReminderSeen(this);
        navPermissionSelfCheckPending = false;
        scheduleNavPermissionSelfCheck(true);
        refreshControls();
    }

    public void composeOpenBackgroundSettingsFromReminder() {
        HudPrefs.markBackgroundReminderSeen(this);
        navPermissionSelfCheckPending = true;
        BgSettingsLauncher.open(this);
        refreshControls();
    }

    public void composeSetBootEnabled(boolean enabled) {
        setBootMode(enabled);
    }

    public void composeSetPngOutputEnabled(boolean enabled) {
        setPngOutputEnabled(enabled);
    }

    public void composeSetNativeOutputEnabled(boolean enabled) {
        setNativeOutputEnabled(enabled);
    }

    public void composeSetLaneOutputEnabled(boolean enabled) {
        setLaneOutputEnabled(enabled);
    }

    public void composeSetDistanceOutputEnabled(boolean enabled) {
        setDistanceOutputEnabled(enabled);
    }

    public void composeSetStreetOutputEnabled(boolean enabled) {
        setStreetOutputEnabled(enabled);
    }

    public void composeSetSmallDistanceClamp(boolean enabled) {
        setSmallDistanceClamp(enabled);
    }

    public void composeSetRoundaboutLeftHandTraffic(boolean enabled) {
        setRoundaboutLeftHandTraffic(enabled);
    }

    public void composeSetUaLanguage(boolean ua) {
        setUiLanguage(ua);
    }

    public void composeSetDarkTheme(boolean dark) {
        setUiTheme(dark);
    }

    public void composeSetStorageLimitGb(int value) {
        HudPrefs.setStorageLimitGb(this, value);
        appendStatus("Storage limit " + HudPrefs.storageLimitGb(this) + " GB");
        refreshControls();
    }

    public void composeDeleteStorageDays(List<String> days) {
        if (days == null || days.isEmpty()) {
            return;
        }
        File root = NavigationLogStorage.navCaptureDir(this);
        int deleted = 0;
        int skippedActiveDays = 0;
        for (String day : days) {
            if (day == null || !day.matches("\\d{8}")) {
                continue;
            }
            if (NavigationLogStorage.isActiveNavCaptureDay(day)) {
                skippedActiveDays++;
                continue;
            }
            File dir = new File(root, day);
            if (isDirectChild(root, dir) && deleteRecursively(dir)) {
                deleted++;
            }
        }
        appendStatus("Storage deleted day folders=" + deleted
                + (skippedActiveDays > 0 ? " skipped active=" + skippedActiveDays : ""));
        refreshControls();
    }

    private boolean isDirectChild(File parent, File child) {
        try {
            return parent.getCanonicalFile().equals(child.getParentFile().getCanonicalFile());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    public void composeMaybeRefreshApps() {
        scheduleAppScan(false);
    }

    public void composeRefreshApps() {
        scheduleAppScan(true);
    }

    private void scheduleAppScan(boolean force) {
        NavAppTaskScanner.Snapshot current = NavAppTaskScanner.get(this).currentSnapshot();
        long ageMs = current.scannedAtMs <= 0
                ? Long.MAX_VALUE
                : Math.max(0L, System.currentTimeMillis() - current.scannedAtMs);
        boolean initialSnapshot = "initial".equals(current.status);
        if (!force && !initialSnapshot && ageMs < APP_SCAN_REFRESH_INTERVAL_MS) {
            return;
        }
        if (!appScanInProgress.compareAndSet(false, true)) {
            return;
        }
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                NavAppTaskScanner.Snapshot scan = NavAppTaskScanner.get(appContext).forceScan();
                AppEventLogger.event(appContext, "apps_refresh force=" + force
                        + " source=" + scan.source
                        + " rows=" + scan.rows.size()
                        + " status=" + scan.status);
            } finally {
                appScanInProgress.set(false);
                handler.post(this::refreshActiveAppsList);
            }
        }, "bydhud-app-scan").start();
    }

    public void composeSetHudForPackage(String packageName, boolean enabled) {
        setNavHudForPackage(packageName, enabled);
    }

    public void composeSetLogOnlyForPackage(String packageName, boolean enabled) {
        setNavLogOnlyForPackage(packageName, enabled);
    }

    public void composeToggleDashboard(String packageName, boolean runtimeBacked) {
        toggleIndependentDashboardDisplay(packageName, runtimeBacked);
    }

    public void composeStartLogcat() {
        startLogcatRecording();
    }

    public void composeStopLogcat() {
        stopLogcatRecording();
    }

    public void composeSetManualMode(boolean enabled) {
        setManualMode(enabled);
        if (enabled) {
            arrowCuratedMode = true;
            if (!hudClient.isBound()) {
                appendStatus("manual mode: connect/start requested");
                startAfterBindPending = true;
                startBindAttempts = 0;
                hudClient.bind();
                scheduleStartAfterBind();
            } else if (!sending) {
                startSending();
            }
        } else {
            stopSending(true);
        }
        refreshControls();
    }

    public void composeStepCurated(int delta) {
        arrowCuratedMode = true;
        curatedIndex = delta >= 0
                ? HudArrowComboCatalog.next(curatedIndex)
                : HudArrowComboCatalog.prev(curatedIndex);
        applyCuratedCombo(canUseManualHud());
    }

    public void composeSendRaw(int pngSourceId, int nativeId, int distanceMeters,
            String street, String lanes) {
        arrowCuratedMode = false;
        state.turnBitmapId = clamp(pngSourceId, 1, 99);
        state.maneuverId = clamp(nativeId, 1, 99);
        state.distanceToIntersection = clamp(distanceMeters, 0, 99999);
        state.roadName = street == null ? "" : street;
        state.laneString = lanes == null ? "" : lanes.trim();
        state.numOfLanes = countLaneTokens(state.laneString);
        state.includeLaneBitmap = state.numOfLanes > 1;
        pngVisible = state.turnBitmapId != HudState.TURN_BITMAP_BLANK_SOURCE_ID;
        nativeVisible = state.maneuverId != HudState.NATIVE_BLANK_ID;
        applyArrowVisibility();
        if (canUseManualHud()) {
            sendCurrentState("manual-raw-compose");
        } else {
            appendStatus("manual raw updated; enable Manual mode to send");
            refreshControls();
        }
    }

    public void composeSendManualLane(String lanes) {
        state.laneString = lanes == null ? "" : lanes.trim();
        state.numOfLanes = countLaneTokens(state.laneString);
        state.includeLaneBitmap = state.numOfLanes > 1;
        if (canUseManualHud()) {
            sendCurrentState("manual-lanes-compose");
        } else {
            appendStatus("manual lanes updated; enable Manual mode to send");
            refreshControls();
        }
    }

    private int countLaneTokens(String lanes) {
        if (lanes == null || lanes.trim().isEmpty()) {
            return 0;
        }
        String[] parts = lanes.split("\\|");
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class ComposeSnapshot {
        public final boolean uaLanguage;
        public final boolean darkTheme;
        public final boolean bootEnabled;
        public final boolean pngOutputEnabled;
        public final boolean nativeOutputEnabled;
        public final boolean laneOutputEnabled;
        public final boolean distanceOutputEnabled;
        public final boolean streetOutputEnabled;
        public final boolean smallDistanceClampEnabled;
        public final boolean roundaboutLeftHandTraffic;
        public final boolean settingsPermissionsGranted;
        public final boolean captureReady;
        public final String permissionSummary;
        public final String adbKeyFingerprint;
        public final String hudStatus;
        public final String hudPackage;
        public final String logOnlyPackages;
        public final String observedPackages;
        public final String activeDashboardPackage;
        public final boolean dashboardMoveInProgress;
        public final boolean logcatRecording;
        public final String logcatStatus;
        public final String logPaths;
        public final String applicationState;
        public final boolean manualModeEnabled;
        public final boolean arrowCuratedMode;
        public final int curatedIndex;
        public final int curatedCount;
        public final int pngSourceId;
        public final int nativeManeuverId;
        public final int distanceMeters;
        public final String streetText;
        public final String laneBitmap;
        public final String lastScanText;
        public final int storageLimitGb;
        public final long navCaptureFolderBytes;
        public final List<ComposeStorageDay> storageDays;
        public final List<ComposeAppRow> supportedApps;
        public final List<ComposeAppRow> allApps;

        ComposeSnapshot(boolean uaLanguage, boolean darkTheme, boolean bootEnabled,
                boolean pngOutputEnabled, boolean nativeOutputEnabled, boolean laneOutputEnabled,
                boolean distanceOutputEnabled, boolean streetOutputEnabled, boolean smallDistanceClampEnabled,
                boolean roundaboutLeftHandTraffic, boolean settingsPermissionsGranted,
                boolean captureReady, String permissionSummary, String adbKeyFingerprint,
                String hudStatus, String hudPackage, String logOnlyPackages,
                String observedPackages, String activeDashboardPackage,
                boolean dashboardMoveInProgress, boolean logcatRecording, String logcatStatus,
                String logPaths, String applicationState, boolean manualModeEnabled,
                boolean arrowCuratedMode, int curatedIndex, int curatedCount,
                int pngSourceId, int nativeManeuverId, int distanceMeters, String streetText,
                String laneBitmap, String lastScanText, int storageLimitGb,
                long navCaptureFolderBytes, List<ComposeStorageDay> storageDays,
                List<ComposeAppRow> supportedApps, List<ComposeAppRow> allApps) {
            this.uaLanguage = uaLanguage;
            this.darkTheme = darkTheme;
            this.bootEnabled = bootEnabled;
            this.pngOutputEnabled = pngOutputEnabled;
            this.nativeOutputEnabled = nativeOutputEnabled;
            this.laneOutputEnabled = laneOutputEnabled;
            this.distanceOutputEnabled = distanceOutputEnabled;
            this.streetOutputEnabled = streetOutputEnabled;
            this.smallDistanceClampEnabled = smallDistanceClampEnabled;
            this.roundaboutLeftHandTraffic = roundaboutLeftHandTraffic;
            this.settingsPermissionsGranted = settingsPermissionsGranted;
            this.captureReady = captureReady;
            this.permissionSummary = permissionSummary == null ? "" : permissionSummary;
            this.adbKeyFingerprint = adbKeyFingerprint == null ? "" : adbKeyFingerprint;
            this.hudStatus = hudStatus == null ? "idle" : hudStatus;
            this.hudPackage = hudPackage == null ? "" : hudPackage;
            this.logOnlyPackages = logOnlyPackages == null ? "" : logOnlyPackages;
            this.observedPackages = observedPackages == null ? "" : observedPackages;
            this.activeDashboardPackage = activeDashboardPackage == null ? "" : activeDashboardPackage;
            this.dashboardMoveInProgress = dashboardMoveInProgress;
            this.logcatRecording = logcatRecording;
            this.logcatStatus = logcatStatus == null ? "" : logcatStatus;
            this.logPaths = logPaths == null ? "" : logPaths;
            this.applicationState = applicationState == null ? "" : applicationState;
            this.manualModeEnabled = manualModeEnabled;
            this.arrowCuratedMode = arrowCuratedMode;
            this.curatedIndex = curatedIndex;
            this.curatedCount = curatedCount;
            this.pngSourceId = pngSourceId;
            this.nativeManeuverId = nativeManeuverId;
            this.distanceMeters = distanceMeters;
            this.streetText = streetText == null ? "" : streetText;
            this.laneBitmap = laneBitmap == null ? "" : laneBitmap;
            this.lastScanText = lastScanText == null ? "--:--:--" : lastScanText;
            this.storageLimitGb = Math.max(1, Math.min(10, storageLimitGb));
            this.navCaptureFolderBytes = Math.max(0L, navCaptureFolderBytes);
            this.storageDays = storageDays == null ? Collections.emptyList() : storageDays;
            this.supportedApps = supportedApps == null ? Collections.emptyList() : supportedApps;
            this.allApps = allApps == null ? Collections.emptyList() : allApps;
        }
    }

    public static final class ComposeStorageDay {
        public final String name;
        public final String createdLabel;
        public final int sessions;
        public final long bytes;
        public final boolean active;

        ComposeStorageDay(String name, String createdLabel, int sessions, long bytes, boolean active) {
            this.name = name == null ? "" : name;
            this.createdLabel = createdLabel == null ? "" : createdLabel;
            this.sessions = Math.max(0, sessions);
            this.bytes = Math.max(0L, bytes);
            this.active = active;
        }
    }

    public static final class ComposeAppRow {
        public final String label;
        public final String packageName;
        public final String packageLine;
        public final boolean installed;
        public final boolean runtimeBacked;
        public final boolean observed;
        public final boolean supportedHud;
        public final boolean supportedSection;
        public final boolean hudEnabled;
        public final boolean logOnlyEnabled;
        public final boolean onDashboard;
        public final boolean dashboardMoveInProgress;
        public final String processName;
        public final int importance;

        ComposeAppRow(String label, String packageName, String packageLine, boolean installed,
                boolean runtimeBacked, boolean observed, boolean supportedHud,
                boolean supportedSection, boolean hudEnabled, boolean logOnlyEnabled, boolean onDashboard,
                boolean dashboardMoveInProgress, String processName, int importance) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.packageLine = packageLine == null ? "" : packageLine;
            this.installed = installed;
            this.runtimeBacked = runtimeBacked;
            this.observed = observed;
            this.supportedHud = supportedHud;
            this.supportedSection = supportedSection;
            this.hudEnabled = hudEnabled;
            this.logOnlyEnabled = logOnlyEnabled;
            this.onDashboard = onDashboard;
            this.dashboardMoveInProgress = dashboardMoveInProgress;
            this.processName = processName == null ? "" : processName;
            this.importance = importance;
        }
    }

    private void refreshUiState() {
        refreshControls();
    }

    private void refreshActiveAppsList() {
        if (appsCuratedListRoot == null || appsRawListRoot == null) {
            return;
        }
        Set<String> capturePackages = NavCapturePrefs.getCapturePackages(this);
        String hudPackage = NavCapturePrefs.getHudPackage(this);
        Set<String> logOnlyPackages = NavCapturePrefs.getLogOnlyPackages(this);
        Set<String> observedPackages = NavCapturePrefs.getObservedPackages(this);
        if (capturePackagesView != null) {
            capturePackagesView.setText("Active HUD: "
                    + (hudPackage.isEmpty() ? "(none)" : hudPackage)
                    + "\nLog only: " + formatCapturePackages(logOnlyPackages)
                    + "\nObserved: " + formatCapturePackages(observedPackages));
        }
        appsCuratedListRoot.removeAllViews();
        appsRawListRoot.removeAllViews();
        List<ActiveAppRow> rawApps =
                activeRowsFromScan(NavAppTaskScanner.get(this).currentSnapshot().rows);
        List<ActiveAppRow> curatedApps = loadCuratedApps(rawApps, capturePackages, observedPackages);
        if (curatedApps.isEmpty()) {
            appsCuratedListRoot.addView(label("No supported navigation apps visible", 14, false));
        } else {
            for (ActiveAppRow app : curatedApps) {
                appsCuratedListRoot.addView(activeAppView(app,
                        app.packageName.equals(hudPackage),
                        logOnlyPackages.contains(app.packageName),
                        true));
            }
        }
        List<ActiveAppRow> allApps = new ArrayList<>();
        for (ActiveAppRow app : rawApps) {
            if (!NavAppFilter.isCuratedNavigationPackage(app.packageName)) {
                allApps.add(app);
            }
        }
        if (allApps.isEmpty()) {
            appsRawListRoot.addView(label("No current non-system background apps visible", 14, false));
            refreshDynamicAppsUi();
            return;
        }
        for (ActiveAppRow app : allApps) {
            appsRawListRoot.addView(activeAppView(app,
                    app.packageName.equals(hudPackage),
                    logOnlyPackages.contains(app.packageName),
                    false));
        }
        refreshDynamicAppsUi();
    }

    private void refreshDynamicAppsUi() {
        applyUiLanguage();
        applyUiTheme();
    }

    private String formatCapturePackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(packageName);
        }
        return builder.toString();
    }

    private List<ActiveAppRow> loadCuratedApps(
            List<ActiveAppRow> rawApps,
            Set<String> capturePackages,
            Set<String> observedPackages) {
        Map<String, ActiveAppRow> rawRows = new HashMap<>();
        for (ActiveAppRow row : rawApps) {
            rawRows.put(row.packageName, row);
        }
        Set<String> candidates = new HashSet<>(NavAppFilter.curatedNavigationPackages());
        candidates.addAll(capturePackages);
        candidates.addAll(observedPackages);
        List<ActiveAppRow> curated = new ArrayList<>();
        for (String packageName : candidates) {
            String normalized = normalizePackage(packageName);
            if (!NavAppFilter.isCuratedNavigationPackage(normalized)
                    || NavAppFilter.shouldHideFromCaptureList(this, normalized)) {
                continue;
            }
            ActiveAppRow existing = rawRows.get(normalized);
            if (existing != null) {
                curated.add(existing);
            } else {
                boolean observed = observedPackages.contains(normalized);
                curated.add(new ActiveAppRow(
                        appLabel(normalized),
                        normalized,
                        observed ? "observed" : "not running",
                        Integer.MAX_VALUE));
            }
        }
        Collections.sort(curated, new Comparator<ActiveAppRow>() {
            @Override
            public int compare(ActiveAppRow left, ActiveAppRow right) {
                return left.label.compareToIgnoreCase(right.label);
            }
        });
        return curated;
    }

    private View activeAppView(ActiveAppRow app, boolean hudEnabled, boolean logOnlyEnabled,
            boolean supportedHud) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 8, 0, 8);
        container.addView(label(app.label
                + "\n" + displayPackageLine(app.packageName)
                + "\nimportance=" + app.importance
                + " process=" + app.processName
                + "\nmode=" + navCaptureModeLabel(hudEnabled, logOnlyEnabled), 13, false));
        Button stopHud = button("Stop HUD", v -> stopNavCaptureForPackage(app.packageName));
        Button displayAction = displayActionForApp(app);
        if (displayAction != null) {
            container.addView(row(displayAction));
        }
        Switch startHud = null;
        if (supportedHud) {
            startHud = switchRow(container, "HUD", hudEnabled,
                    (buttonView, checked) -> setNavHudForPackage(app.packageName, checked));
        }
        Switch startOnlyLog = switchRow(container, "Log", logOnlyEnabled,
                (buttonView, checked) -> setNavLogOnlyForPackage(app.packageName, checked));
        if (startHud != null) {
            startHud.setEnabled(true);
        }
        stopHud.setEnabled(hudEnabled || logOnlyEnabled);
        container.addView(row(stopHud));
        return container;
    }

    private String displayPackageLine(String packageName) {
        String normalized = normalizePackage(packageName);
        if ("com.google.android.apps.maps".equals(normalized)
                || "app.revanced.android.apps.maps".equals(normalized)) {
            return "com.google.android.apps.maps / app.revanced.android.apps.maps";
        }
        return packageName;
    }

    private Button displayActionForApp(ActiveAppRow app) {
        String packageName = app == null ? "" : app.packageName;
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            return null;
        }
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        NavAppDisplayState state = controller.lastState(normalized);
        String activeDashboardPackage = controller.activeDashboardPackage();
        boolean cachedRuntimeDashboard = app.isRuntimeBacked()
                && state.taskId >= 0
                && state.isOnDashboardDisplay();
        boolean onDashboard = normalized.equals(activeDashboardPackage)
                || cachedRuntimeDashboard;
        String label = onDashboard ? "Send to main" : "Send to dashboard";
        Button button = button(label,
                v -> toggleIndependentDashboardDisplay(normalized, app.isRuntimeBacked()));
        button.setEnabled(!controller.isMoveInProgress());
        return button;
    }

    private void toggleIndependentDashboardDisplay(String packageName, boolean runtimeBacked) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("display move ignored: empty package");
            return;
        }
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        if (controller.isMoveInProgress()) {
            appendStatus("display move already running");
            return;
        }
        NavAppDisplayState state = controller.lastState(normalized);
        String activeDashboardPackage = controller.activeDashboardPackage();
        boolean currentlyProjected = normalized.equals(activeDashboardPackage)
                || (runtimeBacked && state.isOnDashboardDisplay());
        controller.moveIndependentDashboardApp(
                normalized,
                !currentlyProjected,
                "ui-independent-dashboard-toggle");
        appendStatus((currentlyProjected ? "returning " : "sending ")
                + normalized
                + (currentlyProjected ? " to main" : " to dashboard"));
        refreshAppsSoon();
    }

    private void refreshAppsSoon() {
        handler.postDelayed(this::refreshActiveAppsList, 500L);
    }

    private String navCaptureModeLabel(boolean hudEnabled, boolean logOnlyEnabled) {
        if (hudEnabled) {
            return "hud";
        }
        if (logOnlyEnabled) {
            return "log-only";
        }
        return "off";
    }

    private void setNavHudForPackage(String packageName, boolean enabled) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("nav capture ignored: empty package");
            return;
        }
        if (NavAppFilter.shouldHideFromCaptureList(this, normalized)) {
            appendStatus("nav capture ignored: system package " + normalized);
            return;
        }
        if (enabled && !ensureNavCaptureRuntimeReadyForStart("hud", normalized)) {
            return;
        }
        if (enabled && !isSupportedHudPackage(normalized)) {
            NavCapturePrefs.setLogOnlyEnabled(this, normalized, true);
            NavCapturePrefs.setHudEnabled(this, normalized, false);
            NavNotificationListenerService.requestActiveNotificationScan(
                    this, "unsupported-start-hud-" + normalized);
            appendStatus("unsupported app: logging only " + normalized);
            NavCaptureStore.rawEvent(this, "unsupported_start_hud", normalized,
                    "Start HUD requested; logging only");
            refreshActiveAppsList();
            refreshControls();
            return;
        }
        String previousHudPackage = NavCapturePrefs.getHudPackage(this);
        if (enabled && !previousHudPackage.isEmpty() && !previousHudPackage.equals(normalized)) {
            returnPreviousHudAppToMain(previousHudPackage, normalized);
            NavHudLiveSender.get(this).stop(previousHudPackage, "ui-switch", true);
            appendStatus("nav live HUD switched off: " + previousHudPackage);
        }
        if (enabled && hudClient.isBound()) {
            stopImmediately("nav-hud-ui-start", false, true);
        }
        NavCapturePrefs.setHudEnabled(this, normalized, enabled);
        appendStatus("nav HUD " + (enabled ? "start " : "stop ") + normalized);
        AppEventLogger.event(this, "nav_hud " + (enabled ? "start " : "stop ") + normalized);
        if (enabled) {
            NavHudLiveSender.get(this).start(normalized, "ui-start");
            NavNotificationListenerService.requestActiveNotificationScan(
                    this, "ui-start-hud-" + normalized);
            appendStatus("nav live HUD start: waiting for navigation payload");
        } else {
            NavHudLiveSender.get(this).stop(normalized, "ui-stop", true);
            if ("com.waze".equals(normalized)) {
                WazeCropCapture.get(this).stop("ui-stop-hud");
            }
            appendStatus("nav live HUD stop requested");
        }
        refreshActiveAppsList();
    }

    private void returnPreviousHudAppToMain(String previousPackage, String nextPackage) {
        String previous = normalizePackage(previousPackage);
        if (previous.isEmpty()) {
            return;
        }
        if ("com.waze".equals(previous)) {
            WazeCropCapture.get(this).stop("hud-switch");
        }
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        appendStatus("return previous HUD app to main: " + previous);
        controller.moveToMain(previous, "hud-switch-to-" + normalizePackage(nextPackage));
    }

    static boolean isSupportedHudPackage(String packageName) {
        return NavCapturePrefs.isSupportedHudPackage(packageName);
    }

    private void setNavLogOnlyForPackage(String packageName, boolean enabled) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("nav log-only ignored: empty package");
            return;
        }
        if (NavAppFilter.shouldHideFromCaptureList(this, normalized)) {
            appendStatus("nav log-only ignored: system package " + normalized);
            return;
        }
        if (enabled && !ensureNavCaptureRuntimeReadyForStart("log-only", normalized)) {
            return;
        }
        if (enabled && NavCapturePrefs.isHudEnabled(this, normalized)) {
            NavHudLiveSender.get(this).stop(normalized, "ui-log-only", true);
        }
        NavCapturePrefs.setLogOnlyEnabled(this, normalized, enabled);
        appendStatus("nav log-only " + (enabled ? "start " : "stop ") + normalized);
        AppEventLogger.event(this, "nav_log_only " + (enabled ? "start " : "stop ") + normalized);
        if (enabled) {
            NavNotificationListenerService.requestActiveNotificationScan(
                    this, "ui-start-log-only-" + normalized);
        }
        refreshActiveAppsList();
    }

    private void stopNavCaptureForPackage(String packageName) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("nav stop ignored: empty package");
            return;
        }
        NavHudLiveSender.get(this).stop(normalized, "ui-stop", true);
        if ("com.waze".equals(normalized)) {
            WazeCropCapture.get(this).stop("ui-stop");
        }
        if (NavCapturePrefs.isHudEnabled(this, normalized)) {
            NavCapturePrefs.setHudEnabled(this, normalized, false);
            appendStatus("nav live HUD stop requested");
            AppEventLogger.event(this, "nav_hud stop " + normalized);
        }
        if (NavCapturePrefs.isLogOnlyEnabled(this, normalized)) {
            NavCapturePrefs.setLogOnlyEnabled(this, normalized, false);
            appendStatus("nav log-only stop " + normalized);
            AppEventLogger.event(this, "nav_log_only stop " + normalized);
        }
        refreshActiveAppsList();
    }

    private String appLabel(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Package can disappear between process listing and label lookup.
        }
        return packageName;
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }

    private String filePath(java.io.File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    private void connectSomeIp() {
        if (hudClient.isBound()) {
            appendStatus("connect skipped: already connected");
            refreshControls();
            return;
        }
        appendStatus("connect requested");
        hudClient.bind();
        refreshControls();
    }

    private void startSending() {
        if (!hudClient.isBound()) {
            appendStatus("start blocked: Connect first");
            refreshControls();
            return;
        }
        if (sending) {
            appendStatus("start skipped: already sending");
            refreshControls();
            return;
        }
        applyNavigationFields();
        try {
            hudClient.start();
            arrowCuratedMode = true;
            curatedIndex = HudArrowComboCatalog.defaultIndex();
            applyCuratedCombo(false);
            sending = true;
            clearSequenceActive = false;
            exitRequested = false;
            sendCount = 0;
            cancelPendingHudCallbacks();
            handler.post(sendLoop);
            appendStatus("sending=true intervalMs=" + SEND_INTERVAL_MS
                    + " backgroundMode=" + BACKGROUND_MODE);
            refreshControls();
        } catch (RemoteException e) {
            appendStatus("start error: " + e.getMessage());
            Log.e(TAG, "startSending failed", e);
            refreshControls();
        }
    }

    private void scheduleStartAfterBind() {
        if (pendingStartAfterBindRunnable != null) {
            handler.removeCallbacks(pendingStartAfterBindRunnable);
        }
        pendingStartAfterBindRunnable = startAfterBindRunnable;
        handler.postDelayed(pendingStartAfterBindRunnable, START_BIND_RETRY_MS);
    }

    private void stopSending(boolean clearHud) {
        sending = false;
        startAfterBindPending = false;
        clearSequenceActive = false;
        cancelPendingHudCallbacks();
        if (clearHud && hudClient.isBound()) {
            clearSequenceActive = true;
            sendClearFrame(1);
            refreshControls();
            return;
        }
        clearSequenceActive = false;
        stopHudService("stop", false);
        refreshControls();
    }

    private void sendClearFrame(int index) {
        if (!clearSequenceActive) {
            return;
        }
        if (!hudClient.isBound()) {
            appendStatus("clear blocked: service not connected");
            clearSequenceActive = false;
            stopHudService("clear-disconnected", true);
            return;
        }
        HudState clearState = state.copyForClear();
        try {
            int ret = hudClient.send(HudRoadPayload.build(clearState));
            appendStatus("clear frame=" + index
                    + "/" + CLEAR_FRAME_COUNT
                    + " ret=" + ret
                    + " field8Tag=" + HudGraphicPayload.turnFieldStatus(clearState)
                    + " field8Magic=" + HudGraphicPayload.turnFieldMagic(clearState)
                    + " field8Desc=" + HudGraphicPayload.turnFieldDescriptor(clearState));
        } catch (RemoteException e) {
            appendStatus("clear error: " + e.getMessage());
            Log.e(TAG, "sendClearFrame failed", e);
        }
        if (index < CLEAR_FRAME_COUNT) {
            pendingClearFrameRunnable = () -> {
                pendingClearFrameRunnable = null;
                sendClearFrame(index + 1);
            };
            handler.postDelayed(pendingClearFrameRunnable, CLEAR_FRAME_INTERVAL_MS);
        } else {
            clearSequenceActive = false;
            pendingClearStopRunnable = () -> {
                pendingClearStopRunnable = null;
                stopHudService("clear", false);
            };
            handler.postDelayed(pendingClearStopRunnable, CLEAR_STOP_DELAY_MS);
        }
    }

    private void stopHudService(String reason, boolean unbindClient) {
        try {
            if (hudClient.isBound()) {
                hudClient.stop();
            }
        } catch (RemoteException e) {
            appendStatus(reason + " stop error: " + e.getMessage());
            Log.e(TAG, "stopHudService failed", e);
        }
        if (unbindClient) {
            hudClient.unbind();
        }
        appendStatus("sending=false reason=" + reason);
        refreshControls();
        if (exitRequested) {
            finishAfterStop();
        }
    }

    private void stopImmediately(String reason, boolean clearHud, boolean unbindClient) {
        sending = false;
        clearSequenceActive = false;
        cancelPendingHudCallbacks();
        if (hudClient != null && hudClient.isBound()) {
            if (clearHud) {
                HudState clearState = state.copyForClear();
                try {
                    int ret = hudClient.send(HudRoadPayload.build(clearState));
                    appendStatus(reason + " immediate clear ret=" + ret
                            + " field8Tag=" + HudGraphicPayload.turnFieldStatus(clearState)
                            + " field8Magic=" + HudGraphicPayload.turnFieldMagic(clearState)
                            + " field8Desc=" + HudGraphicPayload.turnFieldDescriptor(clearState));
                } catch (RemoteException e) {
                    appendStatus(reason + " immediate clear error: " + e.getMessage());
                    Log.e(TAG, "stopImmediately clear failed", e);
                }
            }
            try {
                hudClient.stop();
            } catch (RemoteException e) {
                appendStatus(reason + " immediate stop error: " + e.getMessage());
                Log.e(TAG, "stopImmediately stop failed", e);
            }
        }
        if (unbindClient && hudClient != null) {
            hudClient.unbind();
        }
        appendStatus("sending=false reason=" + reason);
        refreshControls();
    }

    private void cancelPendingHudCallbacks() {
        handler.removeCallbacks(sendLoop);
        startAfterBindPending = false;
        cancelRunnable(pendingStartAfterBindRunnable);
        pendingStartAfterBindRunnable = null;
        cancelRunnable(pendingClearFrameRunnable);
        pendingClearFrameRunnable = null;
        cancelRunnable(pendingClearStopRunnable);
        pendingClearStopRunnable = null;
    }

    private void cancelRunnable(Runnable runnable) {
        if (runnable != null) {
            handler.removeCallbacks(runnable);
        }
    }

    private void exitAndFinish() {
        appendStatus("exit requested");
        exitRequested = true;
        if (LogcatRecorder.isRecording()) {
            LogcatRecorder.Result result = LogcatRecorder.stop(this);
            appendStatus("logcat saved on exit " + result.detail
                    + " file=" + filePath(result.file));
        }
        stopImmediately("exit", true, true);
        finishAfterStop();
    }

    private void finishAfterStop() {
        finishAndRemoveTask();
    }

    private void disconnectHud() {
        stopActiveNavHud("disconnect");
        stopImmediately("disconnect", true, true);
        refreshControls();
    }

    private void stopActiveNavHud(String reason) {
        String hudPackage = NavCapturePrefs.getHudPackage(this);
        if (hudPackage.isEmpty()) {
            if (NavHudLiveSender.get(this).isRunning()) {
                NavHudLiveSender.get(this).stop("", reason, true);
                appendStatus("nav live HUD stop requested");
            }
            return;
        }
        NavCapturePrefs.setHudEnabled(this, hudPackage, false);
        NavHudLiveSender.get(this).stop(hudPackage, reason, true);
        appendStatus("nav HUD stop " + hudPackage);
        AppEventLogger.event(this, "nav_hud stop " + hudPackage + " reason=" + reason);
        refreshActiveAppsList();
    }

    private void setBootMode(boolean enabled) {
        HudPrefs.setBootEnabled(this, enabled);
        appendStatus("Boot " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui boot=" + enabled);
        if (enabled) {
            HudRuntimeSupervisor.ensureStarted(this, "boot-on");
        } else {
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeService.stopPersistent(this, "boot-off");
        }
        refreshControls();
    }

    private void setSmallDistanceClamp(boolean enabled) {
        HudPrefs.setSmallDistanceClampEnabled(this, enabled);
        cachedPayloadKey = "";
        appendStatus("Small distance clamp " + (enabled
                ? "ON: HUD displays min 20m for active 0..19m"
                : "OFF: HUD sends raw 0..19m"));
        AppEventLogger.event(this, "ui small_distance_clamp=" + enabled);
        refreshControls();
    }

    private void setRoundaboutLeftHandTraffic(boolean enabled) {
        HudPrefs.setRoundaboutLeftHandTraffic(this, enabled);
        cachedPayloadKey = "";
        appendStatus("Roundabout traffic " + (enabled
                ? "LEFT: Waze exits use S60-S69"
                : "RIGHT: Waze exits use S50-S59"));
        AppEventLogger.event(this, "ui roundabout_left_hand_traffic=" + enabled);
        refreshControls();
    }

    private void setPngOutputEnabled(boolean enabled) {
        HudPrefs.setPngOutputEnabled(this, enabled);
        cachedPayloadKey = "";
        appendStatus("PNG output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_png=" + enabled);
        refreshControls();
    }

    private void setNativeOutputEnabled(boolean enabled) {
        HudPrefs.setNativeOutputEnabled(this, enabled);
        cachedPayloadKey = "";
        appendStatus("Native output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_native=" + enabled);
        refreshControls();
    }

    private void setLaneOutputEnabled(boolean enabled) {
        HudPrefs.setLaneOutputEnabled(this, enabled);
        cachedPayloadKey = "";
        appendStatus("Lane output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_lanes=" + enabled);
        refreshControls();
    }

    private void setDistanceOutputEnabled(boolean enabled) {
        HudPrefs.setDistanceOutputEnabled(this, enabled);
        cachedPayloadKey = "";
        appendStatus("Distance output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_distance=" + enabled);
        refreshControls();
    }

    private void setStreetOutputEnabled(boolean enabled) {
        HudPrefs.setStreetOutputEnabled(this, enabled);
        cachedPayloadKey = "";
        appendStatus("Street output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_street=" + enabled);
        refreshControls();
    }

    private void setManualMode(boolean enabled) {
        manualModeEnabled = enabled;
        appendStatus("Manual mode " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui manual_mode=" + enabled);
        refreshControls();
    }

    private void setUiLanguage(boolean ua) {
        HudPrefs.setUaLanguage(this, ua);
        appendStatus("UI language " + (ua ? "UA" : "ENG"));
        refreshUiModeButtons();
        applyUiLanguage();
    }

    private void setUiTheme(boolean dark) {
        HudPrefs.setDarkTheme(this, dark);
        appendStatus("UI theme " + (dark ? "dark" : "light"));
        applyUiTheme();
    }

    private void applyUiTheme() {
        if (rootView != null) {
            applyUiTheme(rootView);
        }
        refreshUiModeButtons();
        refreshTabButtons();
    }

    private void applyUiTheme(View view) {
        if (view instanceof LinearLayout || view instanceof ScrollView) {
            view.setBackgroundColor(uiBackgroundColor());
        }
        if (view instanceof EditText) {
            EditText editText = (EditText) view;
            editText.setTextColor(uiTextColor());
            editText.setHintTextColor(uiMutedTextColor());
            editText.setBackgroundColor(uiFieldBackgroundColor());
        } else if (view instanceof Button) {
            styleButton((Button) view, view.isEnabled());
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if (THEME_WARNING_TAG.equals(textView.getTag())) {
                textView.setTextColor(Color.RED);
            } else {
                textView.setTextColor(uiTextColor());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyUiTheme(group.getChildAt(i));
            }
        }
    }

    private void applyUiLanguage() {
        if (rootView != null) {
            applyUiLanguage(rootView);
        }
    }

    private void applyUiLanguage(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            CharSequence current = textView.getText();
            if (current != null) {
                String before = current.toString();
                String after = localizeUiText(before);
                if (!after.equals(before)) {
                    textView.setText(after);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyUiLanguage(group.getChildAt(i));
            }
        }
    }

    private String localizeUiText(String value) {
        return HudPrefs.isUaLanguage(this) ? toUkrainianUiText(value) : toEnglishUiText(value);
    }

    private String toUkrainianUiText(String value) {
        switch (value) {
            case "Main":
                return "Головна";
            case "Apps":
                return "Застосунки";
            case "Logs":
                return "Логи";
            case "Manual":
                return "Ручний";
            case "Light":
                return "Світла";
            case "Permissions and Runtime":
                return "Дозволи та Runtime";
            case "Navigation Output":
                return "Вивід навігації";
            case "Navigation Preferences":
                return "Налаштування навігації";
            case "Boot auto-start":
                return "Автостарт";
            case "Small distance clamp":
                return "Обрізка малої дистанції";
            case "Roundabout left-hand traffic":
                return "Лівосторонній рух на колі";
            case "PNG output":
                return "PNG вивід";
            case "Native output":
                return "Native вивід";
            case "Lanes output":
                return "Вивід смуг";
            case "Distance output":
                return "Вивід дистанції";
            case "Street output":
                return "Вивід вулиці";
            case "Grant ADB":
                return "Надати ADB";
            case "Grant ADB running":
                return "Надання ADB...";
            case "Open \"Disable background apps\" menu":
                return "Відкрити Disable background apps";
            case "Navigation Logs":
                return "Навігаційні логи";
            case "Status Log":
                return "Журнал стану";
            case "Start Logcat":
                return "Старт Logcat";
            case "Stop Logcat":
                return "Стоп Logcat";
            case "Clear Logcat":
                return "Очистити Logcat";
            case "Manual HUD Output":
                return "Ручний HUD вивід";
            case "Manual mode":
                return "Ручний режим";
            case "Connection":
                return "З'єднання";
            case "Connect":
                return "Підключити";
            case "Start":
                return "Старт";
            case "Stop":
                return "Стоп";
            case "Disconnect":
                return "Відключити";
            case "Background":
                return "У фон";
            case "Stop Exit":
                return "Стоп і вихід";
            case "Distance":
                return "Дистанція";
            case "Apply Distance":
                return "Застосувати дистанцію";
            case "Lanes":
                return "Смуги";
            case "Lanes Show":
                return "Показати смуги";
            case "Lanes Hide":
                return "Сховати смуги";
            case "Apply Lanes":
                return "Застосувати смуги";
            case "Next Lanes":
                return "Наступні смуги";
            case "Arrow Visibility":
                return "Видимість стрілок";
            case "PNG Show":
                return "Показати PNG";
            case "PNG Hide":
                return "Сховати PNG";
            case "Native Show":
                return "Показати native";
            case "Native Hide":
                return "Сховати native";
            case "Supported Arrows":
                return "Підтримувані стрілки";
            case "Raw Arrows":
                return "Raw стрілки";
            case "Supported":
                return "Підтримувані";
            case "Prev":
                return "Назад";
            case "Next":
                return "Далі";
            case "PNG Prev":
                return "PNG назад";
            case "PNG Next":
                return "PNG далі";
            case "Native Prev":
                return "Native назад";
            case "Native Next":
                return "Native далі";
            case "Apply Raw":
                return "Застосувати raw";
            case "Quick Hide":
                return "Швидко сховати";
            case "Hide PNG + Native":
                return "Сховати PNG + Native";
            case "Guide Point":
                return "Точка маршруту";
            case "Apply Guide":
                return "Застосувати точку";
            case "Supported apps":
                return "Підтримувані";
            case "All apps":
                return "Всі";
            case "SUPPORTED APPS: Google Maps, Waze, ABRP":
                return "ПІДТРИМУВАНІ: Google Maps, Waze, ABRP";
            case "Unsupported apps are logging only":
                return "Непідтримувані застосунки тільки логуються";
            case "No supported navigation apps visible":
                return "Підтримувані навігатори не знайдені";
            case "No current non-system background apps visible":
                return "Активні застосунки не знайдені";
            case "Refresh apps":
            case "Оновити застосунки":
                return "Оновити застосунки";
            case "Log":
                return "Лог";
            case "Stop HUD":
                return "Стоп HUD";
            case "Send to dashboard":
                return "На приборку";
            case "Send to main":
                return "На main";
            case "Start app first":
                return "Спочатку запустіть";
            default:
                return value;
        }
    }

    private String toEnglishUiText(String value) {
        switch (value) {
            case "Головна":
                return "Main";
            case "Застосунки":
                return "Apps";
            case "Логи":
                return "Logs";
            case "Ручний":
                return "Manual";
            case "Світла":
                return "Light";
            case "Дозволи та Runtime":
                return "Permissions and Runtime";
            case "Вивід навігації":
                return "Navigation Output";
            case "Налаштування навігації":
                return "Navigation Preferences";
            case "Автостарт":
                return "Boot auto-start";
            case "Обрізка малої дистанції":
                return "Small distance clamp";
            case "Лівосторонній рух на колі":
                return "Roundabout left-hand traffic";
            case "PNG вивід":
                return "PNG output";
            case "Native вивід":
                return "Native output";
            case "Вивід смуг":
                return "Lanes output";
            case "Вивід дистанції":
                return "Distance output";
            case "Вивід вулиці":
                return "Street output";
            case "Надати ADB":
                return "Grant ADB";
            case "Надання ADB...":
                return "Grant ADB running";
            case "Відкрити Disable background apps":
                return "Open \"Disable background apps\" menu";
            case "Навігаційні логи":
                return "Navigation Logs";
            case "Журнал стану":
                return "Status Log";
            case "Старт Logcat":
                return "Start Logcat";
            case "Стоп Logcat":
                return "Stop Logcat";
            case "Очистити Logcat":
                return "Clear Logcat";
            case "Ручний HUD вивід":
                return "Manual HUD Output";
            case "Ручний режим":
                return "Manual mode";
            case "З'єднання":
                return "Connection";
            case "Підключити":
                return "Connect";
            case "Старт":
                return "Start";
            case "Стоп":
                return "Stop";
            case "Відключити":
                return "Disconnect";
            case "У фон":
                return "Background";
            case "Стоп і вихід":
                return "Stop Exit";
            case "Дистанція":
                return "Distance";
            case "Застосувати дистанцію":
                return "Apply Distance";
            case "Смуги":
                return "Lanes";
            case "Показати смуги":
                return "Lanes Show";
            case "Сховати смуги":
                return "Lanes Hide";
            case "Застосувати смуги":
                return "Apply Lanes";
            case "Наступні смуги":
                return "Next Lanes";
            case "Видимість стрілок":
                return "Arrow Visibility";
            case "Показати PNG":
                return "PNG Show";
            case "Сховати PNG":
                return "PNG Hide";
            case "Показати native":
                return "Native Show";
            case "Сховати native":
                return "Native Hide";
            case "Підтримувані стрілки":
                return "Supported Arrows";
            case "Raw стрілки":
                return "Raw Arrows";
            case "Підтримувані":
                return "Supported";
            case "Назад":
                return "Prev";
            case "Далі":
                return "Next";
            case "PNG назад":
                return "PNG Prev";
            case "PNG далі":
                return "PNG Next";
            case "Native назад":
                return "Native Prev";
            case "Native далі":
                return "Native Next";
            case "Застосувати raw":
                return "Apply Raw";
            case "Швидко сховати":
                return "Quick Hide";
            case "Сховати PNG + Native":
                return "Hide PNG + Native";
            case "Точка маршруту":
                return "Guide Point";
            case "Застосувати точку":
                return "Apply Guide";
            case "Всі":
                return "All apps";
            case "ПІДТРИМУВАНІ: Google Maps, Waze, ABRP":
                return "SUPPORTED APPS: Google Maps, Waze, ABRP";
            case "Непідтримувані застосунки тільки логуються":
                return "Unsupported apps are logging only";
            case "Підтримувані навігатори не знайдені":
                return "No supported navigation apps visible";
            case "Активні застосунки не знайдені":
                return "No current non-system background apps visible";
            case "Оновити застосунки":
                return "Refresh apps";
            case "Лог":
                return "Log";
            case "Стоп HUD":
                return "Stop HUD";
            case "На приборку":
                return "Send to dashboard";
            case "На main":
                return "Send to main";
            case "Спочатку запустіть":
                return "Start app first";
            default:
                return value;
        }
    }

    private void refreshUiModeButtons() {
        boolean ua = HudPrefs.isUaLanguage(this);
        boolean dark = HudPrefs.isDarkTheme(this);
        setModeButton(languageUaButton, ua);
        setModeButton(languageEngButton, !ua);
        setModeButton(themeDarkButton, dark);
        setModeButton(themeLightButton, !dark);
    }

    private void setModeButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setEnabled(!active);
        if (active) {
            button.setTextColor(uiActiveTextColor());
            button.setBackgroundColor(uiActiveBackgroundColor());
        } else {
            styleButton(button, true);
        }
    }

    private void refreshTabButtons() {
        styleTabButton(mainTabButton, selectedTabIndex == 0);
        styleTabButton(appsTabButton, selectedTabIndex == 1);
        styleTabButton(logTabButton, selectedTabIndex == 2);
        styleTabButton(manualTabButton, selectedTabIndex == 3);
    }

    private void styleTabButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setEnabled(true);
        if (active) {
            button.setTextColor(uiActiveTextColor());
            button.setBackgroundColor(uiActiveBackgroundColor());
        } else {
            styleButton(button, true);
        }
    }

    private void styleButton(Button button, boolean enabled) {
        button.setTextColor(enabled ? uiButtonTextColor() : uiMutedTextColor());
        button.setBackgroundColor(enabled ? uiButtonBackgroundColor() : uiDisabledBackgroundColor());
    }

    private int uiBackgroundColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFF07111D : 0xFFF4F7FB;
    }

    private int uiFieldBackgroundColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFF111C2A : 0xFFFFFFFF;
    }

    private int uiButtonBackgroundColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFF142338 : 0xFFE8EEF7;
    }

    private int uiDisabledBackgroundColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFF0D1724 : 0xFFD8E1EE;
    }

    private int uiActiveBackgroundColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFF245FAD : 0xFF1D6FE8;
    }

    private int uiTextColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFFEAF2FF : 0xFF111827;
    }

    private int uiMutedTextColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFF9CAFC5 : 0xFF5B677A;
    }

    private int uiButtonTextColor() {
        return HudPrefs.isDarkTheme(this) ? 0xFFEAF2FF : 0xFF162235;
    }

    private int uiActiveTextColor() {
        return 0xFFFFFFFF;
    }

    private boolean showBackgroundReminderIfNeeded() {
        if (!HudPrefs.shouldShowBackgroundReminder(this)) {
            return false;
        }
        AppEventLogger.event(this, "bg_settings_reminder_shown version="
                + BuildConfig.VERSION_NAME);
        return true;
    }

    private void scheduleNavPermissionSelfCheck(boolean autoGrant) {
        scheduleNavPermissionSelfCheck(autoGrant, NAV_PERMISSION_SELF_CHECK_DELAY_MS);
    }

    private void scheduleNavPermissionSelfCheck(boolean autoGrant, long delayMs) {
        if (pendingNavPermissionSelfCheckRunnable != null) {
            handler.removeCallbacks(pendingNavPermissionSelfCheckRunnable);
        }
        pendingNavPermissionSelfCheckRunnable = () -> {
            pendingNavPermissionSelfCheckRunnable = null;
            if (!destroyed) {
                runNavPermissionSelfCheck(autoGrant);
            }
        };
        handler.postDelayed(pendingNavPermissionSelfCheckRunnable, delayMs);
    }

    private void maybeRunPendingNavPermissionSelfCheck() {
        if (!navPermissionSelfCheckPending || HudPrefs.shouldShowBackgroundReminder(this)) {
            return;
        }
        navPermissionSelfCheckPending = false;
        scheduleNavPermissionSelfCheck(true);
    }

    private void runNavPermissionSelfCheck(boolean autoGrant) {
        NavRuntimePermissionStatus status = NavRuntimePermissionStatus.check(this);
        String adbKey = LocalAdbBridge.adbKeyFingerprint(this);
        boolean autoAttempted = autoAdbGrantAttemptedThisLaunch;
        String uiSummary = status.uiSummary(autoAttempted, adbKey);
        if (status.readyForCapture()) {
            navRuntimeReconnectAttemptsThisLaunch = 0;
            updateAdbBridgeStatus(uiSummary);
            appendStatus("nav permission self-check: " + status.summary());
            AppEventLogger.event(this, "nav_permission_self_check "
                    + status.summary());
            refreshControls();
            return;
        }

        updateAdbBridgeStatus(uiSummary);
        appendStatus("nav permission self-check: " + status.summary() + " adbKey=" + adbKey);
        AppEventLogger.event(this, "nav_permission_self_check "
                + status.summary() + " adbKey=" + adbKey
                + " autoGrant=" + autoGrant
                + " attempted=" + autoAttempted);
        refreshControls();
        if (autoGrant && !autoAdbGrantAttemptedThisLaunch) {
            autoAdbGrantAttemptedThisLaunch = true;
            requestAdbPermissionGrant(
                    "launch-self-check",
                    LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
        }
    }

    private boolean ensureNavCaptureRuntimeReadyForStart(String mode, String packageName) {
        NavRuntimePermissionStatus status = NavRuntimePermissionStatus.check(this);
        if (status.readyForCapture()) {
            navRuntimeReconnectAttemptsThisLaunch = 0;
            return true;
        }
        String safeMode = mode == null ? "capture" : mode;
        updateAdbBridgeStatus(status.uiSummary(
                !status.settingsGranted(),
                LocalAdbBridge.adbKeyFingerprint(this)));
        appendStatus("nav " + safeMode + " blocked: capture services not ready: "
                + status.summary());
        AppEventLogger.event(this, "nav_capture_start_blocked mode=" + safeMode
                + " package=" + packageName + " status=" + status.summary());
        requestAdbPermissionGrant(
                "start-" + safeMode + "-" + packageName,
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
        refreshActiveAppsList();
        refreshControls();
        return false;
    }

    private void requestNavCaptureRuntimeReconnect(
            String reason,
            NavRuntimePermissionStatus status) {
        NavNotificationListenerService.requestRuntimeRebind(this, reason);
        if (status == null || status.readyForCapture() || status.needsAdbGrant()) {
            scheduleNavPermissionSelfCheck(false, NAV_RUNTIME_RECHECK_DELAY_MS);
            return;
        }
        if (adbGrantInProgress) {
            appendStatus("capture services reconnect pending: adb bridge already running");
            scheduleNavPermissionSelfCheck(false, NAV_RUNTIME_RECHECK_DELAY_MS);
            return;
        }
        if (navRuntimeReconnectAttemptsThisLaunch >= NAV_RUNTIME_RECONNECT_RETRY_LIMIT) {
            appendStatus("capture services reconnect pending: retry limit reached");
            scheduleNavPermissionSelfCheck(false, NAV_RUNTIME_RECHECK_DELAY_MS);
            return;
        }
        navRuntimeReconnectAttemptsThisLaunch++;
        requestAdbPermissionGrant(
                "runtime-reconnect-" + reason,
                LocalAdbBridge.AuthorizationPromptMode.NEVER);
    }

    private void requestAdbPermissionGrant(
            String reason,
            LocalAdbBridge.AuthorizationPromptMode authorizationPromptMode) {
        requestAdbPermissionGrant(reason, authorizationPromptMode, null);
    }

    private void requestAdbPermissionGrant(
            String reason,
            LocalAdbBridge.AuthorizationPromptMode authorizationPromptMode,
            String autoGrantAttemptKey) {
        if (adbGrantInProgress) {
            appendStatus("adb bridge grant already running");
            return;
        }
        adbGrantInProgress = true;
        NavRuntimePermissionStatus runningStatus = NavRuntimePermissionStatus.check(this);
        if (runningStatus.needsAdbGrant()) {
            updateAdbBridgeStatus(
                    runningStatus.uiSummary(false, LocalAdbBridge.adbKeyFingerprint(this))
                            .replace("auto-grant pending", "auto-grant running"));
        } else {
            updateAdbBridgeStatus("Permissions: checking\nADB: grant running");
        }
        appendStatus("adb bridge grant start: " + reason);
        refreshControls();
        Context appContext = getApplicationContext();
        Handler mainHandler = handler;
        Thread thread = new Thread(() -> {
            LocalAdbBridge.Result result;
            try {
                result = NavRuntimePermissionRepair.checkAndRepairBlocking(
                        appContext,
                        reason,
                        true,
                        authorizationPromptMode);
            } catch (RuntimeException e) {
                result = LocalAdbBridge.Result.failed(
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            LocalAdbBridge.Result finalResult = result;
            mainHandler.post(() -> handleAdbGrantResult(finalResult));
        }, "BydHudAdbGrant");
        thread.start();
    }

    private void handleAdbGrantResult(LocalAdbBridge.Result result) {
        if (destroyed) {
            AppEventLogger.event(getApplicationContext(), "adb_bridge_result_after_destroy "
                    + result.code + " " + result.message);
            return;
        }
        adbGrantInProgress = false;
        appendStatus("adb bridge " + result.code + ": " + result.message);
        AppEventLogger.event(this, "adb_bridge_result "
                + result.code + " " + result.message);
        NavRuntimePermissionStatus status = NavRuntimePermissionStatus.check(this);
        if (status.readyForCapture()) {
            navRuntimeReconnectAttemptsThisLaunch = 0;
        }
        String prefix;
        switch (result.code) {
            case GRANTED:
            case ALREADY_GRANTED:
                prefix = "ADB grant ready";
                break;
            case PARTIAL:
                prefix = status.settingsGranted()
                        ? "Permissions granted; capture services reconnecting"
                        : "ADB grant incomplete";
                break;
            case AUTHORIZATION_REQUIRED:
                prefix = "ADB authorization required";
                break;
            case ADB_UNAVAILABLE:
                prefix = "ADB unavailable";
                break;
            default:
                prefix = "ADB grant failed";
                break;
        }
        boolean adbNotGranted = !status.settingsGranted()
                && (result.code == LocalAdbBridge.Result.Code.AUTHORIZATION_REQUIRED
                || result.code == LocalAdbBridge.Result.Code.ADB_UNAVAILABLE
                || result.code == LocalAdbBridge.Result.Code.FAILED
                || result.code == LocalAdbBridge.Result.Code.PARTIAL);
        String permissionSummary =
                status.uiSummary(adbNotGranted, LocalAdbBridge.adbKeyFingerprint(this));
        if (status.settingsGranted()
                && (result.code == LocalAdbBridge.Result.Code.GRANTED
                || result.code == LocalAdbBridge.Result.Code.ALREADY_GRANTED
                || result.code == LocalAdbBridge.Result.Code.PARTIAL)) {
            updateAdbBridgeStatus(permissionSummary);
        } else {
            updateAdbBridgeStatus(prefix + ": " + result.message
                    + "\n" + permissionSummary);
        }
        refreshControls();
        if (!status.readyForCapture() && status.settingsGranted()
                && result.shouldRecheckPermissions()) {
            NavNotificationListenerService.requestRuntimeRebind(this, "adb-result-recheck");
            scheduleNavPermissionSelfCheck(false, NAV_RUNTIME_RECHECK_DELAY_MS);
        }
    }

    private void updateAdbBridgeStatus(String text) {
        if (adbBridgeStatusView != null) {
            adbBridgeStatusView.setText(text);
        }
    }

    private String navPermissionStatusRows(NavPermissionStatus status) {
        return "Notification listener: " + enabledDisabled(status.notificationListenerEnabled)
                + "\nAccessibility service: " + enabledDisabled(status.accessibilityServiceEnabled)
                + "\nAccessibility master: " + enabledDisabled(status.accessibilityMasterEnabled)
                + "\nDashboard overlay: " + enabledDisabled(status.dashboardOverlayEnabled)
                + "\nStorage read: " + enabledDisabled(status.storageReadEnabled)
                + "\nStorage write: " + enabledDisabled(status.storageWriteEnabled);
    }

    private String enabledDisabled(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private void sendCurrentState(String reason) {
        if (!"loop".equals(reason)) {
            applyNavigationFields();
        }
        refreshStateView();
        if (!hudClient.isBound()) {
            appendStatus("send blocked: service not connected; tap Connect first");
            return;
        }
        try {
            PayloadSnapshot payload = getPayloadSnapshot();
            int ret = hudClient.send(payload.bytes);
            sendCount++;
            if (!"loop".equals(reason) || sendCount % LOOP_LOG_EVERY_SENDS == 1) {
                appendStatus("fireEvent ret=" + ret
                        + " payload=" + payload.bytes.length
                        + " reason=" + reason
                        + " field7=" + payload.laneBytes
                        + " field8=" + payload.turnBytes
                        + " field8Tag=" + payload.turnFieldStatus
                        + " field8Magic=" + payload.turnFieldMagic
                        + " field8Desc=" + payload.turnFieldDescriptor
                        + " turnRes=" + payload.turnResource
                        + " displayDist=" + payload.displayDistance
                        + " " + state.summary());
            }
        } catch (RemoteException e) {
            appendStatus("send error: " + e.getMessage());
            stopImmediately("send-error", false, true);
            Log.e(TAG, "sendCurrentState failed", e);
        }
    }

    private PayloadSnapshot getPayloadSnapshot() {
        String key = buildPayloadKey();
        if (key.equals(cachedPayloadKey) && cachedPayload.length > 0) {
            return new PayloadSnapshot(cachedPayload, cachedLaneBytes, cachedTurnBytes,
                    cachedTurnFieldStatus, cachedTurnFieldMagic, cachedTurnFieldDescriptor,
                    cachedTurnResource, cachedDisplayDistance);
        }

        boolean clampSmallDistance = HudPrefs.isSmallDistanceClampEnabled(this);
        HudState payloadState = HudDisplayPolicy.apply(state, clampSmallDistance);
        HudOutputPreferences.apply(this, payloadState);
        cachedPayloadKey = key;
        cachedPayload = HudRoadPayload.build(payloadState);
        cachedLaneBytes = HudGraphicPayload.lanePngLength(payloadState);
        cachedTurnBytes = HudGraphicPayload.turnPngLength(payloadState);
        cachedTurnFieldStatus = HudGraphicPayload.turnFieldStatus(payloadState);
        cachedTurnFieldMagic = HudGraphicPayload.turnFieldMagic(payloadState);
        cachedTurnFieldDescriptor = HudGraphicPayload.turnFieldDescriptor(payloadState);
        cachedTurnResource = HudGraphicPayload.turnResourceName(payloadState);
        cachedDisplayDistance = payloadState.distanceToIntersection;
        return new PayloadSnapshot(cachedPayload, cachedLaneBytes, cachedTurnBytes,
                cachedTurnFieldStatus, cachedTurnFieldMagic, cachedTurnFieldDescriptor,
                cachedTurnResource, cachedDisplayDistance);
    }

    private String buildPayloadKey() {
        return state.distanceToIntersection
                + "|" + state.maneuverId
                + "|" + state.turnBitmapId
                + "|" + state.turnBitmapMode
                + "|" + state.navigationStatus
                + "|" + state.crossStatus
                + "|" + state.carToDestination
                + "|" + state.timeToDestination
                + "|" + state.currentMaxSpeedLimit
                + "|" + state.currentSpeed
                + "|" + state.numOfLanes
                + "|" + state.laneStrokePx
                + "|" + state.laneIconScalePercent
                + "|" + state.laneCanvasScalePercent
                + "|" + state.laneGapPx
                + "|" + state.includeNativeArrow
                + "|" + state.includeLaneBitmap
                + "|" + state.roadName
                + "|" + state.laneString
                + "|" + state.guidePoint
                + "|" + state.navigationRatio
                + "|" + HudPrefs.isSmallDistanceClampEnabled(this)
                + "|" + HudPrefs.isPngOutputEnabled(this)
                + "|" + HudPrefs.isNativeOutputEnabled(this)
                + "|" + HudPrefs.isLaneOutputEnabled(this)
                + "|" + HudPrefs.isDistanceOutputEnabled(this)
                + "|" + HudPrefs.isStreetOutputEnabled(this);
    }

    private static final class PayloadSnapshot {
        final byte[] bytes;
        final int laneBytes;
        final int turnBytes;
        final String turnFieldStatus;
        final String turnFieldMagic;
        final String turnFieldDescriptor;
        final String turnResource;
        final int displayDistance;

        PayloadSnapshot(byte[] bytes, int laneBytes, int turnBytes, String turnFieldStatus,
                String turnFieldMagic, String turnFieldDescriptor, String turnResource,
                int displayDistance) {
            this.bytes = bytes;
            this.laneBytes = laneBytes;
            this.turnBytes = turnBytes;
            this.turnFieldStatus = turnFieldStatus;
            this.turnFieldMagic = turnFieldMagic;
            this.turnFieldDescriptor = turnFieldDescriptor;
            this.turnResource = turnResource;
            this.displayDistance = displayDistance;
        }
    }

    private void switchArrowMode(boolean curated) {
        if (!canUseManualHud()) {
            appendStatus("arrow mode blocked: Connect + Start first");
            refreshControls();
            return;
        }
        arrowCuratedMode = curated;
        if (curated) {
            applyCuratedCombo(true);
        } else {
            syncRawEditsFromState();
            appendStatus("arrow mode=raw");
            refreshControls();
        }
    }

    private void stepCurated(int delta) {
        if (!canUseManualHud() || !arrowCuratedMode) {
            appendStatus("curated step blocked");
            refreshControls();
            return;
        }
        curatedIndex = delta >= 0
                ? HudArrowComboCatalog.next(curatedIndex)
                : HudArrowComboCatalog.prev(curatedIndex);
        applyCuratedCombo(true);
    }

    private void applyCuratedCombo(boolean send) {
        HudArrowComboCatalog.Combo combo = HudArrowComboCatalog.curatedAt(curatedIndex);
        pngVisible = combo.pngVisible;
        nativeVisible = combo.nativeVisible;
        state.turnBitmapId = combo.sourceId;
        state.maneuverId = combo.nativeId;
        if (combo.pngVisible) {
            lastVisiblePngSourceId = combo.sourceId;
        }
        if (combo.nativeVisible) {
            lastVisibleNativeId = combo.nativeId;
        }
        applyArrowVisibility();
        if (roadNameEdit != null) {
            roadNameEdit.setText(combo.roadLabel());
        }
        syncRawEditsFromState();
        appendStatus("curated " + (curatedIndex + 1) + "/" + HudArrowComboCatalog.size()
                + " " + combo.roadLabel()
                + " png=" + combo.pngVisible
                + " native=" + combo.nativeVisible);
        refreshControls();
        if (send) {
            sendCurrentState("curated");
        }
    }

    private void stepRawPng(int delta) {
        if (!canUseManualHud() || arrowCuratedMode) {
            appendStatus("raw png step blocked");
            refreshControls();
            return;
        }
        int current = parseInt(rawPngIdEdit, state.turnBitmapId, 0, 99);
        rawPngIdEdit.setText(String.valueOf(HudArrowComboCatalog.wrapRaw(current + delta)));
        applyRawArrowsAndSend();
    }

    private void stepRawNative(int delta) {
        if (!canUseManualHud() || arrowCuratedMode) {
            appendStatus("raw native step blocked");
            refreshControls();
            return;
        }
        int current = parseInt(rawNativeIdEdit, state.maneuverId, 0, 99);
        rawNativeIdEdit.setText(String.valueOf(HudArrowComboCatalog.wrapRaw(current + delta)));
        applyRawArrowsAndSend();
    }

    private void applyRawArrowsAndSend() {
        if (!canUseManualHud() || arrowCuratedMode) {
            appendStatus("raw apply blocked");
            refreshControls();
            return;
        }
        int sourceId = parseInt(rawPngIdEdit, state.turnBitmapId, 0, 99);
        int nativeId = parseInt(rawNativeIdEdit, state.maneuverId, 0, 99);
        rawPngIdEdit.setText(String.valueOf(sourceId));
        rawNativeIdEdit.setText(String.valueOf(nativeId));
        state.turnBitmapId = sourceId;
        state.maneuverId = nativeId;
        if (pngVisible && sourceId != HudState.TURN_BITMAP_BLANK_SOURCE_ID) {
            lastVisiblePngSourceId = sourceId;
        }
        if (nativeVisible && nativeId != HudState.NATIVE_BLANK_ID) {
            lastVisibleNativeId = nativeId;
        }
        applyArrowVisibility();
        if (roadNameEdit != null) {
            roadNameEdit.setText("RAW N" + HudArrowComboCatalog.two(nativeId)
                    + " S" + HudArrowComboCatalog.two(sourceId));
        }
        appendStatus("raw apply png=S" + HudArrowComboCatalog.two(sourceId)
                + " native=N" + HudArrowComboCatalog.two(nativeId)
                + " pngVisible=" + pngVisible
                + " nativeVisible=" + nativeVisible);
        refreshControls();
        sendCurrentState("raw");
    }

    private void setPngVisible(boolean visible) {
        if (!canUseManualHud()) {
            appendStatus("png visibility blocked: Connect + Start first");
            refreshControls();
            return;
        }
        if (!visible && state.turnBitmapId != HudState.TURN_BITMAP_BLANK_SOURCE_ID) {
            lastVisiblePngSourceId = state.turnBitmapId;
        }
        if (visible && state.turnBitmapId == HudState.TURN_BITMAP_BLANK_SOURCE_ID) {
            state.turnBitmapId = lastVisiblePngSourceId;
        }
        pngVisible = visible;
        applyArrowVisibility();
        syncRawEditsFromState();
        appendStatus("png visible=" + visible);
        refreshControls();
        sendCurrentState(visible ? "png-show" : "png-hide");
    }

    private void setNativeVisible(boolean visible) {
        if (!canUseManualHud()) {
            appendStatus("native visibility blocked: Connect + Start first");
            refreshControls();
            return;
        }
        if (!visible && state.maneuverId != HudState.NATIVE_BLANK_ID) {
            lastVisibleNativeId = state.maneuverId;
        }
        if (visible && state.maneuverId == HudState.NATIVE_BLANK_ID) {
            state.maneuverId = lastVisibleNativeId;
        }
        nativeVisible = visible;
        applyArrowVisibility();
        syncRawEditsFromState();
        appendStatus("native visible=" + visible);
        refreshControls();
        sendCurrentState(visible ? "native-show" : "native-hide");
    }

    private void setBothArrowsHidden() {
        if (!canUseManualHud()) {
            appendStatus("hide arrows blocked: Connect + Start first");
            refreshControls();
            return;
        }
        pngVisible = false;
        nativeVisible = false;
        if (state.turnBitmapId != HudState.TURN_BITMAP_BLANK_SOURCE_ID) {
            lastVisiblePngSourceId = state.turnBitmapId;
        }
        if (state.maneuverId != HudState.NATIVE_BLANK_ID) {
            lastVisibleNativeId = state.maneuverId;
        }
        state.turnBitmapId = HudState.TURN_BITMAP_BLANK_SOURCE_ID;
        state.maneuverId = HudState.NATIVE_BLANK_ID;
        syncRawEditsFromState();
        applyArrowVisibility();
        if (roadNameEdit != null) {
            roadNameEdit.setText("Both hidden N99 S72");
        }
        appendStatus("both arrows hidden");
        refreshControls();
        sendCurrentState("arrows-hide-all");
    }

    private void applyArrowVisibility() {
        if (pngVisible) {
            state.unlockTurnBitmapHidden();
        } else {
            state.hideTurnBitmapWithBlankSource();
        }
        if (nativeVisible) {
            state.includeNativeArrow = true;
        } else {
            state.hideNativeWithBlankId();
        }
    }

    private void syncRawEditsFromState() {
        if (rawPngIdEdit != null) {
            rawPngIdEdit.setText(String.valueOf(state.turnBitmapId));
        }
        if (rawNativeIdEdit != null) {
            rawNativeIdEdit.setText(String.valueOf(state.maneuverId));
        }
    }

    private void applyDistanceAndSend() {
        if (distanceEdit != null) {
            state.distanceToIntersection = parseInt(distanceEdit, state.distanceToIntersection, 0, 99999);
            distanceEdit.setText(String.valueOf(state.distanceToIntersection));
        }
        refreshStateView();
        sendCurrentState("distance");
    }

    private void setLaneBitmap(boolean value) {
        if (!canUseManualHud()) {
            appendStatus("lanes visibility blocked: Connect + Start first");
            refreshControls();
            return;
        }
        state.includeLaneBitmap = value;
        if (value && laneEdit != null && laneEdit.getText().toString().trim().isEmpty()) {
            laneEdit.setText(buildStraightLaneString(state.numOfLanes));
        }
        refreshControls();
        sendCurrentState(value ? "lanes-show" : "lanes-hide");
    }

    private void applyLaneCountAndSend() {
        if (!canUseManualHud()) {
            appendStatus("apply lanes blocked: Connect + Start first");
            refreshControls();
            return;
        }
        int count = parseLaneCount();
        state.numOfLanes = count;
        state.includeLaneBitmap = count > 0;
        String laneString = buildStraightLaneString(count);
        laneEdit.setText(laneString);
        state.laneString = laneString;
        appendStatus("lanes apply count=" + count + " lane=\"" + laneString + "\"");
        refreshControls();
        sendCurrentState("lanes-apply");
    }

    private void randomizeLanesAndSend() {
        if (!canUseManualHud()) {
            appendStatus("randomize lanes blocked: Connect + Start first");
            refreshControls();
            return;
        }
        int count = parseLaneCount();
        state.numOfLanes = count;
        state.includeLaneBitmap = count > 0;
        String laneString = buildRandomLaneString(count);
        laneEdit.setText(laneString);
        state.laneString = laneString;
        appendStatus("lanes random count=" + count + " lane=\"" + laneString + "\"");
        refreshControls();
        sendCurrentState("lanes-random");
    }

    private int parseLaneCount() {
        int count = parseInt(laneCountEdit, state.numOfLanes, 0, 8);
        if (laneCountEdit != null) {
            laneCountEdit.setText(String.valueOf(count));
        }
        return count;
    }

    private String buildStraightLaneString(int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append("S");
        }
        return builder.toString();
    }

    private String buildRandomLaneString(int count) {
        String[] directions = {"L", "SL", "S", "SR", "R"};
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(directions[random.nextInt(directions.length)]);
            if (random.nextBoolean()) {
                builder.append('*');
            }
        }
        return builder.toString();
    }

    private boolean canUseManualHud() {
        return manualModeEnabled
                && hudClient != null
                && hudClient.isBound()
                && sending;
    }

    private void applyTextFieldsAndSend() {
        applyNavigationFields();
        refreshStateView();
        sendCurrentState("fields");
    }

    private void applyNavigationFieldsAndSend() {
        applyNavigationFields();
        refreshStateView();
        sendCurrentState("fields");
    }

    private void applyNavigationFields() {
        if (distanceEdit != null) {
            state.distanceToIntersection = parseInt(distanceEdit, state.distanceToIntersection, 0, 99999);
            distanceEdit.setText(String.valueOf(state.distanceToIntersection));
        }
        if (laneCountEdit != null) {
            state.numOfLanes = parseInt(laneCountEdit, state.numOfLanes, 0, 8);
            laneCountEdit.setText(String.valueOf(state.numOfLanes));
        }
        applyTextFields();
    }

    private void applyTextFields() {
        if (roadNameEdit != null) {
            state.roadName = roadNameEdit.getText().toString();
        }
        if (laneEdit != null) {
            state.laneString = laneEdit.getText().toString();
        }
        if (guidePointEdit != null) {
            state.guidePoint = guidePointEdit.getText().toString();
        }
    }

    private void refreshStateView() {
        if (currentStateView == null) {
            return;
        }
        currentStateView.setText(
                "sending=" + sending
                        + " | connected=" + (hudClient != null && hudClient.isBound())
                        + " | runtimeService=" + HudPrefs.isRuntimeServiceRunning(this)
                        + " | boot=" + HudPrefs.isBootEnabled(this)
                        + " | navHud=" + NavCapturePrefs.getHudPackage(this)
                        + " | navLogOnly=" + formatCapturePackages(NavCapturePrefs.getLogOnlyPackages(this))
                        + " | navDisplay=" + NavAppDisplayController.get(this)
                                .lastState(NavCapturePrefs.getHudPackage(this)).displayId
                        + " | wazeCrop=" + WazeCropCapture.get(this).isRunning()
                        + " | smallDistClamp=" + HudPrefs.isSmallDistanceClampEnabled(this)
                        + " | arrowMode=" + (arrowCuratedMode ? "curated" : "raw")
                        + " | curated=" + (curatedIndex + 1) + "/" + HudArrowComboCatalog.size()
                        + " | nativeId=" + state.maneuverId
                        + " | sourceId=" + state.turnBitmapId
                        + " | dist=" + state.distanceToIntersection + "m"
                        + " | status=" + state.navigationStatus
                        + " | lanes=" + state.numOfLanes
                        + " | field28=" + state.includeNativeArrow
                        + " | pngVisible=" + pngVisible
                        + " | nativeVisible=" + nativeVisible
                        + " | lanePng=" + state.includeLaneBitmap
                        + " | turnPng=" + state.turnBitmapModeName()
                        + " | pngLock=" + state.turnBitmapHiddenLocked
                        + " | pngHide=" + HudState.turnBitmapModeName(state.turnBitmapHiddenMode)
                        + " | field8Tag=" + HudGraphicPayload.turnFieldStatus(state)
                        + " | field8Magic=" + HudGraphicPayload.turnFieldMagic(state)
                        + " | field8Desc=" + HudGraphicPayload.turnFieldDescriptor(state)
                        + " | turnRes=" + HudGraphicPayload.turnResourceName(state)
                        + " | laneSig=" + HudLaneModel.signature(state)
                        + " | icon=" + state.laneIconScalePercent
                        + " | canvas=" + state.laneCanvasScalePercent
                        + " | gapPx=" + state.laneGapPx
                        + "\nroad=\"" + state.roadName + "\""
                        + "\nlane=\"" + state.laneString + "\"");
    }

    private void refreshControls() {
        if (bootSwitch != null) {
            bootSwitch.setOnCheckedChangeListener(null);
            bootSwitch.setChecked(HudPrefs.isBootEnabled(this));
            bootSwitch.setOnCheckedChangeListener(bootSwitchListener);
        }
        if (smallDistanceClampSwitch != null) {
            smallDistanceClampSwitch.setOnCheckedChangeListener(null);
            smallDistanceClampSwitch.setChecked(HudPrefs.isSmallDistanceClampEnabled(this));
            smallDistanceClampSwitch.setOnCheckedChangeListener(smallDistanceClampSwitchListener);
        }
        if (roundaboutLeftHandSwitch != null) {
            roundaboutLeftHandSwitch.setOnCheckedChangeListener(null);
            roundaboutLeftHandSwitch.setChecked(HudPrefs.isRoundaboutLeftHandTraffic(this));
            roundaboutLeftHandSwitch.setOnCheckedChangeListener(roundaboutLeftHandSwitchListener);
        }
        if (pngOutputSwitch != null) {
            pngOutputSwitch.setOnCheckedChangeListener(null);
            pngOutputSwitch.setChecked(HudPrefs.isPngOutputEnabled(this));
            pngOutputSwitch.setOnCheckedChangeListener(pngOutputSwitchListener);
        }
        if (nativeOutputSwitch != null) {
            nativeOutputSwitch.setOnCheckedChangeListener(null);
            nativeOutputSwitch.setChecked(HudPrefs.isNativeOutputEnabled(this));
            nativeOutputSwitch.setOnCheckedChangeListener(nativeOutputSwitchListener);
        }
        if (laneOutputSwitch != null) {
            laneOutputSwitch.setOnCheckedChangeListener(null);
            laneOutputSwitch.setChecked(HudPrefs.isLaneOutputEnabled(this));
            laneOutputSwitch.setOnCheckedChangeListener(laneOutputSwitchListener);
        }
        if (distanceOutputSwitch != null) {
            distanceOutputSwitch.setOnCheckedChangeListener(null);
            distanceOutputSwitch.setChecked(HudPrefs.isDistanceOutputEnabled(this));
            distanceOutputSwitch.setOnCheckedChangeListener(distanceOutputSwitchListener);
        }
        if (streetOutputSwitch != null) {
            streetOutputSwitch.setOnCheckedChangeListener(null);
            streetOutputSwitch.setChecked(HudPrefs.isStreetOutputEnabled(this));
            streetOutputSwitch.setOnCheckedChangeListener(streetOutputSwitchListener);
        }
        if (manualModeSwitch != null) {
            manualModeSwitch.setOnCheckedChangeListener(null);
            manualModeSwitch.setChecked(manualModeEnabled);
            manualModeSwitch.setOnCheckedChangeListener(manualModeSwitchListener);
        }
        refreshUiModeButtons();
        if (adbBridgeGrantButton != null) {
            adbBridgeGrantButton.setText(adbGrantInProgress ? "Grant ADB running" : "Grant ADB");
            adbBridgeGrantButton.setEnabled(!adbGrantInProgress);
        }
        refreshLogcatControls();

        boolean connected = hudClient != null && hudClient.isBound();
        if (connectButton != null) {
            connectButton.setEnabled(!connected
                    && !sending);
        }
        if (startButton != null) {
            startButton.setEnabled(connected
                    && !sending);
        }
        if (stopButton != null) {
            stopButton.setEnabled(connected && sending);
        }
        if (disconnectButton != null) {
            disconnectButton.setEnabled(connected
                    || NavHudLiveSender.get(this).isRunning());
        }
        boolean manualHud = canUseManualHud();
        if (arrowsCuratedButton != null) {
            arrowsCuratedButton.setEnabled(manualHud && !arrowCuratedMode);
        }
        if (arrowsRawButton != null) {
            arrowsRawButton.setEnabled(manualHud && arrowCuratedMode);
        }
        if (curatedPrevButton != null) {
            curatedPrevButton.setEnabled(manualHud && arrowCuratedMode);
        }
        if (curatedNextButton != null) {
            curatedNextButton.setEnabled(manualHud && arrowCuratedMode);
        }
        boolean rawEnabled = manualHud && !arrowCuratedMode;
        if (rawPngIdEdit != null) {
            rawPngIdEdit.setEnabled(rawEnabled);
        }
        if (rawNativeIdEdit != null) {
            rawNativeIdEdit.setEnabled(rawEnabled);
        }
        if (rawApplyButton != null) {
            rawApplyButton.setEnabled(rawEnabled);
        }
        if (rawPngPrevButton != null) {
            rawPngPrevButton.setEnabled(rawEnabled);
        }
        if (rawPngNextButton != null) {
            rawPngNextButton.setEnabled(rawEnabled);
        }
        if (rawNativePrevButton != null) {
            rawNativePrevButton.setEnabled(rawEnabled);
        }
        if (rawNativeNextButton != null) {
            rawNativeNextButton.setEnabled(rawEnabled);
        }
        if (pngShowButton != null) {
            pngShowButton.setEnabled(manualHud);
        }
        if (pngHideButton != null) {
            pngHideButton.setEnabled(manualHud);
        }
        if (nativeShowButton != null) {
            nativeShowButton.setEnabled(manualHud);
        }
        if (nativeHideButton != null) {
            nativeHideButton.setEnabled(manualHud);
        }
        if (laneShowButton != null) {
            laneShowButton.setEnabled(manualHud);
        }
        if (laneHideButton != null) {
            laneHideButton.setEnabled(manualHud);
        }
        if (laneApplyButton != null) {
            laneApplyButton.setEnabled(manualHud);
        }
        if (laneRandomizeButton != null) {
            laneRandomizeButton.setEnabled(manualHud);
        }
        if (distanceApplyButton != null) {
            distanceApplyButton.setEnabled(manualHud);
        }
        refreshStateView();
        applyUiLanguage();
    }

    private String buildSafetyBanner() {
        return "BYD HUD " + BuildConfig.VERSION_NAME
                + " / code " + BuildConfig.VERSION_CODE
                + "\n" + PNG_HIDE_CANDIDATE_LINE
                + "\n" + CLOSE_ACTION_LINE
                + "\n" + BACKGROUND_MODE_LINE
                + "\n" + KILL_ON_STOP_DISABLED_LINE
                + "\n" + BG_SETTINGS_LINE;
    }

    private void appendStatus(String line) {
        Log.i(TAG, line);
        if (statusView != null) {
            statusLines.addLast(line);
            while (statusLines.size() > STATUS_LOG_MAX_LINES) {
                statusLines.removeFirst();
            }
            StringBuilder builder = new StringBuilder();
            for (String statusLine : statusLines) {
                builder.append(statusLine).append('\n');
            }
            statusView.setText(builder.toString());
        }
    }

    private TextView section(String text) {
        TextView view = label(text, 18, true);
        view.setPadding(0, 18, 0, 8);
        return view;
    }

    private TextView label(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView warningLabel(String text) {
        TextView view = label(text, 14, true);
        view.setTag(THEME_WARNING_TAG);
        view.setTextColor(Color.RED);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private EditText editText(String value) {
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setText(value);
        editText.setTextSize(16f);
        return editText;
    }

    private EditText numberEditText(int value, String hint) {
        EditText editText = editText(String.valueOf(value));
        editText.setHint(hint);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return editText;
    }

    private int parseInt(EditText editText, int fallback, int min, int max) {
        if (editText == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(editText.getText().toString().trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private Switch switchRow(LinearLayout parent, String text, boolean initial,
            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = label(text, 14, false);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private LinearLayout row(View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View view : views) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f);
            params.setMargins(2, 2, 2, 2);
            row.addView(view, params);
        }
        return row;
    }

    private static final class ActiveAppRow {
        final String label;
        final String packageName;
        final String processName;
        final int importance;
        final boolean hasProcess;
        final boolean hasTask;
        final int taskId;
        final int displayId;
        final boolean visible;

        ActiveAppRow(String label, String packageName, String processName, int importance) {
            this(label, packageName, processName, importance,
                    importance < Integer.MAX_VALUE - 2
                            && !"installed".equals(processName)
                            && !"observed".equals(processName)
                            && !"not running".equals(processName),
                    false,
                    -1,
                    0,
                    false);
        }

        ActiveAppRow(String label, String packageName, String processName, int importance,
                boolean hasProcess, boolean hasTask, int taskId, int displayId, boolean visible) {
            this.label = label;
            this.packageName = packageName;
            this.processName = processName;
            this.importance = importance;
            this.hasProcess = hasProcess;
            this.hasTask = hasTask;
            this.taskId = taskId;
            this.displayId = displayId;
            this.visible = visible;
        }

        boolean isRuntimeBacked() {
            return hasTask || hasProcess;
        }
    }
}
