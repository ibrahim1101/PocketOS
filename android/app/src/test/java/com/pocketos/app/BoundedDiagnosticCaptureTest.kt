package com.pocketos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDiagnosticCaptureTest {
    @Test
    fun keepsOnlyBoundedTailAndStripsTerminalControlSequences() {
        val capture = BoundedDiagnosticCapture(capacityChars = 12)
        capture.append("old output\n\u001B[31mrecent\u001B[0m\n".toByteArray())

        val snapshot = capture.snapshot()
        assertTrue(snapshot.length <= 12)
        assertFalse(snapshot.contains("\u001B"))
        assertFalse(snapshot.contains("old output"))
        assertTrue(snapshot.contains("recent"))
    }

    @Test
    fun redactsRegisteredAndCredentialLabeledSecretsAcrossChunks() {
        val capture = BoundedDiagnosticCapture()
        capture.append("password=hunter".toByteArray())
        capture.redact("hunter2")
        capture.append("2\nlogin password: hunter2\n".toByteArray())

        val snapshot = capture.snapshot()
        assertFalse(snapshot.contains("hunter2"))
        assertFalse(snapshot.contains("password=hunter"))
        assertTrue(snapshot.contains("[REDACTED]"))
    }

    @Test
    fun preservesAdjacentInteractivePasswordPrompts() {
        val capture = BoundedDiagnosticCapture()
        capture.append("New password:\nRetype password:\n".toByteArray())

        assertTrue(capture.snapshot().contains("New password:"))
        assertTrue(capture.snapshot().contains("Retype password:"))
    }

    @Test
    fun validatesCapacity() {
        val error = runCatching { BoundedDiagnosticCapture(0) }.exceptionOrNull()
        assertEquals("Diagnostic capacity must be positive", error?.message)
    }
}
