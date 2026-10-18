package com.pocketos.app

enum class GuestBootEvent {
    NO_CHANGE,
    READY,
    LOW_BATTERY_REJECTED,
    CHECKPOINT_FAILURE,
    INVALID_TRANSITION,
}

/**
 * Couples the VM lifecycle state machine to serial readiness and session policy.
 * Process creation only advances to BOOTING; RUNNING requires the guest prompt and durable deadline.
 */
class GuestBootCoordinator(
    private val lifecycle: VmLifecycle,
    private val readinessProbe: GuestReadinessProbe,
    private val sessionProtection: SessionProtection,
) {
    fun beginPreparation(): Boolean = lifecycle.transitionTo(VmState.PREPARING)

    fun onPreparationComplete(): Boolean = lifecycle.transitionTo(VmState.STARTING)

    fun onProcessStarted(): Boolean = lifecycle.transitionTo(VmState.BOOTING)

    @Synchronized
    fun onSerialOutput(bytes: ByteArray, batteryPercent: Int): GuestBootEvent {
        if (lifecycle.state != VmState.BOOTING) return GuestBootEvent.NO_CHANGE
        if (!readinessProbe.onSerialOutput(bytes)) return GuestBootEvent.NO_CHANGE

        val saved = try {
            sessionProtection.startWhenRunning(batteryPercent)
        } catch (_: Exception) {
            lifecycle.transitionTo(VmState.FAILED)
            return GuestBootEvent.CHECKPOINT_FAILURE
        }
        if (!saved) {
            lifecycle.transitionTo(VmState.FAILED)
            return if (batteryPercent <= SessionPolicy.BATTERY_CUTOFF_PERCENT) {
                GuestBootEvent.LOW_BATTERY_REJECTED
            } else {
                GuestBootEvent.CHECKPOINT_FAILURE
            }
        }
        if (!lifecycle.transitionTo(VmState.RUNNING)) {
            sessionProtection.clear()
            lifecycle.transitionTo(VmState.FAILED)
            return GuestBootEvent.INVALID_TRANSITION
        }
        return GuestBootEvent.READY
    }

    fun stopReason(batteryPercent: Int): SessionStopReason? {
        if (lifecycle.state != VmState.RUNNING) return null
        return sessionProtection.stopReason(batteryPercent)
    }

    fun requestShutdown(): Boolean =
        lifecycle.state == VmState.RUNNING &&
            lifecycle.transitionTo(VmState.SHUTDOWN_REQUESTED)

    /** Call after the native process exits; unexpected exits invalidate the saved session. */
    fun onProcessExited(): Boolean {
        val current = lifecycle.state
        return when (current) {
            VmState.STOPPING -> {
                sessionProtection.clear()
                lifecycle.transitionTo(VmState.STOPPED)
            }
            VmState.STARTING, VmState.BOOTING, VmState.RUNNING, VmState.SHUTDOWN_REQUESTED -> {
                sessionProtection.clear()
                lifecycle.transitionTo(VmState.FAILED)
            }
            else -> false
        }
    }
}
