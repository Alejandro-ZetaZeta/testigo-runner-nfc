package com.example.sensores_prueba1.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Data model containing real-time cadence, step count, and movement metrics.
 */
data class CadenceData(
    val steps: Int = 0,
    val cadenceSpm: Float = 0f,
    val motionIntensity: Float = 0f,
    val peakMotionIntensity: Float = 0f,
    val isRunning: Boolean = false,
    val accumulatedDistanceMeters: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Precision Cadence Detector for runners.
 * Filters phantom movements, detects genuine rhythmic running cadence,
 * and accumulates indoor stride distance only during active rhythmic running.
 */
class CadenceDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepDetectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometerSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _cadenceFlow = MutableStateFlow(CadenceData())
    val cadenceFlow: StateFlow<CadenceData> = _cadenceFlow.asStateFlow()

    @Volatile
    private var isTracking = false

    // Step tracking and rhythmic cadence state
    private var totalSteps = 0
    private var consecutiveRhythmicSteps = 0
    private val stepTimestampsMs = ArrayDeque<Long>()
    private val windowDurationMs = 6_000L // 6-second rolling window for cadence estimation
    private val inactivityTimeoutMs = 2_200L // Reset cadence if no steps for 2.2s

    private var accumulatedDistanceMeters = 0f
    private var smoothedIntensity = 0f
    private var peakIntensity = 0f
    private var lastStepTimestampMs = 0L
    private var smoothedCadenceSpm = 0f

    // Motion intensity & fallback step detection
    private var accelStepArmed = false
    private val accelStepThreshold = 13.0f // m/s^2 peak threshold for fallback step detection
    private val accelStepResetThreshold = 10.0f // m/s^2 valley threshold
    private val minStepIntervalMs = 230L // Max 260 SPM (reject sensor debounce jitter)
    private val maxRhythmicIntervalMs = 1500L // Min 40 SPM (steps slower than 1.5s are not rhythmic running)

    private val scope = CoroutineScope(Dispatchers.Default)
    private var periodicDecayJob: Job? = null

    /**
     * Start listening to sensors and estimating cadence.
     */
    fun start() {
        if (isTracking) return
        isTracking = true

        val sm = sensorManager ?: return

        stepDetectorSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        accelerometerSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        periodicDecayJob = scope.launch {
            while (isActive && isTracking) {
                delay(500L)
                checkInactivityDecay()
            }
        }
    }

    /**
     * Stop listening to sensors.
     */
    fun stop() {
        if (!isTracking) return
        isTracking = false
        periodicDecayJob?.cancel()
        periodicDecayJob = null
        sensorManager?.unregisterListener(this)
    }

    /**
     * Reset step count, distance, peak intensity, and metrics to strictly 0.
     */
    fun reset() {
        totalSteps = 0
        consecutiveRhythmicSteps = 0
        stepTimestampsMs.clear()
        accumulatedDistanceMeters = 0f
        smoothedIntensity = 0f
        peakIntensity = 0f
        lastStepTimestampMs = 0L
        smoothedCadenceSpm = 0f
        _cadenceFlow.value = CadenceData()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                handleStepDetected(SystemClock.elapsedRealtime())
            }
            Sensor.TYPE_ACCELEROMETER -> {
                handleAccelerometerData(event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun handleAccelerometerData(x: Float, y: Float, z: Float) {
        val now = SystemClock.elapsedRealtime()
        val magnitude = sqrt(x * x + y * y + z * z)
        val dynamicAccel = abs(magnitude - SensorManager.GRAVITY_EARTH)

        // Exponential smoothing for motion intensity
        smoothedIntensity = 0.15f * dynamicAccel + 0.85f * smoothedIntensity
        if (smoothedIntensity > peakIntensity) {
            peakIntensity = smoothedIntensity
        }

        // Fallback step detector if hardware step detector is not available
        if (stepDetectorSensor == null) {
            if (!accelStepArmed && magnitude > accelStepThreshold) {
                if (now - lastStepTimestampMs >= minStepIntervalMs) {
                    accelStepArmed = true
                    handleStepDetected(now)
                }
            } else if (accelStepArmed && magnitude < accelStepResetThreshold) {
                accelStepArmed = false
            }
        }

        updateCadenceState(now)
    }

    private fun handleStepDetected(timestampMs: Long) {
        val dt = if (lastStepTimestampMs == 0L) 0L else (timestampMs - lastStepTimestampMs)

        // Anti-bounce filter (< 230ms is > 260 SPM, physically impossible for human running)
        if (dt in 1 until minStepIntervalMs) return

        totalSteps++

        if (dt in minStepIntervalMs..maxRhythmicIntervalMs) {
            consecutiveRhythmicSteps++
            stepTimestampsMs.addLast(timestampMs)
            pruneOldSteps(timestampMs)

            val instantSpm = (60_000f / dt).coerceIn(40f, 250f)
            smoothedCadenceSpm = if (smoothedCadenceSpm < 40f) {
                instantSpm
            } else {
                0.60f * smoothedCadenceSpm + 0.40f * instantSpm
            }

            // Only accumulate indoor stride distance when a genuine rhythmic running cadence is active (> 40 SPM and >= 2 consecutive steps)
            if (smoothedCadenceSpm >= 40f && consecutiveRhythmicSteps >= 2) {
                val stepStride = calculateDynamicStride(smoothedCadenceSpm, smoothedIntensity)
                accumulatedDistanceMeters += stepStride
            }
        } else {
            // First step after idle/stationary, or isolated motion (picking up phone, shift, single step)
            consecutiveRhythmicSteps = 1
            stepTimestampsMs.clear()
            stepTimestampsMs.addLast(timestampMs)
            smoothedCadenceSpm = 0f
            // Distance remains strictly frozen / 0.0 m
        }

        lastStepTimestampMs = timestampMs
        updateCadenceState(timestampMs)
    }

    private fun pruneOldSteps(now: Long) {
        while (stepTimestampsMs.isNotEmpty() && (now - stepTimestampsMs.first()) > windowDurationMs) {
            stepTimestampsMs.removeFirst()
        }
    }

    private fun checkInactivityDecay() {
        val now = SystemClock.elapsedRealtime()
        pruneOldSteps(now)
        val timeSinceLastStep = now - lastStepTimestampMs
        if (lastStepTimestampMs > 0L && timeSinceLastStep > 1800L) {
            smoothedIntensity *= 0.6f
            smoothedCadenceSpm *= 0.6f
            if (timeSinceLastStep > inactivityTimeoutMs || smoothedCadenceSpm < 40f) {
                smoothedCadenceSpm = 0f
                consecutiveRhythmicSteps = 0
            }
            updateCadenceState(now)
        }
    }

    private fun updateCadenceState(now: Long) {
        val finalCadence = if (consecutiveRhythmicSteps >= 2 && (now - lastStepTimestampMs) <= inactivityTimeoutMs) {
            smoothedCadenceSpm.coerceIn(0f, 260f)
        } else {
            0f
        }

        val isRunning = finalCadence >= 125f || (finalCadence >= 105f && smoothedIntensity > 2.2f)

        _cadenceFlow.value = CadenceData(
            steps = totalSteps,
            cadenceSpm = finalCadence,
            motionIntensity = smoothedIntensity,
            peakMotionIntensity = peakIntensity,
            isRunning = isRunning,
            accumulatedDistanceMeters = accumulatedDistanceMeters,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun calculateDynamicStride(cadenceSpm: Float, dynamicIntensity: Float): Float {
        if (cadenceSpm < 40f) return 0f
        return when {
            cadenceSpm < 95f -> 0.28f + (dynamicIntensity * 0.04f).coerceIn(0f, 0.12f)
            cadenceSpm in 95f..130f -> 0.38f + (dynamicIntensity * 0.06f).coerceIn(0f, 0.18f)
            cadenceSpm in 130f..165f -> 0.55f + (dynamicIntensity * 0.08f).coerceIn(0f, 0.22f)
            else -> 0.75f + (dynamicIntensity * 0.10f).coerceIn(0f, 0.30f)
        }.coerceIn(0.20f, 1.20f)
    }
}
