package com.pocketos.app

import android.content.Context
import android.os.Build
import java.security.MessageDigest

data class AndroidRuntimeGateResult(
    val allowed: Boolean,
    val reason: String,
    val qemuExecutionVerified: Boolean = false,
    val guestBootVerified: Boolean = false,
)

/** Evidence is recorded only by ARM64 instrumentation tests, scoped to this app and guest asset set. */
object AndroidRuntimeValidation {
    private const val PREFS = "android_runtime_validation"
    private const val KEY_VERSION = "validation_version"
    private const val KEY_QEMU = "qemu_execution_verified"
    private const val KEY_GUEST = "guest_boot_verified"
    private const val QEMU_BUILD_VERSION = "qemu-11.1.2"

    fun evaluate(supportedAbis: List<String>, qemuExecutionVerified: Boolean, guestBootVerified: Boolean): AndroidRuntimeGateResult {
        if ("arm64-v8a" !in supportedAbis) {
            return AndroidRuntimeGateResult(false, "requires an ARM64 Android runtime", qemuExecutionVerified, guestBootVerified)
        }
        if (!qemuExecutionVerified) {
            return AndroidRuntimeGateResult(false, "QEMU execution has not been verified on Android", false, guestBootVerified)
        }
        if (!guestBootVerified) {
            return AndroidRuntimeGateResult(false, "Alpine guest boot has not been verified on Android", true, false)
        }
        return AndroidRuntimeGateResult(true, "ARM64 runtime validation passed", true, true)
    }

    fun evaluate(context: Context): AndroidRuntimeGateResult {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val validVersion = preferences.getString(KEY_VERSION, null) == evidenceVersion(context)
        val qemu = validVersion && preferences.getBoolean(KEY_QEMU, false)
        val guest = validVersion && preferences.getBoolean(KEY_GUEST, false)
        return evaluate(Build.SUPPORTED_ABIS.toList(), qemu, guest)
    }

    fun recordQemuExecutionVerified(context: Context) = record(context, qemu = true)
    fun recordGuestBootVerified(context: Context) = record(context, guest = true)

    private fun record(context: Context, qemu: Boolean = false, guest: Boolean = false) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = evidenceVersion(context)
        val sameVersion = preferences.getString(KEY_VERSION, null) == version
        val editor = preferences.edit()
            .putString(KEY_VERSION, version)
            .putBoolean(KEY_QEMU, qemu || (sameVersion && preferences.getBoolean(KEY_QEMU, false)))
            .putBoolean(KEY_GUEST, guest || (sameVersion && preferences.getBoolean(KEY_GUEST, false)))
        check(editor.commit()) { "Could not persist Android runtime validation evidence" }
    }

    private fun evidenceVersion(context: Context): String {
        val manifestHash = context.assets.open("pocketos/assets.sha256").use { input ->
            MessageDigest.getInstance("SHA-256").digest(input.readBytes())
                .joinToString("") { "%02x".format(it) }
        }
        return "$QEMU_BUILD_VERSION:$manifestHash"
    }
}
