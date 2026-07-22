package com.bydhud.app

//builds the runtime UI so operators can control capture, permissions, logs, and updates in one place.

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

//anchors BydHudRuntimeCompose UI orchestration so controls and diagnostics are wired from one place.
object BydHudRuntimeCompose {
    @JvmStatic
    //keeps update I/O here so network, file, and installer failures are handled in one path.
    fun install(activity: MainActivity) {
        val initialTab = if (HudPrefs.takeOptionsIntroForCurrentVersion(activity)) {
            RuntimeTab.Options
        } else {
            RuntimeTab.Apps
        }
        activity.setContent {
            RuntimeApp(activity, initialTab)
        }
    }
}

//defines class UI/state support so Compose code can keep rendering intent explicit.
private enum class RuntimeTab {
    Options,
    Apps,
    Storage,
    Patch,
    Logs,
    Manual
}

private enum class Language {
    Ua,
    En
}

//defines class UI/state support so Compose code can keep rendering intent explicit.
private enum class ManualMode {
    Supported,
    Lanes,
    Raw
}

//models UpdateCheckState data here so transport and parser layers share a stable contract.
private sealed class UpdateCheckState {
    //defines Checking UI/state support so Compose code can keep rendering intent explicit.
    data object Checking : UpdateCheckState()
    //defines Latest UI/state support so Compose code can keep rendering intent explicit.
    data object Latest : UpdateCheckState()
    //defines Available UI/state support so Compose code can keep rendering intent explicit.
    data class Available(val info: AppUpdateManager.UpdateInfo) : UpdateCheckState()
    //defines Downloading UI/state support so Compose code can keep rendering intent explicit.
    data class Downloading(val info: AppUpdateManager.UpdateInfo, val progress: String) : UpdateCheckState()
    //defines Error UI/state support so Compose code can keep rendering intent explicit.
    data class Error(val message: String) : UpdateCheckState()
}

//defines Palette UI/state support so Compose code can keep rendering intent explicit.
private data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val panel: Color,
    val panelAlt: Color,
    val field: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val active: Color,
    val accent: Color,
    val green: Color,
    val greenSoft: Color,
    val yellow: Color,
    val yellowSoft: Color,
    val red: Color,
    val redSoft: Color,
    val disabled: Color
)

//defines Copy UI/state support so Compose code can keep rendering intent explicit.
private data class Copy(
    val language: Language,
    val title: String,
    val subtitle: String,
    val main: String,
    val apps: String,
    val logs: String,
    val manual: String,
    val hudRunning: String,
    val hudIdle: String,
    val hudFailed: String,
    val adbOk: String,
    val adbNotGranted: String,
    val permissionsOk: String,
    val permissionsMissing: String,
    val ukr: String,
    val eng: String,
    val dark: String,
    val light: String,
    val mainHint: String,
    val permissionsRuntime: String,
    val adbPermissions: String,
    val adbHint: String,
    val grantAdb: String,
    val backgroundApps: String,
    val backgroundHint: String,
    val disableBgApps: String,
    val setupDialogTitle: String,
    val setupDialogText: String,
    val setupDialogInstruction: String,
    val setupDialogPrimary: String,
    val setupDialogDismiss: String,
    val bootRuntime: String,
    val bootRuntimeHint: String,
    val saveScreenshotsLogs: String,
    val saveScreenshotsLogsHint: String,
    val checkForUpdates: String,
    val checkForUpdatesHint: String,
    val checkForUpdatesButton: String,
    val betaTesting: String,
    val betaTestingHint: String,
    val shutdown: String,
    val shutdownHint: String,
    val screenCaptureChannel: String,
    val screenCaptureChannelHint: String,
    val updateTitle: String,
    val updateCurrentVersion: String,
    val updateAvailableVersion: String,
    val updateChecking: String,
    val updateLatest: String,
    val updateDownloading: String,
    val updateClose: String,
    val updateAction: String,
    val basicNavigationOutput: String,
    val extraNavigationOptions: String,
    val dashboardControl: String,
    val notice: String,
    val wazeDirectNotice: String,
    val wazeSupportedVersions: String,
    val screenCaptureUnsupportedNotice: String,
    val pngOutput: String,
    val pngHint: String,
    val nativeOutput: String,
    val nativeHint: String,
    val laneOutput: String,
    val laneHint: String,
    val distanceOutput: String,
    val distanceHint: String,
    val streetOutput: String,
    val streetHint: String,
    val textDirectionOutput: String,
    val textDirectionOutputHint: String,
    val showWazeAlerts: String,
    val showWazeAlertsHint: String,
    val fullscreenDashboard: String,
    val fullscreenDashboardHint: String,
    val smallDistanceClamp: String,
    val smallDistanceHint: String,
    val roundaboutLeft: String,
    val roundaboutHint: String,
    val appsHint: String,
    val lastScan: String,
    val refreshApps: String,
    val supportedApps: String,
    val allApps: String,
    val installed: String,
    val notInstalled: String,
    val running: String,
    val notRunning: String,
    val supported: String,
    val dashboardUnavailable: String,
    val logCandidate: String,
    val hud: String,
    val log: String,
    val sendDashboard: String,
    val sendMain: String,
    val startAppFirst: String,
    val noBackgroundApps: String,
    val logsHint: String,
    val logcatRecorder: String,
    val recorderStatus: String,
    val waiting: String,
    val startLogcat: String,
    val stopLogcat: String,
    val applicationState: String,
    val navigationLogs: String,
    val pathHint: String,
    val storage: String,
    val storageHint: String,
    val storageSettings: String,
    val navLogsFolderLimit: String,
    val navLogsFolderLimitHint: String,
    val storageLimitGb: String,
    val currentNavLogsSize: String,
    val navigationLogsFolder: String,
    val privateStorageLocation: String,
    val publicStorageLocation: String,
    val bothStorageLocations: String,
    val shareSelected: String,
    val sortByDate: String,
    val sortByName: String,
    val deleteSelected: String,
    val activeToday: String,
    val sessions: String,
    val created: String,
    val folderSelected: String,
    val folderNotSelected: String,
    val storageNoDayFolders: String,
    val storageCalculating: String,
    val storageSessionsShort: String,
    val storageDeleteTitle: String,
    val storageDeleteSelected: String,
    val storageDeleteQuestion: String,
    val storageDeleteCannotStop: String,
    val storageDeleteYes: String,
    val storageDeleteNo: String,
    val storageDeletingFolder: String,
    val storageDeleteStep: String,
    val patch: String,
    val patchTab: String,
    val patchHint: String,
    val patchWarning: String,
    val patchWarningText: String,
    val patchRiskWarning: String,
    val availableNavigators: String,
    val noSupportedNavigators: String,
    val appVersion: String,
    val patchNotChecked: String,
    val checkPatch: String,
    val applyPatch: String,
    val patchConfirmTitle: String,
    val patchConfirmText: String,
    val patchConfirmOk: String,
    val patchConfirmCancel: String,
    val manualHint: String,
    val manualHudOutput: String,
    val supportedArrows: String,
    val supportedArrowsHint: String,
    val manualLanes: String,
    val manualLanesHint: String,
    val rawManeuverIds: String,
    val rawManeuverHint: String,
    val manualMode: String,
    val manualModeHint: String,
    val pngNumber: String,
    val nativeNumber: String,
    val distance: String,
    val street: String,
    val laneBitmap: String,
    val previous: String,
    val next: String,
    val randomize: String,
    val currentSelection: String,
    val manualPreview: String
)

//defines PressFeedback UI/state support so Compose code can keep rendering intent explicit.
private data class PressFeedback(
    val interactionSource: MutableInteractionSource,
    val pressed: Boolean,
    val modifier: Modifier
)

//guards button callbacks so the visible press state renders before expensive actions start.
private const val VISUAL_PRESS_BEFORE_ACTION_MS = 90L

//guards switch actions so the knob reaches the pending center before backend work starts.
private const val SWITCH_CENTER_BEFORE_ACTION_MS = 120L

//guards stalled switch actions so controls never stay blocked indefinitely.
private const val SWITCH_PENDING_TIMEOUT_MS = 2_000L

private const val PROJECT_REPOSITORY_URL = "https://github.com/sunlixWhyNotAvailable/byd-hud"

//tracks switch transition intent so success can complete and failure can roll back.
private data class SwitchPendingState(
    val from: Boolean,
    val target: Boolean,
    val startedAtMs: Long
)

//shares the nested switch trigger with row-style switch controls without duplicating switch logic.
private data class SwitchExternalControl(
    val trigger: () -> Unit,
    val pending: Boolean
)

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun rememberPressFeedback(enabled: Boolean = true): PressFeedback {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.97f else 1.0f,
        label = "pressScale"
    )
    return PressFeedback(
        interactionSource = interactionSource,
        pressed = enabled && pressed,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    )
}

//adds a short color response so a tap is visible even when the next action is slow.
private fun pressBackground(base: Color, palette: Palette, pressed: Boolean): Color {
    return if (pressed) palette.accent.copy(alpha = if (palette.dark) 0.24f else 0.14f) else base
}

