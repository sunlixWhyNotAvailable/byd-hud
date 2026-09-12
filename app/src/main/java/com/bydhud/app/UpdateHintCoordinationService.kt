package com.bydhud.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.lang.ref.WeakReference

class UpdateHintCoordinationService : Service() {
    private data class Subscription(
        val owner: String,
        val sessionId: String,
        val reply: Messenger,
        val death: IBinder.DeathRecipient
    )

    private val subscriptions = mutableMapOf<IBinder, Subscription>()
    private lateinit var endpoint: Messenger

    override fun onCreate() {
        super.onCreate()
        endpoint = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
        current = WeakReference(this)
    }

    override fun onBind(intent: Intent?): IBinder = endpoint.binder

    override fun onDestroy() {
        subscriptions.values.forEach { subscription ->
            subscription.reply.binder.unlinkToDeath(subscription.death, 0)
        }
        subscriptions.clear()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        if (message.what !in UpdateHintProtocol.SUBSCRIBE..UpdateHintProtocol.UNSUBSCRIBE) return false
        val record = UpdateHintWire.decodeAuthenticated(this, message) ?: run {
            Log.w(TAG, "Rejected message from uid=${message.sendingUid}")
            return true
        }
        when (message.what) {
            UpdateHintProtocol.SUBSCRIBE -> subscribe(record, message.replyTo)
            UpdateHintProtocol.STATE -> {
                if (subscriptions.values.any {
                        it.owner == record.ownerPackage && it.sessionId == record.processSessionId
                    }) {
                    UpdateHintCoordinator.acceptFromService(record.ownerPackage, record)
                }
            }
            UpdateHintProtocol.UNSUBSCRIBE -> unsubscribe(record, message.replyTo)
        }
        return true
    }

    private fun subscribe(record: UpdateHintRecord, reply: Messenger?) {
        if (reply == null) return
        val binder = reply.binder
        val death = IBinder.DeathRecipient {
            Handler(Looper.getMainLooper()).post {
                val removed = subscriptions.remove(binder) ?: return@post
                UpdateHintCoordinator.confirmedDeath(removed.owner, removed.sessionId)
            }
        }
        try {
            binder.linkToDeath(death, 0)
            if (!UpdateHintCoordinator.acceptFromService(record.ownerPackage, record)) {
                binder.unlinkToDeath(death, 0)
                return
            }
            subscriptions.entries.filter { it.value.owner == record.ownerPackage }
                .forEach { (oldBinder, old) ->
                    subscriptions.remove(oldBinder)
                    oldBinder.unlinkToDeath(old.death, 0)
                }
            subscriptions[binder] = Subscription(record.ownerPackage, record.processSessionId, reply, death)
            send(reply, UpdateHintCoordinator.ownSnapshot())
        } catch (_: RemoteException) {
            UpdateHintCoordinator.confirmedDeath(record.ownerPackage, record.processSessionId)
        }
    }

    private fun unsubscribe(record: UpdateHintRecord, reply: Messenger?) {
        val binder = reply?.binder
        if (binder != null) {
            val subscription = subscriptions[binder]
            if (subscription?.owner == record.ownerPackage &&
                subscription.sessionId == record.processSessionId) {
                subscriptions.remove(binder)
                binder.unlinkToDeath(subscription.death, 0)
            }
        } else {
            subscriptions.entries.firstOrNull {
                    it.value.owner == record.ownerPackage &&
                        it.value.sessionId == record.processSessionId
                }?.let { (key, value) ->
                subscriptions.remove(key)
                key.unlinkToDeath(value.death, 0)
            }
        }
    }

    private fun broadcast(record: UpdateHintRecord) {
        subscriptions.entries.toList().forEach { (binder, subscription) ->
            if (!send(subscription.reply, record)) {
                subscriptions.remove(binder)
                binder.unlinkToDeath(subscription.death, 0)
                UpdateHintCoordinator.confirmedDeath(subscription.owner, subscription.sessionId)
            }
        }
    }

    private fun send(reply: Messenger, record: UpdateHintRecord): Boolean = try {
        reply.send(Message.obtain(null, UpdateHintProtocol.STATE).apply {
            data = UpdateHintWire.bundle(record)
        })
        true
    } catch (_: RemoteException) { false }

    companion object {
        private const val TAG = "BYDHUD_UPDATE_HINT"
        private var current: WeakReference<UpdateHintCoordinationService>? = null

        internal fun broadcastOwn(record: UpdateHintRecord) {
            val service = current?.get() ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) service.broadcast(record)
            else Handler(Looper.getMainLooper()).post { service.broadcast(record) }
        }
    }
}
