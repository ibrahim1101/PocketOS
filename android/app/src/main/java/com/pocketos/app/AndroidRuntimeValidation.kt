package com.pocketos.app

data class AndroidRuntimeGateResult(val allowed: Boolean, val reason: String)

/** Cross-compilation is not evidence that an Android device can execute QEMU. */
object AndroidRuntimeValidation {
    fun evaluate(supportedAbis: List<String>, qemuExecutionVerified: Boolean, guestBootVerified: Boolean): AndroidRuntimeGateResult {
        if ("arm64-v8a" !in supportedAbis) return AndroidRuntimeGateResult(false, "requires an ARM64 Android runtime")
        if (!qemuExecutionVerified) return AndroidRuntimeGateResult(false, "QEMU execution has not been verified on Android")
        if (!guestBootVerified) return AndroidRuntimeGateResult(false, "Alpine guest boot has not been verified on Android")
        return AndroidRuntimeGateResult(true, "ARM64 runtime validation passed")
    }
}