@Composable
//delays action launch briefly so tap feedback is visible before synchronous work can block recomposition.
private fun rememberVisualFirstClick(onClick: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latestOnClick by rememberUpdatedState(onClick)
    return remember {
        {
            scope.launch {
                delay(VISUAL_PRESS_BEFORE_ACTION_MS)
                latestOnClick()
            }
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun ModalInputBlocker() {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
    )
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun RuntimeApp(activity: MainActivity, initialTab: RuntimeTab) {
    var snapshot by remember { mutableStateOf(activity.composeSnapshot()) }
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var storageSortOldestFirst by rememberSaveable { mutableStateOf(false) }
    var selectedStorageDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingStorageDeleteDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageDeleteQueue by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageDeleteBusy by remember { mutableStateOf(false) }
    var storageDeleteCurrentDay by remember { mutableStateOf("") }
    var storageDeleteStep by remember { mutableStateOf(0) }
    var storageDeleteTotal by remember { mutableStateOf(0) }
    var storageShareDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageShareBusy by remember { mutableStateOf(false) }
    var lastAppsScanRevision by remember { mutableStateOf(activity.composeAppsScanRevision()) }
    var liveHudStatus by remember { mutableStateOf(snapshot.hudStatus) }
    var lastStorageRefreshRequestMs by remember { mutableStateOf(0L) }
    var showSetupDialog by rememberSaveable { mutableStateOf(activity.composeShouldShowBackgroundReminder()) }
    var autoUpdateCheckEnabled by rememberSaveable { mutableStateOf(AppUpdateManager.isAutoCheckEnabled(activity)) }
    var betaChannelEnabled by rememberSaveable { mutableStateOf(AppUpdateManager.isBetaChannelEnabled(activity)) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var pendingUpdateDialog by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Checking) }
    var appInForeground by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    val latestAutoUpdateCheckEnabled by rememberUpdatedState(autoUpdateCheckEnabled)
    val latestAppInForeground by rememberUpdatedState(appInForeground)
    val latestShowSetupDialog by rememberUpdatedState(showSetupDialog)
    val latestShowUpdateDialog by rememberUpdatedState(showUpdateDialog)
    val palette = if (snapshot.darkTheme) darkPalette() else lightPalette()
    val copy = if (snapshot.uaLanguage) uaCopy() else enCopy()
    val blockingUiFlow = when {
        showSetupDialog -> "setup"
        showUpdateDialog -> "update"
        pendingStorageDeleteDays.isNotEmpty() || storageDeleteBusy -> "storage-delete"
        storageShareBusy -> "storage-share"
        else -> ""
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    fun refresh() {
        val refreshed = activity.composeSnapshot()
        snapshot = refreshed
        liveHudStatus = refreshed.hudStatus
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    fun runAction(action: () -> Unit) {
        action()
        refresh()
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    fun beginUpdateCheck(force: Boolean, showLatestResult: Boolean) {
        //guard update checks behind explicit UI state so repeated taps cannot leave stale results visible.
        updateState = UpdateCheckState.Checking
        if (showLatestResult) {
            if (activity.composeTryStartBlockingUiFlow("update")) {
                showUpdateDialog = true
                pendingUpdateDialog = false
            } else {
                pendingUpdateDialog = true
            }
        }
        updateScope.launch {
            val nextState = try {
                when (val result = AppUpdateManager.checkForUpdate(activity, forceCheck = force)) {
                    AppUpdateManager.CheckResult.UpToDate -> UpdateCheckState.Latest
                    is AppUpdateManager.CheckResult.Available -> UpdateCheckState.Available(result.info)
                }
            } catch (e: Exception) {
                UpdateCheckState.Error(e.message ?: "Update check failed")
            }
            if (!showLatestResult && nextState !is UpdateCheckState.Available) {
                return@launch
            }
            updateState = nextState
            if (activity.composeTryStartBlockingUiFlow("update")) {
                showUpdateDialog = true
                pendingUpdateDialog = false
            } else {
                pendingUpdateDialog = true
            }
        }
    }

    //runs storage deletion as folder steps so the UI can stay responsive without a pre-scan.
    fun beginStorageDelete(days: List<String>) {
        if (days.isEmpty() || storageDeleteBusy || storageShareBusy) {
            return
        }
        if (!activity.composeTryStartBlockingUiFlow("storage-delete")) {
            return
        }
        pendingStorageDeleteDays = emptyList()
        storageDeleteQueue = days
        storageDeleteCurrentDay = days.first()
        storageDeleteStep = 1
        storageDeleteTotal = days.size
        storageDeleteBusy = true
    }

    fun beginStorageShare(days: List<String>) {
        if (days.isEmpty() || storageDeleteBusy || storageShareBusy) {
            return
        }
        if (!activity.composeTryStartBlockingUiFlow("storage-share")) {
            return
        }
        storageShareDays = days
        storageShareBusy = true
    }

    LaunchedEffect(blockingUiFlow) {
        activity.composeReportMainUiState(blockingUiFlow)
    }

    LaunchedEffect(pendingUpdateDialog) {
        while (pendingUpdateDialog) {
            delay(250L)
            if (latestAppInForeground
                && activity.composeTryStartBlockingUiFlow("update")) {
                pendingUpdateDialog = false
                showUpdateDialog = true
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == RuntimeTab.Apps) {
            lastAppsScanRevision = activity.composeAppsScanRevision()
            activity.composeRefreshApps()
        }
        if (selectedTab == RuntimeTab.Storage) {
            activity.composeRequestStorageRefresh(false)
            lastStorageRefreshRequestMs = SystemClock.elapsedRealtime()
            refresh()
        }
    }

    DisposableEffect(activity) {
        appInForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            //guard auto-check so background launches do not show update UI over a hidden app.
            when (event) {
                Lifecycle.Event.ON_RESUME -> appInForeground = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> appInForeground = false
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        latestAutoUpdateCheckEnabled,
        latestAppInForeground,
        latestShowSetupDialog,
        latestShowUpdateDialog
    ) {
        //guard auto-check behind a background-armed timer so foreground opening can consume pending work immediately.
        if (!latestAutoUpdateCheckEnabled ||
            !latestAppInForeground ||
            latestShowSetupDialog ||
            latestShowUpdateDialog
        ) {
            return@LaunchedEffect
        }
        val remainingMs = AppUpdateManager.autoCheckDelayRemainingMs(activity) ?: return@LaunchedEffect
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        if (latestAutoUpdateCheckEnabled &&
            latestAppInForeground &&
            !latestShowSetupDialog &&
            !latestShowUpdateDialog &&
            AppUpdateManager.consumeAutoCheckReady(activity)
        ) {
            beginUpdateCheck(force = false, showLatestResult = false)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            val now = SystemClock.elapsedRealtime()
            if (selectedTab == RuntimeTab.Apps) {
                val scanRevision = activity.composeAppsScanRevision()
                if (scanRevision != lastAppsScanRevision) {
                    lastAppsScanRevision = scanRevision
                    refresh()
                }
                val deliveryStatus = activity.composeHudDeliveryStatus()
                val nextHudStatus = if (deliveryStatus == "idle" && !snapshot.captureReady) {
                    "failed"
                } else {
                    deliveryStatus
                }
                if (liveHudStatus != nextHudStatus) {
                    liveHudStatus = nextHudStatus
                }
            } else {
                refresh()
            }
            if (selectedTab == RuntimeTab.Storage && !storageDeleteBusy) {
                if (now - lastStorageRefreshRequestMs >= 5000L) {
                    activity.composeRequestStorageRefresh(false)
                    lastStorageRefreshRequestMs = now
                }
            }
        }
    }

    LaunchedEffect(storageDeleteBusy, storageDeleteQueue) {
        if (!storageDeleteBusy) {
            return@LaunchedEffect
        }
        if (storageDeleteQueue.isEmpty()) {
            storageDeleteBusy = false
            storageDeleteQueue = emptyList()
            storageDeleteCurrentDay = ""
            storageDeleteStep = 0
            storageDeleteTotal = 0
            selectedStorageDays = emptyList()
            refresh()
            return@LaunchedEffect
        }
        val results = withContext(Dispatchers.IO + NonCancellable) {
            activity.composeDeleteStorageDays(storageDeleteQueue) { day, step, total ->
                activity.runOnUiThread {
                    storageDeleteCurrentDay = day
                    storageDeleteStep = step
                    storageDeleteTotal = total
                }
            }
        }
        results.forEach { result ->
            activity.composeAppendStatus("Storage delete ${result.day}: ${result.message}")
        }
        storageDeleteBusy = false
        storageDeleteQueue = emptyList()
        storageDeleteCurrentDay = ""
        storageDeleteStep = 0
        storageDeleteTotal = 0
        selectedStorageDays = emptyList()
        activity.composeRequestStorageRefresh(true)
        refresh()
    }

    LaunchedEffect(storageShareBusy, storageShareDays) {
        if (!storageShareBusy) {
            return@LaunchedEffect
        }
        val detail = withContext(Dispatchers.IO + NonCancellable) {
            activity.composeShareStorageDays(storageShareDays)
        }
        activity.composeAppendStatus("Storage share: $detail")
        storageShareBusy = false
        storageShareDays = emptyList()
        refresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Header(
                copy = copy,
                palette = palette,
                snapshot = snapshot,
                hudStatus = liveHudStatus,
                onLanguage = { ua -> runAction { activity.composeSetUaLanguage(ua) } },
                onTheme = { dark -> runAction { activity.composeSetDarkTheme(dark) } }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp)
            ) {
                when (selectedTab) {
                    RuntimeTab.Options -> OptionsTab(
                        copy = copy,
                        palette = palette,
                        snapshot = snapshot,
                        activity = activity,
                        runAction = ::runAction,
                        autoUpdateCheckEnabled = autoUpdateCheckEnabled,
                        onAutoUpdateCheckChange = { enabled ->
                            autoUpdateCheckEnabled = enabled
                            AppUpdateManager.setAutoCheckEnabled(activity, enabled)
                        },
                        betaChannelEnabled = betaChannelEnabled,
                        onBetaChannelChange = { enabled ->
                            betaChannelEnabled = enabled
                            AppUpdateManager.setBetaChannelEnabled(activity, enabled)
                        },
                        onManualUpdateCheck = { beginUpdateCheck(force = true, showLatestResult = true) },
                        onDisableBgApps = {
                            if (activity.composeTryStartBlockingUiFlow("setup")) {
                                showSetupDialog = true
                            }
                        },
                        onShutdownClick = { runAction { activity.composeShutdownAndExit() } }
                    )
                    RuntimeTab.Apps -> AppsTab(copy, palette, snapshot, activity, ::runAction)
                    RuntimeTab.Storage -> StorageTab(
                        copy = copy,
                        palette = palette,
                        snapshot = snapshot,
                        sortOldestFirst = storageSortOldestFirst,
                        selectedDays = selectedStorageDays,
                        storageBusy = storageDeleteBusy || storageShareBusy,
                        onStorageLimitGb = { value -> runAction { activity.composeSetStorageLimitGb(value) } },
                        onSortOldestFirst = { storageSortOldestFirst = it },
                        onToggleDay = { day ->
                            selectedStorageDays = if (selectedStorageDays.contains(day)) {
                                selectedStorageDays - day
                            } else {
                                selectedStorageDays + day
                            }
                        },
                        onDeleteSelected = { deletableSelectedDays ->
                            if (activity.composeTryStartBlockingUiFlow("storage-delete")) {
                                pendingStorageDeleteDays = deletableSelectedDays
                            }
                        },
                        onShareSelected = ::beginStorageShare
                    )
                    RuntimeTab.Logs -> LogsTab(copy, palette, snapshot, activity, ::runAction)
                    RuntimeTab.Patch -> PatchTab(copy, palette, snapshot)
                    RuntimeTab.Manual -> ManualTab(copy, palette, snapshot, activity, ::runAction)
                }
            }

            BottomTabs(copy, palette, selectedTab) { selectedTab = it }
        }

        if (showSetupDialog) {
            SetupReminderOverlay(
                copy = copy,
                palette = palette,
                onPrimary = {
                    showSetupDialog = false
                    runAction { activity.composeOpenBackgroundSettingsFromReminder() }
                },
                onDismiss = {
                    showSetupDialog = false
                    runAction { activity.composeDismissBackgroundReminder() }
                }
            )
        }

        if (showUpdateDialog) {
            UpdateCheckOverlay(
                copy = copy,
                palette = palette,
                state = updateState,
                onUpdate = {
                    val available = updateState
                    if (available is UpdateCheckState.Available) {
                        updateState = UpdateCheckState.Downloading(available.info, "0%")
                        updateScope.launch {
                            try {
                                AppUpdateManager.downloadAndInstall(activity, available.info) { progress ->
                                    updateState = UpdateCheckState.Downloading(available.info, progress)
                                }
                            } catch (e: Exception) {
                                updateState = UpdateCheckState.Error(e.message ?: "Download failed")
                            }
                        }
                    }
                },
                onClose = { showUpdateDialog = false }
            )
        }

        if (pendingStorageDeleteDays.isNotEmpty()) {
            StorageDeleteConfirmOverlay(
                copy = copy,
                palette = palette,
                folderCount = pendingStorageDeleteDays.size,
                onConfirm = { beginStorageDelete(pendingStorageDeleteDays) },
                onDismiss = { pendingStorageDeleteDays = emptyList() }
            )
        }

        if (storageDeleteBusy) {
            StorageDeleteOverlay(
                copy = copy,
                palette = palette,
                folderName = storageDeleteCurrentDay,
                step = storageDeleteStep.coerceAtLeast(1),
                total = storageDeleteTotal.coerceAtLeast(1)
            )
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun Header(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    hudStatus: String,
    onLanguage: (Boolean) -> Unit,
    onTheme: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.hud_top_bar_icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(copy.title, color = palette.text, fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Text(
                copy.subtitle,
                color = palette.muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HudStatusPill(hudStatus, copy, palette)
            //guard top-bar adb status so OK means grant-backed capture permissions are already present.
            Pill(if (snapshot.settingsPermissionsGranted) copy.adbOk else copy.adbNotGranted,
                if (snapshot.settingsPermissionsGranted) palette.green else palette.red,
                if (snapshot.settingsPermissionsGranted) palette.greenSoft else palette.redSoft)
            Pill(if (snapshot.captureReady) copy.permissionsOk else copy.permissionsMissing,
                if (snapshot.captureReady) palette.green else palette.red,
                if (snapshot.captureReady) palette.greenSoft else palette.redSoft)
            Segmented(copy.ukr, copy.eng, snapshot.uaLanguage, palette,
                onLeft = { onLanguage(true) },
                onRight = { onLanguage(false) })
            Segmented(copy.dark, copy.light, snapshot.darkTheme, palette,
                onLeft = { onTheme(true) },
                onRight = { onTheme(false) })
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun OptionsTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit,
    autoUpdateCheckEnabled: Boolean,
    onAutoUpdateCheckChange: (Boolean) -> Unit,
    betaChannelEnabled: Boolean,
    onBetaChannelChange: (Boolean) -> Unit,
    onManualUpdateCheck: () -> Unit,
    onDisableBgApps: () -> Unit,
    onShutdownClick: () -> Unit
) {
    LazyPageSurface(copy.main, copy.mainHint, palette) {
        item(key = "notice") {
            Section(copy.notice, palette) {
                Text(
                    text = buildAnnotatedString {
                        append(copy.wazeDirectNotice)
                        append(" ")
                        withStyle(SpanStyle(color = palette.yellow, fontWeight = FontWeight.SemiBold)) {
                            append(copy.wazeSupportedVersions)
                        }
                    },
                    color = palette.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(14.dp)
                )
                Text(
                    text = copy.screenCaptureUnsupportedNotice,
                    color = palette.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp)
                )
            }
        }
        item(key = "runtime-permissions") {
            Section(copy.permissionsRuntime, palette) {
                SettingRow(
                title = copy.adbPermissions,
                hint = copy.adbHint,
                palette = palette,
                action = { HudButton(copy.grantAdb, palette, primary = true, width = 190.dp) { runAction { activity.composeGrantAdb() } } }
                )
                Divider(palette)
                SettingRow(
                title = copy.backgroundApps,
                hint = copy.backgroundHint,
                palette = palette,
                action = { HudButton(copy.disableBgApps, palette, width = 190.dp, onClick = onDisableBgApps) }
                )
                Divider(palette)
                SwitchRow(copy.bootRuntime, copy.bootRuntimeHint, snapshot.bootEnabled, palette) {
                    runAction { activity.composeSetBootEnabled(it) }
                }
                Divider(palette)
                SwitchRow(
                copy.saveScreenshotsLogs,
                copy.saveScreenshotsLogsHint,
                snapshot.detailedDebugArtifactsEnabled,
                palette
                ) {
                    runAction { activity.composeSetDetailedDebugArtifactsEnabled(it) }
                }
                Divider(palette)
                UpdateCheckLine(
                title = copy.checkForUpdates,
                hint = copy.checkForUpdatesHint,
                buttonText = copy.checkForUpdatesButton,
                checked = autoUpdateCheckEnabled,
                onCheckedChange = onAutoUpdateCheckChange,
                onCheckClick = onManualUpdateCheck,
                palette = palette
                )
                Divider(palette)
                SwitchRow(
                copy.betaTesting,
                copy.betaTestingHint,
                betaChannelEnabled,
                palette,
                onBetaChannelChange
                )
                Divider(palette)
                SettingRow(
                title = copy.shutdown,
                hint = copy.shutdownHint,
                palette = palette,
                action = {
                    HudIconButton(
                        icon = R.drawable.ic_shutdown,
                        contentDescription = copy.shutdown,
                        palette = palette,
                        tint = palette.red,
                        onClick = onShutdownClick
                    )
                    }
                )
                Divider(palette)
                SwitchRow(
                    copy.screenCaptureChannel,
                    copy.screenCaptureChannelHint,
                    snapshot.wazeScreenCaptureEnabled,
                    palette
                ) {
                    runAction { activity.composeSetWazeScreenCaptureEnabled(it) }
                }
            }
        }

        item(key = "basic-navigation") {
            Section(copy.basicNavigationOutput, palette) {
                SwitchRow(copy.pngOutput, copy.pngHint, snapshot.pngOutputEnabled, palette) {
                    runAction { activity.composeSetPngOutputEnabled(it) }
                }
                Divider(palette)
                SwitchRow(copy.nativeOutput, copy.nativeHint, snapshot.nativeOutputEnabled, palette) {
                    runAction { activity.composeSetNativeOutputEnabled(it) }
                }
                Divider(palette)
                SwitchRow(copy.laneOutput, copy.laneHint, snapshot.laneOutputEnabled, palette) {
                    runAction { activity.composeSetLaneOutputEnabled(it) }
                }
                Divider(palette)
                SwitchRow(copy.streetOutput, copy.streetHint, snapshot.streetOutputEnabled, palette) {
                    runAction { activity.composeSetStreetOutputEnabled(it) }
                }
                Divider(palette)
                SwitchRow(copy.distanceOutput, copy.distanceHint, snapshot.distanceOutputEnabled, palette) {
                    runAction { activity.composeSetDistanceOutputEnabled(it) }
                }
            }
        }

        item(key = "extra-navigation") {
            Section(copy.extraNavigationOptions, palette) {
                SwitchRow(
                copy.textDirectionOutput,
                copy.textDirectionOutputHint,
                snapshot.textDirectionOutputEnabled,
                palette
                ) {
                    runAction { activity.composeSetTextDirectionOutputEnabled(it) }
                }
                Divider(palette)
                SwitchRow(copy.showWazeAlerts, copy.showWazeAlertsHint, snapshot.wazeAlertsEnabled, palette) {
                    runAction { activity.composeSetWazeAlertsEnabled(it) }
                }
                Divider(palette)
                SwitchRow(copy.smallDistanceClamp, copy.smallDistanceHint, snapshot.smallDistanceClampEnabled, palette) {
                    runAction { activity.composeSetSmallDistanceClamp(it) }
                }
                Divider(palette)
                SwitchRow(copy.roundaboutLeft, copy.roundaboutHint, snapshot.roundaboutLeftHandTraffic, palette) {
                    runAction { activity.composeSetRoundaboutLeftHandTraffic(it) }
                }
            }
        }

        item(key = "dashboard-control") {
            Section(copy.dashboardControl, palette) {
                SwitchRow(
                    copy.fullscreenDashboard,
                    copy.fullscreenDashboardHint,
                    snapshot.fullscreenDashboardEnabled,
                    palette
                ) {
                    runAction { activity.composeSetFullscreenDashboardEnabled(it) }
                }
            }
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun SetupReminderOverlay(
    copy: Copy,
    palette: Palette,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (palette.dark) 0.48f else 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = copy.setupDialogTitle,
                color = palette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SetupInstructionBlock(
                palette = palette,
                text = copy.setupDialogInstruction
            )
            Text(
                text = copy.setupDialogText,
                color = palette.muted,
                fontSize = 14.sp,
                lineHeight = 19.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudButton(
                    text = copy.setupDialogPrimary,
                    palette = palette,
                    primary = true,
                    width = 138.dp,
                    onClick = onPrimary
                )
                HudButton(
                    text = copy.setupDialogDismiss,
                    palette = palette,
                    width = 138.dp,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
//updates shared state here so freshness and lifecycle checks use the same evidence.
private fun UpdateCheckOverlay(
    copy: Copy,
    palette: Palette,
    state: UpdateCheckState,
    onUpdate: () -> Unit,
    onClose: () -> Unit
) {
    val notesScroll = rememberScrollState()
    val updateEnabled = state is UpdateCheckState.Available
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (palette.dark) 0.48f else 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(560.dp)
                .height(430.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(copy.updateTitle, color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${copy.updateCurrentVersion} v${BuildConfig.VERSION_NAME}", color = palette.muted, fontSize = 14.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(notesScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    //render update result inside fixed area so long GitHub notes scroll instead of resizing popup.
                    when (state) {
                        UpdateCheckState.Checking -> Text(
                            copy.updateChecking,
                            color = palette.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        UpdateCheckState.Latest -> Text(
                            copy.updateLatest,
                            color = palette.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        is UpdateCheckState.Available -> AvailableUpdateNotes(
                            copy = copy,
                            palette = palette,
                            version = state.info.version,
                            notes = state.info.releaseNotes
                        )
                        is UpdateCheckState.Downloading -> {
                            Text(
                                copy.updateDownloading,
                                color = palette.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            AvailableUpdateNotes(
                                copy = copy,
                                palette = palette,
                                version = state.info.version,
                                notes = state.info.releaseNotes
                            )
                        }
                        is UpdateCheckState.Error -> Text(
                            state.message,
                            color = palette.red,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (state is UpdateCheckState.Downloading) {
                UpdateProgressBar(progress = state.progress, palette = palette)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudButton(
                    copy.updateAction,
                    palette,
                    primary = true,
                    enabled = updateEnabled,
                    width = 138.dp,
                    onClick = onUpdate
                )
                HudButton(copy.updateClose, palette, width = 138.dp, onClick = onClose)
            }
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun AvailableUpdateNotes(copy: Copy, palette: Palette, version: String, notes: String) {
    Column {
        Text(
            "${copy.updateAvailableVersion} v$version",
            color = palette.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        MarkdownPatchNotesText(notes, palette)
    }
}

@Composable
//render release-note markdown locally so update UI stays dependency-free and predictable.
private fun MarkdownPatchNotesText(text: String, palette: Palette) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text
            .replace("\r\n", "\n")
            .lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) {
            Text("", color = palette.muted, fontSize = 13.sp)
        } else {
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isBlank() -> Spacer(Modifier.height(6.dp))
                    line == "---" -> HorizontalDivider(color = palette.border)
                    line.startsWith("### ") -> MarkdownTextLine(
                        text = line.removePrefix("### ").trim(),
                        palette = palette,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    line.startsWith("## ") -> Text(
                        markdownInline(line.removePrefix("## ").trim(), palette),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.text
                    )
                    line.startsWith("# ") -> MarkdownTextLine(
                        text = line.removePrefix("# ").trim(),
                        palette = palette,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    line.startsWith("- ") || line.startsWith("* ") -> MarkdownBulletLine(
                        bullet = "•",
                        text = line.drop(2).trim(),
                        palette = palette
                    )
                    ORDERED_LIST_REGEX.containsMatchIn(line) -> {
                        val match = ORDERED_LIST_REGEX.find(line)
                        MarkdownBulletLine(
                            bullet = (match?.groupValues?.getOrNull(1) ?: "") + ".",
                            text = line.replaceFirst(ORDERED_LIST_REGEX, "").trim(),
                            palette = palette
                        )
                    }
                    else -> MarkdownTextLine(
                        text = line,
                        palette = palette,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

private val ORDERED_LIST_REGEX = Regex("""^(\d+)\.\s+""")

@Composable
//render list rows with stable indentation so long release-note items wrap cleanly.
private fun MarkdownBulletLine(bullet: String, text: String, palette: Palette) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(bullet, color = palette.text, fontSize = 13.sp, lineHeight = 18.sp)
        Text(
            markdownInline(text, palette),
            color = palette.text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
//render one markdown paragraph without supporting full GitHub-flavored markdown.
private fun MarkdownTextLine(
    text: String,
    palette: Palette,
    fontSize: TextUnit,
    fontWeight: FontWeight
) {
    Text(
        markdownInline(text, palette),
        color = palette.text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = 18.sp
    )
}

//parse only the inline subset used by BYD HUD release notes: bold and code.
private fun markdownInline(text: String, palette: Palette): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val bold = text.indexOf("**", index)
        val code = text.indexOf("`", index)
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull() ?: -1
        if (next < 0) {
            append(text.substring(index))
            break
        }
        if (next > index) {
            append(text.substring(index, next))
        }
        if (next == bold) {
            val end = text.indexOf("**", next + 2)
            if (end < 0) {
                append(text.substring(next))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(next + 2, end))
            }
            index = end + 2
        } else {
            val end = text.indexOf("`", next + 1)
            if (end < 0) {
                append(text.substring(next))
                break
            }
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = palette.disabled,
                    color = palette.text
                )
            ) {
                append(text.substring(next + 1, end))
            }
            index = end + 1
        }
    }
}

@Composable
//updates shared state here so freshness and lifecycle checks use the same evidence.
private fun UpdateProgressBar(progress: String, palette: Palette) {
    val percent = progress.removeSuffix("%").toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.disabled)
            .border(1.dp, palette.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((percent / 100f).coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .background(palette.accent)
        )
        Text(
            "${percent.toInt()}%",
            color = palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
//asks once before deleting folders so a miss-click cannot start destructive cleanup.
private fun StorageDeleteConfirmOverlay(
    copy: Copy,
    palette: Palette,
    folderCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (palette.dark) 0.48f else 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(copy.storageDeleteTitle, color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    String.format(Locale.US, copy.storageDeleteSelected, folderCount),
                    color = palette.yellow,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(copy.storageDeleteQuestion, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(copy.storageDeleteCannotStop, color = palette.muted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudButton(copy.storageDeleteYes, palette, primary = true, width = 138.dp, onClick = onConfirm)
                HudButton(copy.storageDeleteNo, palette, width = 138.dp, onClick = onDismiss)
            }
        }
    }
}

@Composable
//keeps deletion visibly alive while the filesystem work happens off the UI thread.
private fun StorageDeleteOverlay(
    copy: Copy,
    palette: Palette,
    folderName: String,
    step: Int,
    total: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (palette.dark) 0.48f else 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(copy.storageDeleteTitle, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingSpinner(palette)
                    Spacer(Modifier.width(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            String.format(Locale.US, copy.storageDeleteStep, step.coerceAtLeast(1), total.coerceAtLeast(1)),
                            color = palette.muted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${copy.storageDeletingFolder} ",
                                color = palette.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                folderName,
                                color = palette.yellow,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HudButton(copy.updateClose, palette, enabled = false, width = 138.dp, onClick = {})
            }
        }
    }
}

@Composable
//draws an indeterminate spinner without adding a progress pre-scan or extra filesystem work.
private fun LoadingSpinner(palette: Palette) {
    val transition = rememberInfiniteTransition(label = "storageDeleteSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "storageDeleteSpinnerAngle"
    )
    Canvas(modifier = Modifier.size(22.dp)) {
        drawArc(
            color = palette.accent,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 3.4f, cap = StrokeCap.Round)
        )
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun SetupInstructionBlock(
    palette: Palette,
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.yellowSoft)
            .border(
                1.dp,
                palette.yellow.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp)
            )
            .padding(14.dp)
    ) {
        Text(
            text = text,
            color = if (palette.dark) palette.yellow else palette.text,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun AppsTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit
) {
    LazyPageSurface(copy.apps, copy.appsHint, palette, headerAction = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("${copy.lastScan}: ${snapshot.lastScanText}", palette.muted, palette.disabled)
            HudButton(copy.refreshApps, palette, primary = true, width = 178.dp) {
                runAction { activity.composeRefreshApps() }
            }
        }
    }) {
        item(key = "supported-apps") {
            Section(copy.supportedApps, palette) {
                snapshot.supportedApps.forEachIndexed { index, row ->
                    if (index > 0) Divider(palette)
                    AppRow(row, copy, palette, supported = true, activity = activity, runAction = runAction)
                }
            }
        }

        item(key = "all-apps") {
            Section(copy.allApps, palette) {
                if (snapshot.allApps.isEmpty()) {
                    Text(copy.noBackgroundApps, color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
                } else {
                    snapshot.allApps.forEachIndexed { index, row ->
                        if (index > 0) Divider(palette)
                        AppRow(row, copy, palette, supported = false, activity = activity, runAction = runAction)
                    }
                }
            }
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun AppRow(
    row: MainActivity.ComposeAppRow,
    copy: Copy,
    palette: Palette,
    supported: Boolean,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit
) {
    val dashboardEnabled = row.runtimeBacked && row.dashboardStateKnown && !row.dashboardMoveInProgress
    val runningForStatus = row.runtimeBacked
    val dashboardText = when {
        !row.runtimeBacked -> copy.startAppFirst
        row.onDashboard -> copy.sendMain
        else -> copy.sendDashboard
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(appLabel(row), color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            row.packageVersions.forEach { packageVersion ->
                val versionSuffix = packageVersion.versionName
                    .takeIf { it.isNotBlank() }
                    ?.let { "  •  ${copy.appVersion} $it" }
                    .orEmpty()
                Text(
                    "${packageVersion.packageName}$versionSuffix",
                    color = palette.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (supported) {
                    StatusChip(if (row.installed) copy.installed else copy.notInstalled,
                        if (row.installed) ChipKind.Green else ChipKind.Red, palette, width = 116.dp)
                }
                StatusChip(if (runningForStatus) copy.running else copy.notRunning,
                    if (runningForStatus) ChipKind.Green else ChipKind.Yellow, palette, width = 174.dp)
                StatusChip(
                    when {
                        supported && row.supportedHud -> copy.supported
                        supported -> copy.logCandidate
                        else -> copy.logCandidate
                    },
                    if (row.supportedHud) ChipKind.Green else ChipKind.Neutral,
                    palette,
                    width = 132.dp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (supported) {
                CompactSwitchBox(copy.hud, row.hudEnabled, palette, width = 150.dp) {
                    runAction { activity.composeSetHudForPackage(row.packageName, it) }
                }
            }
            CompactSwitchBox(copy.log, row.logOnlyEnabled, palette, width = 150.dp) {
                runAction { activity.composeSetLogOnlyForPackage(row.packageName, it) }
            }
            HudButton(
                dashboardText,
                palette,
                primary = row.runtimeBacked && !row.onDashboard,
                enabled = dashboardEnabled,
                width = 220.dp
            ) {
                runAction { activity.composeMoveDashboard(row.packageName, !row.onDashboard) }
            }
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun LogsTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit
) {
    LazyPageSurface(copy.logs, copy.logsHint, palette) {
        item(key = "log-status") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Section(copy.logcatRecorder, palette, modifier = Modifier.weight(1f).heightIn(min = 204.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(copy.recorderStatus, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(snapshot.logcatStatus.ifBlank { copy.waiting }, color = palette.muted, fontSize = 13.sp)
                    }
                    Pill(if (snapshot.logcatRecording) "recording" else copy.waiting, palette.yellow, palette.yellowSoft)
                }
                Divider(palette)
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HudButton(
                        copy.startLogcat,
                        palette,
                        primary = !snapshot.logcatRecording,
                        enabled = !snapshot.logcatRecording,
                        width = 0.dp,
                        modifier = Modifier.weight(0.65f)
                    ) {
                        runAction { activity.composeStartLogcat() }
                    }
                    HudButton(
                        copy.stopLogcat,
                        palette,
                        primary = snapshot.logcatRecording,
                        enabled = snapshot.logcatRecording,
                        width = 0.dp,
                        modifier = Modifier.weight(0.35f)
                    ) {
                        runAction { activity.composeStopLogcat() }
                    }
                }
                }
                Section(copy.applicationState, palette, modifier = Modifier.weight(1f).heightIn(min = 204.dp)) {
                    CodeBlock(snapshot.applicationState, palette, compact = true, modifier = Modifier.padding(14.dp))
                }
            }
        }

        item(key = "navigation-log-paths") {
            Section(copy.navigationLogs, palette) {
                CodeBlock(snapshot.logPaths + "\n\n" + copy.pathHint, palette, compact = true, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun StorageTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    sortOldestFirst: Boolean,
    selectedDays: List<String>,
    storageBusy: Boolean,
    onStorageLimitGb: (Int) -> Unit,
    onSortOldestFirst: (Boolean) -> Unit,
    onToggleDay: (String) -> Unit,
    onDeleteSelected: (List<String>) -> Unit,
    onShareSelected: (List<String>) -> Unit
) {
    var draftLimit by rememberSaveable(snapshot.storageLimitGb) {
        mutableIntStateOf(snapshot.storageLimitGb)
    }
    val days = if (sortOldestFirst) {
        snapshot.storageDays.sortedBy { it.name }
    } else {
        snapshot.storageDays.sortedByDescending { it.name }
    }
    val selectedDayNames = selectedDays.filter { selected ->
        days.any { it.name == selected }
    }
    LazyPageSurface(copy.storage, copy.storageHint, palette) {
        item(key = "storage-settings") {
            Section(copy.storageSettings, palette) {
                SettingRow(
                title = copy.navLogsFolderLimit,
                hint = copy.navLogsFolderLimitHint,
                palette = palette,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                        ReadOnlyValueField(
                            label = copy.storageLimitGb,
                            value = "$draftLimit ${gbUnit(copy)}",
                            palette = palette,
                            modifier = Modifier.width(150.dp)
                        )
                        HudButton(
                            "OK",
                            palette,
                            primary = true,
                            enabled = draftLimit != snapshot.storageLimitGb,
                            width = 190.dp
                        ) {
                            onStorageLimitGb(draftLimit)
                        }
                    }
                }
                )
                StorageLimitSlider(draftLimit, palette) { next ->
                    draftLimit = next
                }
                Divider(palette)
                SettingRow(
                title = copy.currentNavLogsSize,
                hint = "",
                palette = palette,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (snapshot.storageCalculating) {
                            Pill(copy.storageCalculating, palette.muted, palette.disabled)
                        }
                        val usageColors = storageUsageColors(snapshot.navCaptureFolderBytes, snapshot.storageLimitGb, palette)
                        Pill(
                            formatStorageUsage(snapshot.navCaptureFolderBytes, snapshot.storageLimitGb, copy),
                            usageColors.first,
                            usageColors.second
                        )
                        Text(
                            "(${snapshot.storageSessionCount} ${copy.storageSessionsShort})",
                            color = palette.muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    }
                )
            }
        }

        item(key = "navigation-log-controls") {
            Section(copy.navigationLogsFolder, palette) {
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    snapshot.navCaptureFolderPaths.forEachIndexed { index, path ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        ReadOnlyPathField(path, palette, modifier = Modifier.fillMaxWidth())
                    }
                }
                HudIconButton(
                    icon = R.drawable.ic_share,
                    contentDescription = copy.shareSelected,
                    palette = palette,
                    tint = palette.accent,
                    enabled = selectedDayNames.isNotEmpty() && !storageBusy,
                    onClick = { onShareSelected(selectedDayNames) }
                )
                HudButton(
                    if (sortOldestFirst) copy.sortByName else copy.sortByDate,
                    palette,
                    primary = false,
                    enabled = !storageBusy,
                    width = 190.dp
                ) {
                    onSortOldestFirst(!sortOldestFirst)
                }
                }
                if (days.isEmpty()) {
                    Divider(palette)
                    Text(
                        copy.storageNoDayFolders,
                        color = palette.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        items(
            count = days.size,
            key = { index -> "storage-day-${days[index].name}" }
        ) { index ->
            val day = days[index]
            StorageDayRow(
                day = day,
                copy = copy,
                palette = palette,
                selected = selectedDays.contains(day.name),
                enabled = !storageBusy,
                onToggle = { onToggleDay(day.name) }
            )
        }

        item(key = "storage-delete-action") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                HudButton(
                    copy.deleteSelected,
                    palette,
                    enabled = selectedDayNames.isNotEmpty() && !storageBusy,
                    primary = true,
                    width = 190.dp,
                    onClick = { onDeleteSelected(selectedDayNames) }
                )
            }
        }
    }
}

@Composable
private fun PatchTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot
) {
    val waze = snapshot.patchWaze?.takeIf { it.installed }
    LazyPageSurface(copy.patchTab, copy.patchHint, palette) {
        item(key = "patch-warning") {
            Section(copy.patchWarning, palette) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(copy.patchWarningText, color = palette.text, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(copy.patchRiskWarning, color = palette.yellow, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    RepositoryLink(palette)
                }
            }
        }

        item(key = "available-navigators") {
            Section(copy.availableNavigators, palette) {
                if (waze == null) {
                    Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                    ) {
                        Text(copy.noSupportedNavigators, color = palette.muted, fontSize = 14.sp)
                    }
                } else {
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                        .background(palette.field)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(waze.label, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(waze.packageName, color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("${copy.appVersion}: ${waze.versionName}", color = palette.muted, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            StatusChip(copy.patchNotChecked, ChipKind.Neutral, palette, width = 150.dp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HudButton(copy.checkPatch, palette, enabled = false, width = 170.dp, onClick = {})
                            HudButton(copy.applyPatch, palette, enabled = false, width = 170.dp, onClick = {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryLink(palette: Palette) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = PROJECT_REPOSITORY_URL,
        color = palette.accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable { uriHandler.openUri(PROJECT_REPOSITORY_URL) }
    )
}

@Composable
private fun PatchConfirmOverlay(
    copy: Copy,
    palette: Palette,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (palette.dark) 0.48f else 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(copy.patchConfirmTitle, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(copy.patchConfirmText, color = palette.text, fontSize = 15.sp, lineHeight = 21.sp)
                Text(copy.patchRiskWarning, color = palette.yellow, fontSize = 15.sp, lineHeight = 21.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                HudButton(copy.patchConfirmOk, palette, primary = true, width = 138.dp, onClick = onConfirm)
                HudButton(copy.patchConfirmCancel, palette, width = 138.dp, onClick = onDismiss)
            }
        }
    }
}

@Composable
//renders a disabled value field for values that must be committed through a separate action.
private fun ReadOnlyValueField(
    label: String,
    value: String,
    palette: Palette,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            label,
            color = palette.muted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                .background(palette.field),
            contentAlignment = Alignment.Center
        ) {
            Text(value, color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
//renders the public storage path without implying it is editable.
private fun ReadOnlyPathField(text: String, palette: Palette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .background(palette.field)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = palette.muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
//guards storage-limit selection with a real draggable control and no zero-weight endpoint layout.
private fun StorageLimitSlider(
    limit: Int,
    palette: Palette,
    onLimit: (Int) -> Unit
) {
    val coerced = limit.coerceIn(1, 10)
    var sliderValue by remember(coerced) { mutableStateOf(coerced.toFloat()) }
    Slider(
        value = sliderValue,
        onValueChange = { raw ->
            sliderValue = raw.coerceIn(1f, 10f)
        },
        onValueChangeFinished = {
            val next = storageLimitFromSliderValue(sliderValue)
            sliderValue = next.toFloat()
            onLimit(next)
        },
        valueRange = 1f..10f,
        steps = 8,
        colors = SliderDefaults.colors(
            thumbColor = if (palette.dark) Color(0xFFD9ECFF) else Color.White,
            activeTrackColor = palette.accent,
            inactiveTrackColor = palette.disabled
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
    )
}

//guards persisted storage limit from drag/tap float noise before it reaches prefs.
private fun storageLimitFromSliderValue(value: Float): Int =
    value.roundToInt().coerceIn(1, 10)

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun StorageDayRow(
    day: MainActivity.ComposeStorageDay,
    copy: Copy,
    palette: Palette,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onToggle)
    val baseBackground = if (selected) palette.active else palette.panelAlt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) palette.accent else palette.border, RoundedCornerShape(8.dp))
            .background(pressBackground(baseBackground, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(day.name, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "${copy.created}: ${day.createdLabel}  •  ${day.sessions} ${sessionLabel(day.sessions, copy)}  •  ${storageLocationLabel(day, copy)}",
                color = palette.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Pill(formatBytes(day.bytes, copy), palette.muted, palette.disabled)
        if (day.active) {
            Spacer(Modifier.width(8.dp))
            Pill(copy.activeToday, palette.green, palette.greenSoft)
        }
        Spacer(Modifier.width(8.dp))
        Pill(
            if (selected) copy.folderSelected else copy.folderNotSelected,
            if (selected) palette.accent else palette.muted,
            if (selected) palette.active else palette.disabled
        )
    }
}

private fun storageLocationLabel(day: MainActivity.ComposeStorageDay, copy: Copy): String = when {
    day.hasPublicStorage && day.hasPrivateStorage -> copy.bothStorageLocations
    day.hasPublicStorage -> copy.publicStorageLocation
    else -> copy.privateStorageLocation
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun ManualTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(ManualMode.Supported) }
    var pngNumber by rememberSaveable(snapshot.pngSourceId) { mutableStateOf(snapshot.pngSourceId.coerceIn(1, 99).toString()) }
    var nativeNumber by rememberSaveable(snapshot.nativeManeuverId) { mutableStateOf(snapshot.nativeManeuverId.coerceIn(1, 99).toString()) }
    var distance by rememberSaveable(snapshot.distanceMeters) { mutableStateOf(snapshot.distanceMeters.coerceIn(0, 99999).toString()) }
    var street by rememberSaveable(snapshot.streetText) { mutableStateOf(snapshot.streetText.ifBlank { "TESTER" }) }
    var rawLane by rememberSaveable(snapshot.laneBitmap) { mutableStateOf(snapshot.laneBitmap.ifBlank { defaultLanePayload }) }
    var manualLane by rememberSaveable { mutableStateOf(defaultLanePayload) }
    var laneIndex by rememberSaveable { mutableIntStateOf(0) }

    //sends encoded data here so transport side effects stay behind a single boundary.
    fun sendRaw() {
        if (snapshot.manualModeEnabled) {
            activity.composeSendRaw(
                manualNumber(pngNumber, 5),
                manualNumber(nativeNumber, 5),
                distance.toIntOrNull()?.coerceIn(0, 99999) ?: 230,
                street,
                rawLane
            )
        }
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    fun sendManualLane(value: String) {
        if (snapshot.manualModeEnabled) {
            activity.composeSendManualLane(value)
        }
    }

    LazyPageSurface(copy.manual, copy.manualHint, palette) {
        item(key = "manual-hud-output") {
            Section(copy.manualHudOutput, palette) {
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ManualModeTile(copy.supportedArrows, copy.supportedArrowsHint, mode == ManualMode.Supported, palette, Modifier.weight(1f)) {
                    mode = ManualMode.Supported
                }
                ManualModeTile(copy.manualLanes, copy.manualLanesHint, mode == ManualMode.Lanes, palette, Modifier.weight(1f)) {
                    mode = ManualMode.Lanes
                }
                ManualModeTile(copy.rawManeuverIds, copy.rawManeuverHint, mode == ManualMode.Raw, palette, Modifier.weight(1f)) {
                    mode = ManualMode.Raw
                }
                }
                SwitchRow(copy.manualMode, copy.manualModeHint, snapshot.manualModeEnabled, palette) {
                    runAction { activity.composeSetManualMode(it) }
                }
                Divider(palette)

                when (mode) {
                ManualMode.Supported -> ActionRow(
                    copy.supportedArrows,
                    copy.supportedArrowsHint,
                    palette,
                    left = {
                        HudButton(copy.previous, palette, width = 150.dp) {
                            runAction { activity.composeStepCurated(-1) }
                        }
                    },
                    right = {
                        HudButton(copy.next, palette, width = 150.dp) {
                            runAction { activity.composeStepCurated(1) }
                        }
                    }
                )
                ManualMode.Lanes -> {
                    ActionRow(
                        copy.manualLanes,
                        copy.manualLanesHint,
                        palette,
                        left = {
                            HudButton(previousPlural(copy), palette, width = 150.dp) {
                                val next = stepLane(manualLane, laneIndex, -1)
                                laneIndex = next.first
                                manualLane = next.second
                                sendManualLane(manualLane)
                            }
                        },
                        right = {
                            HudButton(nextPlural(copy), palette, width = 150.dp) {
                                val next = stepLane(manualLane, laneIndex, 1)
                                laneIndex = next.first
                                manualLane = next.second
                                sendManualLane(manualLane)
                            }
                        }
                    )
                    ManualLaneFieldRow(copy, palette, manualLane, { manualLane = it }, onPrevious = {
                        val next = stepLane(manualLane, laneIndex, -1)
                        laneIndex = next.first
                        manualLane = next.second
                        sendManualLane(manualLane)
                    }, onNext = {
                        val next = stepLane(manualLane, laneIndex, 1)
                        laneIndex = next.first
                        manualLane = next.second
                        sendManualLane(manualLane)
                    }, onRandom = {
                        val next = stepLane(manualLane, laneIndex, 1)
                        laneIndex = next.first
                        manualLane = next.second
                        sendManualLane(manualLane)
                    })
                }
                ManualMode.Raw -> {
                    RawFields(
                        copy = copy,
                        palette = palette,
                        pngNumber = pngNumber,
                        onPng = { pngNumber = it },
                        nativeNumber = nativeNumber,
                        onNative = { nativeNumber = it },
                        distance = distance,
                        onDistance = { distance = it },
                        street = street,
                        onStreet = { street = it },
                        lane = rawLane,
                        onLane = { rawLane = it },
                        onPngPrev = {
                            pngNumber = stepNumber(pngNumber, -1, 5)
                            sendRaw()
                        },
                        onPngNext = {
                            pngNumber = stepNumber(pngNumber, 1, 5)
                            sendRaw()
                        },
                        onNativePrev = {
                            nativeNumber = stepNumber(nativeNumber, -1, 5)
                            sendRaw()
                        },
                        onNativeNext = {
                            nativeNumber = stepNumber(nativeNumber, 1, 5)
                            sendRaw()
                        },
                        onLanePrev = {
                            val next = stepLane(rawLane, laneIndex, -1)
                            laneIndex = next.first
                            rawLane = next.second
                            sendRaw()
                        },
                        onLaneNext = {
                            val next = stepLane(rawLane, laneIndex, 1)
                            laneIndex = next.first
                            rawLane = next.second
                            sendRaw()
                        },
                        onRandom = {
                            val next = stepLane(rawLane, laneIndex, 1)
                            laneIndex = next.first
                            rawLane = next.second
                            sendRaw()
                        }
                    )
                }
                }

                Divider(palette)
                CurrentSelection(copy, palette, when (mode) {
                    ManualMode.Supported -> "#${snapshot.curatedIndex + 1}/${snapshot.curatedCount}: S${snapshot.pngSourceId.toString().padStart(2, '0')} / N${snapshot.nativeManeuverId.toString().padStart(2, '0')}"
                    ManualMode.Lanes -> manualLane
                    ManualMode.Raw -> "Raw ${manualId("S", pngNumber, 5)} / ${manualId("N", nativeNumber, 5)} / ${distance}m / $street"
                })
            }
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun RawFields(
    copy: Copy,
    palette: Palette,
    pngNumber: String,
    onPng: (String) -> Unit,
    nativeNumber: String,
    onNative: (String) -> Unit,
    distance: String,
    onDistance: (String) -> Unit,
    street: String,
    onStreet: (String) -> Unit,
    lane: String,
    onLane: (String) -> Unit,
    onPngPrev: () -> Unit,
    onPngNext: () -> Unit,
    onNativePrev: () -> Unit,
    onNativeNext: () -> Unit,
    onLanePrev: () -> Unit,
    onLaneNext: () -> Unit,
    onRandom: () -> Unit
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledInput(copy.pngNumber, pngNumber, onPng, palette, Modifier.weight(1f))
            LabeledInput(copy.nativeNumber, nativeNumber, onNative, palette, Modifier.weight(1f))
            LabeledInput(copy.distance, distance, onDistance, palette, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton(copy.previous, palette, width = 0.dp, modifier = Modifier.weight(1f), onClick = onPngPrev)
                HudButton(copy.next, palette, width = 0.dp, modifier = Modifier.weight(1f), onClick = onPngNext)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton(copy.previous, palette, width = 0.dp, modifier = Modifier.weight(1f), onClick = onNativePrev)
                HudButton(copy.next, palette, width = 0.dp, modifier = Modifier.weight(1f), onClick = onNativeNext)
            }
            LabeledInput(copy.street, street, onStreet, palette, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        ManualLaneFieldRow(copy, palette, lane, onLane, onLanePrev, onLaneNext, onRandom)
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun ManualLaneFieldRow(
    copy: Copy,
    palette: Palette,
    value: String,
    onValue: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRandom: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        LabeledInput(copy.laneBitmap, value, onValue, palette, Modifier.weight(1f))
        HudButton(previousPlural(copy), palette, width = 150.dp, onClick = onPrevious)
        HudButton(nextPlural(copy), palette, width = 150.dp, onClick = onNext)
        HudButton(copy.randomize, palette, width = 150.dp, onClick = onRandom)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun CurrentSelection(copy: Copy, palette: Palette, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(copy.currentSelection, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(value, color = palette.muted, fontSize = 14.sp)
        }
        Pill(copy.manualPreview, palette.muted, palette.disabled)
    }
}

@Composable
private fun LazyPageSurface(
    title: String,
    hint: String,
    palette: Palette,
    headerAction: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "page-header") {
            PageSurfaceHeader(title, hint, palette, headerAction)
            Spacer(Modifier.height(4.dp))
        }
        content()
    }
}

@Composable
private fun PageSurfaceHeader(
    title: String,
    hint: String,
    palette: Palette,
    headerAction: (@Composable () -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Text(hint, color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        headerAction?.invoke()
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun Section(
    title: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.panelAlt)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(title.uppercase(), color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        content()
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun SettingRow(
    title: String,
    hint: String,
    palette: Palette,
    action: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (hint.isNotBlank()) {
                Text(hint, color = palette.muted, fontSize = 13.sp)
            }
        }
        action()
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    palette: Palette,
    onChecked: (Boolean) -> Unit
) {
    SettingRow(title, hint, palette) {
        HudSwitch(checked, onChecked, palette)
    }
}

@Composable
//updates shared state here so freshness and lifecycle checks use the same evidence.
private fun UpdateCheckLine(
    title: String,
    hint: String,
    buttonText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onCheckClick: () -> Unit,
    palette: Palette
) {
    SettingRow(title, hint, palette) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HudButton(buttonText, palette, width = 190.dp, onClick = onCheckClick)
            HudSwitch(checked, onCheckedChange, palette)
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun ActionRow(
    title: String,
    hint: String,
    palette: Palette,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(hint, color = palette.muted, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            left()
            right()
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun ManualModeTile(
    title: String,
    hint: String,
    selected: Boolean,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    val baseBackground = if (selected) palette.active else palette.panelAlt
    Column(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) palette.accent else palette.border, RoundedCornerShape(8.dp))
            .background(pressBackground(baseBackground, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(hint, color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun LabeledInput(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    palette: Palette,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(label, color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = palette.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                .background(palette.field),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    inner()
                }
            }
        )
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun CodeBlock(text: String, palette: Palette, compact: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.border, RoundedCornerShape(7.dp))
            .background(if (palette.dark) Color(0xFF0C1219) else Color(0xFFF7FAFD))
            .padding(if (compact) 10.dp else 14.dp)
    ) {
        Text(text, color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun Divider(palette: Palette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.border)
    )
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun HudButton(
    text: String,
    palette: Palette,
    primary: Boolean = false,
    enabled: Boolean = true,
    width: Dp = 150.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val base = if (width == 0.dp) modifier.height(44.dp) else modifier.width(width).height(44.dp)
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    val baseBackground = when {
        !enabled -> palette.disabled
        primary -> palette.accent.copy(alpha = if (palette.dark) 0.82f else 0.08f)
        else -> palette.panelAlt
    }
    Box(
        modifier = base
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, if (primary) palette.accent else palette.borderStrong, RoundedCornerShape(7.dp))
            .background(pressBackground(baseBackground, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                !enabled -> palette.muted.copy(alpha = 0.55f)
                primary && palette.dark -> Color.White
                else -> palette.text
            },
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
//keeps destructive icon actions visually distinct while reusing the same tap scale feedback.
private fun HudIconButton(
    @DrawableRes
    icon: Int,
    contentDescription: String,
    palette: Palette,
    tint: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    val baseBackground = tint.copy(alpha = if (palette.dark) 0.20f else 0.12f)
    val pressedBackground = tint.copy(alpha = if (palette.dark) 0.88f else 0.72f)
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, tint.copy(alpha = 0.85f), RoundedCornerShape(7.dp))
            .background(if (press.pressed) pressedBackground else baseBackground)
            .then(press.modifier)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = contentDescription,
            tint = when {
                !enabled -> palette.muted.copy(alpha = 0.55f)
                press.pressed -> Color.White
                else -> tint
            },
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun CompactSwitchBox(
    label: String,
    checked: Boolean,
    palette: Palette,
    width: Dp,
    onChecked: (Boolean) -> Unit
) {
    val switchControl = remember { mutableStateOf<SwitchExternalControl?>(null) }
    val rowEnabled = switchControl.value?.pending != true
    val press = rememberPressFeedback(rowEnabled)
    val visualClick = rememberVisualFirstClick {
        switchControl.value?.trigger?.invoke()
    }
    Row(
        modifier = Modifier
            .width(width)
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .background(pressBackground(palette.panelAlt, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                enabled = rowEnabled,
                interactionSource = press.interactionSource,
                indication = null
            ) { visualClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        HudSwitch(checked, onChecked, palette, compact = true, externalControl = switchControl)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun HudSwitch(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    palette: Palette,
    compact: Boolean = false,
    externalControl: MutableState<SwitchExternalControl?>? = null
) {
    val width = if (compact) 42.dp else 56.dp
    val height = if (compact) 27.dp else 32.dp
    val knobOff = if (compact) 16.dp else 19.dp
    val knobPending = if (compact) 18.dp else 22.dp
    val knobOn = if (compact) 20.dp else 25.dp
    val pendingHolder = remember { mutableStateOf<SwitchPendingState?>(null) }
    val pendingState = pendingHolder.value
    val isPending = pendingState != null
    val press = rememberPressFeedback(!isPending)
    val scope = rememberCoroutineScope()
    val latestOnChecked by rememberUpdatedState(onChecked)
    val latestChecked by rememberUpdatedState(checked)
    val triggerToggle = remember(scope) {
        {
            if (pendingHolder.value == null) {
                val from = latestChecked
                val target = !from
                pendingHolder.value = SwitchPendingState(
                    from = from,
                    target = target,
                    startedAtMs = SystemClock.elapsedRealtime()
                )
                scope.launch {
                    delay(SWITCH_CENTER_BEFORE_ACTION_MS)
                    latestOnChecked(target)
                    val deadline = SystemClock.elapsedRealtime() + SWITCH_PENDING_TIMEOUT_MS
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (latestChecked == target) {
                            pendingHolder.value = null
                            return@launch
                        }
                        delay(50L)
                    }
                    pendingHolder.value = null
                }
            }
        }
    }
    val trackChecked = pendingState?.from ?: checked
    val knobSize by animateDpAsState(
        targetValue = when {
            isPending -> knobPending
            checked -> knobOn
            else -> knobOff
        },
        animationSpec = tween(durationMillis = 140),
        label = "switchKnobSize"
    )
    val knobOffset by animateDpAsState(
        targetValue = when {
            isPending -> (width - knobPending) / 2f
            checked -> width - knobOn - 3.dp
            else -> 3.dp
        },
        animationSpec = tween(durationMillis = 140),
        label = "switchKnobOffset"
    )
    SideEffect {
        externalControl?.value = SwitchExternalControl(triggerToggle, isPending)
    }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(100.dp))
            .background(pressBackground(if (trackChecked) palette.accent else palette.disabled, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                enabled = !isPending,
                interactionSource = press.interactionSource,
                indication = null
            ) { triggerToggle() }
            .padding(0.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .offset(x = knobOffset, y = 0.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(if (trackChecked) Color(0xFFD9ECFF) else Color(0xFFD8E3EE))
        )
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun Segmented(
    left: String,
    right: String,
    leftActive: Boolean,
    palette: Palette,
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(22.dp))
            .background(palette.panelAlt)
            .padding(5.dp)
    ) {
        SegmentedItem(left, leftActive, palette, onLeft)
        SegmentedItem(right, !leftActive, palette, onRight)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun SegmentedItem(text: String, active: Boolean, palette: Palette, onClick: () -> Unit) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    val baseBackground = if (active) palette.accent else Color.Transparent
    Box(
        modifier = Modifier
            .height(32.dp)
            .width(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(pressBackground(baseBackground, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) Color.White else palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun Pill(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun StatusChip(text: String, kind: ChipKind, palette: Palette, width: Dp) {
    val colors = when (kind) {
        ChipKind.Green -> palette.green to palette.greenSoft
        ChipKind.Yellow -> palette.yellow to palette.yellowSoft
        ChipKind.Red -> palette.red to palette.redSoft
        ChipKind.Neutral -> palette.muted to palette.disabled
    }
    Pill(text, colors.first, colors.second, Modifier.width(width))
}

//defines class UI/state support so Compose code can keep rendering intent explicit.
private enum class ChipKind {
    Green,
    Yellow,
    Red,
    Neutral
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun HudStatusPill(status: String, copy: Copy, palette: Palette) {
    val normalized = status.lowercase()
    val text = when (normalized) {
        "running" -> copy.hudRunning
        "failed" -> copy.hudFailed
        else -> copy.hudIdle
    }
    val colors = when (normalized) {
        "running" -> palette.green to palette.greenSoft
        "failed" -> palette.red to palette.redSoft
        else -> palette.muted to palette.disabled
    }
    Pill(text, colors.first, colors.second)
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun BottomTabs(copy: Copy, palette: Palette, selected: RuntimeTab, onSelect: (RuntimeTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabButton(copy.apps, RuntimeTab.Apps, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.main, RuntimeTab.Options, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.storage, RuntimeTab.Storage, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.patch, RuntimeTab.Patch, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.logs, RuntimeTab.Logs, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.manual, RuntimeTab.Manual, selected, palette, Modifier.weight(1f), onSelect)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun TabButton(
    text: String,
    tab: RuntimeTab,
    selected: RuntimeTab,
    palette: Palette,
    modifier: Modifier,
    onSelect: (RuntimeTab) -> Unit
) {
    val active = tab == selected
    val press = rememberPressFeedback()
    val baseBackground = if (active) palette.active else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (active) palette.accent else Color.Transparent, RoundedCornerShape(6.dp))
            .background(pressBackground(baseBackground, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null
            ) { onSelect(tab) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TabIcon(tab, palette, active)
        Spacer(Modifier.width(9.dp))
        Text(text, color = if (active) palette.text else palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun TabIcon(tab: RuntimeTab, palette: Palette, active: Boolean) {
    Icon(
        painter = painterResource(id = iconFor(tab)),
        contentDescription = null,
        tint = if (active) palette.text else palette.muted,
        modifier = Modifier.size(20.dp)
    )
}

//keeps tab icon resources local so production does not pull the full material-icons-extended dex payload.
@DrawableRes
private fun iconFor(tab: RuntimeTab): Int = when (tab) {
    RuntimeTab.Options -> R.drawable.ic_tab_options
    RuntimeTab.Apps -> R.drawable.ic_tab_apps
    RuntimeTab.Logs -> R.drawable.ic_tab_logs
    RuntimeTab.Storage -> R.drawable.ic_tab_storage
    RuntimeTab.Patch -> R.drawable.ic_tab_patch
    RuntimeTab.Manual -> R.drawable.ic_tab_manual
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun appLabel(row: MainActivity.ComposeAppRow): String {
    return when (row.packageName) {
        "com.waze" -> "Waze"
        "com.google.android.apps.maps", "app.revanced.android.apps.maps" -> "Google Maps"
        "com.iternio.abrpapp" -> "A Better Routeplanner"
        else -> row.label.ifBlank { row.packageName }
    }
}

private const val defaultLanePayload = "S* | S | S* | S | S*"

private val lanePayloadSamples = listOf(
    defaultLanePayload,
    "L | S* | S*+R",
    "S | S | Rs*",
    "Ls | S*+Ls | S* | S*+R",
    "L | S*+L | S* | S* | R"
)

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun validLane(value: String): Boolean {
    val cells = value.split("|").map { it.trim() }
    return cells.isNotEmpty() && cells.all { it.isNotEmpty() }
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun stepLane(current: String, index: Int, delta: Int): Pair<Int, String> {
    val base = if (validLane(current)) index else 0
    val next = (base + delta + lanePayloadSamples.size) % lanePayloadSamples.size
    return next to lanePayloadSamples[next]
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun manualNumber(value: String, defaultValue: Int): Int =
    value.trim().toIntOrNull()?.takeIf { it in 1..99 } ?: defaultValue

private fun stepNumber(value: String, delta: Int, defaultValue: Int): String {
    val zeroBased = manualNumber(value, defaultValue) - 1
    return ((zeroBased + delta + 99) % 99 + 1).toString()
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun manualId(prefix: String, value: String, defaultValue: Int): String =
    "$prefix${manualNumber(value, defaultValue).toString().padStart(2, '0')}"

private fun sanitizeStorageLimitInput(value: String): String {
    val digits = value.filter { it.isDigit() }.take(2)
    if (digits.isEmpty()) return ""
    return digits.toIntOrNull()?.coerceIn(1, 10)?.toString() ?: "5"
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun formatBytes(bytes: Long, copy: Copy): String {
    val gb = bytes / 1_000_000_000.0
    val mb = bytes / 1_000_000.0
    return if (gb >= 1.0) {
        "${String.format(Locale.US, "%.1f", gb)} ${gbUnit(copy)}"
    } else {
        "${String.format(Locale.US, "%.1f", mb)} ${mbUnit(copy)}"
    }
}

//formats the storage quota compactly so the status pill remains stable on the car tablet.
private fun formatStorageUsage(bytes: Long, limitGb: Int, copy: Copy): String {
    val usedGb = bytes / 1_000_000_000.0
    return "${String.format(Locale.US, "%.1f", usedGb)}/$limitGb ${gbUnit(copy)}"
}

//maps usage ratio to the approved green/yellow/red storage states.
private fun storageUsageColors(bytes: Long, limitGb: Int, palette: Palette): Pair<Color, Color> {
    val ratio = if (limitGb <= 0) 0.0 else bytes / (limitGb * 1_000_000_000.0)
    return when {
        ratio <= 0.5 -> palette.green to palette.greenSoft
        ratio <= 0.9 -> palette.yellow to palette.yellowSoft
        else -> palette.red to palette.redSoft
    }
}

private fun gbUnit(copy: Copy): String =
    if (copy.language == Language.Ua) "ГБ" else "GB"

private fun mbUnit(copy: Copy): String =
    if (copy.language == Language.Ua) "МБ" else "MB"

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun sessionLabel(count: Int, copy: Copy): String {
    if (copy.language == Language.En) {
        return if (count == 1) "session" else "sessions"
    }
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "сесій"
        mod10 == 1 -> "сесія"
        mod10 in 2..4 -> "сесії"
        else -> "сесій"
    }
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun previousPlural(copy: Copy): String =
    if (copy.language == Language.Ua) "Попередні" else copy.previous

private fun nextPlural(copy: Copy): String =
    if (copy.language == Language.Ua) "Наступні" else copy.next

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun darkPalette() = Palette(
    dark = true,
    background = Color(0xFF080D12),
    surface = Color(0xFF0E151D),
    panel = Color(0xFF131B25),
    panelAlt = Color(0xFF172231),
    field = Color(0xFF18212C),
    border = Color(0xFF2B3847),
    borderStrong = Color(0xFF40536A),
    text = Color(0xFFF1F6FF),
    muted = Color(0xFFAAB8CA),
    active = Color(0xFF173A5C),
    accent = Color(0xFF1F6FD8),
    green = Color(0xFF54D898),
    greenSoft = Color(0xFF123C2B),
    yellow = Color(0xFFF2C34E),
    yellowSoft = Color(0xFF453817),
    red = Color(0xFFFF8C8C),
    redSoft = Color(0xFF4C252A),
    disabled = Color(0xFF394453)
)

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun lightPalette() = Palette(
    dark = false,
    background = Color(0xFFEAF1F8),
    surface = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFF0F5FB),
    field = Color(0xFFF7FAFE),
    border = Color(0xFFC9D6E4),
    borderStrong = Color(0xFF6D7D8F),
    text = Color(0xFF121A23),
    muted = Color(0xFF526274),
    active = Color(0xFFD9EAFE),
    accent = Color(0xFF1F6FD8),
    green = Color(0xFF147A55),
    greenSoft = Color(0xFFD8F4E7),
    yellow = Color(0xFF7A5A00),
    yellowSoft = Color(0xFFFFF1C9),
    red = Color(0xFFB42318),
    redSoft = Color(0xFFFFE1E1),
    disabled = Color(0xFFE1E7EF)
)

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun enCopy() = Copy(
    language = Language.En,
    title = "BYD HUD",
    subtitle = "HUD navigation output | v${BuildConfig.VERSION_NAME}",
    main = "Options",
    apps = "Apps",
    logs = "Logs",
    patch = "Patch",
    manual = "Manual",
    hudRunning = "HUD: running",
    hudIdle = "HUD: idle",
    hudFailed = "HUD: failed",
    adbOk = "ADB: OK",
    adbNotGranted = "ADB: not granted",
    permissionsOk = "Permissions: OK",
    permissionsMissing = "Permissions: missing",
    ukr = "Укр",
    eng = "Англ",
    dark = "Dark",
    light = "Light",
    mainHint = "Runtime controls and quick navigation diagnostics",
    permissionsRuntime = "Permissions and Runtime",
    adbPermissions = "ADB permissions",
    adbHint = "Self-check grants required nav capture permissions automatically when ADB is authorized.",
    grantAdb = "Grant ADB",
    backgroundApps = "Background apps",
    backgroundHint = "Open background management screen.",
    disableBgApps = "Disable BG Apps",
    setupDialogTitle = "Background work",
    setupDialogText = "Check this after every install or update, otherwise DiLink can stop HUD while the app is in the background.",
    setupDialogInstruction = "Set Disable background Apps -> BYD HUD = OFF",
    setupDialogPrimary = "Open",
    setupDialogDismiss = "Got it",
    bootRuntime = "Boot runtime service",
    bootRuntimeHint = "Start foreground HUD runtime after boot and watchdog events.",
    saveScreenshotsLogs = "Save diagnostic screenshots and extended logs",
    saveScreenshotsLogsHint = "Keep Waze frames, processing details, and full log history for diagnostics.",
    checkForUpdates = "Check for updates",
    checkForUpdatesHint = "Check for new version and offer updating",
    checkForUpdatesButton = "Check for updates",
    betaTesting = "Take part in beta-testing",
    betaTestingHint = "Check for experimental version. Usage may be unstable or broken",
    shutdown = "Shutdown",
    shutdownHint = "Stop the app until it is opened again",
    screenCaptureChannel = "Screen capture channel (legacy)",
    screenCaptureChannelHint = "Allows screen capture for maneuver output. No longer supported",
    updateTitle = "Update",
    updateCurrentVersion = "Current version:",
    updateAvailableVersion = "Available version:",
    updateChecking = "Checking for update...",
    updateLatest = "This is the latest app version",
    updateDownloading = "Downloading update...",
    updateClose = "Close",
    updateAction = "Update",
    basicNavigationOutput = "Basic navigation output",
    extraNavigationOptions = "Extra navigation options",
    dashboardControl = "Dashboard control",
    notice = "Notice",
    wazeDirectNotice = "Waze HUD output works best through the direct channel. Supported versions:",
    wazeSupportedVersions = "stock 4.95.0.3 / patched 5.20.0.1",
    screenCaptureUnsupportedNotice = "The screen capture channel is no longer supported by the developer.",
    pngOutput = "PNG output",
    pngHint = "Send maneuver source image payload.",
    nativeOutput = "Native output",
    nativeHint = "Send native maneuver id.",
    laneOutput = "Lane output",
    laneHint = "Send lane bitmap payload for multi-lane guidance.",
    distanceOutput = "Distance output",
    distanceHint = "Send distance-to-maneuver field in live navigation payload.",
    streetOutput = "Street output",
    streetHint = "Send next road or Waze street text when available.",
    textDirectionOutput = "Text direction output",
    textDirectionOutputHint = "Send text direction in street output (\"Continue straight\") if no street text available. Street output has priority.",
    showWazeAlerts = "Show Waze alerts",
    showWazeAlertsHint = "Display Waze alerts on the HUD.",
    fullscreenDashboard = "Fullscreen dashboard",
    fullscreenDashboardHint = "Use fullscreen dashboard mode.",
    smallDistanceClamp = "Small distance clamp",
    smallDistanceHint = "Clamp distances below 20 m instead of OEM close marker.",
    roundaboutLeft = "Roundabout left-hand traffic",
    roundaboutHint = "Changes roundabout assets for PNG output. (Legacy with screen capture channel)",
    appsHint = "Supported apps can be armed before launch. Dashboard actions require a running background app.",
    lastScan = "Last scan",
    refreshApps = "Refresh apps",
    supportedApps = "Supported navigation apps",
    allApps = "All background apps",
    installed = "installed",
    notInstalled = "not installed",
    running = "running in background",
    notRunning = "not running",
    supported = "supported",
    dashboardUnavailable = "dashboard unavailable",
    logCandidate = "log candidate",
    hud = "HUD",
    log = "Log",
    sendDashboard = "Send to dashboard",
    sendMain = "Send to main",
    startAppFirst = "Start app first",
    noBackgroundApps = "Supported apps are not duplicated here. This list shows only current non-system background apps.",
    logsHint = "Capture logs and tester-facing navigation artifact paths.",
    logcatRecorder = "Logcat recorder",
    recorderStatus = "Recorder status",
    waiting = "waiting",
    startLogcat = "Start Logcat",
    stopLogcat = "Stop Logcat",
    applicationState = "Application state",
    navigationLogs = "Navigation logs",
    pathHint = "Path to navigation logs on tablet.",
    storage = "Storage",
    storageHint = "Navigation log retention and cleanup controls.",
    storageSettings = "Storage settings",
    navLogsFolderLimit = "Navigation logs folder limit",
    navLogsFolderLimitHint = "Old data is deleted while the app is running when this folder exceeds the limit.",
    storageLimitGb = "Limit, GB",
    currentNavLogsSize = "Current navigation logs folder size",
    navigationLogsFolder = "Navigation logs folder",
    privateStorageLocation = "private folder",
    publicStorageLocation = "public folder",
    bothStorageLocations = "public and private folders",
    shareSelected = "Share selected",
    sortByDate = "Newest first",
    sortByName = "Oldest first",
    deleteSelected = "Delete selected",
    activeToday = "active today",
    sessions = "sessions",
    created = "created",
    folderSelected = "selected",
    folderNotSelected = "tap to select",
    storageNoDayFolders = "No day folders yet. New navigation logs will appear here after dated sessions are created.",
    storageCalculating = "calculating...",
    storageSessionsShort = "sess.",
    storageDeleteTitle = "Delete selected",
    storageDeleteSelected = "Selected %d folders for deletion",
    storageDeleteQuestion = "Run deletion?",
    storageDeleteCannotStop = "After it starts, the operation cannot be stopped from the app.",
    storageDeleteYes = "Yes",
    storageDeleteNo = "No",
    storageDeletingFolder = "Deleting data folder",
    storageDeleteStep = "step %d/%d",
    patchTab = "APPLICATION PATCH",
    patchHint = "Patch navigation apps to enable direct HUD output.",
    patchWarning = "Warning",
    patchWarningText = "A supported navigation app can be patched to enable the direct HUD channel. The installed original app and its data will be removed, then a modified version will be installed in its place. If your Waze version is unsupported, report it to the author for analysis:",
    patchRiskWarning = "Proceed at your own risk. App developer is not responsible for any data loss or errors.",
    availableNavigators = "Available navigation apps",
    noSupportedNavigators = "No supported navigation apps",
    appVersion = "Version",
    patchNotChecked = "not checked",
    checkPatch = "Check",
    applyPatch = "Patch",
    patchConfirmTitle = "Patch Waze?",
    patchConfirmText = "Waze will be checked again before patching. The installed original app and its data will be removed, then the modified version will be installed in its place.",
    patchConfirmOk = "OK",
    patchConfirmCancel = "Cancel",
    manualHint = "Direct manual payload checks for HUD output.",
    manualHudOutput = "Manual HUD output",
    supportedArrows = "Supported arrows",
    supportedArrowsHint = "Prev / Next sends supported PNG+Native combo",
    manualLanes = "Manual lanes",
    manualLanesHint = "Prev / Next sends lane bitmap immediately",
    rawManeuverIds = "Raw maneuver IDs",
    rawManeuverHint = "Number fields send Sxx / Nxx payload IDs immediately",
    manualMode = "Manual mode",
    manualModeHint = "When enabled, Manual controls send HUD payload immediately. Turning it off clears manual output and returns to live navigation output.",
    pngNumber = "PNG number",
    nativeNumber = "Native number",
    distance = "Distance, m",
    street = "Street text",
    laneBitmap = "Lane bitmap",
    previous = "Previous",
    next = "Next",
    randomize = "Randomize",
    currentSelection = "Current selection",
    manualPreview = "manual output preview"
)

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun uaCopy() = enCopy().copy(
    language = Language.Ua,
    subtitle = "Виведення навігації на HUD | v${BuildConfig.VERSION_NAME}",
    main = "Налаштування",
    apps = "Застосунки",
    logs = "Логи",
    patch = "Патч",
    manual = "Ручний",
    hudRunning = "HUD: працює",
    hudIdle = "HUD: очікує",
    hudFailed = "HUD: помилка",
    adbNotGranted = "ADB: не видано",
    permissionsOk = "Права: OK",
    permissionsMissing = "Права: немає",
    dark = "Темна",
    light = "Світла",
    mainHint = "Керування службою та швидка діагностика навігації",
    permissionsRuntime = "Дозволи та служба",
    adbPermissions = "Дозволи ADB",
    adbHint = "Самоперевірка автоматично видає потрібні дозволи, коли ADB авторизований.",
    grantAdb = "Видати ADB",
    backgroundApps = "Робота у фоні",
    backgroundHint = "Відкрити екран керування фоновою роботою.",
    disableBgApps = "Робота у фоні",
    setupDialogTitle = "Робота у фоні",
    setupDialogText = "Це потрібно перевірити після кожного встановлення або оновлення, інакше DiLink може зупинити HUD у фоні.",
    setupDialogInstruction = "Установіть Disable background Apps -> BYD HUD = OFF",
    setupDialogPrimary = "Відкрити",
    setupDialogDismiss = "Зрозуміло",
    bootRuntime = "Авто-запуск",
    bootRuntimeHint = "Запускати фонову службу HUD після завантаження системи, розблокування, оновлення пакета та перевірки стану.",
    saveScreenshotsLogs = "Зберігати діагностичні скріншоти та розширені логи",
    saveScreenshotsLogsHint = "Зберігати кадри Waze, деталі обробки та повну історію логів для діагностики.",
    checkForUpdates = "Перевіряти оновлення",
    checkForUpdatesHint = "Перевіряти наявність нової версії та пропонувати оновитися",
    checkForUpdatesButton = "Перевірити оновлення",
    betaTesting = "Участь у бета-тестуванні",
    betaTestingHint = "Перевіряти наявність експериментальних версій. Може бути нестабільна або зламана робота",
    shutdown = "Вимкнути",
    shutdownHint = "Завершити роботу застосунку до наступного відкриття",
    screenCaptureChannel = "Канал захоплення екрану (сумісність)",
    screenCaptureChannelHint = "Дозволяє використання захоплення екрану для виводу маневрів. Більше не підтримується",
    updateTitle = "Оновлення",
    updateCurrentVersion = "Поточна версія:",
    updateAvailableVersion = "Доступна версія:",
    updateChecking = "Перевіряємо оновлення...",
    updateLatest = "Це остання версія застосунку",
    updateDownloading = "Завантажуємо оновлення...",
    updateClose = "Закрити",
    updateAction = "Оновити",
    basicNavigationOutput = "Базовий вивід навігації",
    extraNavigationOptions = "Додаткові функції навігації",
    dashboardControl = "Керування дашбордом",
    notice = "Примітка",
    wazeDirectNotice = "Вивід Waze на HUD найкраще працює через прямий канал. Підтримувані версії:",
    wazeSupportedVersions = "стокова 4.95.0.3 / патчена 5.20.0.1",
    screenCaptureUnsupportedNotice = "Канал захоплення екрану більше не підтримується розробником.",
    pngOutput = "Вивід PNG",
    pngHint = "Надсилати зображення маневру.",
    nativeOutput = "Вивід штатного маневру",
    nativeHint = "Надсилати штатне динамічне зображення маневру.",
    laneOutput = "Вивід смуг",
    laneHint = "Надсилати зображення смуг, коли аналізатор виявляє багатосмугові підказки.",
    distanceOutput = "Вивід дистанції",
    distanceHint = "Надсилати дистанцію до маневру в даних активної навігації.",
    streetOutput = "Вивід вулиці",
    streetHint = "Надсилати наступну дорогу або назву вулиці з Waze, коли вона доступна.",
    textDirectionOutput = "Вивід напрямків текстом",
    textDirectionOutputHint = "Виводити у поле для вулиці текстові напрямки (\"Прямуйте далі\"), якщо відсутній текст вулиці. Вивід вулиці має пріоритет.",
    showWazeAlerts = "Показувати попередження Waze",
    showWazeAlertsHint = "Відображати попередження Waze на HUD.",
    fullscreenDashboard = "Повний екран приборки",
    fullscreenDashboardHint = "Використовувати повноекранний режим приборки.",
    smallDistanceClamp = "Обрізка малої дистанції",
    smallDistanceHint = "Обмежувати дистанції менше 20 м, щоб штатний HUD не показував власний маркер близької відстані.",
    roundaboutLeft = "Лівосторонній рух на кільці",
    roundaboutHint = "Використовувати зображення кільця для лівостороннього руху у виводі PNG. (Сумісність з каналом захоплення екрану)",
    appsHint = "Підтримувані застосунки можна активувати до запуску. Для приборки застосунок має бути у фоні.",
    lastScan = "Останнє сканування",
    refreshApps = "Оновити застосунки",
    supportedApps = "Підтримувані навігатори",
    allApps = "Усі фонові застосунки",
    installed = "встановлено",
    notInstalled = "не встановлено",
    running = "працює у фоні",
    notRunning = "не запущено",
    supported = "підтримується",
    dashboardUnavailable = "приборка недоступна",
    logCandidate = "кандидат для логів",
    log = "Лог",
    sendDashboard = "На приборку",
    sendMain = "На основний екран",
    startAppFirst = "Спочатку запусти",
    noBackgroundApps = "Підтримувані застосунки тут не дублюються. Тут тільки поточні несистемні фонові застосунки.",
    logsHint = "Збір логів і шляхів до навігаційних логів для тестерів.",
    logcatRecorder = "Запис logcat",
    recorderStatus = "Стан запису",
    waiting = "очікування",
    startLogcat = "Почати запис logcat",
    stopLogcat = "Зупинити запис logcat",
    applicationState = "Стан застосунку",
    navigationLogs = "Навігаційні логи",
    pathHint = "Шлях до навігаційних логів на планшеті.",
    storage = "Сховище",
    storageHint = "Зберігання й очищення навігаційних логів.",
    storageSettings = "Налаштування сховища",
    navLogsFolderLimit = "Ліміт теки з журналом навігації",
    navLogsFolderLimitHint = "Старі дані видаляються під час роботи застосунку, коли тека перевищує ліміт.",
    storageLimitGb = "Ліміт, ГБ",
    currentNavLogsSize = "Поточний розмір теки з журналом навігації",
    navigationLogsFolder = "Тека журналу навігації",
    privateStorageLocation = "приватна тека",
    publicStorageLocation = "публічна тека",
    bothStorageLocations = "публічна та приватна теки",
    shareSelected = "Поділитися вибраним",
    sortByDate = "Нові спочатку",
    sortByName = "Старі спочатку",
    deleteSelected = "Видалити вибране",
    activeToday = "активна сьогодні",
    sessions = "сесій",
    created = "створено",
    folderSelected = "вибрано",
    folderNotSelected = "натисни для вибору",
    storageNoDayFolders = "Денних тек ще немає. Нові навігаційні логи з'являться після створення сесій.",
    storageCalculating = "обчислюємо...",
    storageSessionsShort = "сес.",
    storageDeleteTitle = "Видалення даних",
    storageDeleteSelected = "Обрано %d тек для видалення",
    storageDeleteQuestion = "Виконати видалення?",
    storageDeleteCannotStop = "Після початку зупинити операцію із застосунку неможливо.",
    storageDeleteYes = "Так",
    storageDeleteNo = "Ні",
    storageDeletingFolder = "Видаляємо теку з даними",
    storageDeleteStep = "крок %d/%d",
    patchTab = "ПАТЧ ЗАСТОСУНКУ",
    patchHint = "Патч навігатора для підтримки прямого каналу виводу на HUD.",
    patchWarning = "Попередження",
    patchWarningText = "Для підтримуваного навігатора можна застосувати патч, який вмикає прямий канал HUD. Установлений оригінальний застосунок буде видалено разом із його даними, а замість нього встановлено модифіковану версію. Якщо ваша версія Waze не підтримується, повідомте автору для аналізу:",
    patchRiskWarning = "Дійте на власний ризик. Розробник застосунку не несе відповідальності за втрату даних та помилки.",
    availableNavigators = "Доступні навігатори",
    noSupportedNavigators = "Немає підтримуваних навігаторів",
    appVersion = "Версія",
    patchNotChecked = "не перевірено",
    checkPatch = "Перевірити",
    applyPatch = "Пропатчити",
    patchConfirmTitle = "Пропатчити Waze?",
    patchConfirmText = "Перед застосуванням патчу Waze буде перевірено ще раз. Установлений оригінальний застосунок буде видалено разом із його даними, а замість нього встановлено модифіковану версію.",
    patchConfirmOk = "Ок",
    patchConfirmCancel = "Скасувати",
    manualHint = "Пряма ручна перевірка даних для HUD.",
    manualHudOutput = "Ручний вивід на HUD",
    supportedArrows = "Підтримувані стрілки",
    supportedArrowsHint = "Попередній / Наступний одразу надсилає пару PNG і штатного маневру",
    manualLanes = "Ручні смуги",
    manualLanesHint = "Попередні / Наступні одразу надсилають зображення смуг",
    rawManeuverIds = "Сирі ID маневрів",
    rawManeuverHint = "Числові поля одразу формують ідентифікатори Sxx / Nxx",
    manualMode = "Ручний режим",
    manualModeHint = "Коли увімкнено, ручні елементи одразу надсилають дані на HUD. Вимкнення очищає ручний вивід і повертає активну навігацію.",
    pngNumber = "PNG номер",
    nativeNumber = "Номер штатного маневру",
    distance = "Дистанція, м",
    street = "Текст вулиці",
    laneBitmap = "Зображення смуг",
    previous = "Попередній",
    next = "Наступний",
    randomize = "Випадково",
    currentSelection = "Поточний вибір",
    manualPreview = "попередній перегляд ручного виводу"
)
