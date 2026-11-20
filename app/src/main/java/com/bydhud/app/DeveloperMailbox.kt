package com.bydhud.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object DeveloperMailbox {
    private lateinit var ctx: Context
    private lateinit var store: MailStore
    private lateinit var exchange: MailExchange
    private lateinit var api: MailApi
    private val main = Handler(Looper.getMainLooper())
    private val local = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val eventsWorker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean()
    private val again = AtomicBoolean()
    private val syncTrace=ThreadLocal<String>()
    private var runtimeAlive=false
    private var anchor=WeakReference<View>(null)
    private var uiVisible=false
    private var initialized=false
    private var refreshRequested=false
    private var refreshQueued=false
    var signupPending by mutableStateOf(false)
        private set
    private var userFetch=false
    @Volatile private var withdrawing=false
    private var changingConsent=false
    private var automaticBlocked=false
    private var stream: MailEvents?=null
    private var streamEpoch=-1L
    private var streamRevision=-1L
    private var eventsAdmitted=false
    private var eventsBlocked=false
    private var eventsRetryAt=0L
    private var syncRetryAt=0L
    private val eventsBackoff=MailBackoff()
    private val syncBackoff=MailBackoff()
    private val reconcileRequested=AtomicBoolean(true)
    private var connectedNetwork: Network?=null
    private var observingPower=false
    private var bannerPending=false
    private var vehiclePower=VehiclePowerMonitor.Reading()
    private val bannerRetry=UpdateHintRetryPolicy()
    private var bannerAttempt: UpdateHintPresentationAttempt?=null
    private var bannerBatch=emptyList<String>()
    private var bannerSequence=0L
    private var bannerGeneration=0L
    private var bannerWaitReason=""
    private val retrySync=Runnable { if (eligible() && online()) sync(automatic=true) }
    private val retryEvents=Runnable { openEvents() }
    private var dialogAnchor=WeakReference<View>(null)
    fun dialog(view: View?) { dialogAnchor=WeakReference(view) }
    private val retryQuota=Runnable { if (eligible()) sync() }
    var state by mutableStateOf<MailAccount?>(null)
        private set
    val id get() = state?.id.orEmpty()
    val nickname get() = state?.name.orEmpty()
    val vehicleDescription get() = state?.description.orEmpty()
    val active get() = state?.active==true && !withdrawing
    val announcements get() = state?.news==true
    val banner get() = state?.banner==true
    var ready by mutableStateOf(false)
        private set
    var controlPending by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var letters by mutableStateOf(emptyList<MailLetter>())
        private set
    val unread get() = letters.count { !it.sent && !it.trash && !it.read }
    const val MAX_NICKNAME=80
    const val MAX_DESCRIPTION=160

    @JvmStatic fun initialize(context: Context) {
        if(initialized) return
        initialized=true
        ctx=context.applicationContext
        local.execute {
            try {
                store=MailStore(AndroidMailSql(ctx))
                api=MailApi(URL("https://api.sunlix-heim.com/v1"),"BYD-HUD/"+BuildConfig.VERSION_NAME,MailVault::open,::log)
                exchange=MailExchange(store,api,::log)
                publish()
                main.post { ready=true; schedule() }
            } catch(e: Exception) { failed("storage",e) }
        }
        try {
            ContextCompat.registerReceiver(ctx,object: BroadcastReceiver() {
            override fun onReceive(c: Context,i: Intent) { schedule() }
        },IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },ContextCompat.RECEIVER_NOT_EXPORTED)
        ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { main.post { networkChanged() } }
            override fun onLost(n: Network) { main.post { networkChanged() } }
            override fun onCapabilitiesChanged(n: Network,c: NetworkCapabilities) { main.post { networkChanged() } }
        })
        ctx.getSystemService(DisplayManager::class.java).registerDisplayListener(object: DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) { schedule() }
            override fun onDisplayChanged(id: Int) { if(id==android.view.Display.DEFAULT_DISPLAY) schedule() }
        },main)
        } catch (e: RuntimeException) { failed("wake-listeners",e) }
    }
    @JvmStatic fun runtime(value: Boolean) { runtimeAlive=value; schedule() }
    fun visible(view: View?,value: Boolean) {
        if (uiVisible==value && (!value || anchor.get()===view)) return
        uiVisible=value
        anchor=WeakReference(if(value) view else null)
        schedule()
    }
    private fun eligible()=initialized && ready && (runtimeAlive || uiVisible) && UpdateHintScreenPolicy.canPresent(
        ctx.getSystemService(PowerManager::class.java).isInteractive,
        ctx.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state==android.view.Display.STATE_ON)
    private fun online(): Boolean {
        val cm=ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    }
    private fun networkChanged() {
        val now=ctx.getSystemService(ConnectivityManager::class.java).activeNetwork
        if(now==connectedNetwork) return
        connectedNetwork=now
        stopEvents("network-change")
        eventsAdmitted=false
        reconcileRequested.set(true)
        schedule()
    }
    private fun schedule() {
        observePower()
        if(eligible()) {
            requestBanner()
            if(online()) {
                if (!eventsAdmitted) sync(automatic=true) else openEvents()
            }
        } else if(initialized) {
            stopEvents("lifecycle")
            eventsAdmitted=false
            main.removeCallbacks(retrySync)
            main.removeCallbacks(retryQuota)
            cancelBanner()
        }
    }
    private fun language()=Language.fromCode(HudPrefs.uiLanguage(ctx))
    fun waitMillis(now: Long) = ((state?.sendAfter ?: 0)-now).coerceAtLeast(0)
    private fun publish() {
        val account=store.account()
        val messages=store.visible()
        val pending=store.firstControl()!=null
        val pendingBanner=store.pendingBanner().isNotEmpty()
        main.post {
            controlPending=pending
            state=account; letters=messages
            bannerPending=pendingBanner
            observePower()
            if(stream!=null && (account?.epoch!=streamEpoch || account.revision!=streamRevision || !active || pending)) {
                stopEvents("account-change")
                if(!active || pending) eventsAdmitted=false
            }
            if(!active || !banner || controlPending || bannerBatch.any { id -> messages.none { it.id==id && !it.read && !it.trash } }) cancelBanner()
            main.removeCallbacks(retryQuota)
            val deadline=when {
                account==null || !account.active -> 0L
                account.revision==0L -> account.registerAfter
                messages.any { it.delivery=="queued" && !it.trash } -> account.sendAfter
                else -> 0L
            }
            val delay=deadline-System.currentTimeMillis()
            if(delay>0 && eligible()) main.postDelayed(retryQuota,delay+100L)
            if(eventsAdmitted && !running.get()) openEvents()
        }
    }
    private fun refreshed() {
        synchronized(this) {
            refreshRequested=true
            if(refreshQueued) return
            refreshQueued=true
        }
        local.execute {
            try {
                while(true) {
                    synchronized(this) { refreshRequested=false }
                    publish()
                    main.post { requestBanner() }
                    synchronized(this) { if (!refreshRequested) { refreshQueued=false; return@execute } }
                }
            } catch(e: Exception) { synchronized(this) { refreshQueued=false }; failed("refresh",e) }
        }
    }

    private fun bannerReady(): Boolean {
        return eligible() && active && banner && !controlPending && !changingConsent && vehiclePower.raw in 1..4
    }
    private fun observePower() {
        val needed=eligible() && active && banner && (bannerPending || bannerAttempt!=null)
        if(needed && !observingPower) {
            observingPower=true
            VehiclePowerMonitor.observe(ctx,"mail") { reading ->
                vehiclePower=reading
                log("stage=banner/readiness power=${reading.raw} error=${reading.error}")
                requestBanner()
            }
        } else if(!needed && observingPower) {
            observingPower=false
            VehiclePowerMonitor.remove("mail")
            vehiclePower=VehiclePowerMonitor.Reading()
        }
    }
    private fun cancelBanner() {
        bannerGeneration++
        bannerAttempt?.let { bannerRetry.cancel(it.resultId) }
        bannerAttempt=null; bannerBatch=emptyList()
        MailBanner.dismiss(animated=false)
        observePower()
    }
    private fun requestBanner() {
        observePower()
        if(!bannerPending && bannerAttempt==null) return
        if(!bannerReady()) {
            val reason="eligible=${eligible()} active=$active enabled=$banner control=$controlPending changing=$changingConsent power=${vehiclePower.raw}"
            if(reason!=bannerWaitReason) { bannerWaitReason=reason; log("stage=banner/deferred $reason") }
            cancelBanner(); return
        }
        bannerWaitReason=""
        if(bannerAttempt!=null) return
        val generation=bannerGeneration
        local.execute {
            try {
                val batch=store.pendingBanner()
                main.post {
                    if(generation!=bannerGeneration || batch.isEmpty() || !bannerReady() || bannerAttempt!=null) return@post
                    bannerBatch=batch.map { it.id }
                    val attempt=bannerRetry.begin(++bannerSequence)
                    bannerAttempt=attempt
                    showBanner(batch,attempt)
                }
            } catch(e: Exception) { failed("banner/pending",e) }
        }
    }
    private fun showBanner(batch: List<MailLetter>, attempt: UpdateHintPresentationAttempt) {
        if(!bannerRetry.isCurrent(attempt) || !bannerReady()) { cancelBanner(); return }
        fun finished(shown: Boolean) {
            if(!bannerRetry.isCurrent(attempt)) return
            if(shown) {
                bannerRetry.cancel(attempt.resultId)
                bannerAttempt=null; bannerBatch=emptyList()
                requestBanner()
            } else {
                log("stage=banner/failed messages=${batch.joinToString(",") { it.id }} attempt=${attempt.number} pending=true")
                val retry=bannerRetry.technicalFailure(attempt)
                bannerAttempt=retry?.attempt
                if(retry!=null && bannerReady()) main.postDelayed({
                    if(bannerRetry.isCurrent(retry.attempt)) showBanner(batch,retry.attempt)
                },retry.delayMs)
                // After bounded retries, keep durable pending state until the next readiness event.
            }
        }
        try { MailBanner.show(ctx,dialogAnchor.get() ?: anchor.get(),language(),HudPrefs.isDarkTheme(ctx),batch,
            allowed={ bannerRetry.isCurrent(attempt) && bannerReady() },
            shown={
                if(bannerRetry.attached(attempt)) {
                    val ids=batch.map { it.id }
                    log("stage=banner/shown messages=${ids.joinToString(",")} attempt=${attempt.number}")
                    local.execute {
                        try { store.bannerShown(ids); publish() }
                        catch(e: Exception) { failed("banner/save-shown",e) }
                    }
                }
            }, finished=::finished)
        } catch(e: RuntimeException) { failed("banner/create",e); finished(false) }
    }
    private fun stopEvents(reason: String) {
        main.removeCallbacks(retryEvents)
        stream?.let { log("stage=events/cancel stream=${it.streamId} reason=$reason"); it.cancel() }
        stream=null
    }
    private fun openEvents() {
        if(!eligible() || !online() || !active || controlPending || changingConsent || !eventsAdmitted || eventsBlocked || automaticBlocked || stream!=null || running.get() || reconcileRequested.get()) return
        val wait=eventsRetryAt-MailEvents.monotonic()
        if(wait>0) { main.removeCallbacks(retryEvents); main.postDelayed(retryEvents,wait); return }
        val account=state ?: return
        local.execute {
            try {
                if(!store.valid(account)) return@execute
                val next=api.events(account)
                main.post {
                    if(!eligible() || !online() || !active || controlPending || changingConsent || !eventsAdmitted || eventsBlocked || automaticBlocked || stream!=null || running.get() || reconcileRequested.get() ||
                        state?.epoch!=account.epoch || state?.revision!=account.revision) { next.cancel(); return@post }
                    stream=next; streamEpoch=account.epoch; streamRevision=account.revision
                    eventsWorker.execute {
                        var error: Exception?=null
                        try { next.read { signal -> main.post {
                            if(stream===next && active && !withdrawing) {
                                log("stage=sync/signal stream=${signal.streamId} message=${signal.messageId}")
                                sync(automatic=true)
                            }
                        } } } catch(e: Exception) { error=e }
                        val failure=error
                        main.post {
                            if(stream===next) {
                                stream=null
                                if(failure!=null) eventsFailed(next,failure)
                            }
                        }
                    }
                }
            } catch(e: Exception) { main.post { eventsFailed(null,e) } }
        }
    }
    private fun eventsFailed(previous: MailEvents?, error: Exception) {
        failed("events",error)
        val f=error as? MailFailure
        when {
            f?.reason=="invalid_credentials" || f?.status==401 -> { automaticBlocked=true; eventsBlocked=true }
            f?.status in setOf(400,404,405) -> eventsBlocked=true
            f?.reason in setOf("revision_conflict","consent_inactive") -> {
                eventsAdmitted=false; reconcileRequested.set(true); sync(automatic=true)
            }
            else -> {
                val stable=previous?.readyAt?.takeIf { it>0 }?.let { MailEvents.monotonic()-it } ?: 0
                val wait=maxOf(f?.waitSeconds ?: 0,if(f?.reason in setOf("mailbox_blocked","exchange_paused")) 60 else 0)
                val delay=eventsBackoff.next(wait,stable)
                eventsRetryAt=MailEvents.monotonic()+delay
                log("stage=events/retry delayMs=$delay reason=${f?.reason ?: error.javaClass.simpleName}")
                if(f?.reason !in setOf("network","eof","handshake_timeout","idle_timeout","frame_timeout","temporarily_unavailable","internal_error","server_restart","stream_limit")) {
                    eventsAdmitted=false; reconcileRequested.set(true)
                    syncRetryAt=eventsRetryAt
                    main.removeCallbacks(retrySync)
                    if(eligible() && online()) main.postDelayed(retrySync,delay)
                } else if(eligible() && online()) main.postDelayed(retryEvents,delay)
            }
        }
    }
    fun receive() { userFetch=true; automaticBlocked=false; eventsBlocked=false; sync() }
    private fun sync(automatic: Boolean=false) {
        if(!ready || (state==null && !signupPending)) return
        if(!online()) return
        if(automatic && automaticBlocked) return
        val delay=syncRetryAt-MailEvents.monotonic()
        if(delay>0) { main.removeCallbacks(retrySync); main.postDelayed(retrySync,delay); return }
        if(!running.compareAndSet(false,true)) { again.set(true); return }
        main.removeCallbacks(retrySync)
        busy=true
        val trace="sync=${UUID.randomUUID()} stream=${stream?.streamId.orEmpty()}"
        network.execute {
            syncTrace.set(trace)
            log("stage=sync/start automatic=$automatic")
            var count=0
            var error: Exception?=null
            var warning: MailFailure?=null
            val reconcile=reconcileRequested.getAndSet(false)
            try {
                count=exchange.sync(::refreshed,{ warning=it; failed("exchange",it) },{ !withdrawing },reconcile)
            } catch(e: Exception) { error=e; failed("exchange",e); if(reconcile) reconcileRequested.set(true) }
            finally {
                log("stage=sync/end received=$count failed=${error!=null} warning=${warning?.reason.orEmpty()}")
                syncTrace.remove()
                refreshed()
                main.post {
                    running.set(false); busy=false
                    if(error==null && warning==null) status=if(userFetch && count==0) language().choose("Нових листів немає","No new messages","Новых писем нет") else ""
                    userFetch=false
                    if(error==null) { eventsAdmitted=true; syncBackoff.reset(); syncRetryAt=0; openEvents() }
                    val failure=error
                    if(failure!=null && !automaticBlocked && eligible()) {
                        val f=failure as? MailFailure
                        val wait=maxOf(f?.waitSeconds ?: 0,if(f?.reason in setOf("mailbox_blocked","exchange_paused","receiving_paused")) 60 else 0)
                        val retry=syncBackoff.next(wait)
                        syncRetryAt=MailEvents.monotonic()+retry
                        log("stage=sync/retry delayMs=$retry reason=${f?.reason ?: failure.javaClass.simpleName}")
                        if(online()) main.postDelayed(retrySync,retry)
                    }
                    if(again.getAndSet(false) && eligible()) sync(automatic=true)
                }
            }
        }
    }
    private fun edit(stage: String, syncAfter: Boolean=true, work: ()->Unit) {
        if(stage in setOf("registration","subscription")) {
            changingConsent=true
            eventsBlocked=false
            stopEvents(stage); eventsAdmitted=false; cancelBanner()
        }
        if(stage in setOf("read-local","folder","purge","banner")) cancelBanner()
        local.execute {
            try {
                work(); publish()
                main.post { changingConsent=false; if(syncAfter) { automaticBlocked=false; sync() }; requestBanner() }
            } catch(e: Exception) { main.post { changingConsent=false }; failed(stage,e) }
        }
    }
    fun confirmProfile(name: String, description: String) {
        if(!ready || signupPending || !MailStore.validText(name,MAX_NICKNAME) ||
            '\u0000' in description || description.codePointCount(0,description.length)>MAX_DESCRIPTION) return
        signupPending=true
        edit("registration") {
            try {
                if(store.account()==null) {
                    val vehicle=MailVehicle.read(ctx)
                    store.register(UUID.randomUUID().toString(),MailVault.seal(MailVault.newSecret()),MailVault.seal(vehicle),name.trim(),description.trim())
                } else store.consent(true)
                main.post { status="" }
            } finally { main.post { signupPending=false } }
        }
    }
    fun revoke(saved: ()->Unit) {
        if(withdrawing) return
        withdrawing=true
        stopEvents("withdraw"); eventsAdmitted=false; cancelBanner()
        syncRetryAt=0
        local.execute {
            try {
                store.consent(false)
                publish()
                main.post {
                    withdrawing=false; automaticBlocked=false
                    status=""; saved(); sync()
                }
            } catch(e: Exception) { main.post { withdrawing=false }; failed("withdraw",e) }
        }
    }
    fun setNewsEnabled(value: Boolean) = edit("subscription") { if(store.account()?.active==true) store.consent(true,value) }
    fun setBannerEnabled(value: Boolean) = edit("banner",false) { store.banner(value) }
    fun setRead(ids: Set<String>,value: Boolean) = edit("read-local",false) { store.setRead(ids,value) }
    fun trash(ids: Set<String>,value: Boolean) = edit("folder") { store.folder(ids,if(value) "trash" else "restore") }
    fun deleteFromTrash(ids: Set<String>) = edit("purge") { store.folder(ids,"purge") }
    fun send(subject: String,body: String, saved: ()->Unit) = edit("queue-send") {
        if(store.enqueue(subject,body,System.currentTimeMillis())) { log("stage=send/queued"); main.post(saved) }
    }

    private fun log(event: String) = AppEventLogger.event(ctx,"developer-mail $event ${syncTrace.get().orEmpty()} utc=${java.time.Instant.now()} elapsedMs=${MailEvents.monotonic()}")
    private fun failed(stage: String, e: Exception) {
        val f=e as? MailFailure
        val stack=mailFailureDetails(e)
        log("stage=${f?.stage ?: stage} reason=${f?.reason ?: "local_error"} http=${f?.status ?: 0} request=${f?.request.orEmpty()} waitSeconds=${f?.waitSeconds ?: 0} ${f?.detail.orEmpty()} causes=$stack")
        val l=language()
        val message=when(f?.reason) {
            "dilink_empty" -> l.choose("Авто повернуло порожній DiLink ID","The vehicle returned an empty DiLink ID","Автомобиль вернул пустой DiLink ID")
            "dilink_denied" -> l.choose("Немає доступу до DiLink ID","Access to DiLink ID was denied","Нет доступа к DiLink ID")
            "dilink_unavailable" -> l.choose("Не вдалося прочитати DiLink ID","DiLink ID could not be read","Не удалось прочитать DiLink ID")
            "network" -> l.choose("Помилка з’єднання з поштою","Could not connect to mail","Ошибка соединения с почтой")
            "consent_inactive" -> l.choose("Згода неактивна","Mail consent is inactive","Согласие неактивно")
            "invalid_credentials" -> l.choose("Сервер не прийняв ключ скриньки","Mailbox credentials were rejected","Сервер отклонил ключ ящика")
            "mailbox_blocked" -> l.choose("Скриньку заблоковано","Mailbox blocked","Ящик заблокирован")
            "receiving_paused" -> l.choose("Сервер призупинив надсилання","Server paused outgoing mail","Сервер приостановил отправку")
            "exchange_paused" -> l.choose("Обмін поштою призупинено","Mail exchange is paused","Обмен почтой приостановлен")
            "send_quota","registration_quota" -> l.choose("До повторної спроби","Retry in","До повторной попытки")+" ${f.waitSeconds} s"
            else -> l.choose("Помилка пошти","Mail error","Ошибка почты")+" · "+(f?.reason ?: e.javaClass.simpleName) +
                (f?.status?.takeIf { it>0 }?.let { " (HTTP $it)" }.orEmpty())
        }
        main.post {
            if ((!stage.startsWith("events") && f?.status in setOf(400,401,405,413,415)) || f?.reason in setOf("invalid_credentials","mailbox_id_taken","vehicle_mismatch","request_id_conflict"))
                automaticBlocked=true
            status=message
        }
    }
}
