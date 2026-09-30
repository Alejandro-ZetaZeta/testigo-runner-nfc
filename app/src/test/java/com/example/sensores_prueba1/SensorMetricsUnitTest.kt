package com.example.sensores_prueba1

import com.example.sensores_prueba1.sensors.CadenceData
import com.example.sensores_prueba1.sensors.GpsData
import com.example.sensores_prueba1.sensors.GpsTracker
import com.example.sensores_prueba1.sensors.HandoffGestureEvent
import com.example.sensores_prueba1.sensors.SensorMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorMetricsUnitTest {

    @Test
    fun testGpsDataFormatting() {
        assertEquals("5:30 /km", GpsTracker.formatPace(5.5f))
        assertEquals("--:-- /km", GpsTracker.formatPace(0.0f))
        assertEquals("--:-- /km", GpsTracker.formatPace(35.0f))

        assertEquals("1.50 km", GpsTracker.formatDistance(1500f))
        assertEquals("450 m", GpsTracker.formatDistance(450f))
    }

    @Test
    fun testCadenceDataDefaults() {
        val cadence = CadenceData(
            steps = 120,
            cadenceSpm = 165f,
            motionIntensity = 4.2f,
            peakMotionIntensity = 8.1f,
            isRunning = true
        )

        assertEquals(120, cadence.steps)
        assertEquals(165f, cadence.cadenceSpm, 0.01f)
        assertTrue(cadence.isRunning)
    }

    @Test
    fun testSensorMetricsAggregation() {
        val gps = GpsData(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 12.0f,
            totalDistanceMeters = 800f
        )
        val cadence = CadenceData(
            steps = 650,
            cadenceSpm = 170f,
            isRunning = true
        )
        val gesture = HandoffGestureEvent(
            peakAcceleration = 21.5f,
            pitchAngleDeg = 45f
        )

        val metrics = SensorMetrics(
            gps = gps,
            cadence = cadence,
            lastGesture = gesture,
            gestureTriggerCount = 1,
            isTracking = true,
            elapsedSeconds = 240
        )

        assertTrue(metrics.isTracking)
        assertEquals(1, metrics.gestureTriggerCount)
        assertEquals(650, metrics.cadence.steps)
        assertEquals(800f, metrics.gps.totalDistanceMeters, 0.01f)
        assertEquals(21.5f, metrics.lastGesture?.peakAcceleration ?: 0f, 0.01f)
    }
}
