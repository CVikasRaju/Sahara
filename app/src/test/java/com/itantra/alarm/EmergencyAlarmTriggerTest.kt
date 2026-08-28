package com.itantra.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Before

/**
 * Unit tests for EmergencyAlarmTrigger state logic.
 *
 * These tests cover pure Kotlin/JVM state management behavior only.
 * Android-dependent methods (startEmergencySession, stopEmergencySession,
 * startSiren) require an instrumented test on a real device or emulator,
 * since they depend on AudioManager and NotificationManager system services.
 *
 * Run with: ./gradlew testDebugUnitTest
 */
class EmergencyAlarmTriggerTest {

    private lateinit var trigger: EmergencyAlarmTrigger

    @Before
    fun setUp() {
        // Access the singleton instance for testing
        trigger = EmergencyAlarmTrigger.getInstance()
    }

    @Test
    fun `singleton returns same instance across multiple calls`() {
        val instance1 = EmergencyAlarmTrigger.getInstance()
        val instance2 = EmergencyAlarmTrigger.getInstance()
        assert(instance1 === instance2) {
            "EmergencyAlarmTrigger.getInstance() must return the same singleton object"
        }
    }

    @Test
    fun `getInstance returns non-null object`() {
        val instance = EmergencyAlarmTrigger.getInstance()
        assertNotNull("EmergencyAlarmTrigger singleton must not be null", instance)
    }

    @Test
    fun `stopSiren is idempotent when no siren is running`() {
        // Should not throw even when no siren has been started
        trigger.stopSiren()
        trigger.stopSiren()
        trigger.stopSiren()
        // If we reach here without exception, the test passes
    }
}
