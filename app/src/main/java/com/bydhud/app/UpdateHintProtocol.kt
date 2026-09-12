package com.bydhud.app

internal object UpdateHintProtocol {
    const val ACTION = "com.byd.apps.updatehint.COORDINATION"
    const val METADATA_PROTOCOL_VERSION = "com.byd.apps.updatehint.PROTOCOL_VERSION"
    const val VERSION = 1
    const val INITIAL_EXCHANGE_MS = 500L
    private const val INITIAL_EXCHANGE_NANOS = INITIAL_EXCHANGE_MS * 1_000_000L

    const val SUBSCRIBE = 1
    const val STATE = 2
    const val UNSUBSCRIBE = 3

    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_OWNER_PACKAGE = "ownerPackage"
    const val KEY_PROCESS_SESSION_ID = "processSessionId"
    const val KEY_REVISION = "revision"
    const val KEY_EVENT_ID = "eventId"
    const val KEY_PHASE = "phase"
    const val KEY_REQUESTED_AT_NANOS = "requestedAtElapsedNanos"
    const val KEY_EXPIRES_AT_MS = "expiresAtElapsedMs"
    const val KEY_DISPLAY_ID = "displayId"
    const val KEY_PREFERRED_SIZE_PERCENT = "preferredSizePercent"
    const val KEY_PREFERRED_WIDTH_PX = "preferredWidthPx"
    const val KEY_PREFERRED_HEIGHT_PX = "preferredHeightPx"

    const val OWNER_HUD = "com.bydhud.app"
    const val OWNER_EXTEND = "com.byd.extend"
    const val OWNER_COLLECTOR = "com.bydcollector.collector"
    val OWNERS = listOf(OWNER_HUD, OWNER_EXTEND, OWNER_COLLECTOR)

    fun knownOwners(installedForUid: Array<String>?): Set<String> =
        installedForUid.orEmpty().filterTo(linkedSetOf()) { it in OWNERS }

    fun initialExchangeDelayMs(requestedAtNanos: Long, nowNanos: Long): Long {
        if (nowNanos <= requestedAtNanos) return INITIAL_EXCHANGE_MS
        return ((INITIAL_EXCHANGE_NANOS - (nowNanos - requestedAtNanos)).coerceAtLeast(0L) /
            1_000_000L).coerceAtMost(INITIAL_EXCHANGE_MS)
    }
}

internal enum class UpdateHintPhase { NONE, PENDING, VISIBLE }

internal data class UpdateHintRecord(
    val protocolVersion: Int = UpdateHintProtocol.VERSION,
    val ownerPackage: String,
    val processSessionId: String,
    val revision: Long,
    val eventId: String,
    val phase: UpdateHintPhase,
    val requestedAtElapsedNanos: Long,
    val expiresAtElapsedMs: Long,
    val displayId: Int,
    val preferredSizePercent: Int,
    val preferredWidthPx: Int,
    val preferredHeightPx: Int
) {
    fun isValid(): Boolean {
        if (protocolVersion != UpdateHintProtocol.VERSION ||
            ownerPackage !in UpdateHintProtocol.OWNERS ||
            processSessionId.isBlank() || revision < 0L) return false
        if (phase == UpdateHintPhase.NONE) return true
        return eventId.isNotBlank() && requestedAtElapsedNanos > 0L && displayId == 0 &&
            preferredSizePercent > 0 && preferredWidthPx > 0 && preferredHeightPx > 0 &&
            (phase != UpdateHintPhase.VISIBLE || expiresAtElapsedMs > 0L)
    }

    fun isActive(nowElapsedMs: Long, nowElapsedNanos: Long): Boolean = when (phase) {
        UpdateHintPhase.NONE -> false
        UpdateHintPhase.PENDING -> nowElapsedNanos <= requestedAtElapsedNanos +
            UpdateHintProtocol.INITIAL_EXCHANGE_MS * 1_000_000L
        UpdateHintPhase.VISIBLE -> nowElapsedMs < expiresAtElapsedMs
    }
}
