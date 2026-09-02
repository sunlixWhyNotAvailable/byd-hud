package com.bydhud.app

//builds the runtime UI so operators can control capture, permissions, logs, and updates in one place.

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

//anchors BydHudRuntimeCompose UI orchestration so controls and diagnostics are wired from one place.
object BydHudRuntimeCompose {
    @JvmStatic
    //keeps update I/O here so network, file, and installer failures are handled in one path.
    fun install(activity: MainActivity) {
        val uiSession = RuntimeUiSession.PROCESS.getOrCreate {
            if (HudPrefs.takeOptionsIntroForCurrentVersion(activity)) {
                RuntimeTab.Options.name
            } else {
                RuntimeTab.Apps.name
            }
        }
        activity.setContent {
            RuntimeApp(activity, uiSession)
        }
    }
}

//defines class UI/state support so Compose code can keep rendering intent explicit.
private enum class RuntimeTab {
    Options,
    Apps,
    Storage,
    Patch,
    HudCheck
}

private enum class Language {
    Ua,
    En
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

private enum class StorageShareDestination {
    Sentry,
    Android
}

private enum class SentryUploadPhase {
    Preparing,
    Uploading,
    Success,
    Failure
}

private const val SENTRY_NAV_UPLOAD_COOLDOWN_MS = 30_000L

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
    val hudCheck: String,
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
    val dashboardWindowSize: String,
    val logs: String,
    val notice: String,
    val wazeDirectNotice: String,
    val wazeSupportedVersions: String,
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
    val textTransliteration: String,
    val textTransliterationHint: String,
    val textDirectionOutput: String,
    val textDirectionOutputHint: String,
    val showWazeAlerts: String,
    val showWazeAlertsHint: String,
    val tbtWithoutHudOutput: String,
    val tbtWithoutHudOutputHint: String,
    val switchToTbtOnHudStart: String,
    val switchToTbtOnHudStartHint: String,
    val showWholeRouteMetrics: String,
    val showWholeRouteMetricsHint: String,
    val showEta: String,
    val showEtaHint: String,
    val showRemainingTime: String,
    val showRemainingTimeHint: String,
    val showRemainingDistance: String,
    val showRemainingDistanceHint: String,
    val dashboardScreenMode: String,
    val dashboardScreenModeHint: String,
    val dashboardWidth: String,
    val dashboardWidthHint: String,
    val dashboardHeight: String,
    val dashboardHeightHint: String,
    val dashboardOffset: String,
    val dashboardOffsetHint: String,
    val dashboardScale: String,
    val dashboardScaleHint: String,
    val smallDistanceClamp: String,
    val smallDistanceHint: String,
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
    val navigatorAssetsNotice: String,
    val navigatorAssetDownload: String,
    val navigatorAssetInstall: String,
    val navigatorAssetInstalled: String,
    val navigatorAssetRetry: String,
    val navigatorAssetRestore: String,
    val navigatorAssetInstalling: String,
    val navigatorAssetVerifying: String,
    val navigatorAssetConfirmTitle: String,
    val navigatorAssetConfirmText: String,
    val navigatorAssetConfirmOk: String,
    val navigatorAssetConfirmCancel: String,
    val wazeFeatures: String,
    val customSurface: String,
    val customSurfaceHint: String,
    val hud: String,
    val log: String,
    val sendDashboard: String,
    val sendMain: String,
    val startAppFirst: String,
    val noBackgroundApps: String,
    val startLogcat: String,
    val stopLogcat: String,
    val shareConfiguration: String,
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
    val patchDirectChannel: String,
    val patchWazeAlerts: String,
    val patchClearSelection: String,
    val patchSelectFile: String,
    val patchSelectFileTitle: String,
    val patchSelectFileText: String,
    val patchUnsupportedFileText: String,
    val patchSelectionErrorText: String,
    val patchPatchable: String,
    val patchPatched: String,
    val patchFailed: String,
    val patchSource: String,
    val patchInstalledSource: String,
    val patchProgress: String,
    val patchRecovery: String,
    val patchRestore: String,
    val checkPatch: String,
    val applyPatch: String,
    val patchConfirmTitle: String,
    val patchConfirmText: String,
    val patchConfirmOk: String,
    val patchConfirmCancel: String,
    val hudCheckHint: String,
    val basicOutput: String,
    val basicOutputHint: String,
    val extendedOutput: String,
    val extendedOutputHint: String,
    val checkManeuvers: String,
    val checkManeuversHint: String,
    val checkManeuversChannels: String,
    val checkLanes: String,
    val checkLanesHint: String,
    val distanceMeters: String,
    val checkDistanceHint: String,
    val streetText: String,
    val checkStreetHint: String,
    val checkTransliteration: String,
    val checkTrafficLight: String,
    val checkTrafficLightHint: String,
    val stockImage: String,
    val bitmapImage: String,
    val hudCheckRunning: String,
    val hudCheckStopped: String,
    val hudCheckStart: String,
    val hudCheckStop: String,
    val checkBaseline: String,
    val currentField: String,
    val checkAutomatic: String,
    val checkManualCycle: String,
    val checkManualCycleHint: String,
    val checkCycle: String,
    val checkExtendedNotice: String,
    val previous: String,
    val next: String,
)

private data class ShareCopy(
    val shareLogsTitle: String,
    val shareLogsSelection: String,
    val shareLogsArchiveHint: String,
    val shareLogsSensitiveWarning: String,
    val shareLogsSentryNotice: String,
    val shareToSentry: String,
    val shareToAnotherApp: String,
    val cancel: String,
    val waitingForWrites: String,
    val copying: String,
    val archiving: String,
    val uploadTitle: String,
    val preparing: String,
    val uploading: String,
    val success: String,
    val failure: String,
    val reportId: String,
    val close: String,
    val configurationTitle: String,
    val configurationWarning: String,
    val configurationUploadTitle: String,
    val configurationSuccess: String,
    val configurationFailure: String
)

private data class OperationCardSpec(
    val key: String,
    val title: String,
    val phase: String,
    val detail: String,
    val startedAt: Long,
    val busy: Boolean,
    val stopEnabled: Boolean,
    val closeEnabled: Boolean,
    val failed: Boolean,
    val success: Boolean,
    val onStop: () -> Unit,
    val onClose: () -> Unit
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

private const val FOREGROUND_UI_REFRESH_REQUEST_MS = 30_000L

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
            .clearAndSetSemantics {}
    )
}

private val runtimeViewportKeys = listOf(
    "apps", "storage", "storage-days", "patch", "hud-check", "options-categories",
    "options:runtime-permissions", "options:basic-navigation", "options:route-eta",
    "options:speed-limit", "options:waze-features", "options:extra-navigation",
    "options:dashboard-window-profile", "options:dashboard-widget", "options:dashboard-move"
)

// Compose-owned state is recreated from data; it is never kept in the process holder.
private class SessionViewportState(val initial: RuntimeUiSession.Viewport) {
    val listState = LazyListState(initial.index, initial.offset)
    var restored by mutableStateOf(initial == RuntimeUiSession.Viewport.TOP)

    fun position(): RuntimeUiSession.Viewport? =
        if (restored && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            RuntimeUiSession.Viewport(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        } else null
}

@Composable
private fun rememberSessionViewport(
    session: RuntimeUiSession.Session,
    viewportKey: String,
    contentReady: Boolean
): SessionViewportState {
    val viewport = remember(session, viewportKey) { SessionViewportState(session.viewport(viewportKey)) }
    LaunchedEffect(viewport, contentReady) {
        if (contentReady && !viewport.restored) {
            // Cold placeholders must not clamp and overwrite a saved nonzero position.
            snapshotFlow { viewport.listState.layoutInfo.totalItemsCount }.first { it > 0 }
            viewport.listState.scrollToItem(viewport.initial.index, viewport.initial.offset)
            viewport.restored = true
        }
    }
    return viewport
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun RuntimeApp(activity: MainActivity, uiSession: RuntimeUiSession.Session) {
    var snapshot by remember { mutableStateOf(activity.composeSnapshot()) }
    var selectedTab by remember(uiSession) { mutableStateOf(RuntimeTab.valueOf(uiSession.selectedTab())) }
    var selectedOptionsSectionKey by remember(uiSession) { mutableStateOf(uiSession.selectedOptionsSection()) }
    fun viewportContentReady(viewportKey: String): Boolean = when (viewportKey) {
        "apps" -> snapshot.appScanCacheAvailable || !snapshot.appScanInProgress
        "storage", "storage-days" -> snapshot.storageCacheAvailable || !snapshot.storageCalculating
        else -> true
    }
    val viewportStates = runtimeViewportKeys.associateWith { viewportKey ->
        key(viewportKey) { rememberSessionViewport(uiSession, viewportKey, viewportContentReady(viewportKey)) }
    }
    val appsScrollState = viewportStates.getValue("apps").listState
    val storageScrollState = viewportStates.getValue("storage").listState
    val storageDayScrollState = viewportStates.getValue("storage-days").listState
    val patchScrollState = viewportStates.getValue("patch").listState
    val hudCheckScrollState = viewportStates.getValue("hud-check").listState
    val optionsCategoryScrollState = viewportStates.getValue("options-categories").listState
    val optionsSectionScrollStates = viewportStates.filterKeys { it.startsWith("options:") }
        .mapKeys { it.key.removePrefix("options:") }.mapValues { it.value.listState }
    var storageLimitDraft by remember(snapshot.storageLimitGb) {
        mutableIntStateOf(snapshot.storageLimitGb)
    }
    var previousTab by remember(uiSession) { mutableStateOf(selectedTab) }
    var storageSortOldestFirst by rememberSaveable { mutableStateOf(false) }
    var selectedStorageDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var handledStorageShareLaunchId by rememberSaveable { mutableStateOf(0L) }
    var pendingStorageDeleteDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageDeleteQueue by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageDeleteBusy by remember { mutableStateOf(false) }
    var storageDeleteCurrentDay by remember { mutableStateOf("") }
    var storageDeleteStep by remember { mutableStateOf(0) }
    var storageDeleteTotal by remember { mutableStateOf(0) }
    var storageShareDays by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var storageShareBusy by remember { mutableStateOf(false) }
    var storageShareSummary by remember {
        mutableStateOf<MainActivity.ComposeStorageShareSummary?>(null)
    }
    var storageShareDestination by remember { mutableStateOf<StorageShareDestination?>(null) }
    var storageSharePhase by remember { mutableStateOf<LogShareZip.Phase?>(null) }
    var storageShareStartedAt by rememberSaveable { mutableStateOf(0L) }
    var storageShareOperationToken by rememberSaveable { mutableStateOf(0L) }
    var storageShareTerminalPhase by rememberSaveable { mutableStateOf("") }
    var storageShareTerminalDetail by rememberSaveable { mutableStateOf("") }
    var sentryUploadPhase by remember { mutableStateOf<SentryUploadPhase?>(null) }
    var sentryUploadEventId by remember { mutableStateOf("") }
    var sentryUploadError by remember { mutableStateOf("") }
    var sentryUploadCooldownUntilMs by rememberSaveable { mutableStateOf(0L) }
    var sentryUploadCooldownRemaining by remember { mutableIntStateOf(0) }
    var sentryUploadingConfiguration by remember { mutableStateOf(false) }
    var configurationShareBusy by remember { mutableStateOf(false) }
    var configurationShareVisible by rememberSaveable { mutableStateOf(false) }
    var configurationShareDestination by remember {
        mutableStateOf<StorageShareDestination?>(null)
    }
    var logcatBusy by remember { mutableStateOf(false) }
    var liveHudStatus by remember { mutableStateOf(snapshot.hudStatus) }
    var showSetupDialog by rememberSaveable { mutableStateOf(activity.composeShouldShowBackgroundReminder()) }
    var autoUpdateCheckEnabled by rememberSaveable { mutableStateOf(AppUpdateManager.isAutoCheckEnabled(activity)) }
    var betaChannelEnabled by rememberSaveable { mutableStateOf(AppUpdateManager.isBetaChannelEnabled(activity)) }
    val updateSnapshot by AppUpdateManager.snapshot.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDownloadState by remember(updateSnapshot) { mutableStateOf<UpdateCheckState?>(null) }
    val updateState = updateDownloadState ?: if (updateSnapshot.checking) {
        UpdateCheckState.Checking
    } else {
        when (val result = updateSnapshot.result) {
            AppUpdateManager.CheckResult.UpToDate -> UpdateCheckState.Latest
            is AppUpdateManager.CheckResult.Available -> UpdateCheckState.Available(result.info)
            is AppUpdateManager.CheckResult.Error -> UpdateCheckState.Error(result.message)
            null -> UpdateCheckState.Checking
        }
    }
    var pendingPatchProfile by rememberSaveable { mutableStateOf("") }
    var pendingPatchDestructive by rememberSaveable { mutableStateOf(false) }
    var pendingPatchFileConfirmProfile by rememberSaveable { mutableStateOf("") }
    var pendingPatchFilePickerProfile by rememberSaveable { mutableStateOf("") }
    var patchSourceError by rememberSaveable { mutableStateOf("") }
    var patchActionPendingProfiles by remember { mutableStateOf(emptySet<String>()) }
    var pendingNavigatorAssetId by rememberSaveable { mutableStateOf("") }
    var navigatorAssetActionPending by remember { mutableStateOf(false) }
    var appInForeground by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val palette = remember(snapshot.darkTheme) {
        if (snapshot.darkTheme) darkPalette() else lightPalette()
    }
    val copy = remember(snapshot.uaLanguage) {
        if (snapshot.uaLanguage) uaCopy() else enCopy()
    }
    val shareCopy = remember(copy.language) { shareCopy(copy.language) }
    val blockingUiFlow = when {
        showSetupDialog -> "setup"
        showUpdateDialog -> "update"
        pendingStorageDeleteDays.isNotEmpty() || storageDeleteBusy -> "storage-delete"
        configurationShareVisible || configurationShareBusy
            || (sentryUploadPhase != null && sentryUploadingConfiguration) -> "configuration-share"
        pendingNavigatorAssetId.isNotEmpty() || navigatorAssetActionPending -> "navigator-asset"
        pendingPatchFileConfirmProfile.isNotEmpty() || patchSourceError.isNotEmpty()
            || pendingPatchProfile.isNotEmpty() -> "patch"
        else -> ""
    }

    fun captureUiSession() {
        uiSession.select(selectedTab.name, selectedOptionsSectionKey)
        viewportStates.forEach { (viewportKey, viewport) ->
            uiSession.recordViewport(viewportKey, viewport.position())
        }
    }
    val latestCaptureUiSession by rememberUpdatedState(::captureUiSession)

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    fun refresh() {
        val refreshed = activity.composeSnapshot()
        if (snapshot != refreshed) snapshot = refreshed
        if (liveHudStatus != refreshed.hudStatus) liveHudStatus = refreshed.hudStatus
    }

    fun requestTabStateRefresh(tab: RuntimeTab, reason: String) {
        when (tab) {
            RuntimeTab.Apps -> activity.composeRequestRuntimeUiStateRefresh(false, reason)
            RuntimeTab.Storage -> activity.composeRequestStorageRefresh(false)
            RuntimeTab.Patch -> activity.composeRequestPatchUiStateRefresh(reason)
            RuntimeTab.Options,
            RuntimeTab.HudCheck -> Unit
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    fun runAction(action: () -> Unit) {
        action()
        refresh()
    }

    fun startNavigatorAssetDownload(assetId: String) {
        if (navigatorAssetActionPending) return
        try {
            activity.composeStartNavigatorAssetDownload(assetId)
            refresh()
        } catch (error: Exception) {
            activity.composeAppendStatus("Navigator download failed: ${error.message}")
            refresh()
        }
    }

    fun installNavigatorAsset(assetId: String) {
        if (navigatorAssetActionPending) return
        if (!activity.composeCanInstallNavigatorApks()) {
            activity.composeOpenNavigatorInstallPermission()
            return
        }
        navigatorAssetActionPending = true
        updateScope.launch {
            try {
                val destructive = withContext(Dispatchers.IO) {
                    activity.composeNavigatorAssetRequiresDestructiveConfirm(assetId)
                }
                if (destructive) {
                    pendingNavigatorAssetId = assetId
                } else {
                    withContext(Dispatchers.IO) {
                        activity.composeInstallNavigatorAsset(assetId, false)
                    }
                }
            } catch (error: Exception) {
                activity.composeAppendStatus("Navigator install check failed: ${error.message}")
            } finally {
                navigatorAssetActionPending = false
                refresh()
            }
        }
    }

    fun restoreNavigatorAsset(assetId: String) {
        if (navigatorAssetActionPending) return
        if (!activity.composeCanInstallNavigatorApks()) {
            activity.composeOpenNavigatorInstallPermission()
            return
        }
        navigatorAssetActionPending = true
        updateScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activity.composeRestoreNavigatorAsset(assetId)
                }
            } catch (error: Exception) {
                activity.composeAppendStatus("Navigator restore failed: ${error.message}")
            } finally {
                navigatorAssetActionPending = false
                refresh()
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val profileId = pendingPatchFilePickerProfile
        pendingPatchFilePickerProfile = ""
        if (uri == null) {
            patchActionPendingProfiles = patchActionPendingProfiles - profileId
            return@rememberLauncherForActivityResult
        }
        if (profileId.isEmpty()) return@rememberLauncherForActivityResult
        updateScope.launch {
            try {
                val displayName = withContext(Dispatchers.IO) {
                    activity.composePatchSourceDisplayName(uri)
                }
                patchActionPendingProfiles = patchActionPendingProfiles + profileId
                withContext(Dispatchers.IO) {
                    activity.composeSelectPatchSource(profileId, uri, displayName)
                }
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                patchSourceError = if (message.startsWith("Unsupported source format")) {
                    copy.patchUnsupportedFileText
                } else {
                    message.ifEmpty { copy.patchSelectionErrorText }
                }
            } finally {
                patchActionPendingProfiles = patchActionPendingProfiles - profileId
            }
            refresh()
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
        storageShareTerminalPhase = ""
        storageShareTerminalDetail = ""
        storageShareDays = days
        storageShareBusy = true
        storageShareDestination = null
        updateScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    activity.composeDescribeStorageShareDays(days)
                }
                if (summary.ok) {
                    storageShareSummary = summary
                } else {
                    storageShareDays = emptyList()
                    activity.composeAppendStatus("Storage share failed: ${summary.detail}")
                }
            } catch (error: Exception) {
                storageShareDays = emptyList()
                storageShareDestination = null
                storageShareSummary = null
                activity.composeAppendStatus(
                    "Storage share failed: ${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                storageShareBusy = false
            }
        }
    }

    fun runLogcatAction(start: Boolean) {
        if (logcatBusy) return
        logcatBusy = true
        updateScope.launch {
            withContext(Dispatchers.IO) {
                if (start) activity.composeStartLogcat() else activity.composeStopLogcat()
            }
            logcatBusy = false
            refresh()
        }
    }

    fun beginConfigurationShare(destination: StorageShareDestination) {
        if (configurationShareBusy) return
        configurationShareVisible = false
        configurationShareDestination = destination
        configurationShareBusy = true
        sentryUploadingConfiguration = destination == StorageShareDestination.Sentry
        if (destination == StorageShareDestination.Sentry) {
            sentryUploadEventId = ""
            sentryUploadError = ""
            sentryUploadPhase = SentryUploadPhase.Preparing
        }
    }

    LaunchedEffect(sentryUploadCooldownUntilMs) {
        val deadline = sentryUploadCooldownUntilMs
        if (deadline <= 0L) {
            sentryUploadCooldownRemaining = 0
            return@LaunchedEffect
        }
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            sentryUploadCooldownRemaining = ((remainingMs + 999L) / 1000L)
                .toInt()
                .coerceIn(1, 30)
            delay(minOf(1000L, remainingMs))
        }
        sentryUploadCooldownRemaining = 0
        sentryUploadCooldownUntilMs = 0L
    }

    LaunchedEffect(blockingUiFlow) {
        activity.composeReportMainUiState(blockingUiFlow)
    }

    LaunchedEffect(activity, updateSnapshot.dialogRequested, appInForeground, showSetupDialog) {
        if (!updateSnapshot.dialogRequested || !appInForeground || showSetupDialog) {
            showUpdateDialog = false
            return@LaunchedEffect
        }
        while (!activity.composeTryStartBlockingUiFlow("update")) {
            delay(250L)
        }
        showUpdateDialog = true
    }

    LaunchedEffect(uiSession) {
        snapshotFlow {
            (selectedTab to selectedOptionsSectionKey) to viewportStates.mapValues { it.value.position() }
        }.collect { latestCaptureUiSession() }
    }

    LaunchedEffect(selectedTab) {
        if (previousTab == RuntimeTab.HudCheck && selectedTab != RuntimeTab.HudCheck) {
            runAction { activity.composeHudCheckStop("tab-change") }
        }
        previousTab = selectedTab
        val reason = "tab-${selectedTab.name.lowercase(Locale.ROOT)}"
        requestTabStateRefresh(selectedTab, reason)
    }

    LaunchedEffect(snapshot.shareLaunchId, snapshot.shareLaunchDays) {
        val launchId = snapshot.shareLaunchId
        if (launchId <= handledStorageShareLaunchId || launchId == 0L) {
            return@LaunchedEffect
        }
        val sharedDays = snapshot.shareLaunchDays.toSet()
        if (sharedDays.isNotEmpty()) {
            selectedStorageDays = selectedStorageDays.filterNot { it in sharedDays }
        }
        handledStorageShareLaunchId = launchId
        activity.composeAcknowledgeShareLaunch(launchId)
    }

    DisposableEffect(activity) {
        activity.composeSetSnapshotInvalidationListener { refresh() }
        appInForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            //guard auto-check so background launches do not show update UI over a hidden app.
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    appInForeground = true
                    when (latestSelectedTab) {
                        RuntimeTab.Storage -> activity.composeRequestStorageRefresh(false)
                        RuntimeTab.Patch -> activity.composeRequestPatchUiStateRefresh(
                            false,
                            "activity-resume-patch"
                        )
                        RuntimeTab.Apps -> activity.composeRequestPatchUiStateRefresh(
                            "activity-resume-apps"
                        )
                        RuntimeTab.Options,
                        RuntimeTab.HudCheck -> Unit
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    latestCaptureUiSession()
                    appInForeground = false
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            latestCaptureUiSession()
            activity.lifecycle.removeObserver(observer)
            activity.composeSetSnapshotInvalidationListener(null)
        }
    }

    LaunchedEffect(appInForeground) {
        if (!appInForeground) {
            return@LaunchedEffect
        }
        var lastBackgroundRequestAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000L)
            val assetStateChanging = snapshot.navigatorAssets.any {
                    it.state == NavigatorAssetManager.DOWNLOADING
                            || it.state == NavigatorAssetManager.VERIFYING
                            || it.state == NavigatorAssetManager.INSTALL_REQUESTED
                            || it.state == NavigatorAssetManager.UNINSTALL_REQUESTED
            }
            val patchWasBusy = snapshot.patchOperations.any { it.busy }
            val assetCacheMissing = snapshot.navigatorAssets.isEmpty()
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackgroundRequestAt >= FOREGROUND_UI_REFRESH_REQUEST_MS) {
                activity.composeRequestRuntimeUiStateRefresh(false, "foreground-periodic")
                if (assetCacheMissing) {
                    activity.composeRequestPatchUiStateRefresh(
                        false,
                        "foreground-asset-bootstrap"
                    )
                }
                lastBackgroundRequestAt = now
            }
            if (assetStateChanging || patchWasBusy) {
                activity.composeRequestNavigatorAssetUiStateRefresh("foreground-asset-periodic")
            }
            val deliveryStatus = activity.composeHudDeliveryStatus()
            if (liveHudStatus != deliveryStatus) {
                liveHudStatus = deliveryStatus
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
        refresh()
    }

