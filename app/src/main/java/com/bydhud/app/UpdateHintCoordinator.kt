package com.bydhud.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import java.util.UUID
import kotlin.math.roundToInt

data class UpdateHintRequest(
    val eventId: String,
    val requestedAtElapsedNanos: Long,
    val preferredSizePercent: Int,
    val preferredWidthPx: Int,
    val preferredHeightPx: Int
)

data class UpdateHintPlacement(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val effectiveSizePercent: Float
)

object UpdateHintCoordinator {
    private const val TAG = "BYDHUD_UPDATE_HINT"
    private val main = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()
    private val engine = UpdateHintStateEngine()
    private var revision = 0L
    private var appContext: Context? = null
    private var own: UpdateHintRecord = noneRecord()
    private var callback: ((UpdateHintPlacement) -> Unit)? = null
    private var admitted = false
    private var admissionGeneration = 0L
    private var lastPlacement: UpdateHintPlacement? = null
    private val waitingForInitial = mutableSetOf<String>()
    private val peers = mutableMapOf<String, PeerConnection>()
    private var packageReceiverRegistered = false
    private var displayListenerRegistered = false
    private var expiryRunnable: Runnable? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val owner = intent.data?.schemeSpecificPart ?: return
            if (owner !in UpdateHintProtocol.OWNERS || owner == UpdateHintProtocol.OWNER_HUD) return
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                engine.current(owner)?.let { engine.confirmedDeath(owner, it.processSessionId) }
                disconnect(owner, sendUnsubscribe = false)
                publishLayout()
            } else if (intent.action == Intent.ACTION_PACKAGE_REPLACED) {
                engine.current(owner)?.let { engine.confirmedDeath(owner, it.processSessionId) }
                disconnect(owner, sendUnsubscribe = false)
                discoverPeer(owner, initial = false)
                publishLayout()
            } else if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
                disconnect(owner, sendUnsubscribe = false)
                discoverPeer(owner, initial = false)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) publishLayout()
        }
    }

    fun request(
        context: Context,
        request: UpdateHintRequest,
        onPlacement: (UpdateHintPlacement) -> Unit
    ) = onMain {
        require(request.eventId.isNotBlank())
        require(request.requestedAtElapsedNanos > 0L)
        require(request.preferredSizePercent > 0)
        require(request.preferredWidthPx > 0 && request.preferredHeightPx > 0)
        if (own.phase != UpdateHintPhase.NONE) releaseMain(own.eventId, "replaced")
        appContext = context.applicationContext
        callback = onPlacement
        admitted = false
        val admission = ++admissionGeneration
        lastPlacement = null
        own = UpdateHintRecord(
            ownerPackage = UpdateHintProtocol.OWNER_HUD,
            processSessionId = sessionId,
            revision = ++revision,
            eventId = request.eventId,
            phase = UpdateHintPhase.PENDING,
            requestedAtElapsedNanos = request.requestedAtElapsedNanos,
            expiresAtElapsedMs = 0L,
            displayId = Display.DEFAULT_DISPLAY,
            preferredSizePercent = request.preferredSizePercent,
            preferredWidthPx = request.preferredWidthPx,
            preferredHeightPx = request.preferredHeightPx
        )
        engine.accept(UpdateHintProtocol.OWNER_HUD, own)
        registerObservers()
        waitingForInitial.clear()
        UpdateHintProtocol.OWNERS.filter { it != UpdateHintProtocol.OWNER_HUD }
            .forEach { discoverPeer(it, initial = true) }
        sendOwn(UpdateHintProtocol.STATE)
        main.postDelayed({
            if (admissionGeneration == admission && own.eventId == request.eventId && !admitted) admit()
        }, UpdateHintProtocol.initialExchangeDelayMs(
            request.requestedAtElapsedNanos, SystemClock.elapsedRealtimeNanos()))
        maybeAdmit()
    }

    fun markVisible(eventId: String, expiresAtElapsedMs: Long) = onMain {
        if (own.eventId != eventId || own.phase != UpdateHintPhase.PENDING ||
            expiresAtElapsedMs <= SystemClock.elapsedRealtime()) return@onMain
        own = own.copy(
            revision = ++revision,
            phase = UpdateHintPhase.VISIBLE,
            expiresAtElapsedMs = expiresAtElapsedMs
        )
        engine.accept(UpdateHintProtocol.OWNER_HUD, own)
        sendOwn(UpdateHintProtocol.STATE)
        publishLayout()
    }

    fun updateGeometry(
        eventId: String,
        preferredSizePercent: Int,
        preferredWidthPx: Int,
        preferredHeightPx: Int
    ) = onMain {
        if (own.eventId != eventId || own.phase == UpdateHintPhase.NONE ||
            preferredSizePercent <= 0 || preferredWidthPx <= 0 || preferredHeightPx <= 0) return@onMain
        if (own.preferredSizePercent == preferredSizePercent &&
            own.preferredWidthPx == preferredWidthPx && own.preferredHeightPx == preferredHeightPx) return@onMain
        own = own.copy(
            revision = ++revision,
            preferredSizePercent = preferredSizePercent,
            preferredWidthPx = preferredWidthPx,
            preferredHeightPx = preferredHeightPx
        )
        engine.accept(UpdateHintProtocol.OWNER_HUD, own)
        sendOwn(UpdateHintProtocol.STATE)
        publishLayout()
    }

    fun release(eventId: String, reason: String) = onMain { releaseMain(eventId, reason) }

    internal fun ownSnapshot(): UpdateHintRecord = own

    internal fun acceptFromService(authenticatedOwner: String, record: UpdateHintRecord): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val changed = engine.accept(authenticatedOwner, record)
        if (changed) publishLayout()
        return engine.current(authenticatedOwner)?.processSessionId == record.processSessionId
    }

    internal fun confirmedDeath(owner: String, processSessionId: String) {
        onMain {
            if (engine.confirmedDeath(owner, processSessionId)) publishLayout()
        }
    }

    private fun receive(connection: PeerConnection, message: Message): Boolean {
        if (message.what != UpdateHintProtocol.STATE || peers[connection.owner] !== connection) return false
        val context = appContext ?: return true
        val record = UpdateHintWire.decodeAuthenticated(context, message) ?: run {
            Log.w(TAG, "Rejected state from uid=${message.sendingUid}")
            return true
        }
        if (record.ownerPackage != connection.owner) return true
        waitingForInitial.remove(record.ownerPackage)
        val changed = engine.accept(record.ownerPackage, record)
        if (engine.current(record.ownerPackage)?.processSessionId == record.processSessionId) {
            connection.acceptedSessionId = record.processSessionId
        }
        if (changed) publishLayout()
        maybeAdmit()
        return true
    }

    private fun releaseMain(eventId: String, reason: String) {
        if (own.eventId != eventId || own.phase == UpdateHintPhase.NONE) return
        own = noneRecord(++revision)
        engine.accept(UpdateHintProtocol.OWNER_HUD, own)
        Log.i(TAG, "release event=$eventId reason=$reason")
        sendOwn(UpdateHintProtocol.STATE)
        peers.keys.toList().forEach { disconnect(it, sendUnsubscribe = true) }
        unregisterObservers()
        callback = null
        admitted = false
        ++admissionGeneration
        lastPlacement = null
        waitingForInitial.clear()
        expiryRunnable?.let(main::removeCallbacks)
        expiryRunnable = null
    }

    private fun maybeAdmit() {
        if (!admitted && waitingForInitial.isEmpty()) admit()
    }

    private fun admit() {
        if (admitted || own.phase == UpdateHintPhase.NONE) return
        admitted = true
        publishLayout(includeOwnExpiredPending = true)
    }

    private fun publishLayout(includeOwnExpiredPending: Boolean = false) {
        val context = appContext ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val active = engine.active(nowMs, nowNanos).toMutableList()
        peers.keys.toList().forEach { owner ->
            val snapshot = engine.current(owner)
            if (snapshot != null && !snapshot.isActive(nowMs, nowNanos)) {
                disconnect(owner, sendUnsubscribe = true)
            }
        }
        if (includeOwnExpiredPending && active.none { it.ownerPackage == own.ownerPackage } &&
            own.phase == UpdateHintPhase.PENDING) active += own
        val sorted = active.sortedWith(compareBy<UpdateHintRecord> { it.requestedAtElapsedNanos }
            .thenBy { UpdateHintProtocol.OWNERS.indexOf(it.ownerPackage) })
        val density = context.resources.displayMetrics.density
        val result = UpdateHintLayoutEngine.layout(
            sorted.map {
                UpdateHintLayoutItem(it.ownerPackage, it.preferredSizePercent,
                    it.preferredWidthPx, it.preferredHeightPx)
            },
            usableBounds(context),
            (18f * density).roundToInt(),
            (8f * density).roundToInt()
        )[UpdateHintProtocol.OWNER_HUD]
        if (admitted && result != null) {
            val placement = UpdateHintPlacement(result.xPx, result.yPx, result.widthPx,
                result.heightPx, result.effectiveSizePercent)
            if (placement != lastPlacement) {
                lastPlacement = placement
                callback?.invoke(placement)
                Log.i(TAG, "layout event=${own.eventId} x=${placement.xPx} y=${placement.yPx} " +
                    "w=${placement.widthPx} h=${placement.heightPx} scale=${placement.effectiveSizePercent}")
            }
        }
        if (own.phase != UpdateHintPhase.NONE) scheduleExpiry(active, nowMs, nowNanos)
    }

    private fun scheduleExpiry(active: List<UpdateHintRecord>, nowMs: Long, nowNanos: Long) {
        expiryRunnable?.let(main::removeCallbacks)
        val delay = active.minOfOrNull { record ->
            when (record.phase) {
                UpdateHintPhase.PENDING ->
                    ((record.requestedAtElapsedNanos + 500_000_000L - nowNanos) / 1_000_000L).coerceAtLeast(0L)
                UpdateHintPhase.VISIBLE -> (record.expiresAtElapsedMs - nowMs).coerceAtLeast(0L)
                UpdateHintPhase.NONE -> Long.MAX_VALUE
            }
        } ?: return
        expiryRunnable = Runnable { publishLayout() }.also { main.postDelayed(it, delay + 1L) }
    }

    private fun discoverPeer(owner: String, initial: Boolean) {
        val context = appContext ?: return
        if (peers.containsKey(owner)) return
        val intent = Intent(UpdateHintProtocol.ACTION).setPackage(owner)
        val service = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
            .firstOrNull {
                it.serviceInfo?.packageName == owner &&
                    it.serviceInfo?.metaData?.getInt(UpdateHintProtocol.METADATA_PROTOCOL_VERSION, -1) ==
                    UpdateHintProtocol.VERSION
            }?.serviceInfo ?: return
        if (initial) waitingForInitial += owner
        val connection = PeerConnection(owner)
        peers[owner] = connection
        val bound = try {
            context.bindService(Intent(UpdateHintProtocol.ACTION)
                .setComponent(ComponentName(service.packageName, service.name)), connection,
                Context.BIND_AUTO_CREATE)
        } catch (error: RuntimeException) {
            Log.w(TAG, "bind failed owner=$owner", error)
            false
        }
        if (!bound) {
            peers.remove(owner)
            waitingForInitial.remove(owner)
        }
    }

    private fun sendOwn(what: Int) {
        peers.values.forEach { it.send(what, own) }
        UpdateHintCoordinationService.broadcastOwn(own)
    }

    private fun disconnect(owner: String, sendUnsubscribe: Boolean) {
        val context = appContext ?: return
        val peer = peers.remove(owner) ?: return
        if (sendUnsubscribe) peer.send(UpdateHintProtocol.UNSUBSCRIBE, own)
        val death = peer.deathRecipient
        if (death != null) {
            try { peer.binder?.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
        }
        try { context.unbindService(peer) } catch (_: IllegalArgumentException) { }
        waitingForInitial.remove(owner)
    }

    private fun registerObservers() {
        val context = appContext ?: return
        if (!packageReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            context.registerReceiver(packageReceiver, filter)
            packageReceiverRegistered = true
        }
        if (!displayListenerRegistered) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(displayListener, main)
            displayListenerRegistered = true
        }
    }

    private fun unregisterObservers() {
        val context = appContext ?: return
        if (packageReceiverRegistered) {
            try { context.unregisterReceiver(packageReceiver) } catch (_: IllegalArgumentException) { }
            packageReceiverRegistered = false
        }
        if (displayListenerRegistered) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun usableBounds(context: Context): UpdateHintBounds {
        val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bounds = metrics.bounds
            return UpdateHintBounds(
                0,
                0,
                bounds.width() - insets.left - insets.right,
                bounds.height() - insets.top - insets.bottom
            )
        }
        val display = windows.defaultDisplay
        val size = Point()
        // API 29 exposes the decor-adjusted main-display size but no token-free WindowInsets.
        display.getSize(size)
        return UpdateHintBounds(0, 0, size.x, size.y)
    }

    private class PeerConnection(val owner: String) : ServiceConnection {
        var remote: Messenger? = null
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        var acceptedSessionId: String? = null
        private val callbackMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            UpdateHintCoordinator.receive(this, message)
        })

        private fun binderDied(deadBinder: IBinder) {
            UpdateHintCoordinator.main.post {
                if (UpdateHintCoordinator.peers[owner] !== this@PeerConnection ||
                    binder !== deadBinder) return@post
                acceptedSessionId?.let { UpdateHintCoordinator.engine.confirmedDeath(owner, it) }
                UpdateHintCoordinator.disconnect(owner, sendUnsubscribe = false)
                UpdateHintCoordinator.publishLayout()
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (UpdateHintCoordinator.peers[owner] !== this) return
            binder = service
            remote = Messenger(service)
            acceptedSessionId = null
            val death = IBinder.DeathRecipient { binderDied(service) }
            deathRecipient = death
            try { service.linkToDeath(death, 0) } catch (_: RemoteException) {
                binderDied(service)
                return
            }
            send(UpdateHintProtocol.SUBSCRIBE, UpdateHintCoordinator.own)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (UpdateHintCoordinator.peers[owner] !== this) return
            remote = null // transient disconnect retains the last unexpired snapshot
        }

        override fun onBindingDied(name: ComponentName) {
            if (UpdateHintCoordinator.peers[owner] !== this) return
            binder?.let(::binderDied)
        }

        override fun onNullBinding(name: ComponentName) {
            if (UpdateHintCoordinator.peers[owner] !== this) return
            UpdateHintCoordinator.disconnect(owner, sendUnsubscribe = false)
            UpdateHintCoordinator.maybeAdmit()
        }

        fun send(what: Int, record: UpdateHintRecord) {
            val endpoint = remote ?: return
            val message = Message.obtain(null, what).apply {
                data = UpdateHintWire.bundle(record)
                if (what == UpdateHintProtocol.SUBSCRIBE || what == UpdateHintProtocol.UNSUBSCRIBE) {
                    replyTo = callbackMessenger
                }
            }
            try { endpoint.send(message) } catch (_: RemoteException) { }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun noneRecord(nextRevision: Long = 0L) = UpdateHintRecord(
        ownerPackage = UpdateHintProtocol.OWNER_HUD,
        processSessionId = sessionId,
        revision = nextRevision,
        eventId = "",
        phase = UpdateHintPhase.NONE,
        requestedAtElapsedNanos = 0L,
        expiresAtElapsedMs = 0L,
        displayId = Display.DEFAULT_DISPLAY,
        preferredSizePercent = 0,
        preferredWidthPx = 0,
        preferredHeightPx = 0
    )
}

internal object UpdateHintWire {
    fun bundle(record: UpdateHintRecord) = Bundle().apply {
        putInt(UpdateHintProtocol.KEY_PROTOCOL_VERSION, record.protocolVersion)
        putString(UpdateHintProtocol.KEY_OWNER_PACKAGE, record.ownerPackage)
        putString(UpdateHintProtocol.KEY_PROCESS_SESSION_ID, record.processSessionId)
        putLong(UpdateHintProtocol.KEY_REVISION, record.revision)
        putString(UpdateHintProtocol.KEY_EVENT_ID, record.eventId)
        putString(UpdateHintProtocol.KEY_PHASE, record.phase.name)
        putLong(UpdateHintProtocol.KEY_REQUESTED_AT_NANOS, record.requestedAtElapsedNanos)
        putLong(UpdateHintProtocol.KEY_EXPIRES_AT_MS, record.expiresAtElapsedMs)
        putInt(UpdateHintProtocol.KEY_DISPLAY_ID, record.displayId)
        putInt(UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT, record.preferredSizePercent)
        putInt(UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX, record.preferredWidthPx)
        putInt(UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX, record.preferredHeightPx)
    }

    private fun record(data: Bundle): UpdateHintRecord? = try {
        UpdateHintRecord(
            protocolVersion = data.getInt(UpdateHintProtocol.KEY_PROTOCOL_VERSION, -1),
            ownerPackage = data.getString(UpdateHintProtocol.KEY_OWNER_PACKAGE, ""),
            processSessionId = data.getString(UpdateHintProtocol.KEY_PROCESS_SESSION_ID, ""),
            revision = data.getLong(UpdateHintProtocol.KEY_REVISION, -1L),
            eventId = data.getString(UpdateHintProtocol.KEY_EVENT_ID, ""),
            phase = UpdateHintPhase.valueOf(data.getString(UpdateHintProtocol.KEY_PHASE, "")),
            requestedAtElapsedNanos = data.getLong(UpdateHintProtocol.KEY_REQUESTED_AT_NANOS, 0L),
            expiresAtElapsedMs = data.getLong(UpdateHintProtocol.KEY_EXPIRES_AT_MS, 0L),
            displayId = data.getInt(UpdateHintProtocol.KEY_DISPLAY_ID, -1),
            preferredSizePercent = data.getInt(UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT, 0),
            preferredWidthPx = data.getInt(UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX, 0),
            preferredHeightPx = data.getInt(UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX, 0)
        ).takeIf { it.isValid() }
    } catch (_: RuntimeException) { null }

    fun decodeAuthenticated(context: Context, message: Message): UpdateHintRecord? {
        val installedOwners = try {
            UpdateHintProtocol.knownOwners(
                context.packageManager.getPackagesForUid(message.sendingUid))
        } catch (_: RuntimeException) {
            return null
        }
        if (installedOwners.isEmpty()) return null
        return try {
            record(message.data)?.takeIf { it.ownerPackage in installedOwners }
        } catch (_: RuntimeException) {
            null
        }
    }
}
