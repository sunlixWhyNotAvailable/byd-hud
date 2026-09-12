package com.bydhud.app

internal class UpdateHintStateEngine {
    private val latest = linkedMapOf<String, UpdateHintRecord>()
    private val records = linkedMapOf<String, UpdateHintRecord>()
    private val retiredSessions = mutableMapOf<String, MutableSet<String>>()

    fun accept(authenticatedOwner: String, record: UpdateHintRecord): Boolean {
        if (authenticatedOwner != record.ownerPackage || !record.isValid()) return false
        if (record.processSessionId in retiredSessions.getOrPut(record.ownerPackage) { mutableSetOf() }) {
            return false
        }
        val current = latest[record.ownerPackage]
        if (current != null) {
            if (record.processSessionId == current.processSessionId) {
                if (record.revision <= current.revision) return false
                if (current.phase != UpdateHintPhase.NONE && current.eventId == record.eventId &&
                    current.requestedAtElapsedNanos != record.requestedAtElapsedNanos) return false
                if (current.phase == UpdateHintPhase.VISIBLE && record.phase == UpdateHintPhase.VISIBLE &&
                    current.eventId == record.eventId &&
                    current.expiresAtElapsedMs != record.expiresAtElapsedMs) return false
                if (current.phase == UpdateHintPhase.VISIBLE && record.phase == UpdateHintPhase.PENDING &&
                    current.eventId == record.eventId) return false
            } else {
                retiredSessions.getOrPut(record.ownerPackage) { mutableSetOf() }
                    .add(current.processSessionId)
            }
        }
        latest[record.ownerPackage] = record
        if (record.phase == UpdateHintPhase.NONE) records.remove(record.ownerPackage)
        else records[record.ownerPackage] = record
        return true
    }

    fun confirmedDeath(ownerPackage: String, processSessionId: String): Boolean {
        val current = latest[ownerPackage] ?: return false
        if (current.processSessionId != processSessionId) return false
        retiredSessions.getOrPut(ownerPackage) { mutableSetOf() }.add(processSessionId)
        latest.remove(ownerPackage)
        records.remove(ownerPackage)
        return true
    }

    fun active(nowElapsedMs: Long, nowElapsedNanos: Long): List<UpdateHintRecord> {
        records.entries.removeAll { !it.value.isActive(nowElapsedMs, nowElapsedNanos) }
        return records.values.sortedWith(
            compareBy<UpdateHintRecord> { it.requestedAtElapsedNanos }
                .thenBy { UpdateHintProtocol.OWNERS.indexOf(it.ownerPackage) }
        )
    }

    fun current(ownerPackage: String): UpdateHintRecord? = latest[ownerPackage]
}