    LaunchedEffect(storageShareBusy, storageShareDays, storageShareDestination) {
        val destination = storageShareDestination
        val operationToken = storageShareOperationToken
        if (!storageShareBusy || destination == null) {
            return@LaunchedEffect
        }
        try {
            if (destination == StorageShareDestination.Sentry) {
                val result = runInterruptible(Dispatchers.IO) {
                    LogShareZip.attachProgressListener { phase ->
                        activity.runOnUiThread { storageSharePhase = phase }
                    }
                    try {
                        activity.composeUploadStorageDaysToSentry(
                            storageShareDays,
                            operationToken
                        ) {
                            activity.runOnUiThread {
                                storageSharePhase = null
                                sentryUploadPhase = SentryUploadPhase.Uploading
                            }
                        }
                    } finally {
                        LogShareZip.clearProgressListener()
                    }
                }
                sentryUploadEventId = result.eventId
                sentryUploadError = result.detail
                sentryUploadPhase = if (result.ok) {
                    SentryUploadPhase.Success
                } else {
                    SentryUploadPhase.Failure
                }
                activity.composeAppendStatus("Sentry log upload: ${result.detail}")
            } else {
                val detail = runInterruptible(Dispatchers.IO) {
                    LogShareZip.attachProgressListener { phase ->
                        activity.runOnUiThread { storageSharePhase = phase }
                    }
                    try {
                        activity.composeShareStorageDays(storageShareDays, operationToken)
                    } finally {
                        LogShareZip.clearProgressListener()
                    }
                }
                activity.composeAppendStatus("Storage share: $detail")
            }
        } catch (cancelled: CancellationException) {
            storageShareTerminalPhase = "CANCELLED"
            storageShareTerminalDetail = if (copy.language == Language.Ua) {
                "Операцію скасовано"
            } else {
                "Operation cancelled"
            }
            activity.composeAppendStatus("Storage share cancelled")
            throw cancelled
        } catch (error: Exception) {
            val detail = error.message ?: error.javaClass.simpleName
            storageShareTerminalPhase = "FAILED"
            storageShareTerminalDetail = detail
            if (destination == StorageShareDestination.Sentry) {
                sentryUploadError = detail
                sentryUploadPhase = SentryUploadPhase.Failure
            }
            activity.composeAppendStatus("Storage share failed: $detail")
        } finally {
            storageSharePhase = null
            storageShareBusy = false
            storageShareDays = emptyList()
            storageShareDestination = null
            storageShareOperationToken = 0L
            refresh()
        }
    }

