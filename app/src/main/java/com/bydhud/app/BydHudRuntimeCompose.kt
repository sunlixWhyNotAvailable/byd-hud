package com.bydhud.app

//builds the runtime UI so operators can control capture, permissions, logs, and updates in one place.

import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

//anchors BydHudRuntimeCompose UI orchestration so controls and diagnostics are wired from one place.
object BydHudRuntimeCompose {
    @JvmStatic
    //keeps update I/O here so network, file, and installer failures are handled in one path.
    fun install(activity: MainActivity) {
        activity.setContent {
            RuntimeApp(activity)
        }
    }
}

//defines class UI/state support so Compose code can keep rendering intent explicit.
private enum class RuntimeTab {
    Main,
    Apps,
    Logs,
    Storage,
    Manual
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
    data class Downloading(val version: String, val progress: String) : UpdateCheckState()
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
    val updateTitle: String,
    val updateCurrentVersion: String,
    val updateAvailableVersion: String,
    val updateChecking: String,
    val updateLatest: String,
    val updateDownloading: String,
    val updateClose: String,
    val updateAction: String,
    val navigationOutput: String,
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
    val storageFolderHint: String,
    val sortByDate: String,
    val sortByName: String,
    val deleteSelected: String,
    val activeToday: String,
    val sessions: String,
    val created: String,
    val folderSelected: String,
    val folderNotSelected: String,
    val storageNoDayFolders: String,
    val storageDeleteTitle: String,
    val storageDeleteSelected: String,
    val storageDeleteQuestion: String,
    val storageDeleteCannotStop: String,
    val storageDeleteYes: String,
    val storageDeleteNo: String,
    val storageDeletingFolder: String,
    val storageDeleteStep: String,
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
private fun RuntimeApp(activity: MainActivity) {
    var snapshot by remember { mutableStateOf(activity.composeSnapshot()) }
    var selectedTab by rememberSaveable { mutableStateOf(RuntimeTab.Main) }
    var storageSortOldestFirst by rememberSaveable { mutableStateOf(false) }
    var selectedStorageDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingStorageDeleteDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageDeleteQueue by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageDeleteStep by rememberSaveable { mutableIntStateOf(0) }
    var storageDeleteBusy by remember { mutableStateOf(false) }
    var showSetupDialog by rememberSaveable { mutableStateOf(activity.composeShouldShowBackgroundReminder()) }
    var autoUpdateCheckEnabled by rememberSaveable { mutableStateOf(AppUpdateManager.isAutoCheckEnabled(activity)) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Checking) }
    var appInForeground by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    val latestAutoUpdateCheckEnabled by rememberUpdatedState(autoUpdateCheckEnabled)
    val latestAppInForeground by rememberUpdatedState(appInForeground)
    val latestShowSetupDialog by rememberUpdatedState(showSetupDialog)
    val latestShowUpdateDialog by rememberUpdatedState(showUpdateDialog)
    val palette = if (snapshot.darkTheme) darkPalette() else lightPalette()
    val copy = if (snapshot.uaLanguage) uaCopy() else enCopy()

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    fun refresh() {
        snapshot = activity.composeSnapshot()
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
            showUpdateDialog = true
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
            showUpdateDialog = true
        }
    }

    //runs storage deletion as folder steps so the UI can stay responsive without a pre-scan.
    fun beginStorageDelete(days: List<String>) {
        if (days.isEmpty() || storageDeleteBusy) {
            return
        }
        pendingStorageDeleteDays = emptyList()
        storageDeleteQueue = days
        storageDeleteStep = 0
        storageDeleteBusy = true
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == RuntimeTab.Apps) {
            activity.composeRefreshApps()
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
            if (selectedTab == RuntimeTab.Apps) {
                activity.composeMaybeRefreshApps()
            }
            refresh()
        }
    }

    LaunchedEffect(storageDeleteBusy, storageDeleteStep, storageDeleteQueue) {
        if (!storageDeleteBusy) {
            return@LaunchedEffect
        }
        val day = storageDeleteQueue.getOrNull(storageDeleteStep)
        if (day == null) {
            storageDeleteBusy = false
            storageDeleteQueue = emptyList()
            storageDeleteStep = 0
            selectedStorageDays = emptyList()
            refresh()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            activity.composeDeleteStorageDay(day)
        }
        activity.composeAppendStatus("Storage delete ${result.day}: ${result.message}")
        val nextStep = storageDeleteStep + 1
        if (nextStep >= storageDeleteQueue.size) {
            storageDeleteBusy = false
            storageDeleteQueue = emptyList()
            storageDeleteStep = 0
            selectedStorageDays = emptyList()
            refresh()
        } else {
            storageDeleteStep = nextStep
        }
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
                onLanguage = { ua -> runAction { activity.composeSetUaLanguage(ua) } },
                onTheme = { dark -> runAction { activity.composeSetDarkTheme(dark) } }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp)
                ) {
                    when (selectedTab) {
                        RuntimeTab.Main -> MainTab(
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
                            onManualUpdateCheck = { beginUpdateCheck(force = true, showLatestResult = true) },
                            onDisableBgApps = { showSetupDialog = true }
                        )
                        RuntimeTab.Apps -> AppsTab(copy, palette, snapshot, activity, ::runAction)
                        RuntimeTab.Logs -> LogsTab(copy, palette, snapshot, activity, ::runAction)
                        RuntimeTab.Storage -> StorageTab(
                            copy = copy,
                            palette = palette,
                            snapshot = snapshot,
                            sortOldestFirst = storageSortOldestFirst,
                            selectedDays = selectedStorageDays,
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
                                pendingStorageDeleteDays = deletableSelectedDays
                            }
                        )
                        RuntimeTab.Manual -> ManualTab(copy, palette, snapshot, activity, ::runAction)
                    }
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
                        updateState = UpdateCheckState.Downloading(available.info.version, "0%")
                        updateScope.launch {
                            try {
                                AppUpdateManager.downloadAndInstall(activity, available.info) { progress ->
                                    updateState = UpdateCheckState.Downloading(available.info.version, progress)
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
                folderName = storageDeleteQueue.getOrNull(storageDeleteStep).orEmpty(),
                step = storageDeleteStep + 1,
                total = storageDeleteQueue.size.coerceAtLeast(1)
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
            HudStatusPill(snapshot.hudStatus, copy, palette)
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
private fun MainTab(
    copy: Copy,
    palette: Palette,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit,
    autoUpdateCheckEnabled: Boolean,
    onAutoUpdateCheckChange: (Boolean) -> Unit,
    onManualUpdateCheck: () -> Unit,
    onDisableBgApps: () -> Unit
) {
    PageSurface(copy.main, copy.mainHint, palette) {
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
        }

        Spacer(Modifier.height(10.dp))

        Section(copy.navigationOutput, palette) {
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
            SwitchRow(copy.distanceOutput, copy.distanceHint, snapshot.distanceOutputEnabled, palette) {
                runAction { activity.composeSetDistanceOutputEnabled(it) }
            }
            Divider(palette)
            SwitchRow(copy.streetOutput, copy.streetHint, snapshot.streetOutputEnabled, palette) {
                runAction { activity.composeSetStreetOutputEnabled(it) }
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
//updates shared state here so freshness and lifecycle checks use the same evidence.
private fun MarkdownPatchNotesText(text: String, palette: Palette) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
        if (lines.isEmpty()) {
            Text("", color = palette.muted, fontSize = 13.sp)
        } else {
            lines.forEach { line ->
                when {
                    line.isBlank() -> Spacer(Modifier.height(6.dp))
                    line.startsWith("## ") -> Text(
                        line.removePrefix("## ").trim(),
                        color = palette.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    else -> Text(line, color = palette.text, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
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
    PageSurface(copy.apps, copy.appsHint, palette, headerAction = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("${copy.lastScan}: ${snapshot.lastScanText}", palette.muted, palette.disabled)
            HudButton(copy.refreshApps, palette, primary = true, width = 178.dp) {
                runAction { activity.composeRefreshApps() }
            }
        }
    }) {
        Section(copy.supportedApps, palette) {
            snapshot.supportedApps.forEachIndexed { index, row ->
                if (index > 0) Divider(palette)
                AppRow(row, copy, palette, supported = true, activity = activity, runAction = runAction)
            }
        }

        Spacer(Modifier.height(10.dp))

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
    val dashboardEnabled = row.runtimeBacked && !row.dashboardMoveInProgress
    val runningForStatus = row.runtimeBacked || row.observed
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
            Text(row.packageLine.ifBlank { row.packageName }, color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (row.installed) copy.installed else copy.notInstalled,
                    if (row.installed) ChipKind.Green else ChipKind.Red, palette, width = 116.dp)
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
                runAction { activity.composeToggleDashboard(row.packageName, row.runtimeBacked) }
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
    PageSurface(copy.logs, copy.logsHint, palette) {
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
                    HudButton(copy.startLogcat, palette, primary = true, width = 0.dp, modifier = Modifier.weight(0.65f)) {
                        runAction { activity.composeStartLogcat() }
                    }
                    HudButton(copy.stopLogcat, palette, width = 0.dp, modifier = Modifier.weight(0.35f)) {
                        runAction { activity.composeStopLogcat() }
                    }
                }
            }
            Section(copy.applicationState, palette, modifier = Modifier.weight(1f).heightIn(min = 204.dp)) {
                CodeBlock(snapshot.applicationState, palette, compact = true, modifier = Modifier.padding(14.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Section(copy.navigationLogs, palette) {
            CodeBlock(snapshot.logPaths + "\n\n" + copy.pathHint, palette, compact = true, modifier = Modifier.padding(14.dp))
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
    onStorageLimitGb: (Int) -> Unit,
    onSortOldestFirst: (Boolean) -> Unit,
    onToggleDay: (String) -> Unit,
    onDeleteSelected: (List<String>) -> Unit
) {
    var limitText by rememberSaveable(snapshot.storageLimitGb) {
        mutableStateOf(snapshot.storageLimitGb.toString())
    }
    val days = if (sortOldestFirst) {
        snapshot.storageDays.sortedBy { it.name }
    } else {
        snapshot.storageDays.sortedByDescending { it.name }
    }
    val deletableSelectedDays = selectedDays.filter { selected ->
        days.any { it.name == selected && !it.active }
    }
    PageSurface(copy.storage, copy.storageHint, palette) {
        Section(copy.storageSettings, palette) {
            SettingRow(
                title = copy.navLogsFolderLimit,
                hint = copy.navLogsFolderLimitHint,
                palette = palette,
                action = {
                    LabeledInput(
                        copy.storageLimitGb,
                        limitText,
                        { value ->
                            val sanitized = sanitizeStorageLimitInput(value)
                            limitText = sanitized
                            sanitized.toIntOrNull()?.let(onStorageLimitGb)
                        },
                        palette,
                        Modifier.width(120.dp)
                    )
                }
            )
            StorageLimitSlider(snapshot.storageLimitGb, palette) {
                val next = if (snapshot.storageLimitGb >= 10) 1 else snapshot.storageLimitGb + 1
                limitText = next.toString()
                onStorageLimitGb(next)
            }
            Divider(palette)
            SettingRow(
                title = copy.currentNavLogsSize,
                hint = "/sdcard/Documents/BYD-HUD/nav-capture",
                palette = palette,
                action = {
                    Pill(
                        "${formatBytes(snapshot.navCaptureFolderBytes)} / ${snapshot.storageLimitGb} GB",
                        palette.green,
                        palette.greenSoft
                    )
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        Section(copy.navigationLogsFolder, palette) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(copy.storageFolderHint, color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                HudButton(
                    if (sortOldestFirst) copy.sortByName else copy.sortByDate,
                    palette,
                    primary = true,
                    width = 180.dp
                ) {
                    onSortOldestFirst(!sortOldestFirst)
                }
            }
            Divider(palette)
            if (days.isEmpty()) {
                Text(
                    copy.storageNoDayFolders,
                    color = palette.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                days.forEachIndexed { index, day ->
                    if (index > 0) Divider(palette)
                    StorageDayRow(
                        day = day,
                        copy = copy,
                        palette = palette,
                        selected = !day.active && selectedDays.contains(day.name),
                        onToggle = { onToggleDay(day.name) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                HudButton(
                    copy.deleteSelected,
                    palette,
                    enabled = deletableSelectedDays.isNotEmpty(),
                    width = 190.dp,
                    onClick = { onDeleteSelected(deletableSelectedDays) }
                )
            }
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun StorageLimitSlider(
    limit: Int,
    palette: Palette,
    onStep: () -> Unit
) {
    val press = rememberPressFeedback()
    val progress = ((limit - 1).coerceIn(0, 9)) / 9f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onStep
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(palette.disabled)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(progress.coerceAtLeast(0.01f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(palette.accent)
            )
            Box(modifier = Modifier.weight((1f - progress).coerceAtLeast(0.01f)))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(progress.coerceIn(0f, 1f)))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .border(2.dp, palette.accent, RoundedCornerShape(100.dp))
                    .background(if (palette.dark) Color(0xFFD9ECFF) else Color.White)
            )
            Spacer(Modifier.weight((1f - progress).coerceIn(0f, 1f)))
        }
    }
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun StorageDayRow(
    day: MainActivity.ComposeStorageDay,
    copy: Copy,
    palette: Palette,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val canSelect = !day.active
    val press = rememberPressFeedback(canSelect)
    val baseBackground = if (selected) palette.active else palette.panelAlt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) palette.accent else palette.border, RoundedCornerShape(8.dp))
            .background(pressBackground(baseBackground, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                enabled = canSelect,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onToggle
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(day.name, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "${copy.created}: ${day.createdLabel}  •  ${day.sessions} ${sessionLabel(day.sessions, copy)}",
                color = palette.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Pill(formatBytes(day.bytes), palette.muted, palette.disabled)
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

    PageSurface(copy.manual, copy.manualHint, palette) {
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
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun PageSurface(
    title: String,
    hint: String,
    palette: Palette,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                Text(hint, color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            headerAction?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        content()
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
            Text(hint, color = palette.muted, fontSize = 13.sp)
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
                onClick = onClick
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
    val baseBackground = when {
        !enabled -> Color.Transparent
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
                onClick = onClick
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
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun CompactSwitchBox(
    label: String,
    checked: Boolean,
    palette: Palette,
    width: Dp,
    onChecked: (Boolean) -> Unit
) {
    val press = rememberPressFeedback()
    Row(
        modifier = Modifier
            .width(width)
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .background(pressBackground(palette.panelAlt, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null
            ) { onChecked(!checked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        HudSwitch(checked, onChecked, palette, compact = true)
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun HudSwitch(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    palette: Palette,
    compact: Boolean = false
) {
    val press = rememberPressFeedback()
    val width = if (compact) 42.dp else 56.dp
    val height = if (compact) 27.dp else 32.dp
    val knob = if (compact) 20.dp else 25.dp
    var settleTarget by remember { mutableStateOf<Boolean?>(null) }
    var lastChecked by remember { mutableStateOf(checked) }
    val travel = width - knob - 6.dp
    val settling = settleTarget != null || lastChecked != checked
    val knobOffset by animateDpAsState(
        targetValue = when {
            settling -> 3.dp + travel / 2f
            checked -> 3.dp + travel
            else -> 3.dp
        },
        animationSpec = tween(durationMillis = 140),
        label = "switchKnobOffset"
    )
    LaunchedEffect(checked) {
        if (lastChecked != checked) {
            settleTarget = checked
            delay(120)
            lastChecked = checked
            settleTarget = null
        }
    }
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(100.dp))
            .background(pressBackground(if (checked) palette.accent else palette.disabled, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null
            ) { onChecked(!checked) }
            .padding(0.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(knob)
                .offset(x = knobOffset, y = 3.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(if (checked) Color(0xFFD9ECFF) else Color(0xFFD8E3EE))
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
                onClick = onClick
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
        TabButton(copy.main, RuntimeTab.Main, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.apps, RuntimeTab.Apps, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.logs, RuntimeTab.Logs, selected, palette, Modifier.weight(1f), onSelect)
        TabButton(copy.storage, RuntimeTab.Storage, selected, palette, Modifier.weight(1f), onSelect)
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
    val color = if (active) palette.text else palette.muted
    Canvas(modifier = Modifier.size(19.dp)) {
        val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            RuntimeTab.Main -> {
                drawLine(color, Offset(size.width * 0.18f, size.height * 0.50f), Offset(size.width * 0.50f, size.height * 0.22f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.50f, size.height * 0.22f), Offset(size.width * 0.82f, size.height * 0.50f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.28f, size.height * 0.50f), Offset(size.width * 0.28f, size.height * 0.82f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.72f, size.height * 0.50f), Offset(size.width * 0.72f, size.height * 0.82f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.28f, size.height * 0.82f), Offset(size.width * 0.72f, size.height * 0.82f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            }
            RuntimeTab.Apps -> {
                repeat(2) { row ->
                    repeat(2) { col ->
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(size.width * (0.18f + col * 0.38f), size.height * (0.18f + row * 0.38f)),
                            size = Size(size.width * 0.22f, size.height * 0.22f),
                            cornerRadius = CornerRadius(3f, 3f),
                            style = stroke
                        )
                    }
                }
            }
            RuntimeTab.Logs -> {
                drawRoundRect(color, Offset(size.width * 0.24f, size.height * 0.12f), Size(size.width * 0.52f, size.height * 0.76f), CornerRadius(5f, 5f), style = stroke)
                drawLine(color, Offset(size.width * 0.36f, size.height * 0.35f), Offset(size.width * 0.64f, size.height * 0.35f), strokeWidth = 2f)
                drawLine(color, Offset(size.width * 0.36f, size.height * 0.53f), Offset(size.width * 0.64f, size.height * 0.53f), strokeWidth = 2f)
            }
            RuntimeTab.Storage -> {
                drawRoundRect(color, Offset(size.width * 0.20f, size.height * 0.20f), Size(size.width * 0.60f, size.height * 0.18f), CornerRadius(6f, 6f), style = stroke)
                drawRoundRect(color, Offset(size.width * 0.20f, size.height * 0.41f), Size(size.width * 0.60f, size.height * 0.18f), CornerRadius(6f, 6f), style = stroke)
                drawRoundRect(color, Offset(size.width * 0.20f, size.height * 0.62f), Size(size.width * 0.60f, size.height * 0.18f), CornerRadius(6f, 6f), style = stroke)
            }
            RuntimeTab.Manual -> {
                drawRoundRect(color, Offset(size.width * 0.18f, size.height * 0.18f), Size(size.width * 0.64f, size.height * 0.64f), CornerRadius(5f, 5f), style = stroke)
                drawLine(color, Offset(size.width * 0.34f, size.height * 0.66f), Offset(size.width * 0.66f, size.height * 0.34f), strokeWidth = 2.4f, cap = StrokeCap.Round)
            }
        }
    }
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
private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 10.0) {
        "${gb.toInt()} GB"
    } else {
        "${String.format(Locale.US, "%.1f", gb)} GB"
    }
}

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun sessionLabel(count: Int, copy: Copy): String {
    if (copy.sessions == "sessions") {
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
    if (copy.previous == "Попередній") "Попередні" else copy.previous

private fun nextPlural(copy: Copy): String =
    if (copy.next == "Наступний") "Наступні" else copy.next

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
    accent = Color(0xFF2F86F6),
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
    accent = Color(0xFF2F86F6),
    green = Color(0xFF36CF88),
    greenSoft = Color(0xFFD8F4E7),
    yellow = Color(0xFFF1C04C),
    yellowSoft = Color(0xFFFFF1C9),
    red = Color(0xFFFF7C7C),
    redSoft = Color(0xFFFFE1E1),
    disabled = Color(0xFFE1E7EF)
)

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun enCopy() = Copy(
    title = "BYD HUD",
    subtitle = "HUD navigation output | v${BuildConfig.VERSION_NAME}",
    main = "Main",
    apps = "Apps",
    logs = "Logs",
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
    mainHint = "Runtime controls and navigation output preferences.",
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
    saveScreenshotsLogs = "Save screenshots and detailed logs",
    saveScreenshotsLogsHint = "Data for app debugging.",
    checkForUpdates = "Check for updates",
    checkForUpdatesHint = "Check for new version and offer updating",
    checkForUpdatesButton = "Check for updates",
    updateTitle = "Update",
    updateCurrentVersion = "Current version:",
    updateAvailableVersion = "Available version:",
    updateChecking = "Checking for update...",
    updateLatest = "This is the latest app version",
    updateDownloading = "Downloading update...",
    updateClose = "Close",
    updateAction = "Update",
    navigationOutput = "Navigation output",
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
    smallDistanceClamp = "Small distance clamp",
    smallDistanceHint = "Clamp distances below 20 m instead of OEM close marker.",
    roundaboutLeft = "Roundabout left-hand traffic",
    roundaboutHint = "Changes roundabout assets for PNG output.",
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
    storageFolderHint = "Day folders under /sdcard/Documents/BYD-HUD/nav-capture.",
    sortByDate = "Newest first",
    sortByName = "Oldest first",
    deleteSelected = "Delete selected",
    activeToday = "active today",
    sessions = "sessions",
    created = "created",
    folderSelected = "selected",
    folderNotSelected = "tap to select",
    storageNoDayFolders = "No day folders yet. New navigation logs will appear here after dated sessions are created.",
    storageDeleteTitle = "Delete selected",
    storageDeleteSelected = "Selected %d folders for deletion",
    storageDeleteQuestion = "Run deletion?",
    storageDeleteCannotStop = "After it starts, the operation cannot be stopped from the app.",
    storageDeleteYes = "Yes",
    storageDeleteNo = "No",
    storageDeletingFolder = "Deleting data folder",
    storageDeleteStep = "step %d/%d",
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
    subtitle = "Виведення навігації на HUD | v${BuildConfig.VERSION_NAME}",
    main = "Головна",
    apps = "Застосунки",
    logs = "Логи",
    manual = "Вручну",
    hudRunning = "HUD: працює",
    hudIdle = "HUD: очікує",
    hudFailed = "HUD: помилка",
    adbNotGranted = "ADB: не видано",
    permissionsOk = "Права: OK",
    permissionsMissing = "Права: немає",
    dark = "Темна",
    light = "Світла",
    mainHint = "Керування runtime та налаштування виводу навігації.",
    permissionsRuntime = "Дозволи та Runtime",
    adbPermissions = "ADB дозволи",
    adbHint = "Self-check автоматично видає потрібні дозволи, коли ADB авторизований.",
    grantAdb = "Видати ADB",
    backgroundApps = "Робота у фоні",
    backgroundHint = "Відкрити екран керування фоновою роботою.",
    disableBgApps = "Робота у фоні",
    setupDialogTitle = "Робота у фоні",
    setupDialogText = "Це потрібно перевірити після кожного встановлення або оновлення, інакше DiLink може зупинити HUD у фоні.",
    setupDialogInstruction = "Set Disable background Apps -> BYD HUD = OFF",
    setupDialogPrimary = "Відкрити",
    setupDialogDismiss = "Зрозуміло",
    bootRuntime = "Авто-запуск",
    bootRuntimeHint = "Запускати foreground HUD runtime після boot та watchdog подій.",
    saveScreenshotsLogs = "Зберігати скріншоти та детальні логи",
    saveScreenshotsLogsHint = "Дані для відладки роботи програми.",
    checkForUpdates = "Перевіряти оновлення",
    checkForUpdatesHint = "Перевіряти наявність нової версії та пропонувати оновитись",
    checkForUpdatesButton = "Перевірити оновлення",
    updateTitle = "Оновлення",
    updateCurrentVersion = "Поточна версія:",
    updateAvailableVersion = "Доступна версія:",
    updateChecking = "Перевіряємо оновлення...",
    updateLatest = "Це остання версія застосунку",
    updateDownloading = "Завантажуємо оновлення...",
    updateClose = "Закрити",
    updateAction = "Оновити",
    navigationOutput = "Вивід навігації",
    pngOutput = "PNG вивід",
    pngHint = "Надсилати зображення маневру.",
    nativeOutput = "Native вивід",
    nativeHint = "Надсилати штатне динамічне зображення маневру.",
    laneOutput = "Вивід смуг",
    laneHint = "Надсилати bitmap смуг для multi-lane guidance.",
    distanceOutput = "Вивід дистанції",
    distanceHint = "Надсилати поле дистанції до маневру у live navigation payload.",
    streetOutput = "Вивід вулиці",
    streetHint = "Надсилати наступну дорогу або Waze street text.",
    smallDistanceClamp = "Обрізка малої дистанції",
    smallDistanceHint = "Обрізати дистанції нижче 20 м замість OEM close marker.",
    roundaboutLeft = "Лівосторонній рух на кільці",
    appsHint = "Підтримувані застосунки можна активувати до запуску. Для приборки застосунок має бути у фоні.",
    lastScan = "Останній scan",
    refreshApps = "Оновити застосунки",
    supportedApps = "Підтримувані навігатори",
    allApps = "Всі фонові застосунки",
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
    logsHint = "Збір логів і шляхів до артефактів для тестерів.",
    logcatRecorder = "Запис logcat",
    recorderStatus = "Стан запису",
    waiting = "очікування",
    startLogcat = "Старт logcat",
    stopLogcat = "Стоп logcat",
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
    storageFolderHint = "Денні теки у /sdcard/Documents/BYD-HUD/nav-capture.",
    sortByDate = "Нові спочатку",
    sortByName = "Старі спочатку",
    deleteSelected = "Видалити вибране",
    activeToday = "активна сьогодні",
    sessions = "сесій",
    created = "створено",
    folderSelected = "вибрано",
    folderNotSelected = "натисни для вибору",
    storageNoDayFolders = "Денних тек ще немає. Нові навігаційні логи з'являться після створення сесій.",
    storageDeleteTitle = "Видалення вибраного",
    storageDeleteSelected = "Обрано %d тек для видалення",
    storageDeleteQuestion = "Виконати видалення?",
    storageDeleteCannotStop = "Після початку зупинити операцію із застосунку неможливо.",
    storageDeleteYes = "Так",
    storageDeleteNo = "Ні",
    storageDeletingFolder = "Видаляємо теку з даними",
    storageDeleteStep = "крок %d/%d",
    manualHint = "Пряма ручна перевірка HUD payload.",
    manualHudOutput = "Ручний HUD вивід",
    supportedArrows = "Підтримувані стрілки",
    supportedArrowsHint = "Попередній / Наступний одразу надсилає PNG+Native combo",
    manualLanes = "Ручні смуги",
    manualLanesHint = "Попередні / Наступні одразу надсилає lane bitmap",
    rawManeuverIds = "Сирі ID маневрів",
    rawManeuverHint = "Числові поля одразу формують Sxx / Nxx payload ID",
    manualMode = "Ручний режим",
    manualModeHint = "Коли увімкнено, ручні перемикачі одразу надсилають HUD payload. Вимкнення очищає ручний вивід і повертає live-навігацію.",
    pngNumber = "PNG номер",
    nativeNumber = "Native номер",
    distance = "Дистанція, м",
    street = "Текст вулиці",
    laneBitmap = "Bitmap смуг",
    previous = "Попередній",
    next = "Наступний",
    randomize = "Випадково",
    currentSelection = "Поточний вибір",
    manualPreview = "preview ручного виводу"
)
