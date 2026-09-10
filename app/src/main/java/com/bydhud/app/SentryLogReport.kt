package com.bydhud.app

data class SentryLogReport(val comment: String?, val title: String) {
    companion object {
        @JvmStatic
        fun create(
            rawComment: String,
            version: String,
            submittedAtLabel: String,
            logsLabel: String
        ): SentryLogReport {
            val comment = rawComment.trim().takeIf { it.isNotBlank() }
            val subject = if (comment == null) {
                "$logsLabel — $submittedAtLabel"
            } else {
                val oneLine = comment.replace(Regex("[\\s\\p{Z}]+"), " ")
                val end = oneLine.offsetByCodePoints(
                    0,
                    minOf(100, oneLine.codePointCount(0, oneLine.length))
                )
                oneLine.substring(0, end) + if (end < oneLine.length) "…" else ""
            }
            return SentryLogReport(comment, "BYD HUD $version — $subject")
        }
    }
}
