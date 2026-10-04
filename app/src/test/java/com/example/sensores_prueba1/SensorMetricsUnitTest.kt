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
            isRunning = true,
            accumulatedDistanceMeters = 85.5f
        )

        assertEquals(120, cadence.steps)
        assertEquals(165f, cadence.cadenceSpm, 0.01f)
        assertTrue(cadence.isRunning)
        assertEquals(85.5f, cadence.accumulatedDistanceMeters, 0.01f)
    }

    @Test
    fun testStationaryGpsDataDefaults() {
        val defaultGps = GpsData()
        assertEquals(0.0f, defaultGps.totalDistanceMeters, 0.001f)
        assertEquals(0.0f, defaultGps.speedMps, 0.001f)
        assertEquals(0.0f, defaultGps.currentPaceMinPerKm, 0.001f)
        assertFalse(defaultGps.isGpsFixed)
    }

    @Test
    fun testSensorMetricsAggregation() {
        val gps = GpsData(
            latitude = 37.7749,
            longitude = -122.4194,
            speedKmh = 12.0f,
            totalDistanceMeters = 800f,
            isGpsFixed = true
        )
        val cadence = CadenceData(
            steps = 650,
            cadenceSpm = 170f,
            isRunning = true,
            accumulatedDistanceMeters = 520f
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
        assertEquals(800f, metrics.fusedDistanceMeters, 0.01f)
        assertEquals(21.5f, metrics.lastGesture?.peakAcceleration ?: 0f, 0.01f)
    }

    @Test
    fun testIndoorFusedDistanceFallback() {
        val gpsUnfixed = GpsData(
            totalDistanceMeters = 0.0f,
            isGpsFixed = false
        )
        val indoorCadence = CadenceData(
            steps = 150,
            cadenceSpm = 160f,
            isRunning = true,
            accumulatedDistanceMeters = 112.5f
        )

        val metrics = SensorMetrics(
            gps = gpsUnfixed,
            cadence = indoorCadence,
            isTracking = true
        )

        assertEquals(112.5f, metrics.fusedDistanceMeters, 0.01f)
    }
}
