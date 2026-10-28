package com.pocketos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputModeDetectorTest {
    @Test
    fun masksPasswordPromptSplitAcrossSerialReads() {
        val detector = TerminalInputModeDetector()

        assertFalse(detector.append("Alpine login: pocketos\nNew pass"))
        assertTrue(detector.append("word: "))
        assertTrue(detector.append("Retype pass"))
        assertTrue(detector.append("word: "))
    }

    @Test
    fun returnsToVisibleInputAfterLoginOrShellPrompt() {
        val detector = TerminalInputModeDetector()

        assertTrue(detector.append("Password: "))
        assertFalse(detector.append("\npocketos login: "))
        assertTrue(detector.append("Password: "))
        assertFalse(detector.append("\npocketos:~$ "))
    }

    @Test
    fun doesNotMaskOrdinaryTextThatOnlyMentionsPasswords() {
        val detector = TerminalInputModeDetector()

        assertFalse(detector.append("See the password policy in /etc/login.defs\n"))
        assertFalse(detector.append("This file stores a password hash.\n"))
    }

    @Test
    fun detectsPromptAfterLargeSerialChunk() {
        val detector = TerminalInputModeDetector()

        assertFalse(detector.append("x".repeat(4_096)))
        assertFalse(detector.append("\n"))
        assertTrue(detector.append("Password: "))
    }
}
