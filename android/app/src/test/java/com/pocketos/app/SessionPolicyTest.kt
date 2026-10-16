package com.pocketos.app

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    @Test fun refusesLowBattery() {
        assertFalse(SessionPolicy.canStart(20))
        assertTrue(SessionPolicy.canStart(21))
    }
    @Test fun stopsAtOneHour() {
        assertFalse(SessionPolicy.shouldStop(1000, 3600999, 90))
        assertTrue(SessionPolicy.shouldStop(1000, 3601000, 90))
    }
    @Test fun stopsAtBatteryCutoff() {
        assertTrue(SessionPolicy.shouldStop(0, 1000, 20))
    }
    @Test fun wifiDoesNotTriggerStop() {
        assertFalse(SessionPolicy.STOP_ON_WIFI_DISCONNECT)
    }
}
