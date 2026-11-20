package com.bydhud.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

internal class MailFailure(val stage: String, val reason: String, val status: Int = 0,
    val request: String = "", val waitSeconds: Long = 0, cause: Throwable? = null, val detail: String = "") :
    IOException("$stage: $reason (HTTP $status; request $request)", cause)

internal fun mailHttpFailure(input: java.io.InputStream?, header: (String)->String?, stage: String, request: String, code: Int): MailFailure {
    val raw = input?.bufferedReader(Charsets.UTF_8)?.use { reader ->
        val chars = CharArray(8192)
        var used = 0
        while (used < chars.size) {
            val n = reader.read(chars, used, chars.size-used)
            if (n <= 0) break
            used += n
        }
        String(chars, 0, used)
    }.orEmpty()
    val json = runCatching { JSONObject(raw) }.getOrNull()
    val reason = json?.optString("error")?.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "http_error"
    val retry = header("Retry-After")
    val delay = retry?.toLongOrNull() ?: runCatching {
        (java.time.ZonedDateTime.parse(retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()-System.currentTimeMillis()+999)/1000
    }.getOrDefault(0)
    fun headerValue(name: String) = header(name)?.take(128)?.replace(Regex("[^A-Za-z0-9 /;=._:-]"), "_").orEmpty()
    return MailFailure(stage, reason, code, request, maxOf(0, delay, json?.optLong("retryAfterSeconds", 0) ?: 0),
        detail = "json=${json!=null} contentType=${headerValue("Content-Type")} cfRay=${headerValue("CF-Ray")}")
}

internal class MailApi(private val base: URL, private val userAgent: String, private val decrypt: (String)->String,
    private val log: (String)->Unit = {}) {
    init {
        require(base.protocol == "https" || (base.protocol == "http" && base.host in setOf("127.0.0.1","localhost")))
    }
    private val messageIdPattern=Regex("[1-9][0-9]{0,17}")
    private fun call(a: MailAccount, stage: String, method: String, suffix: String = "", revision: Boolean = false,
        data: JSONObject? = null): JSONObject {
        val request = UUID.randomUUID().toString()
        val connection = URL("$base/mailboxes/${UUID.fromString(a.id)}$suffix").openConnection() as HttpURLConnection
        var phase="connect"
        var httpStatus=0
        val began=MailEvents.monotonic()
        try {
            log("stage=$stage/request request=$request revision=${a.revision}"+
                if(stage=="send") " outgoing=${data?.optString("requestId").orEmpty()}" else "")
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent",userAgent)
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("X-Request-Id",request)
            connection.setRequestProperty("Authorization","Bearer ${decrypt(a.credential)}")
            if (revision) connection.setRequestProperty("X-Consent-Revision",a.revision.toString())
            if (data != null) {
                connection.doOutput = true
                val bytes=data.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type","application/json")
                phase="write"
                connection.outputStream.use { it.write(bytes) }
            }
            phase="response"
            val code = connection.responseCode
            httpStatus=code
            if (code !in 200..299) {
                throw mailHttpFailure(connection.errorStream,connection::getHeaderField,stage,request,code)
            }
            phase="decode"
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                // A full v1 page fits even when every Unicode character is JSON-escaped.
                val text=StringBuilder()
                val buffer=CharArray(8192)
                while (true) {
                    val count=reader.read(buffer)
                    if (count<0) break
                    if (text.length+count>8*1024*1024) throw MailFailure(stage,"response_too_large",code,request)
                    text.append(buffer,0,count)
                }
                JSONObject(text.toString()).also { json ->
                    when (stage) {
                        "register","state","consent" -> require(json.getString("mailboxId")==a.id &&
                            json.getLong("revision")>0 && json.has("active") && json.has("announcements"))
                        "ack","folder" -> require(json.getBoolean("ok"))
                        "send" -> require(json.getString("messageId").matches(messageIdPattern))
                    }
                    log("stage=$stage/response request=$request http=$code durationMs=${MailEvents.monotonic()-began}")
                }
            }
        } catch(e: MailFailure) { throw e }
        catch(e: Exception) { throw MailFailure("$stage/$phase", if (e is IOException) "network" else "invalid_response", httpStatus,request,cause=e) }
        finally { connection.disconnect() }
    }

    fun register(a: MailAccount) = call(a,"register","PUT",data=JSONObject()
        .put("nickname",a.name).put("description",a.description).put("consent",true).put("dilinkId",decrypt(a.binding)))
    fun state(a: MailAccount) = call(a,"state","GET")
    fun consent(a: MailAccount, c: MailControl) = call(a,"consent","PUT","/consent",data=JSONObject()
        .put("previousRevision",a.revision).put("active",c.active).put("announcements",c.news))
    fun fetch(a: MailAccount) = call(a,"receive","GET","/messages?cursor="+URLEncoder.encode(a.cursor,"UTF-8"),true)
    fun ack(a: MailAccount, ids: List<String>) = call(a,"ack","POST","/ack",true,JSONObject().put("messageIds",JSONArray(ids)))
    fun folder(a: MailAccount, ops: List<MailFolderOp>) = call(a,"folder","POST","/folder",true,
        JSONObject().put("messageIds",JSONArray(ops.map { it.serverId }.distinct())).put("action",ops.first().action))
    fun send(a: MailAccount, letter: MailLetter) = call(a,"send","POST","/messages",true,
        JSONObject().put("requestId",letter.id).put("subject",letter.subject).put("body",letter.body))
    fun events(a: MailAccount) = MailEvents(URL("$base/mailboxes/${UUID.fromString(a.id)}/events"),
        userAgent,decrypt(a.credential),a.revision,log)
}

