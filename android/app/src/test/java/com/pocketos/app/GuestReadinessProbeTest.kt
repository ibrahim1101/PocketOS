package com.pocketos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestReadinessProbeTest {
    @Test
    fun waitsForCompleteLoginPromptAcrossSerialChunks() {
        val probe = GuestReadinessProbe()
        val lineBreak = 13.toChar().toString() + 10.toChar()
        assertFalse(probe.onSerialOutput(("Booting Linux" + lineBreak).toByteArray()))
        assertFalse(probe.onSerialOutput("localhost log".toByteArray()))
        assertFalse(probe.isReady)
        assertTrue(probe.onSerialOutput("in: ".toByteArray()))
        assertTrue(probe.isReady)
        assertTrue(probe.onSerialOutput("later output".toByteArray()))
    }

    @Test
    fun acceptsPlainLoginPromptAndAnsiDecoratedPrompt() {
        val plain = GuestReadinessProbe()
        assertTrue(plain.onSerialOutput("login: ".toByteArray()))

        val decorated = GuestReadinessProbe()
        val escape = 27.toChar().toString()
        val prompt = escape + "[32mlocalhost login: " + escape + "[0m"
        assertTrue(decorated.onSerialOutput(prompt.toByteArray()))
    }

    @Test
    fun recognizesFirstBootPasswordPromptAcrossChunks() {
        val probe = GuestReadinessProbe()
        assertFalse(probe.onSerialOutput("kernel ready\r\nNew pass".toByteArray()))
        assertTrue(probe.onSerialOutput("word: ".toByteArray()))
    }

    @Test
    fun doesNotTreatIncompleteOrUnrelatedSetupTextAsReady() {
        val probe = GuestReadinessProbe()
        assertFalse(probe.onSerialOutput("PocketOS first boot: creating the pocketos account".toByteArray()))
        assertFalse(probe.onSerialOutput("New pass".toByteArray()))
        assertFalse(probe.onSerialOutput("not New password: ".toByteArray()))
    }

    @Test
    fun doesNotTreatLastLoginBannerAsGuestReady() {
        val probe = GuestReadinessProbe()
        assertFalse(probe.onSerialOutput("Last login:".toByteArray()))
    }
}
