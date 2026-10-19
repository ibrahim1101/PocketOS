package com.pocketos.app

import android.content.SharedPreferences

data class GuestRuntimeSettings(
    val memoryMiB: Int = DEFAULT_MEMORY_MIB,
    val vcpus: Int = DEFAULT_VCPUS,
    val hostHttpPort: Int = DEFAULT_HOST_HTTP_PORT,
) {
    init {
        require(memoryMiB in MIN_MEMORY_MIB..MAX_MEMORY_MIB) { "Memory must be between 512 and 4096 MiB" }
        require(vcpus in MIN_VCPUS..MAX_VCPUS) { "vCPUs must be between 1 and 4" }
        require(hostHttpPort in 1024..65535) { "Host HTTP port must be between 1024 and 65535" }
    }

    fun save(preferences: SharedPreferences) {
        check(preferences.edit().putInt(KEY_MEMORY, memoryMiB).putInt(KEY_VCPUS, vcpus)
            .putInt(KEY_HOST_PORT, hostHttpPort).commit()) { "Could not save guest settings" }
    }

    companion object {
        const val DEFAULT_MEMORY_MIB = 1024
        const val DEFAULT_VCPUS = 2
        const val DEFAULT_HOST_HTTP_PORT = 18080
        const val MIN_MEMORY_MIB = 512
        const val MAX_MEMORY_MIB = 4096
        const val MIN_VCPUS = 1
        const val MAX_VCPUS = 4
        private const val KEY_MEMORY = "memory_mib"
        private const val KEY_VCPUS = "vcpus"
        private const val KEY_HOST_PORT = "host_http_port"

        fun load(preferences: SharedPreferences) = GuestRuntimeSettings(
            memoryMiB = preferences.getInt(KEY_MEMORY, DEFAULT_MEMORY_MIB),
            vcpus = preferences.getInt(KEY_VCPUS, DEFAULT_VCPUS),
            hostHttpPort = preferences.getInt(KEY_HOST_PORT, DEFAULT_HOST_HTTP_PORT),
        )
    }
}
