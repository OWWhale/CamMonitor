package com.a0soft.gphone.camonitor.camera

import com.a0soft.gphone.camonitor.data.camera.CameraXVideoCapturer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeThresholdTest {

    @Test
    fun testNightModeThresholdConstants() {
        assertEquals("Night enter lux threshold should be 5.0f", 5.0f, CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER, 0.001f)
        assertEquals("Night exit lux threshold should be 15.0f", 15.0f, CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT, 0.001f)
        assertTrue("Exit lux threshold must be greater than enter lux for hysteresis", CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT > CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER)
        assertEquals("Debounce delay should be 1500ms", 1500L, CameraXVideoCapturer.LIGHT_DEBOUNCE_DELAY_MS)
    }

    @Test
    fun testHysteresisLogic() {
        // Given starting in standard (day) mode
        var isNight = false

        // Simulate bright room (150 lux) -> stays day
        isNight = evaluateNightMode(150.0f, isNight)
        assertFalse("150 lux should keep night mode off", isNight)

        // Drop below 5 lux (e.g. 2 lux) -> enters night
        isNight = evaluateNightMode(2.0f, isNight)
        assertTrue("2 lux should trigger night mode", isNight)

        // Ambient rises to 10 lux (within hysteresis deadband 5..15) -> stays night
        isNight = evaluateNightMode(10.0f, isNight)
        assertTrue("10 lux is within deadband and should remain night mode", isNight)

        // Ambient rises above 15 lux (e.g. 30 lux) -> exits night
        isNight = evaluateNightMode(30.0f, isNight)
        assertFalse("30 lux should exit night mode to standard mode", isNight)

        // Ambient drops to 8 lux (within deadband) -> stays day
        isNight = evaluateNightMode(8.0f, isNight)
        assertFalse("8 lux is within deadband and should remain standard mode", isNight)
    }

    private fun evaluateNightMode(lux: Float, currentNight: Boolean): Boolean {
        return when {
            lux < CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER -> true
            lux > CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT -> false
            else -> currentNight
        }
    }
}