internal class MailExchange(private val store: MailStore,private val api: MailApi, private val log: (String)->Unit = {}) {
    fun sync(changed: ()->Unit, warning: (MailFailure)->Unit, mailAllowed: ()->Boolean,
        reconcile: Boolean = false): Int {
        var received=0
        var conflicts=0
        var checkState=reconcile
        while (true) {
            val a=store.account() ?: return received
            val control=store.firstControl()
            try {
                if (!mailAllowed() && control?.active != false) return received
                if (a.revision==0L) {
                    // A register reply may have been lost before an offline withdrawal.
                    if (control!=null && !control.active) {
                        try { store.remoteState(api.state(a).getLong("revision")) }
                        catch(e: MailFailure) {
                            if (e.reason!="invalid_credentials") throw e
                            when {
                                !a.registerAttempted -> store.completeControl(control,0)
                                a.active && a.registerAfter<=System.currentTimeMillis() -> {
                                    // Explicit renewal also resolves a registration whose response was lost.
                                    store.remoteState(api.register(a).getLong("revision"))
                                }
                                else -> return received // Keep withdrawal until a later state check confirms it.
                            }
                        }
                        continue
                    }
                    if ((!a.active && control==null) || a.registerAfter>System.currentTimeMillis()) return received
                    store.registering()
                    val registered=api.register(a)
                    store.remoteState(registered.getLong("revision"))
                    if (!registered.getBoolean("active") && store.firstControl()==null)
                        store.reconcile(registered.getLong("revision"),false,false)
                    changed()
                    continue
                }
                if (control!=null) {
                    val result=api.consent(a,control)
                    store.completeControl(control,result.getLong("revision"))
                    changed()
                    continue
                }
                if (checkState && a.revision>0) {
                    checkState=false
                    val s=api.state(a)
                    store.reconcile(s.getLong("revision"),s.getBoolean("active"),s.getBoolean("announcements"))
                    changed()
                    continue
                }
                if (!mailAllowed() || !store.valid(a)) return received
                fun ackPending() {
                    while (mailAllowed() && store.valid(a)) {
                        val ids=store.ackIds()
                        if(ids.isEmpty()) break
                        try {
                            api.ack(a,ids); store.acknowledged(ids)
                            log("stage=ack/stored messages=${ids.joinToString(",")}")
                        }
                        catch(e: MailFailure) {
                            if(e.reason!="message_not_available") throw e
                            // A subscription change can invalidate only part of an offered page.
                            for (id in ids) {
                                if (!mailAllowed() || !store.valid(a)) break
                                try { api.ack(a,listOf(id)) }
                                catch(single: MailFailure) { if(single.reason!="message_not_available") throw single; warning(single) }
                                store.acknowledged(listOf(id))
                                log("stage=ack/resolved message=$id")
                            }
                        }
                    }
                }
                ackPending()
                if (!mailAllowed()) return received
                if (!store.valid(a)) continue
                val page=api.fetch(a)
                check(page.getLong("revision")==a.revision)
                val messages=page.getJSONArray("messages")
                require(messages.length()<=100) { "Invalid mail page size" }
                val items=(0 until messages.length()).map { i ->
                    val m=messages.getJSONObject(i)
                    val id=m.getString("messageId")
                    val subject=m.getString("subject")
                    val body=m.getString("body")
                    val kind=m.getString("kind")
                    require(id.matches(Regex("[1-9][0-9]{0,17}")) && MailStore.validText(subject,200) &&
                        MailStore.validText(body,4000) && kind in setOf("direct","announcement")) { "Invalid mail item" }
                    MailIncoming(id,Instant.parse(m.getString("createdAt")).toEpochMilli(),subject,body,kind=="announcement")
                }
                val next=page.getString("nextCursor")
                val more=page.getBoolean("hasMore")
                check(!more || (next!=a.cursor && items.isNotEmpty()))
                if (!mailAllowed()) return received
                val accepted=store.acceptPage(a,items,next)
                received+=accepted
                log("stage=receive/stored messages=${items.joinToString(",") { it.id }} added=$accepted more=$more revision=${a.revision}")
                if(items.isNotEmpty()) changed()
                ackPending()
                if(more || !store.valid(a)) continue
                while(mailAllowed() && store.valid(a) && (store.account()?.sendAfter ?: 0)<=System.currentTimeMillis()) {
                    val out=store.outgoing() ?: break
                    try {
                        val receipt=api.send(a,out)
                        store.receipt(a,out.id,receipt.getString("messageId"),Instant.parse(receipt.getString("createdAt")).toEpochMilli())
                        log("stage=send/stored outgoing=${out.id} message=${receipt.getString("messageId")}")
                        changed()
                    } catch(e: MailFailure) {
                        when {
                            e.status==429 -> store.cooldown(false,System.currentTimeMillis()+maxOf(1,e.waitSeconds)*1000)
                            e.reason=="receiving_paused" -> store.cooldown(false,System.currentTimeMillis()+maxOf(60,e.waitSeconds)*1000)
                            e.reason=="request_id_conflict" || e.status==400 || e.status==413 -> store.reject(out.id)
                            else -> throw e
                        }
                        changed()
                        warning(e)
                        break
                    }
                }
                while(mailAllowed() && store.valid(a)) {
                    val batch=store.folderBatch()
                    if (batch.isEmpty()) break
                    try {
                        api.folder(a,batch)
                        store.finishFolder(batch)
                    } catch(e: MailFailure) {
                        if(e.reason!="message_not_available") throw e
                        for(op in batch) {
                            if (!mailAllowed() || !store.valid(a)) break
                            try { api.folder(a,listOf(op)) }
                            catch(single: MailFailure) { if(single.reason!="message_not_available") throw single; warning(single) }
                            store.finishFolder(listOf(op))
                        }
                    }
                }
                return received
            } catch(e: MailFailure) {
                if(e.reason=="registration_quota" && e.status==429) {
                    store.cooldown(true,System.currentTimeMillis()+maxOf(1,e.waitSeconds)*1000)
                    changed()
                }
                if(e.reason=="revision_conflict" && conflicts++<2) {
                    val s=api.state(a)
                    store.reconcile(s.getLong("revision"),s.getBoolean("active"),s.getBoolean("announcements"))
                    changed()
                    continue
                }
                if(e.reason=="consent_inactive" && store.firstControl()==null) {
                    val s=api.state(a)
                    store.reconcile(s.getLong("revision"),s.getBoolean("active"),s.getBoolean("announcements"))
                    changed()
                }
                throw e
            }
        }
    }
}
