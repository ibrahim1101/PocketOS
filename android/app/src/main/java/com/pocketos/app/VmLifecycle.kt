package com.pocketos.app

/**
 * Host-side VM lifecycle states. RUNNING means the guest readiness probe passed,
 * not merely that a native process was launched.
 */
enum class VmState {
    STOPPED,
    PREPARING,
    STARTING,
    BOOTING,
    RUNNING,
    SHUTDOWN_REQUESTED,
    STOPPING,
    FAILED,
    RECOVERING,
}

/** Serializes lifecycle updates and rejects duplicate or out-of-order events. */
class VmLifecycle(initialState: VmState = VmState.STOPPED) {
    private val lock = Any()

    @Volatile
    var state: VmState = initialState
        private set

    fun transitionTo(next: VmState): Boolean = synchronized(lock) {
        if (next !in ALLOWED_TRANSITIONS.getValue(state)) {
            return@synchronized false
        }
        state = next
        true
    }

    fun canStart(): Boolean = state == VmState.STOPPED

    companion object {
        private val ALLOWED_TRANSITIONS = mapOf(
            VmState.STOPPED to setOf(VmState.PREPARING),
            VmState.PREPARING to setOf(VmState.STARTING, VmState.STOPPING, VmState.FAILED),
            VmState.STARTING to setOf(VmState.BOOTING, VmState.STOPPING, VmState.FAILED),
            VmState.BOOTING to setOf(VmState.RUNNING, VmState.STOPPING, VmState.FAILED),
            VmState.RUNNING to setOf(VmState.SHUTDOWN_REQUESTED, VmState.FAILED),
            VmState.SHUTDOWN_REQUESTED to setOf(VmState.STOPPING, VmState.FAILED),
            VmState.STOPPING to setOf(VmState.STOPPED, VmState.FAILED),
            VmState.FAILED to setOf(VmState.RECOVERING, VmState.STOPPED),
            VmState.RECOVERING to setOf(VmState.STOPPED, VmState.PREPARING, VmState.FAILED),
        )
    }
}
