package com.bydhud.app

import org.json.JSONObject
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class MailEvent(val type: String, val streamId: String, val messageId: String = "")

/** Consumes actual UTF-8 SSE lines; comments keep the transport alive without fetching mail. */
internal class MailEventParser(private val revision: Long, private val event: (MailEvent) -> Unit) {
    private var type = ""
    private val data = StringBuilder()
    private var size = 0
    var streamId = ""
        private set

    fun line(line: String) {
        size += line.length
        require(size <= 65536) { "SSE frame too large" }
        if (line.isEmpty()) {
            if (data.isNotEmpty()) dispatch()
            type = ""; data.setLength(0); size = 0
        } else if (!line.startsWith(':')) {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> type = value
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            }
        }
    }

    private fun dispatch() {
        if (type !in setOf("ready", "mail_available", "stream_closed")) return
        val json = JSONObject(data.toString())
        val id = json.getString("streamId")
        require(UUID.fromString(id).toString() == id) { "Invalid SSE stream identity" }
        if (type == "ready") {
            require(streamId.isEmpty() && json.getLong("revision") == revision &&
                json.getInt("heartbeatSeconds") == 20) { "Invalid SSE ready" }
            streamId = id
        } else require(streamId == id && streamId.isNotEmpty()) { "SSE stream mismatch" }
        if (type == "stream_closed") {
            val reason = json.getString("reason")
            require(reason.matches(Regex("[a-z_]{1,64}"))) { "Invalid SSE close reason" }
            throw MailFailure("events/closed", reason, waitSeconds = json.optLong("retryAfterSeconds", 0).coerceAtLeast(0))
        }
        val message = if (type == "mail_available") json.getString("messageId") else ""
        if (type == "mail_available") require(json.getLong("revision") == revision &&
            message.matches(Regex("[1-9][0-9]{0,17}"))) { "Invalid SSE mail signal" }
        event(MailEvent(type, id, message))
    }
}

/** Backoff only resets after a stable channel; server Retry-After is a lower bound. */
internal class MailBackoff {
    private var attempt = 0
    fun reset() { attempt = 0 }
    fun next(waitSeconds: Long = 0, stableMillis: Long = 0, jitter: Double = Math.random()): Long {
        if (stableMillis >= 60_000) reset()
        val base = minOf(30_000L, 2_000L shl attempt)
        attempt = minOf(attempt+1,4)
        val server = waitSeconds.coerceIn(0, Long.MAX_VALUE / 2000) * 1000
        return maxOf(server, (base * (1 + jitter.coerceIn(0.0, 1.0) * 0.2)).toLong())
    }
}

internal class MailEvents(private val url: URL, private val agent: String, private val secret: String,
    private val revision: Long, private val log: (String) -> Unit) {
    private val cancelled = AtomicBoolean()
    @Volatile private var call: Call? = null
    @Volatile private var deadline = 0L
    @Volatile private var lastByte = 0L
    @Volatile private var lastFrame = 0L
    @Volatile private var timeout: String? = null
    @Volatile var readyAt = 0L
        private set
    @Volatile var streamId = ""
        private set

    fun cancel() {
        cancelled.set(true)
        call?.cancel()
    }

    fun read(event: (MailEvent) -> Unit) {
        if (cancelled.get()) return
        val request = UUID.randomUUID().toString()
        val c = client.newBuilder().eventListener(object: EventListener() {
            override fun requestHeadersEnd(call: Call, request: Request) { deadline=monotonic()+10_000 }
        }).build().newCall(Request.Builder().url(url)
            .header("User-Agent",agent).header("Authorization","Bearer $secret")
            .header("X-Consent-Revision",revision.toString()).header("X-Request-Id",request)
            .header("Accept","text/event-stream").header("Accept-Encoding","identity").build())
        call = c
        var phase = "connect"
        var code = 0
        val began = monotonic()
        var frames = 0
        val parser = MailEventParser(revision) { signal ->
            if (!cancelled.get()) {
                if (signal.type == "ready") { readyAt = monotonic(); deadline = 0 }
                streamId = signal.streamId
                log("stage=events/${signal.type} request=$request stream=$streamId message=${signal.messageId} revision=$revision")
                event(signal)
            }
        }
        deadline = began + 10_000
        val watch = watchdog.scheduleAtFixedRate({
            val now = monotonic()
            val reason = when {
                cancelled.get() -> "cancelled"
                deadline > 0 && now >= deadline -> "handshake_timeout"
                readyAt > 0 && now - lastByte >= 60_000 -> "idle_timeout"
                readyAt > 0 && now - lastFrame >= 60_000 -> "frame_timeout"
                else -> null
            }
            if (reason != null) { timeout = reason; c.cancel() }
        }, 1, 1, TimeUnit.SECONDS)
        try {
            if (cancelled.get()) return
            log("stage=events/connect request=$request revision=$revision")
            phase = "headers"
            c.execute().use { response ->
            code = response.code
            if (code != 200) throw mailHttpFailure(response.body?.byteStream(),{response.header(it)}, "events/headers", request, code)
            require(response.header("Content-Type")?.substringBefore(';')?.trim() == "text/event-stream") { "Invalid SSE content type" }
            phase = "read"; deadline = monotonic() + 10_000
            lastByte = monotonic(); lastFrame = lastByte
            val input = object : FilterInputStream(checkNotNull(response.body).byteStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) lastByte = monotonic() }
                override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also {
                    if (it > 0) lastByte = monotonic()
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(input, decoder).buffered().use { reader ->
                val line = StringBuilder()
                var previousCr = false
                var first = true
                while (!cancelled.get()) {
                    val n = reader.read()
                    if (n < 0) throw EOFException("SSE connection ended")
                    val ch = n.toChar()
                    if (first) { first = false; if (ch == '\uFEFF') continue }
                    if (ch == '\n' && previousCr) { previousCr = false; continue }
                    previousCr = ch == '\r'
                    if (ch == '\r' || ch == '\n') {
                        parser.line(line.toString())
                        if (line.isEmpty()) { lastFrame = monotonic(); frames++ }
                        line.setLength(0)
                    } else {
                        require(line.length < 65536) { "SSE line too large" }
                        line.append(ch)
                    }
                }
            }
            }
        } catch (e: Exception) {
            if (!cancelled.get()) {
                if (e is MailFailure) throw MailFailure(e.stage, e.reason, e.status, request, e.waitSeconds, e, e.detail)
                throw MailFailure("events/$phase", timeout ?: when (e) {
                    is EOFException -> "eof"
                    is java.io.IOException -> "network"
                    else -> "invalid_response"
                }, code, request, cause = e)
            }
        } finally {
            watch.cancel(false)
            c.cancel(); call = null
            log("stage=events/end request=$request stream=$streamId frames=$frames durationMs=${monotonic()-began} cancelled=${cancelled.get()}")
        }
    }

    companion object {
        private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS)
            .writeTimeout(10,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mail-stream-watchdog").apply { isDaemon = true } }
        fun monotonic() = System.nanoTime() / 1_000_000
    }
}
