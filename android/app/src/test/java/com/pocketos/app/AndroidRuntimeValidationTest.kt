package com.pocketos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRuntimeValidationTest {
    @Test fun rejectsNonArm64Runtime() {
        val result = AndroidRuntimeValidation.evaluate(listOf("x86_64"), true, true)
        assertTrue(!result.allowed)
        assertEquals("requires an ARM64 Android runtime", result.reason)
    }
    @Test fun requiresQemuExecutionEvidence() {
        val result = AndroidRuntimeValidation.evaluate(listOf("arm64-v8a"), false, true)
        assertTrue(!result.allowed)
        assertEquals("QEMU execution has not been verified on Android", result.reason)
    }
    @Test fun requiresGuestBootEvidence() {
        val result = AndroidRuntimeValidation.evaluate(listOf("arm64-v8a"), true, false)
        assertTrue(!result.allowed)
        assertEquals("Alpine guest boot has not been verified on Android", result.reason)
    }
    @Test fun allowsValidatedRuntime() {
        assertTrue(AndroidRuntimeValidation.evaluate(listOf("arm64-v8a"), true, true).allowed)
    }
}
