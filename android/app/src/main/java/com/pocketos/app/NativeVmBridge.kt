package com.pocketos.app

/** JNI boundary smoke-tested in the Android emulator; no QEMU engine is linked yet. */
object NativeVmBridge {
    private val loadError: String? = try {
        System.loadLibrary("pocketos_vm")
        null
    } catch (error: UnsatisfiedLinkError) {
        error.message ?: "native library could not be loaded"
    }

    fun status(): String {
        val error = loadError
        return if (error != null) {
            "NATIVE_BRIDGE_UNAVAILABLE: $error"
        } else {
            nativeStatus()
        }
    }

    @JvmStatic
    private external fun nativeStatus(): String
}
