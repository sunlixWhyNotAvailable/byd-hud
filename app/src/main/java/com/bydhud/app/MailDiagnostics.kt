package com.bydhud.app

/** Retain causes and suppressed failures without copying parsed letter values into diagnostics. */
internal fun mailFailureDetails(error: Throwable): String = buildString {
    val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
    fun appendFailure(value: Throwable, relation: String) {
        if(!seen.add(value)) return
        val message=when {
            value is org.json.JSONException || value is java.time.format.DateTimeParseException -> "[response value redacted]"
            value.message?.startsWith("Invalid UUID string:")==true -> "Invalid UUID string: [response value redacted]"
            else -> FailureDiagnostics.redact(value.message.orEmpty())
                .replace(Regex("https?://[^\\s]+"),"[URL redacted]")
        }
        append(relation).append(value.javaClass.name).append(": ").append(message).append('\n')
        value.stackTrace.forEach { append("  at ").append(it).append('\n') }
        value.suppressed.forEach { appendFailure(it,"suppressed: ") }
        value.cause?.let { appendFailure(it,"caused by: ") }
    }
    appendFailure(error,"")
}
