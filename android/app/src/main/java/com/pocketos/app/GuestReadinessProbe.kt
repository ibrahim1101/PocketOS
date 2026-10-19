package com.pocketos.app

/**
 * Detects Alpine's first-run password or normal login prompt as a guest-ready boundary.
 * Feed raw serial output chunks; readiness becomes true only after a complete prompt.
 */
class GuestReadinessProbe(
    private val maxRetainedCharacters: Int = 4096,
) {
    private val serialTail = StringBuilder()
    var isReady: Boolean = false
        private set

    init {
        require(maxRetainedCharacters >= MIN_PROMPT_LENGTH)
    }

    @Synchronized
    fun onSerialOutput(bytes: ByteArray): Boolean {
        if (isReady || bytes.isEmpty()) return isReady
        serialTail.append(bytes.toString(Charsets.UTF_8))
        if (serialTail.length > maxRetainedCharacters) {
            serialTail.delete(0, serialTail.length - maxRetainedCharacters)
        }
        val cleanTail = ANSI_ESCAPE.replace(serialTail, "")
        isReady = FIRST_BOOT_PROMPT.containsMatchIn(cleanTail) || LOGIN_PROMPT.containsMatchIn(cleanTail)
        return isReady
    }

    companion object {
        private const val MIN_PROMPT_LENGTH = 7
        private val ANSI_ESCAPE = Regex("\\x1B\\[[0-?]*[ -/]*[@-~]")
        private val FIRST_BOOT_PROMPT = Regex("(?im)(?:^|[\\r\\n])New password:\\s*$")
        private val LOGIN_PROMPT = Regex("(?im)(?:^|[\\r\\n])(?:(?!Last\\b)[A-Za-z0-9_.-]+\\s+)?login:\\s*$")
    }
}