    LaunchedEffect(configurationShareBusy, configurationShareDestination) {
        val destination = configurationShareDestination
        if (!configurationShareBusy || destination == null) {
            return@LaunchedEffect
        }
        try {
            if (destination == StorageShareDestination.Sentry) {
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    activity.composeUploadVehicleConfigurationToSentry {
                        activity.runOnUiThread { sentryUploadPhase = SentryUploadPhase.Uploading }
                    }
                }
                sentryUploadEventId = result.eventId
                sentryUploadError = result.detail
                sentryUploadPhase = if (result.ok) {
                    SentryUploadPhase.Success
                } else {
                    SentryUploadPhase.Failure
                }
                activity.composeAppendStatus("Sentry configuration upload: ${result.detail}")
            } else {
                val detail = withContext(Dispatchers.IO + NonCancellable) {
                    activity.composeShareVehicleConfiguration()
                }
                activity.composeAppendStatus("Configuration share: $detail")
            }
        } catch (error: Exception) {
            val detail = error.message ?: error.javaClass.simpleName
            if (destination == StorageShareDestination.Sentry) {
                sentryUploadError = detail
                sentryUploadPhase = SentryUploadPhase.Failure
            }
            activity.composeAppendStatus("Configuration share failed: $detail")
        } finally {
            configurationShareBusy = false
            configurationShareDestination = null
            refresh()
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
                        selectedSectionKey = selectedOptionsSectionKey,
                        onSelectedSectionKeyChange = { selectedOptionsSectionKey = it },
                        categoryScrollState = optionsCategoryScrollState,
                        sectionScrollStates = optionsSectionScrollStates,
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
                        onManualUpdateCheck = { AppUpdateManager.requestManualCheck(activity) },
                        onDisableBgApps = {
                            if (activity.composeTryStartBlockingUiFlow("setup")) {
                                showSetupDialog = true
                            }
                        },
                        onShutdownClick = { runAction { activity.composeShutdownAndExit() } },
                        dashboardWidget = snapshot.dashboardWidgetState,
                        widgetOverlayPermission = snapshot.dashboardWidgetOverlayPermission,
                        onDashboardWidgetChange = { value ->
                            runAction { activity.composeSetDashboardWidgetState(value) }
                        },
                        onWidgetPermissionRequest = {
                            runAction { activity.composeRequestDashboardWidgetPermission() }
                        }
                    )
                    RuntimeTab.Apps -> AppsTab(
                        copy = copy,
                        palette = palette,
                        scrollState = appsScrollState,
                        snapshot = snapshot,
                        activity = activity,
                        runAction = ::runAction,
                        onDownloadAsset = ::startNavigatorAssetDownload,
                        onInstallAsset = ::installNavigatorAsset,
                        onRestoreAsset = ::restoreNavigatorAsset
                    )
                    RuntimeTab.Storage -> StorageTab(
                        copy = copy,
                        palette = palette,
                        scrollState = storageScrollState,
                        dayScrollState = storageDayScrollState,
                        snapshot = snapshot,
                        configurationShareBusy = configurationShareBusy,
                        logcatBusy = logcatBusy,
                        onStartLogcat = { runLogcatAction(true) },
                        onStopLogcat = { runLogcatAction(false) },
                        onShareConfiguration = {
                            if (!configurationShareBusy
                                && activity.composeTryStartBlockingUiFlow("configuration-share")) {
                                configurationShareVisible = true
                            }
                        },
                        sortOldestFirst = storageSortOldestFirst,
                        selectedDays = selectedStorageDays,
                        storageActionBusy = storageDeleteBusy || storageShareBusy,
                        storageSortBusy = storageDeleteBusy,
                        storageLimitDraft = storageLimitDraft,
                        onStorageLimitDraftChange = { storageLimitDraft = it },
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
                    RuntimeTab.Patch -> PatchTab(
                        copy = copy,
                        palette = palette,
                        scrollState = patchScrollState,
                        snapshot = snapshot,
                        actionPendingProfiles = patchActionPendingProfiles,
                        onSelectFile = { profileId ->
                            if (!patchActionPendingProfiles.contains(profileId)) {
                                pendingPatchFileConfirmProfile = profileId
                            }
                        },
                        onClear = { profileId ->
                            runAction { activity.composeClearPatchSource(profileId) }
                        },
                        onCheck = { profileId ->
                            if (!patchActionPendingProfiles.contains(profileId)) {
                                patchActionPendingProfiles = patchActionPendingProfiles + profileId
                                updateScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            activity.composeCheckNavigatorPatch(profileId)
                                        }
                                    } catch (error: Exception) {
                                        activity.composeAppendStatus("Patch check failed: ${error.message}")
                                    } finally {
                                        patchActionPendingProfiles =
                                            patchActionPendingProfiles - profileId
                                    }
                                    refresh()
                                }
                            }
                        },
                        onPatch = { profileId ->
                            if (!activity.composeCanInstallNavigatorApks()) {
                                activity.composeOpenNavigatorInstallPermission()
                            } else {
                                pendingPatchProfile = profileId
                                pendingPatchDestructive =
                                    activity.composeNavigatorPatchIsDestructive(profileId)
                            }
                        },
                        onRestore = { profileId ->
                            if (!activity.composeCanInstallNavigatorApks()) {
                                activity.composeOpenNavigatorInstallPermission()
                            } else if (!patchActionPendingProfiles.contains(profileId)) {
                                patchActionPendingProfiles = patchActionPendingProfiles + profileId
                                updateScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            activity.composeRestoreNavigator(profileId)
                                        }
                                    } catch (error: Exception) {
                                        activity.composeAppendStatus("Restore failed: ${error.message}")
                                    } finally {
                                        patchActionPendingProfiles =
                                            patchActionPendingProfiles - profileId
                                    }
                                    refresh()
                                }
                            }
                        }
                    )
                    RuntimeTab.HudCheck -> HudCheckTab(
                        copy = copy,
                        palette = palette,
                        scrollState = hudCheckScrollState,
                        snapshot = snapshot,
                        activity = activity,
                        runAction = ::runAction
                    )
                }
            }

            BottomTabs(copy, palette, selectedTab) { selectedTab = it }
        }

        OperationProgressStack(
            copy = copy,
            shareCopy = shareCopy,
            palette = palette,
            patchOperations = snapshot.patchOperations,
            storageSharePhase = storageSharePhase,
            storageShareTerminalPhase = storageShareTerminalPhase,
            storageShareTerminalDetail = storageShareTerminalDetail,
            storageShareStartedAt = storageShareStartedAt,
            onCancelShare = {
                activity.composeCancelStorageShareOperation(storageShareOperationToken)
                storageShareTerminalPhase = "CANCELLED"
                storageShareTerminalDetail = if (copy.language == Language.Ua) {
                    "Операцію скасовано"
                } else {
                    "Operation cancelled"
                }
                storageShareBusy = false
                if (sentryUploadPhase == SentryUploadPhase.Preparing) {
                    sentryUploadPhase = null
                }
            },
            onCloseShare = {
                storageShareTerminalPhase = ""
                storageShareTerminalDetail = ""
                storageShareStartedAt = 0L
            },
            onCancelPatch = { profileId ->
                updateScope.launch {
                    withContext(Dispatchers.IO) {
                        activity.composeCancelNavigatorPatch(profileId)
                    }
                    refresh()
                }
            },
            onDismissPatch = { profileId ->
                updateScope.launch {
                    withContext(Dispatchers.IO) {
                        activity.composeDismissNavigatorPatch(profileId)
                    }
                    refresh()
                }
            }
        )

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
                uaLanguage = snapshot.uaLanguage,
                state = updateState,
                onUpdate = {
                    val available = updateState
                    if (available is UpdateCheckState.Available) {
                        updateDownloadState = UpdateCheckState.Downloading(available.info, "0%")
                        updateScope.launch {
                            try {
                                AppUpdateManager.downloadAndInstall(activity, available.info) { progress ->
                                    updateDownloadState = UpdateCheckState.Downloading(available.info, progress)
                                }
                            } catch (e: Exception) {
                                updateDownloadState = UpdateCheckState.Error(e.message ?: "Download failed")
                            }
                        }
                    }
                },
                onClose = {
                    AppUpdateManager.dismissResult()
                    showUpdateDialog = false
                }
            )
        }

        if (pendingPatchProfile.isNotEmpty()) {
            val row = snapshot.patchRows.firstOrNull { it.profileId == pendingPatchProfile }
            PatchConfirmOverlay(
                copy = copy,
                palette = palette,
                navigator = row?.label ?: pendingPatchProfile,
                destructive = pendingPatchDestructive,
                onConfirm = {
                    if (!patchActionPendingProfiles.contains(pendingPatchProfile)) {
                        val profileId = pendingPatchProfile
                        val destructiveApproved = pendingPatchDestructive
                        pendingPatchProfile = ""
                        patchActionPendingProfiles = patchActionPendingProfiles + profileId
                        updateScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    activity.composeApplyNavigatorPatch(
                                        profileId,
                                        destructiveApproved
                                    )
                                }
                            } catch (error: Exception) {
                                activity.composeAppendStatus("Patch failed: ${error.message}")
                            } finally {
                                patchActionPendingProfiles =
                                    patchActionPendingProfiles - profileId
                            }
                            refresh()
                        }
                    }
                },
                onDismiss = { pendingPatchProfile = "" }
            )
        }

        if (pendingNavigatorAssetId.isNotEmpty()) {
            val asset = snapshot.navigatorAssets.firstOrNull { it.id == pendingNavigatorAssetId }
            if (asset != null) {
                NavigatorAssetConfirmOverlay(
                    copy = copy,
                    palette = palette,
                    asset = asset,
                    onConfirm = {
                        if (!navigatorAssetActionPending) {
                            val assetId = pendingNavigatorAssetId
                            pendingNavigatorAssetId = ""
                            navigatorAssetActionPending = true
                            updateScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        activity.composeInstallNavigatorAsset(assetId, true)
                                    }
                                } catch (error: Exception) {
                                    activity.composeAppendStatus(
                                        "Navigator install failed: ${error.message}"
                                    )
                                } finally {
                                    navigatorAssetActionPending = false
                                }
                                refresh()
                            }
                        }
                    },
                    onDismiss = { pendingNavigatorAssetId = "" }
                )
            }
        }

        if (pendingPatchFileConfirmProfile.isNotEmpty()) {
            PatchFileSelectionOverlay(
                copy = copy,
                palette = palette,
                onConfirm = {
                    val profileId = pendingPatchFileConfirmProfile
                    pendingPatchFileConfirmProfile = ""
                    pendingPatchFilePickerProfile = profileId
                    apkPicker.launch(arrayOf(
                        "application/vnd.android.package-archive",
                        "application/zip",
                        "application/octet-stream"
                    ))
                },
                onDismiss = { pendingPatchFileConfirmProfile = "" }
            )
        }

        if (patchSourceError.isNotEmpty()) {
            PatchFileErrorOverlay(
                copy = copy,
                palette = palette,
                message = patchSourceError,
                onClose = { patchSourceError = "" }
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

        storageShareSummary?.let { summary ->
            val sentryCooldownActive = sentryUploadCooldownUntilMs > SystemClock.elapsedRealtime()
            val sentryButtonRemaining = if (sentryCooldownActive) {
                sentryUploadCooldownRemaining.coerceAtLeast(1)
            } else {
                0
            }
            StorageShareDestinationOverlay(
                copy = copy,
                shareCopy = shareCopy,
                palette = palette,
                summary = summary,
                onSentry = {
                    val now = SystemClock.elapsedRealtime()
                    if (sentryUploadCooldownUntilMs <= now) {
                        sentryUploadCooldownUntilMs = now + SENTRY_NAV_UPLOAD_COOLDOWN_MS
                        sentryUploadCooldownRemaining = 30
                        storageShareSummary = null
                        sentryUploadingConfiguration = false
                        sentryUploadEventId = ""
                        sentryUploadError = ""
                        sentryUploadPhase = SentryUploadPhase.Preparing
                        storageShareDestination = StorageShareDestination.Sentry
                        storageSharePhase = LogShareZip.Phase.WAITING_FOR_WRITES
                        storageShareStartedAt = System.currentTimeMillis()
                        storageShareOperationToken = activity.composeBeginStorageShareOperation()
                        storageShareTerminalPhase = ""
                        storageShareBusy = true
                    }
                },
                sentryButtonText = if (sentryButtonRemaining > 0) {
                    sentryButtonRemaining.toString()
                } else {
                    shareCopy.shareToSentry
                },
                sentryButtonEnabled = sentryButtonRemaining == 0,
                onAnotherApp = {
                    storageShareSummary = null
                    storageShareDestination = StorageShareDestination.Android
                    storageSharePhase = LogShareZip.Phase.WAITING_FOR_WRITES
                    storageShareStartedAt = System.currentTimeMillis()
                    storageShareOperationToken = activity.composeBeginStorageShareOperation()
                    storageShareTerminalPhase = ""
                    storageShareBusy = true
                },
                onCancel = {
                    storageShareSummary = null
                    storageShareDays = emptyList()
                }
            )
        }

        sentryUploadPhase?.let { phase ->
            if (storageSharePhase != null && !sentryUploadingConfiguration) return@let
            SentryUploadOverlay(
                copy = shareCopy,
                palette = palette,
                phase = phase,
                eventId = sentryUploadEventId,
                error = sentryUploadError,
                configuration = sentryUploadingConfiguration,
                onClose = {
                    sentryUploadPhase = null
                    sentryUploadingConfiguration = false
                }
            )
        }

        if (configurationShareVisible) {
            ConfigurationShareDestinationOverlay(
                copy = shareCopy,
                palette = palette,
                onSentry = { beginConfigurationShare(StorageShareDestination.Sentry) },
                onAnotherApp = { beginConfigurationShare(StorageShareDestination.Android) },
                onCancel = { configurationShareVisible = false }
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
            // ADB reflects persisted authorization evidence; Permissions reflects current grants.
            Pill(if (snapshot.adbAuthorized) copy.adbOk else copy.adbNotGranted,
                if (snapshot.adbAuthorized) palette.green else palette.red,
                if (snapshot.adbAuthorized) palette.greenSoft else palette.redSoft)
            Pill(if (snapshot.settingsPermissionsGranted) copy.permissionsOk else copy.permissionsMissing,
                if (snapshot.settingsPermissionsGranted) palette.green else palette.red,
                if (snapshot.settingsPermissionsGranted) palette.greenSoft else palette.redSoft)
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
    selectedSectionKey: String,
    onSelectedSectionKeyChange: (String) -> Unit,
    categoryScrollState: LazyListState,
    sectionScrollStates: Map<String, LazyListState>,
    autoUpdateCheckEnabled: Boolean,
    onAutoUpdateCheckChange: (Boolean) -> Unit,
    betaChannelEnabled: Boolean,
    onBetaChannelChange: (Boolean) -> Unit,
    onManualUpdateCheck: () -> Unit,
    onDisableBgApps: () -> Unit,
    onShutdownClick: () -> Unit,
    dashboardWidget: DashboardWidgetState,
    widgetOverlayPermission: Boolean,
    onDashboardWidgetChange: (DashboardWidgetState) -> Unit,
    onWidgetPermissionRequest: () -> Unit
) {
    val ua = copy.language == Language.Ua
    var widgetColorTarget by remember { mutableStateOf<Boolean?>(null) }
    var showSteeringButtonCapture by remember { mutableStateOf(false) }
    var steeringCaptureRevision by remember { mutableStateOf(0L) }
    var steeringCaptureLearningRevision by remember { mutableStateOf(0L) }
    val steeringLearningRevision = NavAccessibilityService.keyLearningRevision()
    val steeringProfiles = if (ua) {
        listOf("Обраний профіль", "Частковий", "Повний")
    } else {
        listOf("Selected profile", "Partial", "Full")
    }
    val steeringProfileIndex = when (snapshot.steeringTransferProfile) {
        SteeringTransferPreferences.PROFILE_PARTIAL -> 1
        SteeringTransferPreferences.PROFILE_FULL -> 2
        else -> 0
    }
    LaunchedEffect(
        showSteeringButtonCapture,
        snapshot.steeringTransferRevision,
        snapshot.steeringButtonLearning,
        steeringLearningRevision
    ) {
        if (showSteeringButtonCapture
            && !snapshot.steeringButtonLearning
            && (snapshot.steeringTransferRevision > steeringCaptureRevision
                || steeringLearningRevision > steeringCaptureLearningRevision)
        ) {
            showSteeringButtonCapture = false
        }
    }
    DisposableEffect(showSteeringButtonCapture) {
        val wasVisible = showSteeringButtonCapture
        onDispose {
            if (wasVisible) {
                activity.composeCancelSteeringButtonLearning()
            }
        }
    }
    val routeMetricModes = if (ua) {
        listOf("Вимкнений", "До зупинки", "Весь маршрут")
    } else {
        listOf("Off", "Next stop", "Entire route")
    }
    val textTransliterationModes = if (ua) {
        listOf("Вимкнено", "Українська", "Універсальна")
    } else {
        listOf("Off", "Ukrainian", "Universal")
    }
    val speedLimitModes = if (ua) {
        listOf("Вимкнено", "У полі з маневром", "У полі зі смугами", "У вільному полі", "Композитний")
    } else {
        listOf("Off", "In maneuver field", "In lane field", "In a free field", "Composite")
    }
    val speedLimitFallbackModes = if (ua) {
        listOf("Вимкнено", "У полі з маневром", "У полі зі смугами")
    } else {
        listOf("Off", "In maneuver field", "In lane field")
    }
    val speedLimitCompositePlacementModes = if (ua) {
        listOf("Тільки маневру", "Тільки смуг", "Вільне або маневру", "Вільне або смуг")
    } else {
        listOf("Maneuver only", "Lanes only", "Free or maneuver", "Free or lanes")
    }
    val dashboardScreenModes = if (ua) {
        listOf("Немає", "Частковий", "Повний")
    } else {
        listOf("None", "Partial", "Full")
    }
    val routeMetricsTitle = if (ua) {
        "Режим виводу ЕТА/часу/дистанції"
    } else {
        "ETA/time/distance output mode"
    }
    val routeMetricsHint = if (ua) {
        "До зупинки показує значення до наступної проміжної або кінцевої точки; весь маршрут - до кінцевої точки. Waze підтримує весь маршрут і використовує доступне значення до зупинки, якщо окремий показник маршруту відсутній."
    } else {
        "Next stop uses the next intermediate or final stop; entire route uses the final destination. Waze supports the whole route and uses an available next-stop value when an individual route metric is missing."
    }
    val speedLimitModeHint = if (ua) {
        "Показувати поточне обмеження швидкості у вибраному полі HUD."
    } else {
        "Show the current speed limit in the selected HUD field."
    }
    val freeFallbackHint = if (ua) {
        "Визначає, чи можна тимчасово перекрити зайняте поле, коли вільного поля немає."
    } else {
        "Choose whether to temporarily replace an occupied field when no field is free."
    }
    val overlaySecondsHint = if (ua) {
        "Кількість секунд показу обмеження поверх активного маневру або смуг. Ціле число від 1 до 10."
    } else {
        "Seconds to show the speed limit over an active maneuver or lane output. Whole numbers from 1 to 10."
    }
    val compositePlacementHint = if (ua) {
        "Визначає поле для композитного знаку та пріоритет, коли одне з полів вільне."
    } else {
        "Choose the composite sign field and its priority when one field is free."
    }
    val compositeManeuverSizeHint = if (ua) {
        "Розмір композитного знаку у пікселях для зображення маневру. Дозволено ціле число від 1 до 103."
    } else {
        "Composite sign size in pixels for the maneuver image. Whole numbers from 1 to 103 only."
    }
    val compositeLaneSizeHint = if (ua) {
        "Розмір композитного знаку у пікселях для зображення смуг. Дозволено ціле число від 1 до 36."
    } else {
        "Composite sign size in pixels for the lane image. Whole numbers from 1 to 36 only."
    }
    val freeFallbackEnabled = snapshot.speedLimitMode == 3
    val compositeEnabled = snapshot.speedLimitMode == HudPrefs.SPEED_LIMIT_COMPOSITE
    val overlaySecondsEnabled = snapshot.speedLimitMode in 1..2
            || (freeFallbackEnabled && snapshot.speedLimitFreeFallback != 0)

    val sections = buildOptionsSections {
        optionsSection("runtime-permissions", copy.permissionsRuntime, R.drawable.ic_options_build) {
            row("adb-permissions") {
                SettingRow(copy.adbPermissions, copy.adbHint, palette) {
                    HudButton(copy.grantAdb, palette, primary = true, width = 190.dp) {
                        runAction { activity.composeGrantAdb() }
                    }
                }
            }
            row("background-apps") {
                SettingRow(copy.backgroundApps, copy.backgroundHint, palette) {
                    HudButton(copy.disableBgApps, palette, width = 190.dp, onClick = onDisableBgApps)
                }
            }
            row("boot-runtime") {
                SwitchRow(copy.bootRuntime, copy.bootRuntimeHint, snapshot.bootEnabled, palette) {
                    runAction { activity.composeSetBootEnabled(it) }
                }
            }
            row("diagnostic-artifacts") {
                SwitchRow(
                    copy.saveScreenshotsLogs,
                    copy.saveScreenshotsLogsHint,
                    snapshot.detailedDebugArtifactsEnabled,
                    palette
                ) {
                    runAction { activity.composeSetDetailedDebugArtifactsEnabled(it) }
                }
            }
            row("check-for-updates") {
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
            row("beta-testing") {
                SwitchRow(copy.betaTesting, copy.betaTestingHint, betaChannelEnabled, palette, onChecked = onBetaChannelChange)
            }
            row("shutdown") {
                SettingRow(copy.shutdown, copy.shutdownHint, palette) {
                    HudIconButton(
                        icon = R.drawable.ic_shutdown,
                        contentDescription = copy.shutdown,
                        palette = palette,
                        tint = palette.red,
                        onClick = onShutdownClick
                    )
                }
            }
        }
        optionsSection("basic-navigation", copy.basicNavigationOutput, R.drawable.ic_options_navigation) {
            row("png-output") {
                SwitchRow(copy.pngOutput, copy.pngHint, snapshot.pngOutputEnabled, palette) {
                    runAction { activity.composeSetPngOutputEnabled(it) }
                }
            }
            row("native-output") {
                SwitchRow(copy.nativeOutput, copy.nativeHint, snapshot.nativeOutputEnabled, palette) {
                    runAction { activity.composeSetNativeOutputEnabled(it) }
                }
            }
            row("lane-output") {
                SwitchRow(copy.laneOutput, copy.laneHint, snapshot.laneOutputEnabled, palette) {
                    runAction { activity.composeSetLaneOutputEnabled(it) }
                }
            }
            row("street-output") {
                SwitchRow(copy.streetOutput, copy.streetHint, snapshot.streetOutputEnabled, palette) {
                    runAction { activity.composeSetStreetOutputEnabled(it) }
                }
            }
            row("text-transliteration") {
                SettingRow(copy.textTransliteration, copy.textTransliterationHint, palette) {
                    HudDropdown(
                        selectedIndex = snapshot.transliterationMode,
                        options = textTransliterationModes,
                        palette = palette,
                        width = 190.dp,
                        onSelected = { mode -> runAction { activity.composeSetTransliterationMode(mode) } }
                    )
                }
            }
            row("distance-output") {
                SwitchRow(copy.distanceOutput, copy.distanceHint, snapshot.distanceOutputEnabled, palette) {
                    runAction { activity.composeSetDistanceOutputEnabled(it) }
                }
            }
        }
        optionsSection("route-eta", if (ua) "ЕТА маршруту" else "Route ETA", R.drawable.ic_options_schedule) {
            row("route-metrics-mode") {
                SettingRow(routeMetricsTitle, routeMetricsHint, palette) {
                    HudDropdown(
                        selectedIndex = snapshot.routeMetricsMode,
                        options = routeMetricModes,
                        palette = palette,
                        width = 190.dp,
                        onSelected = { mode -> runAction { activity.composeSetRouteMetricsMode(mode) } }
                    )
                }
            }
            row("eta-output") {
                SwitchRow(copy.showEta, copy.showEtaHint, snapshot.etaOutputEnabled, palette, enabled = snapshot.routeMetricsMode != 0) {
                    runAction { activity.composeSetEtaOutputEnabled(it) }
                }
            }
            row("remaining-time-output") {
                SwitchRow(copy.showRemainingTime, copy.showRemainingTimeHint, snapshot.remainingTimeOutputEnabled, palette, enabled = snapshot.routeMetricsMode != 0) {
                    runAction { activity.composeSetRemainingTimeOutputEnabled(it) }
                }
            }
            row("remaining-distance-output") {
                SwitchRow(copy.showRemainingDistance, copy.showRemainingDistanceHint, snapshot.remainingDistanceOutputEnabled, palette, enabled = snapshot.routeMetricsMode != 0) {
                    runAction { activity.composeSetRemainingDistanceOutputEnabled(it) }
                }
            }
        }
        optionsSection("speed-limit", if (ua) "Обмеження швидкості" else "Speed limit", R.drawable.ic_options_speed) {
            row("speed-limit-mode") {
                SettingRow(if (ua) "Режим виводу обмеження швидкості" else "Speed limit output mode", speedLimitModeHint, palette) {
                    HudDropdown(
                        selectedIndex = snapshot.speedLimitMode,
                        options = speedLimitModes,
                        palette = palette,
                        width = 190.dp,
                        onSelected = { mode -> runAction { activity.composeSetSpeedLimitMode(mode) } }
                    )
                }
            }
            row("speed-limit-fallback") {
                SettingRow(
                    if (ua) "Накладання у режимі «У вільному полі»" else "Overlay in \"In a free field\" mode",
                    freeFallbackHint,
                    palette,
                    enabled = freeFallbackEnabled
                ) {
                    HudDropdown(
                        selectedIndex = snapshot.speedLimitFreeFallback,
                        options = speedLimitFallbackModes,
                        palette = palette,
                        width = 190.dp,
                        enabled = freeFallbackEnabled,
                        onSelected = { mode -> runAction { activity.composeSetSpeedLimitFreeFallback(mode) } }
                    )
                }
            }
            row("speed-limit-overlay-seconds") {
                SettingRow(
                    if (ua) "Час показу при накладанні" else "Display time when overlapping",
                    overlaySecondsHint,
                    palette,
                    enabled = overlaySecondsEnabled
                ) {
                    HudIntegerStepper(
                        value = snapshot.speedLimitOverlaySeconds,
                        palette = palette,
                        enabled = overlaySecondsEnabled,
                        onValueChange = { seconds -> runAction { activity.composeSetSpeedLimitOverlaySeconds(seconds) } }
                    )
                }
            }
            row("speed-limit-composite-placement") {
                SettingRow(
                    if (ua) "Поле для виводу у композитному режимі" else "Composite output field",
                    compositePlacementHint,
                    palette,
                    enabled = compositeEnabled
                ) {
                    HudDropdown(
                        selectedIndex = snapshot.speedLimitCompositePlacement,
                        options = speedLimitCompositePlacementModes,
                        palette = palette,
                        width = 190.dp,
                        enabled = compositeEnabled,
                        onSelected = { placement -> runAction { activity.composeSetSpeedLimitCompositePlacement(placement) } }
                    )
                }
            }
            row("speed-limit-maneuver-size") {
                SettingRow(
                    if (ua) "Розмір знаку у полі маневру" else "Sign size in maneuver field",
                    compositeManeuverSizeHint,
                    palette,
                    enabled = compositeEnabled
                ) {
                    HudIntegerStepper(
                        value = snapshot.speedLimitManeuverOverlaySize,
                        palette = palette,
                        enabled = compositeEnabled,
                        maxValue = 103,
                        fallbackValue = 64,
                        onValueChange = { size -> runAction { activity.composeSetSpeedLimitManeuverOverlaySize(size) } }
                    )
                }
            }
            row("speed-limit-lane-size") {
                SettingRow(
                    if (ua) "Розмір знаку у полі для смуг" else "Sign size in lane field",
                    compositeLaneSizeHint,
                    palette,
                    enabled = compositeEnabled
                ) {
                    HudIntegerStepper(
                        value = snapshot.speedLimitLaneOverlaySize,
                        palette = palette,
                        enabled = compositeEnabled,
                        maxValue = 36,
                        fallbackValue = 36,
                        onValueChange = { size -> runAction { activity.composeSetSpeedLimitLaneOverlaySize(size) } }
                    )
                }
            }
        }
        optionsSection("waze-features", copy.wazeFeatures, R.drawable.waze_app_icon) {
            row("waze-alerts") {
                SwitchRow(copy.showWazeAlerts, copy.showWazeAlertsHint, snapshot.wazeAlertsEnabled, palette) {
                    runAction { activity.composeSetWazeAlertsEnabled(it) }
                }
            }
            row("waze-custom-surface") {
                SwitchRow(copy.customSurface, copy.customSurfaceHint, snapshot.wazeCustomSurfaceEnabled, palette) {
                    runAction { activity.composeSetWazeCustomSurfaceEnabled(it) }
                }
            }
        }
        optionsSection("extra-navigation", copy.extraNavigationOptions, R.drawable.ic_options_settings) {
            row("tbt-without-hud") {
                SwitchRow(copy.tbtWithoutHudOutput, copy.tbtWithoutHudOutputHint, snapshot.tbtWithoutHudOutputEnabled, palette) {
                    runAction { activity.composeSetTbtWithoutHudOutputEnabled(it) }
                }
            }
            row("switch-to-tbt-on-hud-start") {
                SwitchRow(copy.switchToTbtOnHudStart, copy.switchToTbtOnHudStartHint, snapshot.switchToTbtOnHudStartEnabled, palette) {
                    runAction { activity.composeSetSwitchToTbtOnHudStartEnabled(it) }
                }
            }
            row("text-direction") {
                SwitchRow(copy.textDirectionOutput, copy.textDirectionOutputHint, snapshot.textDirectionOutputEnabled, palette) {
                    runAction { activity.composeSetTextDirectionOutputEnabled(it) }
                }
            }
            row("small-distance-clamp") {
                SwitchRow(copy.smallDistanceClamp, copy.smallDistanceHint, snapshot.smallDistanceClampEnabled, palette) {
                    runAction { activity.composeSetSmallDistanceClamp(it) }
                }
            }
        }
        optionsSection("dashboard-window-profile", copy.dashboardWindowSize, R.drawable.ic_options_directions_car) {
            row("dashboard-screen-mode") {
                SettingRow(copy.dashboardScreenMode, copy.dashboardScreenModeHint, palette) {
                    HudDropdown(
                        selectedIndex = snapshot.dashboardScreenMode,
                        options = dashboardScreenModes,
                        palette = palette,
                        width = 190.dp,
                        onSelected = { mode -> runAction { activity.composeSetDashboardScreenMode(mode) } }
                    )
                }
            }
            if (snapshot.dashboardScreenMode != HudPrefs.DASHBOARD_MODE_NONE) {
                row("dashboard-width") {
                    DashboardPercentRow(copy.dashboardWidth, copy.dashboardWidthHint,
                        snapshot.dashboardWidthPercent,
                        DashboardProjectionPolicy.MIN_WIDTH_PERCENT,
                        DashboardProjectionPolicy.MAX_WIDTH_PERCENT, palette) {
                        runAction {
                            activity.composeSetDashboardWidthPercent(
                                snapshot.dashboardScreenMode, it)
                        }
                    }
                }
                row("dashboard-height") {
                    DashboardPercentRow(copy.dashboardHeight, copy.dashboardHeightHint,
                        snapshot.dashboardHeightPercent,
                        DashboardProjectionPolicy.MIN_HEIGHT_PERCENT,
                        DashboardProjectionPolicy.MAX_HEIGHT_PERCENT, palette) {
                        runAction {
                            activity.composeSetDashboardHeightPercent(
                                snapshot.dashboardScreenMode, it)
                        }
                    }
                }
                row("dashboard-offset") {
                    DashboardPercentRow(copy.dashboardOffset, copy.dashboardOffsetHint,
                        snapshot.dashboardOffsetPercent,
                        DashboardProjectionPolicy.MIN_OFFSET_PERCENT,
                        DashboardProjectionPolicy.MAX_OFFSET_PERCENT, palette) {
                        runAction {
                            activity.composeSetDashboardOffsetPercent(
                                snapshot.dashboardScreenMode, it)
                        }
                    }
                }
                row("dashboard-scale") {
                    DashboardPercentRow(copy.dashboardScale, copy.dashboardScaleHint,
                        snapshot.dashboardScalePercent,
                        DashboardProjectionPolicy.MIN_SCALE_PERCENT,
                        DashboardProjectionPolicy.MAX_SCALE_PERCENT, palette) {
                        runAction {
                            activity.composeSetDashboardScalePercent(
                                snapshot.dashboardScreenMode, it)
                        }
                    }
                }
            }
        }
        optionsSection(
            "dashboard-widget",
            if (ua) "Віджет приборки" else "Dashboard widget",
            R.drawable.ic_options_widgets,
            preview = { DashboardWidgetSample(dashboardWidget, ua, palette) }
        ) {
            row("widget-shape") {
                SettingRow(
                    if (ua) "Віджет зміни приборки" else "Dashboard switch widget",
                    if (ua) "Оберіть форму, щоб увімкнути віджет" else "Choose a shape to enable the widget",
                    palette
                ) {
                    HudDropdown(
                        selectedIndex = dashboardWidget.shape.ordinal,
                        options = if (ua) listOf("Викл.", "Квадрат", "Коло") else listOf("Off", "Square", "Circle"),
                        palette = palette,
                        width = 190.dp,
                        optionIcons = listOf(null, 1, 2),
                        onSelected = { index ->
                            onDashboardWidgetChange(
                                dashboardWidget.selectShape(DashboardWidgetShape.entries[index]).normalized()
                            )
                        }
                    )
                }
            }
            row("widget-auto-collapse") {
                SettingRow(
                    if (ua) "Автоматично згортати віджет після зміни режиму" else "Automatically collapse the widget after a mode change",
                    "",
                    palette,
                    enabled = dashboardWidget.enabled
                ) {
                    HudSwitch(
                        dashboardWidget.autoCollapse,
                        { onDashboardWidgetChange(dashboardWidget.copy(autoCollapse = it)) },
                        palette,
                        enabled = dashboardWidget.enabled
                    )
                }
            }
            row("widget-auto-collapse-inactivity") {
                SettingRow(
                    if (ua) "Автоматично згортати віджет через 5 секунд неактивності" else "Automatically collapse the widget after 5 seconds of inactivity",
                    "",
                    palette,
                    enabled = dashboardWidget.enabled
                ) {
                    HudSwitch(
                        dashboardWidget.autoCollapseAfterInactivity,
                        { onDashboardWidgetChange(dashboardWidget.copy(autoCollapseAfterInactivity = it)) },
                        palette,
                        enabled = dashboardWidget.enabled
                    )
                }
            }
            row("widget-apply-window-profile") {
                SettingRow(
                    if (ua) "Застосовувати профіль вікна приборки" else "Apply dashboard window profile",
                    "",
                    palette,
                    enabled = dashboardWidget.enabled
                ) {
                    HudSwitch(
                        dashboardWidget.applyWindowProfile,
                        { onDashboardWidgetChange(dashboardWidget.copy(applyWindowProfile = it)) },
                        palette,
                        enabled = dashboardWidget.enabled
                    )
                }
            }
            if (dashboardWidget.enabled && !widgetOverlayPermission) {
                row("widget-overlay-permission") {
                    SettingRow(
                        if (ua) "Показ поверх інших застосунків" else "Display over other apps",
                        if (ua) "Потрібен для віджета на робочому столі" else "Required to show the widget on the home screen",
                        palette
                    ) {
                        HudButton(
                            if (ua) "Надати дозвіл" else "Grant permission",
                            palette,
                            primary = true,
                            width = 190.dp,
                            onClick = onWidgetPermissionRequest
                        )
                    }
                }
            }
            row("widget-size") {
                WidgetNumberLine(
                    if (ua) "Розмір" else "Size",
                    "24–160 dp",
                    dashboardWidget.sizeDp,
                    DashboardWidgetState.SIZE_RANGE,
                    "dp",
                    palette,
                    dashboardWidget.enabled,
                    showTicks = false
                ) { value -> onDashboardWidgetChange(dashboardWidget.resize(value)) }
            }
            row("widget-opening-direction") {
                SettingRow(
                    if (ua) "Напрямок розкриття віджету" else "Widget opening direction",
                    "",
                    palette,
                    enabled = dashboardWidget.enabled
                ) {
                    HudDropdown(
                        selectedIndex = dashboardWidget.openingDirectionIndex,
                        options = if (ua) listOf("Вгору", "Вправо", "Вниз", "Вліво") else listOf("Up", "Right", "Down", "Left"),
                        palette = palette,
                        width = 190.dp,
                        enabled = dashboardWidget.enabled,
                        onSelected = { index -> onDashboardWidgetChange(dashboardWidget.selectOpeningDirection(index)) }
                    )
                }
            }
            row("widget-corner-radius") {
                WidgetNumberLine(
                    if (ua) "Заокруглення кутів" else "Corner rounding",
                    if (ua) "Доступне тільки для квадратного віджета" else "Available only for the square widget",
                    dashboardWidget.cornerRadiusDp,
                    dashboardWidget.cornerRadiusRange,
                    "dp",
                    palette,
                    dashboardWidget.enabled && dashboardWidget.shape == DashboardWidgetShape.Square
                ) { value -> onDashboardWidgetChange(dashboardWidget.selectCornerRadius(value)) }
            }
            row("widget-transparency") {
                WidgetNumberLine(
                    if (ua) "Прозорість" else "Transparency",
                    if (ua) "0% — видимий, 100% — невидимий" else "0% visible, 100% invisible",
                    dashboardWidget.transparency,
                    0..100,
                    "%",
                    palette,
                    dashboardWidget.enabled
                ) { value -> onDashboardWidgetChange(dashboardWidget.copy(transparency = value).normalized()) }
            }
            row("widget-color") {
                WidgetColorLine(if (ua) "Колір" else "Color", dashboardWidget.fillArgb, palette, dashboardWidget.enabled) {
                    widgetColorTarget = false
                }
            }
            row("widget-border-size") {
                WidgetNumberLine(
                    if (ua) "Розмір рамки" else "Border width",
                    if (ua) "0 — прибрати рамку" else "0 removes the border",
                    dashboardWidget.borderDp,
                    DashboardWidgetState.BORDER_RANGE,
                    "dp",
                    palette,
                    dashboardWidget.enabled
                ) { value -> onDashboardWidgetChange(dashboardWidget.copy(borderDp = value).normalized()) }
            }
            row("widget-border-color") {
                WidgetColorLine(if (ua) "Колір рамки" else "Border color", dashboardWidget.borderArgb, palette, dashboardWidget.enabled) {
                    widgetColorTarget = true
                }
            }
        }
        optionsSection("dashboard-move", if (ua) "Перенесення на приборку" else "Move to dashboard", R.drawable.ic_options_open_in_new) {
            row("move-steering-button") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HudButton(
                        if (ua) "Вибрати кнопку для перенесення" else "Select steering-wheel button",
                        palette,
                        primary = true,
                        width = 250.dp
                    ) {
                        if (activity.composeBeginSteeringButtonLearning()) {
                            steeringCaptureRevision = snapshot.steeringTransferRevision
                            steeringCaptureLearningRevision =
                                NavAccessibilityService.keyLearningRevision()
                            showSteeringButtonCapture = true
                        }
                    }
                    HudReadOnlyField(
                        steeringButtonLabel(snapshot.steeringTransferKeyCode, ua),
                        palette,
                        Modifier.weight(1f)
                    )
                    HudButton(
                        if (ua) "Скинути кнопку" else "Reset button",
                        palette,
                        enabled = snapshot.steeringTransferKeyCode >= 0,
                        width = 145.dp
                    ) { runAction { activity.composeResetSteeringButton() } }
                }
            }
            row("move-app") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HudTransferAppDropdown(
                        entries = snapshot.steeringTransferApps,
                        selectedPackage = snapshot.steeringTransferPackage,
                        ua = ua,
                        palette = palette,
                        width = 400.dp,
                        onSelected = { packageName ->
                            runAction { activity.composeSetSteeringTransferPackage(packageName) }
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    HudButton(
                        if (ua) "Скинути застосунок" else "Reset app",
                        palette,
                        enabled = snapshot.steeringTransferPackage.isNotEmpty(),
                        width = 170.dp
                    ) { runAction { activity.composeResetSteeringTransferPackage() } }
                }
            }
            row("move-window-profile") {
                SettingRow(
                    if (ua) "Профіль вікна для перенесення на приборку" else "Dashboard window profile for transfer",
                    "",
                    palette
                ) {
                    HudDropdown(
                        selectedIndex = steeringProfileIndex,
                        options = steeringProfiles,
                        palette = palette,
                        width = 190.dp,
                        onSelected = { index ->
                            val profile = when (index) {
                                1 -> SteeringTransferPreferences.PROFILE_PARTIAL
                                2 -> SteeringTransferPreferences.PROFILE_FULL
                                else -> SteeringTransferPreferences.PROFILE_SELECTED
                            }
                            runAction { activity.composeSetSteeringTransferProfile(profile) }
                        }
                    )
                }
            }
        }
    }
    SidebarOptionsSurface(
        title = copy.main,
        hint = copy.mainHint,
        categoriesLabel = if (ua) "Категорії" else "Categories",
        sections = sections,
        palette = palette,
        selectedKey = selectedSectionKey,
        onSelectedKeyChange = onSelectedSectionKeyChange,
        categoryScrollState = categoryScrollState,
        sectionScrollStates = sectionScrollStates
    )
    widgetColorTarget?.let { border ->
        WidgetColorPicker(
            initialArgb = if (border) dashboardWidget.borderArgb else dashboardWidget.fillArgb,
            title = if (border) {
                if (ua) "Колір рамки" else "Border color"
            } else {
                if (ua) "Колір віджета" else "Widget color"
            },
            copy = copy,
            palette = palette,
            onDismiss = { widgetColorTarget = null },
            onSelect = { color ->
                onDashboardWidgetChange(
                    if (border) dashboardWidget.copy(borderArgb = color).normalized()
                    else dashboardWidget.copy(fillArgb = color).normalized()
                )
                widgetColorTarget = null
            }
        )
    }
    if (showSteeringButtonCapture) {
        SteeringButtonCaptureDialog(
            ua = ua,
            palette = palette,
            onDismiss = {
                showSteeringButtonCapture = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetNumberLine(
    title: String,
    hint: String,
    value: Int,
    range: IntRange,
    unit: String,
    palette: Palette,
    enabled: Boolean,
    showTicks: Boolean = true,
    onChange: (Int) -> Unit
) {
    val safeValue = value.coerceIn(range)
    val sliderInteraction = remember { MutableInteractionSource() }
    val defaultSliderColors = SliderDefaults.colors(
        thumbColor = if (palette.dark) Color(0xFFD9ECFF) else Color.White,
        activeTrackColor = palette.accent,
        inactiveTrackColor = palette.disabled,
        disabledThumbColor = palette.muted.copy(alpha = 0.72f),
        disabledActiveTrackColor = palette.borderStrong,
        disabledInactiveTrackColor = palette.disabled.copy(alpha = 0.72f),
        activeTickColor = palette.accent,
        inactiveTickColor = palette.disabled,
        disabledActiveTickColor = palette.borderStrong,
        disabledInactiveTickColor = palette.disabled.copy(alpha = 0.72f)
    )
    val sliderColors = if (showTicks) defaultSliderColors else defaultSliderColors.copy(
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent
    )
    Column {
        ActionRow(title, hint, palette, enabled = enabled) {
            Row(Modifier.width(190.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                HudIntegerStepper(
                    value = safeValue,
                    palette = palette,
                    enabled = enabled,
                    minValue = range.first,
                    maxValue = range.last,
                    fallbackValue = safeValue,
                    onValueChange = onChange
                )
                Text(unit, color = palette.muted, fontSize = 13.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
            }
        }
        Slider(
            value = safeValue.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = sliderColors,
            interactionSource = sliderInteraction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = sliderInteraction,
                    colors = sliderColors,
                    enabled = enabled,
                    thumbSize = DpSize(4.dp, 28.dp)
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(48.dp)
        )
    }
}

@Composable
private fun WidgetColorLine(
    title: String,
    argb: Int,
    palette: Palette,
    enabled: Boolean,
    onPick: () -> Unit
) {
    ActionRow(title, "", palette, enabled = enabled) {
        Row(Modifier.width(106.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(52.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                    .background(palette.field).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(28.dp).background(Color(argb), RoundedCornerShape(4.dp)).border(1.dp, palette.borderStrong, RoundedCornerShape(4.dp)))
            }
            HudIconButton(R.drawable.ic_palette, title, palette, palette.accent, enabled = enabled, modifier = Modifier.size(44.dp), onClick = onPick)
        }
    }
}

@Composable
private fun WidgetColorPicker(
    initialArgb: Int,
    title: String,
    copy: Copy,
    palette: Palette,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val ua = copy.language == Language.Ua
    var draft by remember(initialArgb) { mutableIntStateOf(initialArgb or 0xFF000000.toInt()) }
    var hexDraft by remember(initialArgb) { mutableStateOf(String.format(Locale.ROOT, "%06X", initialArgb and 0xFFFFFF)) }
    fun updateDraftColor(value: Int) {
        draft = value or 0xFF000000.toInt()
        hexDraft = String.format(Locale.ROOT, "%06X", draft and 0xFFFFFF)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(8.dp))
                .background(palette.surface).border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(Color(draft), RoundedCornerShape(7.dp)).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp)))
                BasicTextField(
                    value = hexDraft,
                    onValueChange = { raw ->
                        val cleaned = sanitizeWidgetHex(raw)
                        hexDraft = cleaned
                        parseWidgetHexArgb(cleaned)?.let { draft = it }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = palette.text, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(112.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                        .background(palette.field).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                        .onFocusChanged {
                            if (!it.isFocused && hexDraft.length != 6) {
                                hexDraft = String.format(Locale.ROOT, "%06X", draft and 0xFFFFFF)
                            }
                        }
                        .padding(horizontal = 10.dp),
                    decorationBox = { field ->
                        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Text("#", color = palette.muted, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                            field()
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                listOf(palette.accent, palette.green, palette.yellow, palette.red, Color.Gray, Color.White, Color.Black).forEach { color ->
                    val press = rememberPressFeedback(true)
                    Box(
                        Modifier.size(36.dp).then(press.modifier).clip(RoundedCornerShape(6.dp)).background(color)
                            .border(if (draft == color.toArgb()) 3.dp else 1.dp, palette.borderStrong, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF) }
                            .clickable(interactionSource = press.interactionSource, indication = null) { updateDraftColor(color.toArgb()) }
                    )
                }
            }
            listOf(16 to "R", 8 to "G", 0 to "B").forEach { (shift, label) ->
                WidgetNumberLine(label, "", (draft ushr shift) and 0xFF, 0..255, "", palette, true) { value ->
                    updateDraftColor((draft and (0xFF shl shift).inv()) or (value shl shift))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HudButton(if (ua) "Обрати" else "Select", palette, primary = true, width = 0.dp, modifier = Modifier.weight(1f)) { onSelect(draft) }
                HudButton(if (ua) "Скасувати" else "Cancel", palette, primary = false, width = 0.dp, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

private fun sanitizeWidgetHex(raw: String): String = raw.removePrefix("#")
    .filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
    .take(6)
    .uppercase()

private fun parseWidgetHexArgb(raw: String): Int? = sanitizeWidgetHex(raw)
    .takeIf { it.length == 6 }
    ?.toIntOrNull(16)
    ?.or(0xFF000000.toInt())

@Composable
private fun DashboardWidgetGraphic(
    state: DashboardWidgetState,
    modifier: Modifier = Modifier,
    size: Dp = state.sizeDp.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val shape = if (state.shape == DashboardWidgetShape.Circle) CircleShape else {
        RoundedCornerShape(state.cornerRadiusDp.coerceIn(state.cornerRadiusRange).dp)
    }
    Box(
        modifier.size(size).graphicsLayer { alpha = state.alpha }
            .background(Color(state.fillArgb), shape)
            .then(if (state.borderDp == 0) Modifier else Modifier.border(state.borderDp.dp, Color(state.borderArgb), shape)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun DashboardWidgetSample(state: DashboardWidgetState, ua: Boolean, palette: Palette) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (ua) "Вигляд віджета" else "Widget preview", color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(8.dp))
                .background(palette.field).border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (state.enabled) DashboardWidgetGraphic(state) {
                val foreground = if (Color(state.fillArgb).luminance() > 0.5f) Color.Black else Color.White
                Icon(
                    painterResource(R.drawable.ic_widget_toggle),
                    contentDescription = null,
                    modifier = Modifier.size(state.sizeDp.dp),
                    tint = foreground
                )
            }
            else Text(if (ua) "Вимкнено" else "Off", color = palette.muted, fontSize = 14.sp)
        }
        Text(
            if (ua) "Натисніть віджет, щоб розкрити режими\nПеретягніть у зручне місце\nЗатисніть, щоб приховати до наступного відкриття застосунку"
            else "Tap the widget to expand the modes\nDrag to a convenient position\nLong-press to hide until the app is opened again",
            color = palette.muted, fontSize = 13.sp
        )
        if (state.hidden && state.enabled) Text(
            if (ua) "Віджет приховано — відкрийте BYD HUD, щоб повернути"
            else "Widget hidden — open BYD HUD to restore",
            color = palette.yellow,
            fontSize = 13.sp
        )
    }
}

private class DashboardWidgetPointerGesture {
    var down = Offset.Zero
    var previous = Offset.Zero
    var dragging = false
    var longPressTriggered = false
    var longPressJob: Job? = null

    fun reset() {
        longPressJob?.cancel()
        longPressJob = null
        dragging = false
        longPressTriggered = false
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DashboardWidgetAnchorContent(
    state: DashboardWidgetState,
    windowSizeDp: Offset,
    ua: Boolean,
    onChange: (DashboardWidgetState) -> Unit,
    onHide: () -> Unit,
    onPositionSettled: () -> Unit,
    onInteraction: () -> Unit
) {
    val latestState by rememberUpdatedState(state)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnHide by rememberUpdatedState(onHide)
    val latestOnPositionSettled by rememberUpdatedState(onPositionSettled)
    val latestOnInteraction by rememberUpdatedState(onInteraction)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val gestureScope = rememberCoroutineScope()
    val gesture = remember { DashboardWidgetPointerGesture() }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "widget-press")
    val latestWindow by rememberUpdatedState(windowSizeDp)
    val graphicSize = state.sizeDp.dp
    val foreground = if (Color(state.fillArgb).luminance() > 0.5f) Color.Black else Color.White
    val anchorInteraction = Modifier
        .semantics {
            role = Role.Button
            contentDescription = if (ua) "Плаваючий віджет приборки" else "Floating dashboard widget"
            onClick(if (state.expanded) { if (ua) "Згорнути" else "Collapse" } else { if (ua) "Розкрити" else "Expand" }) {
                latestOnInteraction()
                val next = latestState.toggleExpanded()
                latestOnChange(next)
                latestOnPositionSettled()
                true
            }
            onLongClick(if (ua) "Приховати до відкриття застосунку" else "Hide until the app opens") { latestOnHide(); true }
        }
        .pointerInteropFilter { event ->
            fun dragBy(delta: Offset) {
                val current = latestState
                val cell = minOf(current.sizeDp.toFloat(), latestWindow.x, latestWindow.y)
                latestOnChange(current.dragBy(
                    delta.x / density.density,
                    delta.y / density.density,
                    latestWindow.x - cell,
                    latestWindow.y - cell
                ).normalized())
            }
            val raw = Offset(event.rawX, event.rawY)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.reset()
                    latestOnInteraction()
                    gesture.down = raw
                    gesture.previous = raw
                    pressed = true
                    gesture.longPressJob = gestureScope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        if (!gesture.dragging && !gesture.longPressTriggered) {
                            gesture.longPressTriggered = true
                            pressed = false
                            latestOnHide()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gesture.longPressTriggered
                        && !gesture.dragging
                        && (raw - gesture.down).getDistance() > viewConfiguration.touchSlop
                    ) {
                        gesture.dragging = true
                        gesture.longPressJob?.cancel()
                        gesture.longPressJob = null
                        pressed = false
                    }
                    if (gesture.dragging) {
                        latestOnInteraction()
                        dragBy(raw - gesture.previous)
                        gesture.previous = raw
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    gesture.longPressJob?.cancel()
                    pressed = false
                    val moved = (raw - gesture.down).getDistance() > viewConfiguration.touchSlop
                    if (!gesture.longPressTriggered && (gesture.dragging || moved)) {
                        if (!gesture.dragging) dragBy(raw - gesture.previous)
                        latestOnPositionSettled()
                    } else if (!gesture.longPressTriggered) {
                        latestOnInteraction()
                        val next = latestState.toggleExpanded()
                        latestOnChange(next)
                        latestOnPositionSettled()
                    }
                    gesture.reset()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    gesture.longPressJob?.cancel()
                    pressed = false
                    if (gesture.dragging) latestOnPositionSettled()
                    gesture.reset()
                    true
                }
                else -> true
            }
        }
    Box(
        Modifier.size(graphicSize).then(anchorInteraction),
        contentAlignment = Alignment.Center
    ) {
        DashboardWidgetGraphic(
            state,
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            size = graphicSize
        ) {
            if (state.expanded) {
                WidgetCloseIcon(graphicSize * 0.45f, foreground)
            } else {
                Icon(
                    painterResource(R.drawable.ic_widget_toggle),
                    contentDescription = null,
                    modifier = Modifier.size(graphicSize),
                    tint = foreground
                )
            }
        }
    }
}

@Composable
internal fun DashboardWidgetMenuContent(
    state: DashboardWidgetState,
    layout: DashboardWidgetMenuLayout,
    busy: Boolean,
    onMode: (DashboardWidgetMode) -> Unit,
    onInteraction: () -> Unit
) {
    val latestOnMode by rememberUpdatedState(onMode)
    val latestOnInteraction by rememberUpdatedState(onInteraction)
    val graphicSize = layout.cellSize.dp
    val foreground = if (Color(state.fillArgb).luminance() > 0.5f) Color.Black else Color.White
    val fields: @Composable () -> Unit = {
        state.fields(layout.expandForward).filterNotNull().forEach { mode ->
            key(mode) {
                val interaction = remember { MutableInteractionSource() }
                val modePressed by interaction.collectIsPressedAsState()
                Box(
                    Modifier.size(graphicSize).clickable(
                        enabled = !busy,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button
                    ) {
                            latestOnInteraction()
                            latestOnMode(mode)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    DashboardWidgetGraphic(
                        state,
                        Modifier.graphicsLayer { scaleX = if (modePressed && !busy) 0.97f else 1f; scaleY = scaleX },
                        size = graphicSize
                    ) {
                        val resource = when (mode) {
                            DashboardWidgetMode.IpcOff -> R.drawable.ic_widget_ipc_off
                            DashboardWidgetMode.Tbt -> R.drawable.ic_widget_tbt
                            DashboardWidgetMode.Mini -> R.drawable.ic_widget_mini
                            DashboardWidgetMode.Full -> R.drawable.ic_widget_full
                        }
                        Icon(painterResource(resource), mode.label, Modifier.size(graphicSize * 0.8f), tint = foreground)
                    }
                }
            }
        }
    }
    Box(Modifier.size(layout.width.dp, layout.height.dp)) {
        if (state.orientation == DashboardWidgetOrientation.Vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(DashboardWidgetState.GAP.dp)) { fields() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(DashboardWidgetState.GAP.dp)) { fields() }
        }
    }
}

@Composable
private fun WidgetCloseIcon(size: Dp, tint: Color) {
    Canvas(Modifier.size(size)) {
        val inset = 2.dp.toPx()
        drawLine(tint, Offset(inset, inset), Offset(this.size.width - inset, this.size.height - inset), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, Offset(this.size.width - inset, inset), Offset(inset, this.size.height - inset), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
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
    uaLanguage: Boolean,
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
                            notes = AppUpdateManager.releaseNotesForLanguage(
                                state.info.releaseNotes,
                                uaLanguage
                            )
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
                                notes = AppUpdateManager.releaseNotesForLanguage(
                                    state.info.releaseNotes,
                                    uaLanguage
                                )
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
private fun StorageShareDestinationOverlay(
    copy: Copy,
    shareCopy: ShareCopy,
    palette: Palette,
    summary: MainActivity.ComposeStorageShareSummary,
    onSentry: () -> Unit,
    sentryButtonText: String,
    sentryButtonEnabled: Boolean,
    onAnotherApp: () -> Unit,
    onCancel: () -> Unit
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
                shareCopy.shareLogsTitle,
                color = palette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    String.format(
                        Locale.US,
                        shareCopy.shareLogsSelection,
                        summary.dayCount,
                        summary.fileCount,
                        formatBytes(summary.sourceBytes, copy)
                    ),
                    color = palette.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    shareCopy.shareLogsArchiveHint,
                    color = palette.text,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
                Text(
                    shareCopy.shareLogsSensitiveWarning,
                    color = palette.yellow,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
                Text(
                    shareCopy.shareLogsSentryNotice,
                    color = palette.text,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                HudButton(
                    sentryButtonText,
                    palette,
                    primary = true,
                    enabled = sentryButtonEnabled,
                    width = 210.dp,
                    onClick = onSentry
                )
                HudButton(
                    shareCopy.shareToAnotherApp,
                    palette,
                    width = 150.dp,
                    onClick = onAnotherApp
                )
                HudButton(shareCopy.cancel, palette, width = 138.dp, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun OperationProgressStack(
    copy: Copy,
    shareCopy: ShareCopy,
    palette: Palette,
    patchOperations: List<MainActivity.ComposePatchOperation>,
    storageSharePhase: LogShareZip.Phase?,
    storageShareTerminalPhase: String,
    storageShareTerminalDetail: String,
    storageShareStartedAt: Long,
    onCancelShare: () -> Unit,
    onCloseShare: () -> Unit,
    onCancelPatch: (String) -> Unit,
    onDismissPatch: (String) -> Unit
) {
    val cards = buildList {
        if (storageShareTerminalPhase.isNotEmpty()) {
            add(OperationCardSpec(
                key = "share",
                title = shareCopy.shareLogsTitle,
                phase = if (storageShareTerminalPhase == "CANCELLED") {
                    if (copy.language == Language.Ua) "Скасовано" else "Cancelled"
                } else {
                    shareCopy.failure
                },
                detail = storageShareTerminalDetail,
                startedAt = storageShareStartedAt,
                busy = false,
                stopEnabled = false,
                closeEnabled = true,
                failed = storageShareTerminalPhase == "FAILED",
                success = false,
                onStop = {},
                onClose = onCloseShare
            ))
        } else if (storageSharePhase != null) {
            val phaseText = when (storageSharePhase) {
                LogShareZip.Phase.WAITING_FOR_WRITES -> shareCopy.waitingForWrites
                LogShareZip.Phase.COPYING -> shareCopy.copying
                LogShareZip.Phase.ARCHIVING -> shareCopy.archiving
            }
            add(OperationCardSpec(
                key = "share",
                title = shareCopy.shareLogsTitle,
                phase = phaseText,
                detail = "",
                startedAt = storageShareStartedAt,
                busy = true,
                stopEnabled = true,
                closeEnabled = false,
                failed = false,
                success = false,
                onStop = onCancelShare,
                onClose = {}
            ))
        }
        patchOperations.filter {
            it.kind.isNotEmpty() && it.phase != "IDLE"
                    && !(it.recoveryRequired && it.acknowledged)
        }.forEach { operation ->
            val navigator = if (operation.profileId == "waze") "Waze" else "Google Maps"
            val phase = patchStepLabel(operation, copy.language).let { label ->
                if (operation.progress in 1..99) "$label · ${operation.progress}%" else label
            }
            add(OperationCardSpec(
                key = "patch-${operation.profileId}",
                title = "$navigator · ${patchProgressTitle(operation, copy)}",
                phase = phase,
                detail = operation.error.ifEmpty { operation.detail },
                startedAt = operation.startedAt,
                busy = operation.busy,
                stopEnabled = operation.cancelAllowed,
                closeEnabled = operation.phase == "FAILED" || operation.phase == "CANCELLED"
                        || operation.recoveryRequired,
                failed = operation.phase == "FAILED" || operation.recoveryRequired,
                success = operation.phase == "VERIFIED",
                onStop = { onCancelPatch(operation.profileId) },
                onClose = { onDismissPatch(operation.profileId) }
            ))
        }
    }.sortedByDescending { it.startedAt }.take(3)

    if (cards.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            modifier = Modifier.padding(end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            cards.forEach { card ->
                OperationProgressCard(card, shareCopy, palette, copy.language)
            }
        }
    }
}

@Composable
private fun OperationProgressCard(
    card: OperationCardSpec,
    copy: ShareCopy,
    palette: Palette,
    language: Language
) {
    Column(
        modifier = Modifier
            .width(460.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            card.title,
            color = palette.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (card.busy) LoadingSpinner(palette)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.phase,
                    color = when {
                        card.failed -> palette.red
                        card.success -> palette.green
                        else -> palette.text
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.detail.isNotEmpty()) {
                    Text(
                        card.detail,
                        color = palette.muted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (card.busy) {
                HudButton(
                    if (language == Language.Ua) "Зупинити" else "Stop",
                    palette,
                    enabled = card.stopEnabled,
                    width = 138.dp,
                    onClick = card.onStop
                )
            } else if (card.closeEnabled) {
                HudButton(copy.close, palette, width = 138.dp, onClick = card.onClose)
            }
        }
    }
}

@Composable
private fun SentryUploadOverlay(
    copy: ShareCopy,
    palette: Palette,
    phase: SentryUploadPhase,
    eventId: String,
    error: String,
    configuration: Boolean,
    onClose: () -> Unit
) {
    val complete = phase == SentryUploadPhase.Success || phase == SentryUploadPhase.Failure
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
                if (configuration) copy.configurationUploadTitle else copy.uploadTitle,
                color = palette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            if (!complete) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingSpinner(palette)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (phase == SentryUploadPhase.Preparing) copy.preparing else copy.uploading,
                        color = palette.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Text(
                    if (phase == SentryUploadPhase.Success) {
                        if (configuration) copy.configurationSuccess else copy.success
                    } else {
                        if (configuration) copy.configurationFailure else copy.failure
                    },
                    color = if (phase == SentryUploadPhase.Success) palette.green else palette.red,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val detail = if (phase == SentryUploadPhase.Success) {
                    "${copy.reportId}: $eventId"
                } else {
                    error
                }
                CodeBlock(detail, palette, compact = true)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    HudButton(copy.close, palette, width = 138.dp, onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun ConfigurationShareDestinationOverlay(
    copy: ShareCopy,
    palette: Palette,
    onSentry: () -> Unit,
    onAnotherApp: () -> Unit,
    onCancel: () -> Unit
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
                .width(760.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                copy.configurationTitle,
                color = palette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    copy.configurationWarning,
                    color = palette.yellow,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
                Text(
                    copy.shareLogsSentryNotice,
                    color = palette.text,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                HudButton(
                    copy.shareToSentry,
                    palette,
                    primary = true,
                    width = 210.dp,
                    onClick = onSentry
                )
                HudButton(
                    copy.shareToAnotherApp,
                    palette,
                    width = 220.dp,
                    onClick = onAnotherApp
                )
                HudButton(copy.cancel, palette, width = 138.dp, onClick = onCancel)
            }
        }
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
private fun NavigatorAssetList(
    copy: Copy,
    palette: Palette,
    assets: List<NavigatorAssetManager.AssetSnapshot>,
    onDownload: (String) -> Unit,
    onInstall: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    Section("${copy.notice}: ${copy.navigatorAssetsNotice}", palette) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NavigatorAssetColumn(
                title = "Waze",
                assets = assets.filter { it.packageName == "com.waze" },
                copy = copy,
                palette = palette,
                modifier = Modifier.weight(1f),
                onDownload = onDownload,
                onInstall = onInstall,
                onRestore = onRestore
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
            NavigatorAssetColumn(
                title = "Google Maps ReVanced",
                assets = assets.filter {
                    it.packageName == "app.revanced.android.apps.maps"
                },
                copy = copy,
                palette = palette,
                modifier = Modifier.weight(1f),
                onDownload = onDownload,
                onInstall = onInstall,
                onRestore = onRestore
            )
        }
    }
}

@Composable
private fun NavigatorAssetColumn(
    title: String,
    assets: List<NavigatorAssetManager.AssetSnapshot>,
    copy: Copy,
    palette: Palette,
    modifier: Modifier,
    onDownload: (String) -> Unit,
    onInstall: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        assets.forEach { asset ->
            val variant = asset.label.removePrefix("$title ").removePrefix("Waze ").trim()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                    if (variant.isEmpty() || variant == title) asset.versionName
                    else "$variant ${asset.versionName}",
                color = palette.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
                NavigatorAssetAction(copy, palette, asset, onDownload, onInstall, onRestore)
            }
        }
    }
}

@Composable
private fun NavigatorAssetAction(
    copy: Copy,
    palette: Palette,
    asset: NavigatorAssetManager.AssetSnapshot,
    onDownload: (String) -> Unit,
    onInstall: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    val label = when (asset.state) {
        NavigatorAssetManager.DOWNLOADING -> asset.progress
        NavigatorAssetManager.VERIFYING -> copy.navigatorAssetVerifying
        NavigatorAssetManager.READY -> copy.navigatorAssetInstall
        NavigatorAssetManager.INSTALL_REQUESTED,
        NavigatorAssetManager.UNINSTALL_REQUESTED -> copy.navigatorAssetInstalling
        NavigatorAssetManager.INSTALLED -> copy.navigatorAssetInstalled
        NavigatorAssetManager.RECOVERY_REQUIRED -> copy.navigatorAssetRestore
        NavigatorAssetManager.ERROR -> copy.navigatorAssetRetry
        else -> copy.navigatorAssetDownload
    }
    val enabled = asset.state != NavigatorAssetManager.DOWNLOADING
            && asset.state != NavigatorAssetManager.VERIFYING
            && asset.state != NavigatorAssetManager.INSTALL_REQUESTED
            && asset.state != NavigatorAssetManager.UNINSTALL_REQUESTED
            && asset.state != NavigatorAssetManager.INSTALLED
    Text(
        text = label,
        color = when {
            asset.state == NavigatorAssetManager.RECOVERY_REQUIRED -> palette.red
            enabled -> palette.accent
            else -> palette.muted
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) {
                when (asset.state) {
                    NavigatorAssetManager.READY -> onInstall(asset.id)
                    NavigatorAssetManager.RECOVERY_REQUIRED -> onRestore(asset.id)
                    else -> onDownload(asset.id)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun AppsTab(
    copy: Copy,
    palette: Palette,
    scrollState: LazyListState,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit,
    onDownloadAsset: (String) -> Unit,
    onInstallAsset: (String) -> Unit,
    onRestoreAsset: (String) -> Unit
) {
    val scanningLabel = if (copy.language == Language.Ua) "Сканування" else "Scanning"
    val scanFailedLabel = if (copy.language == Language.Ua) "Помилка сканування" else "Scan failed"
    val scanStatusText = when {
        snapshot.appScanInProgress -> scanningLabel
        snapshot.appScanStatus.isNotBlank() -> "$scanFailedLabel: ${snapshot.appScanStatus}"
        else -> "${copy.lastScan}: ${snapshot.lastScanText}"
    }
    val scanStatusColor = if (snapshot.appScanStatus.isNotBlank() && !snapshot.appScanInProgress) {
        palette.red to palette.redSoft
    } else {
        palette.muted to palette.disabled
    }
    LazyPageSurface(copy.apps, copy.appsHint, palette, scrollState, headerAction = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(scanStatusText, scanStatusColor.first, scanStatusColor.second, Modifier.width(230.dp))
            HudButton(copy.refreshApps, palette, primary = true, width = 178.dp) {
                runAction { activity.composeRefreshApps() }
            }
        }
    }, itemSpacing = 0.dp) {
        item(key = "apps-page-gap") {
            Spacer(Modifier.height(10.dp))
        }

        item(key = "navigator-assets") {
            NavigatorAssetList(
                copy = copy,
                palette = palette,
                assets = snapshot.navigatorAssets,
                onDownload = onDownloadAsset,
                onInstall = onInstallAsset,
                onRestore = onRestoreAsset
            )
        }

        item(key = "supported-apps-gap") {
            Spacer(Modifier.height(10.dp))
        }
        item(key = "supported-apps-header") {
            AppSectionHeader(
                copy.supportedApps,
                palette,
                bottom = snapshot.appScanCacheAvailable && snapshot.supportedApps.isEmpty()
            )
        }
        if (!snapshot.appScanCacheAvailable) {
            item(key = "supported-apps-status") {
                AppSectionMessage(scanStatusText, palette)
            }
        } else {
            items(
                count = snapshot.supportedApps.size,
                key = { index -> snapshot.supportedApps[index].packageName }
            ) { index ->
                AppSectionRow(index, snapshot.supportedApps.lastIndex, palette) {
                    AppRow(
                        snapshot.supportedApps[index],
                        copy,
                        palette,
                        supported = snapshot.supportedApps[index].supportedHud,
                        runtimeStatusKnown = snapshot.appRuntimeStatusKnown,
                        activity = activity,
                        runAction = runAction
                    )
                }
            }
        }

        item(key = "all-apps-gap") {
            Spacer(Modifier.height(10.dp))
        }
        item(key = "all-apps-header") {
            AppSectionHeader(copy.allApps, palette)
        }
        if (!snapshot.appScanCacheAvailable) {
            item(key = "all-apps-status") {
                AppSectionMessage(scanStatusText, palette)
            }
        } else if (snapshot.allApps.isEmpty()) {
            item(key = "all-apps-empty") {
                AppSectionMessage(copy.noBackgroundApps, palette)
            }
        } else {
            items(
                count = snapshot.allApps.size,
                key = { index -> snapshot.allApps[index].packageName }
            ) { index ->
                AppSectionRow(index, snapshot.allApps.lastIndex, palette) {
                    AppRow(
                        snapshot.allApps[index],
                        copy,
                        palette,
                        supported = false,
                        runtimeStatusKnown = snapshot.appRuntimeStatusKnown,
                        activity = activity,
                        runAction = runAction
                    )
                }
            }
        }
    }
}

@Composable
private fun AppSectionHeader(title: String, palette: Palette, bottom: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .appSectionSegmentFrame(palette, palette.panelAlt, top = true, bottom = bottom)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            title.uppercase(),
            color = palette.muted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun AppSectionMessage(text: String, palette: Palette, bottom: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .appSectionSegmentFrame(palette, palette.panel, top = false, bottom = bottom)
    ) {
        Text(text, color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun AppSectionRow(
    index: Int,
    lastIndex: Int,
    palette: Palette,
    closeSection: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appSectionSegmentFrame(
                palette,
                palette.panel,
                top = false,
                bottom = closeSection && index == lastIndex
            )
    ) {
        if (index > 0) Divider(palette)
        content()
    }
}

private fun Modifier.appSectionSegmentFrame(
    palette: Palette,
    background: Color,
    top: Boolean,
    bottom: Boolean
): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (top) 8.dp else 0.dp,
        topEnd = if (top) 8.dp else 0.dp,
        bottomStart = if (bottom) 8.dp else 0.dp,
        bottomEnd = if (bottom) 8.dp else 0.dp
    )
    return clip(shape)
        .background(background)
        .drawBehind {
            val strokeWidth = 1.dp.toPx()
            val halfStroke = strokeWidth / 2f
            val radius = 8.dp.toPx()
            val arcSize = Size(radius * 2f - strokeWidth, radius * 2f - strokeWidth)
            val sideTop = if (top) radius else 0f
            val sideBottom = if (bottom) size.height - radius else size.height
            drawLine(
                palette.border,
                Offset(halfStroke, sideTop),
                Offset(halfStroke, sideBottom),
                strokeWidth
            )
            drawLine(
                palette.border,
                Offset(size.width - halfStroke, sideTop),
                Offset(size.width - halfStroke, sideBottom),
                strokeWidth
            )
            if (top) {
                drawLine(
                    palette.border,
                    Offset(radius, halfStroke),
                    Offset(size.width - radius, halfStroke),
                    strokeWidth
                )
                drawArc(
                    palette.border,
                    180f,
                    90f,
                    false,
                    Offset(halfStroke, halfStroke),
                    arcSize,
                    style = Stroke(strokeWidth)
                )
                drawArc(
                    palette.border,
                    270f,
                    90f,
                    false,
                    Offset(size.width - radius * 2f + halfStroke, halfStroke),
                    arcSize,
                    style = Stroke(strokeWidth)
                )
            }
            if (bottom) {
                drawLine(
                    palette.border,
                    Offset(radius, size.height - halfStroke),
                    Offset(size.width - radius, size.height - halfStroke),
                    strokeWidth
                )
                drawArc(
                    palette.border,
                    90f,
                    90f,
                    false,
                    Offset(halfStroke, size.height - radius * 2f + halfStroke),
                    arcSize,
                    style = Stroke(strokeWidth)
                )
                drawArc(
                    palette.border,
                    0f,
                    90f,
                    false,
                    Offset(size.width - radius * 2f + halfStroke,
                        size.height - radius * 2f + halfStroke),
                    arcSize,
                    style = Stroke(strokeWidth)
                )
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
    runtimeStatusKnown: Boolean,
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
                if (runtimeStatusKnown) {
                    StatusChip(if (runningForStatus) copy.running else copy.notRunning,
                        if (runningForStatus) ChipKind.Green else ChipKind.Yellow, palette, width = 174.dp)
                }
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
private fun StorageTab(
    copy: Copy,
    palette: Palette,
    scrollState: LazyListState,
    dayScrollState: LazyListState,
    snapshot: MainActivity.ComposeSnapshot,
    configurationShareBusy: Boolean,
    logcatBusy: Boolean,
    onStartLogcat: () -> Unit,
    onStopLogcat: () -> Unit,
    onShareConfiguration: () -> Unit,
    sortOldestFirst: Boolean,
    selectedDays: List<String>,
    storageActionBusy: Boolean,
    storageSortBusy: Boolean,
    storageLimitDraft: Int,
    onStorageLimitDraftChange: (Int) -> Unit,
    onStorageLimitGb: (Int) -> Unit,
    onSortOldestFirst: (Boolean) -> Unit,
    onToggleDay: (String) -> Unit,
    onDeleteSelected: (List<String>) -> Unit,
    onShareSelected: (List<String>) -> Unit
) {
    val storageScanText = if (copy.language == Language.Ua) {
        "Сканування сховища..."
    } else {
        "Scanning storage..."
    }
    val storageScanFailureText = if (copy.language == Language.Ua) {
        "Помилка сканування"
    } else {
        "Scan failed"
    }
    val coldStorageText = if (snapshot.storageScanError.isNotBlank()) {
        "$storageScanFailureText: ${snapshot.storageScanError}"
    } else {
        storageScanText
    }
    val days = if (sortOldestFirst) {
        snapshot.storageDays.sortedBy { it.name }
    } else {
        snapshot.storageDays.sortedByDescending { it.name }
    }
    val selectedDayNames = selectedDays.filter { selected ->
        days.any { it.name == selected }
    }
    val storageFolderBlocks = snapshot.navCaptureFolderPaths
        .distinct()
        .map { path ->
            val public = path.trimEnd('/').endsWith("/Documents/BYD-HUD", ignoreCase = true)
            Triple(if (public) 0 else 1, if (public) copy.publicStorageLocation else copy.privateStorageLocation, path)
        }
        .sortedBy { it.first }
    LazyPageSurface(copy.storage, copy.storageHint, palette, scrollState) {
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
                            value = "$storageLimitDraft ${gbUnit(copy)}",
                            palette = palette,
                            modifier = Modifier.width(150.dp)
                        )
                        HudButton(
                            "OK",
                            palette,
                            primary = true,
                            enabled = storageLimitDraft != snapshot.storageLimitGb,
                            width = 190.dp
                        ) {
                            onStorageLimitGb(storageLimitDraft)
                        }
                    }
                }
                )
                StorageLimitSlider(storageLimitDraft, palette, onStorageLimitDraftChange)
                Divider(palette)
                SettingRow(
                title = copy.currentNavLogsSize,
                hint = "",
                palette = palette,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!snapshot.storageCacheAvailable) {
                            Pill(
                                coldStorageText,
                                if (snapshot.storageScanError.isNotBlank()) palette.red else palette.muted,
                                if (snapshot.storageScanError.isNotBlank()) palette.redSoft else palette.disabled
                            )
                        } else {
                            if (snapshot.storageCalculating) {
                                Pill(copy.storageCalculating, palette.muted, palette.disabled)
                            }
                            if (snapshot.storageScanError.isNotBlank()) {
                                Pill(storageScanFailureText, palette.red, palette.redSoft)
                            }
                            val usageColors = storageUsageColors(
                                snapshot.navCaptureFolderBytes,
                                snapshot.storageLimitGb,
                                palette
                            )
                            Pill(
                                formatStorageUsage(
                                    snapshot.navCaptureFolderBytes,
                                    snapshot.storageLimitGb,
                                    copy
                                ),
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
                    }
                )
            }
        }

        item(key = "storage-logs") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appSectionSegmentFrame(palette, palette.panelAlt, top = true, bottom = false)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(copy.logs.uppercase(Locale.ROOT), color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ShareIconLabelButton(
                            label = copy.shareSelected,
                            palette = palette,
                            enabled = selectedDayNames.isNotEmpty() && !storageActionBusy,
                            width = 190.dp,
                            onClick = { onShareSelected(selectedDayNames) }
                        )
                        HudButton(
                            if (sortOldestFirst) copy.sortByName else copy.sortByDate,
                            palette,
                            primary = false,
                            enabled = snapshot.storageCacheAvailable && !storageSortBusy,
                            width = 180.dp,
                            onClick = { onSortOldestFirst(!sortOldestFirst) }
                        )
                    }
                }
                if (days.isEmpty()) {
                    AppSectionMessage(
                        if (!snapshot.storageCacheAvailable) coldStorageText else copy.storageNoDayFolders,
                        palette,
                        bottom = false
                    )
                } else {
                    LazyColumn(
                        state = dayScrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
                        items(
                            count = days.size,
                            key = { index -> "storage-day-${days[index].name}" },
                            contentType = { "storage-day" }
                        ) { index ->
                            val day = days[index]
                            Box(
                                Modifier.fillMaxWidth()
                                    .appSectionSegmentFrame(palette, palette.panel, top = false, bottom = false)
                                    .padding(start = 14.dp, end = 14.dp, top = 10.dp)
                            ) {
                                StorageDayRow(
                                    day = day,
                                    copy = copy,
                                    palette = palette,
                                    selected = selectedDays.contains(day.name),
                                    enabled = !storageSortBusy,
                                    onToggle = { onToggleDay(day.name) }
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().appSectionSegmentFrame(palette, palette.panel, top = false, bottom = true)
                        .padding(14.dp)
                ) {
                    HudButton(
                        if (snapshot.logcatRecording) copy.stopLogcat else copy.startLogcat,
                        palette,
                        primary = true,
                        enabled = !storageActionBusy && !logcatBusy,
                        width = 180.dp,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        if (snapshot.logcatRecording) onStopLogcat() else onStartLogcat()
                    }
                    HudButton(
                        copy.shareConfiguration,
                        palette,
                        primary = false,
                        enabled = !storageActionBusy && !configurationShareBusy,
                        width = 300.dp,
                        modifier = Modifier.align(Alignment.Center),
                        onClick = onShareConfiguration
                    )
                    HudButton(
                        copy.deleteSelected,
                        palette,
                        enabled = selectedDayNames.isNotEmpty() && !storageActionBusy,
                        primary = true,
                        width = 190.dp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = { onDeleteSelected(selectedDayNames) }
                    )
                }
            }
        }

        item(key = "navigation-log-folders") {
            Section(copy.navigationLogsFolder, palette, bodyPadding = 14.dp) {
                when {
                    !snapshot.storageCacheAvailable ->
                        CodeBlock(coldStorageText, palette, compact = true)
                    storageFolderBlocks.isEmpty() ->
                        CodeBlock(copy.storageNoDayFolders, palette, compact = true)
                    storageFolderBlocks.size == 1 -> {
                        val (_, label, path) = storageFolderBlocks.single()
                        CodeBlock("$label:\n$path", palette, compact = true)
                    }
                    else -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        storageFolderBlocks.forEach { (_, label, path) ->
                            CodeBlock(
                                "$label:\n$path",
                                palette,
                                compact = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchTab(
    copy: Copy,
    palette: Palette,
    scrollState: LazyListState,
    snapshot: MainActivity.ComposeSnapshot,
    actionPendingProfiles: Set<String>,
    onSelectFile: (String) -> Unit,
    onClear: (String) -> Unit,
    onCheck: (String) -> Unit,
    onPatch: (String) -> Unit,
    onRestore: (String) -> Unit
) {
    LazyPageSurface(copy.patchTab, copy.patchHint, palette, scrollState) {
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
                if (snapshot.patchRows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 0.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(copy.noSupportedNavigators, color = palette.muted, fontSize = 14.sp)
                    }
                } else {
                    snapshot.patchRows.forEach { row ->
                        val operation = snapshot.patchOperations.firstOrNull {
                            it.profileId == row.profileId
                        }
                        val busy = actionPendingProfiles.contains(row.profileId)
                                || operation?.busy == true
                                || operation?.recoveryRequired == true
                        Divider(palette)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                                .background(palette.field)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.width(245.dp)) {
                                Text(
                                    row.label,
                                    color = palette.text,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    row.packageName,
                                    color = palette.muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val version = if (row.externalSource) {
                                    row.sourceVersion
                                } else {
                                    row.installedVersion
                                }
                                if (version.isNotEmpty()) {
                                    Text("${copy.appVersion}: $version", color = palette.muted, fontSize = 12.sp)
                                }
                                Text(
                                    "${copy.patchSource}: ${if (row.externalSource) row.sourceName else copy.patchInstalledSource}",
                                    color = palette.muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val componentStates = buildList {
                                add(copy.patchDirectChannel to row.directState)
                                if (row.gmsCoreLabel.isNotEmpty()) {
                                    add(patchSecondaryLabel(row, copy.language) to row.gmsCoreState)
                                }
                                add(patchOptionalLabel(row, copy.language) to row.optionalState)
                                if (row.alertLabel.isNotEmpty()) {
                                    add(patchAlertLabel(row, copy.language, copy) to row.alertState)
                                }
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                componentStates.chunked(2).forEach { columnStates ->
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        columnStates.forEach { (label, state) ->
                                            PatchComponentChip(label, state, copy, palette)
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.width(550.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (row.externalSource) {
                                        SelectedPatchFileField(
                                            version = selectedPatchVersion(row),
                                            copy = copy,
                                            palette = palette,
                                            enabled = !busy,
                                            onClear = { onClear(row.profileId) }
                                        )
                                    } else {
                                        HudButton(
                                            copy.patchSelectFile,
                                            palette,
                                            enabled = !busy,
                                            width = 210.dp,
                                            onClick = { onSelectFile(row.profileId) }
                                        )
                                    }
                                    HudButton(
                                        copy.checkPatch,
                                        palette,
                                        enabled = !busy,
                                        width = 150.dp,
                                        onClick = { onCheck(row.profileId) }
                                    )
                                    HudButton(
                                        copy.applyPatch,
                                        palette,
                                        primary = true,
                                        enabled = !busy && row.patchEnabled,
                                        width = 150.dp,
                                        onClick = { onPatch(row.profileId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        snapshot.patchOperations.filter { it.recoveryRequired }.forEach { operation ->
            item(key = "patch-recovery-${operation.profileId}") {
                Section(copy.patchRecovery, palette) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            operation.detail,
                            color = palette.red,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        HudButton(
                            copy.patchRestore,
                            palette,
                            primary = true,
                            enabled = !actionPendingProfiles.contains(operation.profileId),
                            width = 190.dp,
                            onClick = { onRestore(operation.profileId) }
                        )
                    }
                }
            }
        }

    }
}

private fun selectedPatchVersion(row: MainActivity.ComposeNavigatorPatchRow): String {
    if (row.sourceVersion.isNotEmpty()) return row.sourceVersion
    val match = Regex("\\d+(?:\\.\\d+){2,}").find(row.sourceName)
    return match?.value ?: row.sourceName.ifEmpty { "selected" }
}

private fun patchOptionalLabel(
    row: MainActivity.ComposeNavigatorPatchRow,
    language: Language
): String {
    if (row.profileId == "waze") {
        return if (language == Language.Ua) "Стабільність" else "Stability"
    }
    return if (language == Language.Ua) "Аудіоканал" else row.optionalLabel
}

private fun patchSecondaryLabel(
    row: MainActivity.ComposeNavigatorPatchRow,
    language: Language
): String {
    if (row.profileId == "waze") {
        return if (language == Language.Ua) "Смуги" else "Lanes"
    }
    return row.gmsCoreLabel
}

private fun patchAlertLabel(
    row: MainActivity.ComposeNavigatorPatchRow,
    language: Language,
    copy: Copy
): String {
    if (row.profileId != "waze") return row.alertLabel
    return copy.patchWazeAlerts
}

@Composable
private fun PatchComponentChip(
    label: String,
    state: String,
    copy: Copy,
    palette: Palette
) {
    val stateCopy = when (state) {
        "PATCHABLE" -> copy.patchPatchable
        "PATCHED" -> copy.patchPatched
        "FAILED" -> copy.patchFailed
        else -> copy.patchNotChecked
    }
    val kind = when (state) {
        "PATCHABLE" -> ChipKind.Yellow
        "PATCHED" -> ChipKind.Green
        "FAILED" -> ChipKind.Red
        else -> ChipKind.Neutral
    }
    StatusChip("$label: $stateCopy", kind, palette, width = 240.dp)
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
private fun SelectedPatchFileField(
    version: String,
    copy: Copy,
    palette: Palette,
    enabled: Boolean,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(210.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .background(palette.panelAlt),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = version,
            color = palette.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 11.dp)
        )
        HudIconButton(
            icon = android.R.drawable.ic_menu_close_clear_cancel,
            contentDescription = copy.patchClearSelection,
            palette = palette,
            tint = palette.muted,
            enabled = enabled,
            modifier = Modifier.width(40.dp).height(44.dp),
            onClick = onClear
        )
    }
}

@Composable
private fun PatchFileSelectionOverlay(
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
            Text(
                text = highlightPatchSelectionText(copy.patchSelectFileTitle, copy.language, palette.yellow),
                color = palette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = highlightPatchSelectionText(copy.patchSelectFileText, copy.language, palette.yellow),
                color = palette.text,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            )
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
private fun PatchFileErrorOverlay(
    copy: Copy,
    palette: Palette,
    message: String,
    onClose: () -> Unit
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
            Text(copy.patchSelectionErrorText, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(message, color = palette.text, fontSize = 15.sp, lineHeight = 21.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HudButton(copy.updateClose, palette, primary = true, width = 138.dp, onClick = onClose)
            }
        }
    }
}

private fun highlightPatchSelectionText(
    text: String,
    language: Language,
    highlight: Color
) = buildAnnotatedString {
    append(text)
    val words = if (language == Language.Ua) listOf("іншу", "замінить")
    else listOf("another", "replace")
    words.forEach { word ->
        var start = text.indexOf(word)
        while (start >= 0) {
            addStyle(SpanStyle(color = highlight), start, start + word.length)
            start = text.indexOf(word, start + word.length)
        }
    }
}

@Composable
private fun NavigatorAssetConfirmOverlay(
    copy: Copy,
    palette: Palette,
    asset: NavigatorAssetManager.AssetSnapshot,
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
            Text(
                copy.navigatorAssetConfirmTitle.replace("%s", asset.label),
                color = palette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                copy.navigatorAssetConfirmText,
                color = palette.yellow,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                HudButton(copy.navigatorAssetConfirmCancel, palette, onClick = onDismiss)
                Spacer(Modifier.width(10.dp))
                HudButton(
                    copy.navigatorAssetConfirmOk,
                    palette,
                    primary = true,
                    onClick = onConfirm
                )
            }
        }
    }
}

@Composable
private fun PatchConfirmOverlay(
    copy: Copy,
    palette: Palette,
    navigator: String,
    destructive: Boolean,
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
            Text(
                copy.patchConfirmTitle.replace("%s", navigator),
                color = palette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.field)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (destructive) copy.patchConfirmText else if (copy.language == Language.Ua) {
                        "Патч буде встановлено як оновлення. Дані навігатора буде збережено."
                    } else {
                        "The patch will be installed as an update. Navigation app data will be preserved."
                    },
                    color = palette.text,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
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

private fun patchProgressTitle(
    operation: MainActivity.ComposePatchOperation,
    copy: Copy
): String {
    return if (operation.kind == "CHECK" || operation.kind == "SELECT") {
        if (copy.language == Language.Ua) {
            "Перевірка сумісності навігатора для патчу"
        } else {
            "Checking navigator compatibility for patching"
        }
    } else {
        copy.patchProgress
    }
}

private fun patchStepLabel(
    operation: MainActivity.ComposePatchOperation,
    language: Language
): String {
    val step = when (operation.kind) {
        "CHECK" -> when (operation.phase) {
            "COPYING" -> 1 to 3
            "VERIFYING" -> 2 to 3
            "SCANNING" -> 3 to 3
            else -> null
        }
        "PATCH" -> when (operation.phase) {
            "COPYING" -> 1 to 6
            "VERIFYING" -> 2 to 6
            "SCANNING" -> 3 to 6
            "PATCHING" -> 4 to 6
            "SIGNING" -> 5 to 6
            "OUTPUT_VERIFY" -> 6 to 6
            else -> null
        }
        else -> null
    }
    val label = patchPhaseLabel(operation.phase, language)
    return if (step == null) label else "(${step.first}/${step.second}) $label"
}

private fun patchPhaseLabel(phase: String, language: Language): String {
    if (language == Language.Ua) {
        return when (phase) {
            "COPYING" -> "Копіювання застосунку"
            "VERIFYING" -> "Перевірка пакета"
            "SCANNING" -> "Перевірка сумісності"
            "PATCHING" -> "Застосування патчу"
            "REPACKING" -> "Перепакування APK"
            "SIGNING" -> "Підпис APK"
            "OUTPUT_VERIFY" -> "Перевірка результату"
            "INSTALLED_VERIFY" -> "Перевірка встановленого APK"
            "READY_TO_INSTALL" -> "Очікування черги встановлення"
            "AWAITING_PERMISSION" -> "Очікування дозволу"
            "CANCEL_REQUESTED" -> "Зупинка операції"
            "INSTALL_PREPARING" -> "Підготовка системного інсталятора"
            "COMMITTING" -> "Передавання APK системному інсталятору"
            "UNINSTALL_REQUESTED" -> "Очікування видалення"
            "INSTALL_REQUESTED" -> "Очікування встановлення"
            "RECOVERY_REQUIRED" -> "Потрібне відновлення"
            "FAILED" -> "Помилка"
            "CANCELLED" -> "Скасовано"
            "VERIFIED" -> "Перевірено"
            else -> "Підготовка операції"
        }
    }
    return when (phase) {
        "COPYING" -> "Copying application"
        "VERIFYING" -> "Verifying package"
        "SCANNING" -> "Checking compatibility"
        "PATCHING" -> "Applying patch"
        "REPACKING" -> "Repacking APK"
        "SIGNING" -> "Signing APK"
        "OUTPUT_VERIFY" -> "Verifying result"
        "INSTALLED_VERIFY" -> "Verifying installed APK"
        "READY_TO_INSTALL" -> "Waiting in install queue"
        "AWAITING_PERMISSION" -> "Waiting for permission"
        "CANCEL_REQUESTED" -> "Stopping operation"
        "INSTALL_PREPARING" -> "Preparing Android installer"
        "COMMITTING" -> "Submitting APK to Android installer"
        "UNINSTALL_REQUESTED" -> "Waiting for removal"
        "INSTALL_REQUESTED" -> "Waiting for installation"
        "RECOVERY_REQUIRED" -> "Recovery required"
        "FAILED" -> "Failed"
        "CANCELLED" -> "Cancelled"
        "VERIFIED" -> "Verified"
        else -> phase.lowercase(Locale.ROOT).replace('_', ' ')
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
//guards storage-limit selection with a real draggable control and no zero-weight endpoint layout.
private fun StorageLimitSlider(
    limit: Int,
    palette: Palette,
    onLimit: (Int) -> Unit
) {
    val coerced = limit.coerceIn(1, 10)
    Slider(
        value = coerced.toFloat(),
        onValueChange = { onLimit(storageLimitFromSliderValue(it)) },
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
private fun DashboardPercentRow(
    title: String,
    hint: String,
    percent: Int,
    minPercent: Int,
    maxPercent: Int,
    palette: Palette,
    onPercent: (Int) -> Unit
) {
    val coerced = percent.coerceIn(minPercent, maxPercent)
    var sliderValue by remember(coerced) { mutableStateOf(coerced.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = palette.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${sliderValue.roundToInt()}%",
                color = palette.text,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
        Text(rowExplanation(hint), color = palette.muted, fontSize = 13.sp)
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it.coerceIn(minPercent.toFloat(), maxPercent.toFloat()) },
            onValueChangeFinished = {
                val next = sliderValue.roundToInt().coerceIn(minPercent, maxPercent)
                sliderValue = next.toFloat()
                onPercent(next)
            },
            valueRange = minPercent.toFloat()..maxPercent.toFloat(),
            steps = (maxPercent - minPercent) - 1,
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
}

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
    day.hasPublicStorage && day.hasPrivateStorage -> copy.bothStorageLocations.lowercase(Locale.ROOT)
    day.hasPublicStorage -> copy.publicStorageLocation.lowercase(Locale.ROOT)
    else -> copy.privateStorageLocation.lowercase(Locale.ROOT)
}

@Composable
//keeps HUD checks on the shared runtime snapshot; the worker owns cadence and payload sends.
private fun HudCheckTab(
    copy: Copy,
    palette: Palette,
    scrollState: LazyListState,
    snapshot: MainActivity.ComposeSnapshot,
    activity: MainActivity,
    runAction: (() -> Unit) -> Unit
) {
    val state = snapshot.hudCheck
    val ukrainian = snapshot.uaLanguage
    val statusText = if (state.running) copy.hudCheckRunning else copy.hudCheckStopped
    val deliveryStatus = snapshot.hudCheckStatus.trim()
    val statusIsError = deliveryStatus.contains("error", ignoreCase = true)
        || deliveryStatus.contains("failed", ignoreCase = true)
        || deliveryStatus.contains("помил", ignoreCase = true)
    val statusColor = when {
        statusIsError -> palette.red to palette.redSoft
        state.running -> palette.green to palette.greenSoft
        else -> palette.muted to palette.disabled
    }
    fun step(field: HudCheckState.Field, delta: Int) =
        runAction { activity.composeHudCheckStep(field, delta) }

    LazyPageSurface(
        title = copy.hudCheck,
        hint = copy.hudCheckHint,
        palette = palette,
        state = scrollState,
        headerAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(statusText, statusColor.first, statusColor.second)
                HudButton(
                    if (state.running) copy.hudCheckStop else copy.hudCheckStart,
                    palette,
                    primary = true,
                    width = 180.dp
                ) { runAction { activity.composeHudCheckToggleRunning() } }
            }
        }
    ) {
        item(key = "hud-check-mode") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                HudCheckModeTile(
                    copy.basicOutput,
                    copy.basicOutputHint,
                    state.mode == HudCheckState.Mode.BASIC,
                    palette,
                    Modifier.weight(1f)
                ) { runAction { activity.composeHudCheckSelectMode(HudCheckState.Mode.BASIC) } }
                HudCheckModeTile(
                    copy.extendedOutput,
                    copy.extendedOutputHint,
                    state.mode == HudCheckState.Mode.EXTENDED,
                    palette,
                    Modifier.weight(1f)
                ) { runAction { activity.composeHudCheckSelectMode(HudCheckState.Mode.EXTENDED) } }
            }
        }
        item(key = "hud-check-status") {
            if (state.running && deliveryStatus.isNotBlank()) {
                Text(
                    deliveryStatus,
                    color = if (statusIsError) palette.red else palette.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (state.mode == HudCheckState.Mode.BASIC) {
            item(key = "hud-check-basic") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                        .background(palette.panel)
                ) {
                    HudCheckRow(copy.checkManeuvers, copy.checkManeuversHint, state.maneuverLabel(ukrainian), palette,
                        onPrevious = { step(HudCheckState.Field.MANEUVER, -1) },
                        onNext = { step(HudCheckState.Field.MANEUVER, 1) },
                        option = {
                            OutputImageChoice(
                                bitmap = state.maneuverBitmap,
                                stockText = copy.stockImage,
                                bitmapText = copy.bitmapImage,
                                palette = palette
                            ) { runAction { activity.composeHudCheckSetManeuverBitmap(it) } }
                        })
                    Divider(palette)
                    HudCheckRow(copy.checkLanes, copy.checkLanesHint, state.lanes(), palette, monospace = true,
                        onPrevious = { step(HudCheckState.Field.LANES, -1) },
                        onNext = { step(HudCheckState.Field.LANES, 1) },
                        option = {
                            OutputImageChoice(
                                bitmap = state.laneBitmap,
                                stockText = copy.stockImage,
                                bitmapText = copy.bitmapImage,
                                palette = palette
                            ) { runAction { activity.composeHudCheckSetLaneBitmap(it) } }
                        })
                    Divider(palette)
                    HudCheckRow(copy.distanceMeters, copy.checkDistanceHint, state.distance().toString(), palette,
                        onPrevious = { step(HudCheckState.Field.DISTANCE, -1) },
                        onNext = { step(HudCheckState.Field.DISTANCE, 1) })
                    Divider(palette)
                    HudCheckRow(copy.streetText, copy.checkStreetHint, state.effectiveStreet(), palette,
                        onPrevious = { step(HudCheckState.Field.STREET, -1) },
                        onNext = { step(HudCheckState.Field.STREET, 1) },
                        option = {
                            CompactSwitchBox(
                                label = copy.checkTransliteration,
                                checked = state.transliterate,
                                palette = palette,
                                width = 230.dp
                            ) { runAction { activity.composeHudCheckSetTransliterate(it) } }
                        })
                    Divider(palette)
                    HudCheckRow(copy.checkTrafficLight, copy.checkTrafficLightHint, state.trafficLightLabel(ukrainian), palette,
                        onPrevious = { step(HudCheckState.Field.TRAFFIC_LIGHT, -1) },
                        onNext = { step(HudCheckState.Field.TRAFFIC_LIGHT, 1) })
                }
            }
        } else {
            item(key = "hud-check-baseline") {
                Section(copy.checkBaseline, palette, bodyPadding = 10.dp, headerVerticalPadding = 6.dp) {
                    val baseline = listOf(
                        "${copy.checkManeuvers} · ${copy.checkManeuversChannels}" to
                            "${if (ukrainian) "Прямо" else "Straight"} · ${copy.stockImage}",
                        copy.checkLanes to "S* | S | S* | S | S* · ${copy.stockImage}",
                        copy.distanceMeters to "77",
                        copy.streetText to "Continue straight"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        baseline.forEach { (title, value) ->
                            Column(Modifier.weight(1f)) {
                                Text(title, color = palette.muted, fontSize = 13.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(value, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
            item(key = "hud-check-extended") {
                Section(copy.currentField, palette, bodyPadding = 10.dp, headerVerticalPadding = 6.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .heightIn(min = 100.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(palette.field)
                                    .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(state.extendedLabel(ukrainian), color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                Text(state.extendedField(), color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(state.extendedValue(), color = palette.accent, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                            }
                        }
                        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CompactSwitchBox(
                                label = if (state.automatic) copy.checkAutomatic else copy.checkManualCycle,
                                checked = state.automatic,
                                palette = palette,
                                width = 280.dp
                            ) { runAction { activity.composeHudCheckSetAutomatic(it) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                HudChevronButton(
                                    direction = ChevronDirection.Left,
                                    contentDescription = copy.previous,
                                    palette = palette,
                                    enabled = !state.automatic
                                ) { runAction { activity.composeHudCheckStepExtended(-1) } }
                                Text(
                                    "${state.extendedIndex + 1} / ${HudCheckState.extendedCount()}",
                                    color = palette.text,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                                HudChevronButton(
                                    direction = ChevronDirection.Right,
                                    contentDescription = copy.next,
                                    palette = palette,
                                    enabled = !state.automatic
                                ) { runAction { activity.composeHudCheckStepExtended(1) } }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.automatic) copy.checkCycle else copy.checkManualCycleHint,
                        color = palette.text,
                        fontSize = 13.sp
                    )
                    Text(copy.checkExtendedNotice, color = palette.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun HudCheckRow(
    title: String,
    hint: String,
    value: String,
    palette: Palette,
    monospace: Boolean = false,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    option: @Composable () -> Unit = {}
) {
    ActionRow(title, hint, palette, verticalPadding = 6.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HudChevronButton(ChevronDirection.Left, "$title: previous", palette, onClick = onPrevious)
            Box(
                modifier = Modifier
                    .width(310.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                    .background(palette.field)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    value,
                    color = palette.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HudChevronButton(ChevronDirection.Right, "$title: next", palette, onClick = onNext)
        }
        Spacer(Modifier.width(20.dp))
        Box(Modifier.width(230.dp), contentAlignment = Alignment.CenterEnd) { option() }
    }
}

private enum class ChevronDirection { Left, Right }

@Composable
private fun HudChevronButton(
    direction: ChevronDirection,
    contentDescription: String,
    palette: Palette,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .background(pressBackground(if (enabled) palette.panelAlt else palette.disabled, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val color = if (enabled) palette.text else palette.muted.copy(alpha = 0.55f)
            val centerY = size.height / 2f
            val edgeX = if (direction == ChevronDirection.Left) size.width * 0.68f else size.width * 0.32f
            val tipX = if (direction == ChevronDirection.Left) size.width * 0.32f else size.width * 0.68f
            drawLine(color, Offset(edgeX, centerY - size.height * 0.28f), Offset(tipX, centerY), strokeWidth = 2.2f, cap = StrokeCap.Round)
            drawLine(color, Offset(tipX, centerY), Offset(edgeX, centerY + size.height * 0.28f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun HudCheckModeTile(
    title: String,
    hint: String,
    selected: Boolean,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    val background = if (selected) palette.active else palette.panelAlt
    Column(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) palette.accent else palette.border, RoundedCornerShape(8.dp))
            .background(pressBackground(background, palette, press.pressed))
            .then(press.modifier)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(rowExplanation(hint), color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OutputImageChoice(
    bitmap: Boolean,
    stockText: String,
    bitmapText: String,
    palette: Palette,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .width(230.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(22.dp))
            .background(palette.panelAlt)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutputImageChoiceItem(stockText, !bitmap, palette, Modifier.weight(1f)) { onChange(false) }
        OutputImageChoiceItem(bitmapText, bitmap, palette, Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable
private fun OutputImageChoiceItem(
    text: String,
    active: Boolean,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(pressBackground(if (active) palette.accent else Color.Transparent, palette, press.pressed))
            .then(press.modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = visualClick
            )
            .semantics {
                selected = active
                role = Role.RadioButton
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) Color.White else palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun LazyPageSurface(
    title: String,
    hint: String,
    palette: Palette,
    state: LazyListState,
    headerAction: (@Composable () -> Unit)? = null,
    itemSpacing: Dp = 10.dp,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
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

private data class OptionsRowSpec(
    val key: String,
    val content: @Composable () -> Unit
)

private class OptionsSectionScope {
    val rows = mutableListOf<OptionsRowSpec>()

    fun row(key: String, content: @Composable () -> Unit) {
        rows += OptionsRowSpec(key, content)
    }
}

private data class OptionsSectionSpec(
    val key: String,
    val title: String,
    @DrawableRes val icon: Int,
    val rows: List<OptionsRowSpec>,
    val preview: (@Composable () -> Unit)? = null
)

private class OptionsSectionsScope {
    val sections = mutableListOf<OptionsSectionSpec>()

    fun optionsSection(
        key: String,
        title: String,
        @DrawableRes icon: Int,
        preview: (@Composable () -> Unit)? = null,
        content: OptionsSectionScope.() -> Unit
    ) {
        sections += OptionsSectionSpec(
            key = key,
            title = title,
            icon = icon,
            preview = preview,
            rows = OptionsSectionScope().apply(content).rows.toList()
        )
    }
}

private fun buildOptionsSections(
    content: OptionsSectionsScope.() -> Unit
): List<OptionsSectionSpec> = OptionsSectionsScope().apply(content).sections.toList()

@Composable
private fun SidebarOptionsSurface(
    title: String,
    hint: String,
    categoriesLabel: String,
    sections: List<OptionsSectionSpec>,
    palette: Palette,
    selectedKey: String,
    onSelectedKeyChange: (String) -> Unit,
    categoryScrollState: LazyListState,
    sectionScrollStates: Map<String, LazyListState>
) {
    val selectedSection = sections.firstOrNull { it.key == selectedKey } ?: sections.first()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.panel)
            .padding(14.dp)
    ) {
        Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text(hint, color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .background(palette.panelAlt)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = categoriesLabel.uppercase(Locale.ROOT),
                    color = palette.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Spacer(Modifier.height(2.dp))
                LazyColumn(
                    state = categoryScrollState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = sections.size,
                        key = { index -> sections[index].key },
                        contentType = { "options-category" }
                    ) { index ->
                        val section = sections[index]
                        SidebarOptionsCategoryItem(
                            section = section,
                            selected = section.key == selectedSection.key,
                            palette = palette,
                            onClick = { onSelectedKeyChange(section.key) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                key(selectedSection.key) {
                    LazyColumn(
                        state = sectionScrollStates.getValue(selectedSection.key),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                            .background(palette.surface),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        sidebarOptionsSection(selectedSection, palette)
                    }
                }
                selectedSection.preview?.let { preview ->
                    Box(Modifier.width(196.dp).fillMaxHeight()) { preview() }
                }
            }
        }
    }
}

@Composable
private fun SidebarOptionsCategoryItem(
    section: OptionsSectionSpec,
    selected: Boolean,
    palette: Palette,
    onClick: () -> Unit
) {
    val foreground = if (selected) palette.accent else palette.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(section.icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(foreground),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = section.title,
            color = foreground,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun LazyListScope.sidebarOptionsSection(
    section: OptionsSectionSpec,
    palette: Palette
) {
    item(key = "${section.key}:header", contentType = "options-header") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .appSectionSegmentFrame(palette, palette.panelAlt, top = true, bottom = false)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(section.title.uppercase(Locale.ROOT), color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
    section.rows.forEachIndexed { index, row ->
        item(key = "${section.key}:${row.key}", contentType = "options-row") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .appSectionSegmentFrame(
                        palette,
                        palette.panel,
                        top = false,
                        bottom = index == section.rows.lastIndex
                    )
            ) {
                if (index > 0) Divider(palette)
                row.content()
            }
        }
    }
}

@Composable
//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun Section(
    title: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    bodyPadding: Dp = 0.dp,
    headerVerticalPadding: Dp = 10.dp,
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
                .padding(horizontal = 14.dp, vertical = headerVerticalPadding)
        ) {
            Text(title.uppercase(), color = palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Column(modifier = Modifier.padding(bodyPadding)) {
            content()
        }
    }
}

private fun rowExplanation(text: String): String = text.trimEnd().removeSuffix(".")

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun SettingRow(
    title: String,
    hint: String,
    palette: Palette,
    enabled: Boolean = true,
    action: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) palette.text else palette.muted.copy(alpha = 0.62f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            if (hint.isNotBlank()) {
                Text(
                    rowExplanation(hint),
                    color = palette.muted.copy(alpha = if (enabled) 1f else 0.52f),
                    fontSize = 13.sp
                )
            }
        }
        action()
    }
}

private fun steeringButtonLabel(keyCode: Int, ua: Boolean): String {
    if (keyCode < 0) return ""
    val name = when (keyCode) {
        305 -> if (ua) "Ліва зірочка" else "Left star"
        309 -> if (ua) "Режими приборки / завершення виклику" else "Dashboard modes / end call"
        310 -> if (ua) "Круговий огляд" else "Surround view"
        320 -> if (ua) "Голосове керування" else "Voice control"
        321 -> if (ua) "Ліва додаткова" else "Left auxiliary"
        351 -> if (ua) "Права зірочка" else "Right star"
        383 -> if (ua) "Права додаткова" else "Right auxiliary"
        else -> return if (ua) "Кнопка (код $keyCode)" else "Button (code $keyCode)"
    }
    return "$name ($keyCode)"
}

@Composable
private fun HudReadOnlyField(
    value: String,
    palette: Palette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.field)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            value,
            color = palette.text,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HudTransferAppDropdown(
    entries: List<InstalledTransferAppCatalog.Entry>,
    selectedPackage: String,
    ua: Boolean,
    palette: Palette,
    width: Dp,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = remember(entries, selectedPackage) {
        InstalledTransferAppCatalog.selectionOrFallback(entries, selectedPackage)
    }
    val hasSelection = selectedPackage.isNotBlank()
    val fieldText = if (hasSelection) selected.label() else {
        if (ua) "Вибрати застосунок для перенесення" else "Select app to transfer"
    }
    Box(Modifier.width(width)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, palette.accent, RoundedCornerShape(7.dp))
                .background(palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.08f))
                .clickable(enabled = entries.isNotEmpty()) { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasSelection) {
                TransferAppIcon(selected, palette)
                Spacer(Modifier.width(9.dp))
            }
            Text(
                fieldText,
                color = if (palette.dark) Color.White else palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("▾", color = palette.text.copy(alpha = 0.78f), fontSize = 18.sp)
        }
        if (expanded) {
            Popup(
                popupPositionProvider = HudDropdownPositionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                LazyColumn(
                    Modifier
                        .width(width)
                        .heightIn(max = 330.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                        .background(palette.panel)
                ) {
                    items(entries.size, key = { entries[it].packageName() }) { index ->
                        val entry = entries[index]
                        val selectedRow = entry.packageName() == selectedPackage
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(
                                    if (selectedRow) palette.accent.copy(alpha = 0.18f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    onSelected(entry.packageName())
                                    expanded = false
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TransferAppIcon(entry, palette)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.label(),
                                    color = palette.text,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedRow) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    entry.packageName(),
                                    color = palette.muted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (index < entries.lastIndex) Divider(palette)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferAppIcon(
    entry: InstalledTransferAppCatalog.Entry,
    palette: Palette
) {
    if (entry.isFallback()) {
        Icon(
            painterResource(R.drawable.ic_options_open_in_new),
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(30.dp)
        )
        return
    }
    val bitmap = remember(entry.packageName(), entry.icon()) {
        entry.icon().toBitmap().asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(30.dp)
    )
}

@Composable
private fun SteeringButtonCaptureDialog(
    ua: Boolean,
    palette: Palette,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (ua) "Натисніть кнопку на кермі..." else "Press a steering-wheel button...",
                color = palette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (ua) {
                    "Під час роботи вибраного застосунку призначена кнопка не виконуватиме штатну дію"
                } else {
                    "While the selected app is active, the assigned button will not perform its original action"
                },
                color = palette.muted,
                fontSize = 13.sp
            )
            HudButton(
                if (ua) "Скасувати" else "Cancel",
                palette,
                width = 0.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun HudDropdown(
    selectedIndex: Int,
    options: List<String>,
    palette: Palette,
    width: Dp,
    enabled: Boolean = true,
    optionIcons: List<Int?> = emptyList(),
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(options.indices)
    val rowHeight = 40.dp
    val selectedBackground = palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.08f)
    val selectedContent = if (palette.dark) Color.White else palette.text
    val fieldBackground = if (enabled) selectedBackground else palette.panelAlt
    val fieldBorder = if (enabled) palette.accent else palette.borderStrong
    val fieldContent = if (enabled) selectedContent else palette.muted.copy(alpha = 0.62f)

    Box(modifier = Modifier.width(width)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, fieldBorder, RoundedCornerShape(6.dp))
                .background(fieldBackground)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            optionIcons.getOrNull(safeIndex)?.let {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    WidgetOptionIcon(it, fieldContent)
                }
            }
            Text(
                text = options[safeIndex],
                color = fieldContent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            Text(
                text = "▾",
                color = fieldContent.copy(alpha = 0.78f),
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        if (expanded && enabled) {
            Popup(
                popupPositionProvider = HudDropdownPositionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    modifier = Modifier
                        .width(width)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, palette.borderStrong, RoundedCornerShape(6.dp))
                        .background(palette.panel)
                ) {
                    options.forEachIndexed { index, option ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .background(if (index == safeIndex) selectedBackground else Color.Transparent)
                                .clickable {
                                    onSelected(index)
                                    expanded = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            optionIcons.getOrNull(index)?.let {
                                Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                                    WidgetOptionIcon(it, if (index == safeIndex && palette.dark) Color.White else palette.text)
                                }
                            }
                            Text(
                                text = option,
                                color = if (index == safeIndex && palette.dark) Color.White else palette.text,
                                fontSize = 14.sp,
                                fontWeight = if (index == safeIndex) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            )
                            if (index < options.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(palette.border)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetOptionIcon(kind: Int, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        if (kind == 1) {
            drawRect(tint, style = Stroke(width = 2.dp.toPx()))
        } else {
            drawCircle(tint, radius = size.minDimension * 0.38f, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private object HudDropdownPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val anchoredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> below.coerceIn(0, maxY)
        }
        return IntOffset(anchoredX.coerceIn(0, maxX), y)
    }
}

@Composable
private fun HudIntegerStepper(
    value: Int,
    palette: Palette,
    enabled: Boolean,
    minValue: Int = 1,
    maxValue: Int? = 10,
    fallbackValue: Int = 5,
    onValueChange: (Int) -> Unit
) {
    val current = value.takeIf { isValidHudInteger(it, minValue, maxValue) }
        ?: fallbackValue
    var textValue by remember(value, minValue, maxValue, fallbackValue) {
        mutableStateOf(current.toString())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudButton(
            text = "-",
            palette = palette,
            width = 42.dp,
            enabled = enabled && current > minValue,
            onClick = { onValueChange(current - 1) }
        )
        BasicTextField(
            value = textValue,
            onValueChange = { rawValue ->
                val candidate = rawValue.filter(Char::isDigit)
                if (candidate.isEmpty() || isValidHudInteger(
                        candidate.toIntOrNull(), minValue, maxValue
                    )) {
                    textValue = candidate
                    candidate.toIntOrNull()?.let(onValueChange)
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                color = if (enabled) palette.text else palette.muted.copy(alpha = 0.62f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier
                .width(52.dp)
                .onFocusChanged { focusState ->
                    if (enabled && !focusState.isFocused && !isValidHudInteger(
                            textValue.toIntOrNull(), minValue, maxValue
                        )) {
                        textValue = fallbackValue.toString()
                        onValueChange(fallbackValue)
                    }
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .border(
                            1.dp,
                            if (enabled) palette.borderStrong else palette.border,
                            RoundedCornerShape(7.dp)
                        )
                        .background(if (enabled) palette.field else palette.panelAlt),
                    contentAlignment = Alignment.Center
                ) {
                    innerTextField()
                }
            }
        )
        HudButton(
            text = "+",
            palette = palette,
            width = 42.dp,
            enabled = enabled && current < (maxValue ?: Int.MAX_VALUE),
            onClick = { onValueChange(current + 1) }
        )
    }
}

private fun isValidHudInteger(value: Int?, minValue: Int, maxValue: Int?): Boolean =
    value != null && value >= minValue && (maxValue == null || value <= maxValue)

@Composable
//renders this UI section here so screen structure stays traceable during preview and car testing.
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    palette: Palette,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    val switchControl = remember { mutableStateOf<SwitchExternalControl?>(null) }
    val rowEnabled = enabled && switchControl.value?.pending != true
    val press = rememberPressFeedback(rowEnabled)
    val visualClick = rememberVisualFirstClick {
        switchControl.value?.trigger?.invoke()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(pressBackground(Color.Transparent, palette, press.pressed))
            .then(press.modifier)
            .toggleable(
                value = checked,
                enabled = rowEnabled,
                role = Role.Switch,
                interactionSource = press.interactionSource,
                indication = null,
                onValueChange = { visualClick() }
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) palette.text else palette.muted.copy(alpha = 0.62f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            if (hint.isNotBlank()) {
                Text(
                    rowExplanation(hint),
                    color = palette.muted.copy(alpha = if (enabled) 1f else 0.52f),
                    fontSize = 13.sp
                )
            }
        }
        HudSwitch(
            checked,
            onChecked,
            palette,
            enabled = enabled,
            externalControl = switchControl
        )
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
    verticalPadding: Dp = 12.dp,
    enabled: Boolean = true,
    action: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) palette.text else palette.muted.copy(alpha = 0.62f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                rowExplanation(hint),
                color = palette.muted.copy(alpha = if (enabled) 1f else 0.52f),
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        action()
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
private fun ShareIconLabelButton(
    label: String,
    palette: Palette,
    enabled: Boolean,
    width: Dp,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    Row(
        modifier = Modifier.width(width).height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .background(pressBackground(if (enabled) palette.panelAlt else palette.disabled, palette, press.pressed))
            .then(press.modifier)
            .clickable(enabled = enabled, interactionSource = press.interactionSource, indication = null, onClick = visualClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_share),
            contentDescription = null,
            tint = if (enabled) palette.accent else palette.muted.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (enabled) palette.text else palette.muted.copy(alpha = 0.55f),
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
            .toggleable(
                value = checked,
                enabled = rowEnabled,
                role = Role.Switch,
                interactionSource = press.interactionSource,
                indication = null,
                onValueChange = { visualClick() }
            )
            .semantics(mergeDescendants = true) {}
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
    enabled: Boolean = true,
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
    val press = rememberPressFeedback(enabled && !isPending)
    val scope = rememberCoroutineScope()
    val latestOnChecked by rememberUpdatedState(onChecked)
    val latestChecked by rememberUpdatedState(checked)
    val triggerToggle = remember(scope, enabled) {
        {
            if (enabled && pendingHolder.value == null) {
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
            .background(
                pressBackground(
                    if (enabled && trackChecked) palette.accent else palette.disabled,
                    palette,
                    press.pressed
                )
            )
            .then(press.modifier)
            .toggleable(
                value = trackChecked,
                enabled = enabled && !isPending,
                role = Role.Switch,
                interactionSource = press.interactionSource,
                indication = null,
                onValueChange = { triggerToggle() }
            )
            .then(if (externalControl != null) Modifier.clearAndSetSemantics {} else Modifier)
            .padding(0.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .offset(x = knobOffset, y = 0.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(
                    if (enabled) {
                        if (trackChecked) Color(0xFFD9ECFF) else Color(0xFFD8E3EE)
                    } else {
                        palette.muted.copy(alpha = 0.45f)
                    }
                )
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
        Text(
            text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        TabButton(copy.hudCheck, RuntimeTab.HudCheck, selected, palette, Modifier.weight(1f), onSelect)
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
    RuntimeTab.Storage -> R.drawable.ic_tab_storage
    RuntimeTab.Patch -> R.drawable.ic_tab_patch
    RuntimeTab.HudCheck -> R.drawable.ic_tab_manual
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
    patch = "Patch",
    hudCheck = "HUD check",
    hudRunning = "HUD: running",
    hudIdle = "HUD: idle",
    hudFailed = "HUD: failed",
    adbOk = "ADB: OK",
    adbNotGranted = "ADB: not granted",
    permissionsOk = "Permissions: OK",
    permissionsMissing = "Permissions: missing",
    ukr = "UA",
    eng = "ENG",
    dark = "Dark",
    light = "Light",
    mainHint = "Navigation settings",
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
    dashboardWindowSize = "Dashboard window profile",
    notice = "Notice",
    wazeDirectNotice = "Waze HUD output works best through the direct channel. Supported versions:",
    wazeSupportedVersions = "stock 4.95.0.3 / patched 5.20.0.1",
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
    textTransliteration = "Text transliteration",
    textTransliterationHint = "Convert Ukrainian Cyrillic or other scripts to Latin characters for street names and directions if the HUD does not display them correctly.",
    textDirectionOutput = "Text direction output",
    textDirectionOutputHint = "Send text direction in street output (\"Continue straight\") if no street text available. Street output has priority.",
    showWazeAlerts = "Show Waze alerts",
    showWazeAlertsHint = "Display Waze alerts on the HUD.",
    tbtWithoutHudOutput = "Create a TBT card even for an active navigator session without HUD output",
    tbtWithoutHudOutputHint = "The TBT card is also created for an active navigator not selected for HUD output.\nIf two navigators are active, priority goes to the navigator with HUD output, or to the most recently started navigator when no HUD output is selected.",
    switchToTbtOnHudStart = "Switch to the TBT card when HUD output starts",
    switchToTbtOnHudStartHint = "Automatically open the TBT card when navigation output to the HUD starts.",
    showWholeRouteMetrics = "Show ETA/time/distance for entire route",
    showWholeRouteMetricsHint = "Prefer whole-route values. Waze falls back to an available next-stop value when an individual whole-route metric is missing.",
    showEta = "Show ETA",
    showEtaHint = "Prepend the estimated arrival time to the street text.",
    showRemainingTime = "Show remaining time",
    showRemainingTimeHint = "Prepend the remaining trip time to the street text.",
    showRemainingDistance = "Show remaining distance",
    showRemainingDistanceHint = "Prepend the remaining trip distance to the street text.",
    dashboardScreenMode = "Dashboard screen mode",
    dashboardScreenModeHint = "Choose the dashboard screen mode",
    dashboardWidth = "Width",
    dashboardWidthHint = "Window width as a percentage of the dashboard width.",
    dashboardHeight = "Height",
    dashboardHeightHint = "Window height as a percentage of the dashboard height.",
    dashboardOffset = "Horizontal offset",
    dashboardOffsetHint = "Position within the remaining horizontal space: 0% left, 50% center, 100% right.",
    dashboardScale = "Scale",
    dashboardScaleHint = "Scale the projected dashboard content inside the window.",
    smallDistanceClamp = "Small distance clamp",
    smallDistanceHint = "Send 11 m for distances from 0 to 10 m instead of the OEM close marker.",
    appsHint = "Manage navigator output to the HUD and dashboard.",
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
    navigatorAssetsNotice = "Direct HUD output works best with these supported navigator builds",
    navigatorAssetDownload = "Download",
    navigatorAssetInstall = "Install",
    navigatorAssetInstalled = "Installed",
    navigatorAssetRetry = "Retry",
    navigatorAssetRestore = "Restore",
    navigatorAssetInstalling = "Installing...",
    navigatorAssetVerifying = "Verifying...",
    navigatorAssetConfirmTitle = "Replace %s?",
    navigatorAssetConfirmText = "The installed navigator will be removed before this APK is installed. Its local data may be lost. The previous APK-set is staged for recovery.",
    navigatorAssetConfirmOk = "Replace",
    navigatorAssetConfirmCancel = "Cancel",
    wazeFeatures = "Waze features",
    customSurface = "Start with custom surface",
    customSurfaceHint = "Open Waze's navigation surface only after a route starts. Use ordinary Waze for search and route setup.",
    hud = "HUD",
    log = "Log",
    sendDashboard = "Send to dashboard",
    sendMain = "Send to main",
    startAppFirst = "Start app first",
    noBackgroundApps = "Supported apps are not duplicated here. This list shows only current non-system background apps.",
    startLogcat = "Start Logcat",
    stopLogcat = "Stop Logcat",
    shareConfiguration = "Export configuration",
    storage = "Storage and logs",
    storageHint = "Navigation log recording, sharing, retention, and cleanup controls.",
    storageSettings = "Storage settings",
    navLogsFolderLimit = "Navigation logs folder limit",
    navLogsFolderLimitHint = "Old data is deleted while the app is running when this folder exceeds the limit.",
    storageLimitGb = "Limit, GB",
    currentNavLogsSize = "Current navigation logs folder size",
    navigationLogsFolder = "Navigation logs folder",
    logs = "Logs",
    privateStorageLocation = "Private folder",
    publicStorageLocation = "Public folder",
    bothStorageLocations = "Public and private folders",
    shareSelected = "Share logs",
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
    patchWarningText = "Select an installed navigator or an APK/APKM/APKS/APK-only XAPK file. Compatible components are patched locally and verified before Android asks you to install the result. Patch eligibility uses package, archive topology, manifest, and exact DEX structure; a repository signer is not required. A signer mismatch may require removing the installed app and can lose local data. Report unsupported versions for analysis:",
    patchRiskWarning = "Proceed at your own risk. App developer is not responsible for any data loss or errors.",
    availableNavigators = "Available navigation apps",
    noSupportedNavigators = "No supported navigation apps",
    appVersion = "Version",
    patchNotChecked = "check",
    patchDirectChannel = "Direct channel",
    patchWazeAlerts = "Alerts",
    patchClearSelection = "Clear selected file",
    patchSelectFile = "Optionally select file",
    patchSelectFileTitle = "Select another app version?",
    patchSelectFileText = "Select another downloaded version of the app that will replace the currently installed version.",
    patchUnsupportedFileText = "Only APK, APKM, APKS, and APK-only XAPK files are supported.",
    patchSelectionErrorText = "The selected source could not be used.",
    patchPatchable = "patch",
    patchPatched = "ready",
    patchFailed = "failed",
    patchSource = "Source",
    patchInstalledSource = "installed app",
    patchProgress = "Applying navigator patch",
    patchRecovery = "Recovery required",
    patchRestore = "Restore source package",
    checkPatch = "Check",
    applyPatch = "Patch",
    patchConfirmTitle = "Patch %s?",
    patchConfirmText = "The installed navigation app must be removed before the patched package can be installed. Its local data will be lost. The selected source package is kept for recovery.",
    patchConfirmOk = "OK",
    patchConfirmCancel = "Cancel",
    hudCheckHint = "Direct payload checks for HUD and dashboard TBT output",
    basicOutput = "Basic output",
    basicOutputHint = "Independent controls for the main output fields",
    extendedOutput = "Extended output",
    extendedOutputHint = "Constant basic packet · automatic or manual cycle",
    checkManeuvers = "Maneuvers",
    checkManeuversHint = "PNG and native follow the same maneuver",
    checkManeuversChannels = "OEM + native",
    checkLanes = "Lanes",
    checkLanesHint = "Recommended and combined lane directions",
    distanceMeters = "Distance, m",
    checkDistanceHint = "1 · 11 · 20 · 55 · 155 · 1555 · 15555",
    streetText = "Street",
    checkStreetHint = "Latin and Cyrillic words · only Cyrillic is transliterated",
    checkTransliteration = "Transliteration",
    checkTrafficLight = "Traffic-light field",
    checkTrafficLightHint = "Directions, colors, numbers and stock symbols",
    stockImage = "Stock",
    bitmapImage = "Bitmap",
    hudCheckRunning = "Running",
    hudCheckStopped = "Stopped",
    hudCheckStart = "Start",
    hudCheckStop = "Stop",
    checkBaseline = "Constant basic packet",
    currentField = "Expected additional output",
    checkAutomatic = "Auto",
    checkManualCycle = "Manual",
    checkManualCycleHint = "Use the arrows to change fields · Auto resumes from this step",
    checkCycle = "One step every 500 ms · turn Auto off to hold the current step",
    checkExtendedNotice = "The basic packet stays unchanged · additional field visibility depends on the vehicle",
    previous = "Previous",
    next = "Next",
)

//keeps this HUD step isolated so cluster payload behavior stays predictable.
private fun uaCopy() = enCopy().copy(
    language = Language.Ua,
    subtitle = "Виведення навігації на HUD | v${BuildConfig.VERSION_NAME}",
    main = "Налаштування",
    apps = "Застосунки",
    patch = "Патч",
    hudCheck = "Перевірка HUD",
    hudRunning = "HUD: працює",
    hudIdle = "HUD: очікує",
    hudFailed = "HUD: помилка",
    adbNotGranted = "ADB: не видано",
    permissionsOk = "Права: OK",
    permissionsMissing = "Права: немає",
    dark = "Темна",
    light = "Світла",
    mainHint = "Налаштування навігації",
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
    dashboardWindowSize = "Профіль вікна приборки",
    notice = "Примітка",
    wazeDirectNotice = "Вивід Waze на HUD найкраще працює через прямий канал. Підтримувані версії:",
    wazeSupportedVersions = "стокова 4.95.0.3 / патчена 5.20.0.1",
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
    textTransliteration = "Транслітерація тексту",
    textTransliterationHint = "Перетворювати українську кирилицю або інші системи письма на латиницю для вулиць/напрямків, якщо HUD не відображає назви коректно.",
    textDirectionOutput = "Вивід напрямків текстом",
    textDirectionOutputHint = "Виводити у поле для вулиці текстові напрямки (\"Прямуйте далі\"), якщо відсутній текст вулиці. Вивід вулиці має пріоритет.",
    showWazeAlerts = "Показувати попередження Waze",
    showWazeAlertsHint = "Відображати попередження Waze на HUD.",
    tbtWithoutHudOutput = "Формувати TBT-картку навіть для активної сесії навігатора без виводу на HUD",
    tbtWithoutHudOutputHint = "TBT-картка формуватиметься і для активного навігатора, не вибраного для виводу на HUD.\nЯкщо одночасно активні два навігатори, пріоритет має навігатор з активним виводом на HUD або останній запущений навігатор, якщо вивід на HUD не вибрано.",
    switchToTbtOnHudStart = "Перемикатися на TBT-картку при початку виводу на HUD",
    switchToTbtOnHudStartHint = "Автоматично відкривати TBT-картку, коли починається вивід навігації на HUD.",
    showWholeRouteMetrics = "Показувати ETA/час/дистанцію всього маршруту",
    showWholeRouteMetricsHint = "Надавати перевагу значенням усього маршруту. Waze використовує доступне значення до зупинки, якщо окремий показник усього маршруту відсутній.",
    showEta = "Показувати час прибуття",
    showEtaHint = "Додавати очікуваний час прибуття перед назвою вулиці.",
    showRemainingTime = "Показувати залишок часу",
    showRemainingTimeHint = "Додавати залишок часу поїздки перед назвою вулиці.",
    showRemainingDistance = "Показувати залишок дистанції",
    showRemainingDistanceHint = "Додавати залишок дистанції поїздки перед назвою вулиці.",
    dashboardScreenMode = "Режим екрану на приборці",
    dashboardScreenModeHint = "Оберіть режим екрану на приборці",
    dashboardWidth = "Ширина",
    dashboardWidthHint = "Ширина вікна у відсотках від ширини приборки.",
    dashboardHeight = "Висота",
    dashboardHeightHint = "Висота вікна у відсотках від висоти приборки.",
    dashboardOffset = "Горизонтальне зміщення",
    dashboardOffsetHint = "Положення у вільному просторі: 0% ліворуч, 50% по центру, 100% праворуч.",
    dashboardScale = "Масштаб",
    dashboardScaleHint = "Масштаб проєктованого вмісту приборки всередині вікна.",
    smallDistanceClamp = "Обрізка малої дистанції",
    smallDistanceHint = "Передавати 11 м для дистанцій від 0 до 10 м замість штатного маркера близької відстані.",
    appsHint = "Керування виводом навігаторів на HUD та приборку.",
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
    navigatorAssetsNotice = "Вивід на HUD найкраще працює з цими підтримуваними збірками навігаторів",
    navigatorAssetDownload = "Скачати",
    navigatorAssetInstall = "Встановити",
    navigatorAssetInstalled = "Встановлено",
    navigatorAssetRetry = "Повторити",
    navigatorAssetRestore = "Відновити",
    navigatorAssetInstalling = "Встановлення...",
    navigatorAssetVerifying = "Перевірка...",
    navigatorAssetConfirmTitle = "Замінити %s?",
    navigatorAssetConfirmText = "Перед встановленням цього APK установлений навігатор буде видалено. Його локальні дані може бути втрачено. Попередній APK-set збережено для відновлення.",
    navigatorAssetConfirmOk = "Замінити",
    navigatorAssetConfirmCancel = "Скасувати",
    wazeFeatures = "Функції Waze",
    customSurface = "Запускати з власним surface",
    customSurfaceHint = "Відкривати навігаційний surface Waze лише після початку маршруту. Для пошуку та побудови маршруту використовуйте звичайний Waze.",
    log = "Лог",
    sendDashboard = "На приборку",
    sendMain = "На основний екран",
    startAppFirst = "Спочатку запусти",
    noBackgroundApps = "Підтримувані застосунки тут не дублюються. Тут тільки поточні несистемні фонові застосунки.",
    startLogcat = "Почати Logcat",
    stopLogcat = "Зупинити Logcat",
    shareConfiguration = "Експортувати конфігурацію",
    storage = "Сховище та логи",
    storageHint = "Запис, поширення, зберігання й очищення навігаційних логів.",
    storageSettings = "Налаштування сховища",
    navLogsFolderLimit = "Ліміт теки з журналом навігації",
    navLogsFolderLimitHint = "Старі дані видаляються під час роботи застосунку, коли тека перевищує ліміт.",
    storageLimitGb = "Ліміт, ГБ",
    currentNavLogsSize = "Поточний розмір теки з журналом навігації",
    navigationLogsFolder = "Тека журналу навігації",
    logs = "Логи",
    privateStorageLocation = "Приватна тека",
    publicStorageLocation = "Публічна тека",
    bothStorageLocations = "Публічна та приватна теки",
    shareSelected = "Поділитись логами",
    ukr = "Укр",
    eng = "Англ",
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
    patchWarningText = "Оберіть установлений навігатор або файл APK/APKM/APKS/XAPK без OBB. Сумісні компоненти буде пропатчено локально та перевірено до системного запиту на встановлення. Сумісність визначається package, структурою архіву, manifest і точною DEX-структурою; ключ репозиторію не потрібен. Невідповідність підпису може вимагати видалення встановленого застосунку та призвести до втрати локальних даних. Повідомляйте про непідтримувані версії для аналізу:",
    patchRiskWarning = "Дійте на власний ризик. Розробник застосунку не несе відповідальності за втрату даних та помилки.",
    availableNavigators = "Доступні навігатори",
    noSupportedNavigators = "Немає підтримуваних навігаторів",
    appVersion = "Версія",
    patchNotChecked = "перевір",
    patchDirectChannel = "Прямий канал",
    patchWazeAlerts = "Попередження",
    patchClearSelection = "Скасувати вибір файла",
    patchSelectFile = "Опційно обрати файл",
    patchSelectFileTitle = "Обрати іншу версію застосунку?",
    patchSelectFileText = "Обрати іншу завантажену версію застосунку, яка замінить поточну встановлену версію застосунку.",
    patchUnsupportedFileText = "Підтримуються лише файли APK, APKM, APKS та XAPK без OBB.",
    patchSelectionErrorText = "Обране джерело неможливо використати.",
    patchPatchable = "патчити",
    patchPatched = "готово",
    patchFailed = "помилка",
    patchSource = "Джерело",
    patchInstalledSource = "установлений застосунок",
    patchProgress = "Застосування патчу навігатора",
    patchRecovery = "Потрібне відновлення",
    patchRestore = "Відновити вихідний пакет",
    checkPatch = "Перевір",
    applyPatch = "Пропатчити",
    patchConfirmTitle = "Пропатчити %s?",
    patchConfirmText = "Перед встановленням пропатченого пакета установлений навігатор потрібно видалити. Його локальні дані буде втрачено. Обраний вихідний пакет зберігається для відновлення.",
    patchConfirmOk = "Ок",
    patchConfirmCancel = "Скасувати",
    hudCheckHint = "Пряма перевірка даних HUD і TBT-картки приборки",
    basicOutput = "Базовий вивід",
    basicOutputHint = "Незалежне керування основними полями виводу",
    extendedOutput = "Розширений вивід",
    extendedOutputHint = "Постійний базовий пакет · цикл авто або ручний",
    checkManeuvers = "Маневри",
    checkManeuversHint = "PNG і native відповідають одному маневру",
    checkManeuversChannels = "OEM + native",
    checkLanes = "Смуги",
    checkLanesHint = "Рекомендовані та суміщені напрямки",
    distanceMeters = "Дистанція, м",
    checkDistanceHint = "1 · 11 · 20 · 55 · 155 · 1555 · 15555",
    streetText = "Вулиця",
    checkStreetHint = "Латиниця й кирилиця · транслітерація лише кирилиці",
    checkTransliteration = "Транслітерація",
    checkTrafficLight = "Поле світлофора",
    checkTrafficLightHint = "Напрямки, кольори, числа та штатні символи",
    stockImage = "Штатне",
    bitmapImage = "Bitmap",
    hudCheckRunning = "Працює",
    hudCheckStopped = "Зупинено",
    hudCheckStart = "Почати",
    hudCheckStop = "Зупинити",
    checkBaseline = "Постійний базовий пакет",
    currentField = "Додатково має відображатись",
    checkAutomatic = "Авто",
    checkManualCycle = "Ручний",
    checkManualCycleHint = "Стрілки змінюють поле · Авто продовжує з цього кроку",
    checkCycle = "Один крок кожні 500 мс · вимкніть Авто, щоб утримати крок",
    checkExtendedNotice = "Базовий пакет не змінюється · відображення додаткового поля залежить від автомобіля",
    previous = "Попередній",
    next = "Наступний",
)

private fun shareCopy(language: Language) = if (language == Language.Ua) {
    ShareCopy(
        shareLogsTitle = "Поділитися навігаційними логами",
        shareLogsSelection = "Обрано днів: %d · файлів: %d · %s",
        shareLogsArchiveHint = "Буде підготовлено один ZIP-архів із повним вмістом усіх обраних тек за дні.",
        shareLogsSensitiveWarning = "Архів може містити точні координати, маршрути, назви вулиць і пошукові запити, знімки або direct-зображення Waze та повний системний logcat.",
        shareLogsSentryNotice = "Надсилання розробнику використовує сервіс Sentry.",
        shareToSentry = "Надіслати розробнику",
        shareToAnotherApp = "Інший застосунок",
        cancel = "Скасувати",
        waitingForWrites = "Очікування записів",
        copying = "Копіювання",
        archiving = "Архівація",
        uploadTitle = "Надсилання логів розробнику",
        preparing = "Готуємо архів...",
        uploading = "Надсилаємо архів...",
        success = "Логи успішно надіслано.",
        failure = "Не вдалося надіслати логи.",
        reportId = "ID звіту",
        close = "Закрити",
        configurationTitle = "Поділитися конфігурацією авто",
        configurationWarning = "Архів міститиме доступні поточні значення HUD/приборки; дозволи, компоненти, стан виконання й налаштування BYD HUD; відомості про пристрій, прошивку, пакети/процеси, дисплеї, аудіо й систему; мережеву діагностику та SOME/IP. Мережеві адреси маскуються; облікові дані, VIN, акаунти, маршрути, координати, вміст сповіщень і аудіозаписи не включаються. Автоматичного надсилання немає. Перевірте отримувача перед надсиланням.",
        configurationUploadTitle = "Надсилання конфігурації розробнику",
        configurationSuccess = "Конфігурацію успішно надіслано.",
        configurationFailure = "Не вдалося надіслати конфігурацію."
    )
} else {
    ShareCopy(
        shareLogsTitle = "Share navigation logs",
        shareLogsSelection = "%d selected days · %d files · %s",
        shareLogsArchiveHint = "One ZIP archive containing the complete selected day folders will be prepared.",
        shareLogsSensitiveWarning = "The archive may contain exact coordinates, routes, street and search text, Waze screenshots or direct images, and full system logcat output.",
        shareLogsSentryNotice = "Sending to developer uses Sentry service.",
        shareToSentry = "Send to developer",
        shareToAnotherApp = "Another app",
        cancel = "Cancel",
        waitingForWrites = "Waiting for writes",
        copying = "Copying",
        archiving = "Archiving",
        uploadTitle = "Sending logs to developer",
        preparing = "Preparing the archive...",
        uploading = "Uploading the archive...",
        success = "Logs were sent successfully.",
        failure = "The logs could not be sent.",
        reportId = "Report ID",
        close = "Close",
        configurationTitle = "Share vehicle configuration",
        configurationWarning = "The archive includes available live HUD/cluster values; permissions, components, runtime and BYD HUD options; device/firmware, package/process, display, audio and system metadata; network and SOME/IP diagnostics. Network addresses are masked; credentials, VIN, account data, routes, coordinates, notification contents and recordings are excluded. Nothing is uploaded automatically. Verify the recipient before sending.",
        configurationUploadTitle = "Sending configuration to developer",
        configurationSuccess = "Configuration sent successfully.",
        configurationFailure = "The configuration could not be sent."
    )
}
