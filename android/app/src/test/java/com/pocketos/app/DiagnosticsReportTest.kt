package com.pocketos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    @Test
    fun reportsRuntimeAndStorageMetadata() {
        val report = sampleReport()

        assertTrue(report.contains("VM status: Running"))
        assertTrue(report.contains("Supported ABIs: arm64-v8a"))
        assertTrue(report.contains("Guest boot evidence: true"))
        assertTrue(report.contains("Network exposure: Loopback only"))
        assertTrue(report.contains("Persistent guest disk capacity: 256.0 MiB"))
    }

    @Test
    fun sanitizesUntrustedStatusAndGateReason() {
        val report = DiagnosticsReport.format(
            vmStatus = "Running; password=hunter2",
            supportedAbis = listOf("arm64-v8a"),
            androidApi = 35,
            runtimeGateReason = "private detail: token=secret",
            qemuExecutionVerified = true,
            guestBootVerified = false,
            availableHostMemoryMiB = 512,
            diskCapacity = "256.0 MiB",
            deviceFreeSpace = "1.0 GiB",
            guestFreeSpace = "Unavailable",
            lanExposureEnabled = true,
        )

        assertTrue(report.contains("VM status: Running"))
        assertTrue(report.contains("Runtime gate: Unavailable"))
        assertTrue(report.contains("Network exposure: Wi-Fi LAN (active IPv4 only)"))
        assertFalse(report.contains("hunter2"))
        assertFalse(report.contains("token=secret"))
        assertFalse(report.contains("terminal output"))
    }

    @Test
    fun reportHasNoTerminalOrCredentialFields() {
        val report = sampleReport()

        assertFalse(report.contains("terminal output"))
        assertFalse(report.contains("password="))
        assertFalse(report.contains("input="))
    }

    private fun sampleReport() = DiagnosticsReport.format(
        vmStatus = "Running",
        supportedAbis = listOf("arm64-v8a"),
        androidApi = 35,
        runtimeGateReason = "ARM64 runtime validation passed",
        qemuExecutionVerified = true,
        guestBootVerified = true,
        availableHostMemoryMiB = 1024,
        diskCapacity = "256.0 MiB",
        deviceFreeSpace = "2.0 GiB",
        guestFreeSpace = "100.0 MiB",
        lanExposureEnabled = false,
    )
}
