package com.pocketos.app

/** Keeps a small sanitized tail for failure reports without retaining an unbounded transcript. */
class BoundedDiagnosticCapture(private val capacityChars: Int = 8_192) {
    private val tail = StringBuilder()
    private val secrets = linkedSetOf<String>()

    init {
        require(capacityChars > 0) { "Diagnostic capacity must be positive" }
    }

    @Synchronized
    fun redact(value: String) {
        if (value.isEmpty()) return
        secrets += value
        replaceSecretsInTail()
    }

    @Synchronized
    fun append(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        tail.append(sanitize(text))
        replaceSecretsInTail()
        if (tail.length > capacityChars) tail.delete(0, tail.length - capacityChars)
    }

    @Synchronized
    fun snapshot(): String = sanitize(tail.toString())

    private fun replaceSecretsInTail() {
        var safe = tail.toString()
        secrets.forEach { secret -> safe = safe.replace(secret, REDACTED) }
        tail.setLength(0)
        tail.append(sanitize(safe))
    }

    private fun sanitize(value: String): String {
        var safe = ANSI_ESCAPE.replace(value, "")
        secrets.forEach { secret -> safe = safe.replace(secret, REDACTED) }
        safe = CREDENTIAL_VALUE.replace(safe) { match -> "${match.groupValues[1]}[REDACTED]" }
        return safe.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
    }

    private companion object {
        const val REDACTED = "[REDACTED]"
        val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
        val CREDENTIAL_VALUE = Regex("(?i)(password|passwd|token|secret|credential)(\\s*[:=]\\s*)\\S+")
    }
}
