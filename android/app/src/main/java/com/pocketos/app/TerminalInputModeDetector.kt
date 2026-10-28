package com.pocketos.app

/** Tracks prompt text across serial read boundaries so password input stays masked. */
internal class TerminalInputModeDetector {
    private val recentOutput = StringBuilder()
    private var passwordPromptActive = false

    @Synchronized
    fun append(serialText: CharSequence): Boolean {
        recentOutput.append(serialText)
        if (recentOutput.length > MAX_CONTEXT_CHARS) {
            recentOutput.delete(0, recentOutput.length - MAX_CONTEXT_CHARS)
        }
        val context = recentOutput.toString()
        when {
            PASSWORD_PROMPT.containsMatchIn(context) -> passwordPromptActive = true
            LOGIN_PROMPT.containsMatchIn(context) || SHELL_PROMPT.containsMatchIn(context) ->
                passwordPromptActive = false
        }
        return passwordPromptActive
    }

    private companion object {
        const val MAX_CONTEXT_CHARS = 256
        val PASSWORD_PROMPT = Regex(
            "(?:^|[\\r\\n])[^\\r\\n]{0,120}?(?:new\\s+|retype\\s+)?password\\s*:\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val LOGIN_PROMPT = Regex(
            "(?:^|[\\r\\n])[^\\r\\n]{0,120}\\blogin\\s*:\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val SHELL_PROMPT = Regex("(?:^|[\\r\\n])[^\\r\\n]{0,120}[#$]\\s*$")
    }
}
