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
import kotlin.math.sqrt

/**
 * Data model containing real-time cadence and movement metrics.
 */
data class CadenceData(
    val steps: Int = 0,
    val cadenceSpm: Float = 0f,
    val motionIntensity: Float = 0f,
    val peakMotionIntensity: Float = 0f,
    val isRunning: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Detects runner cadence (Steps Per Minute - SPM), cumulative steps, and motion intensity
 * using a fusion of [Sensor.TYPE_STEP_DETECTOR] and [Sensor.TYPE_ACCELEROMETER].
 */
class CadenceDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepDetectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometerSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _cadenceFlow = MutableStateFlow(CadenceData())
    val cadenceFlow: StateFlow<CadenceData> = _cadenceFlow.asStateFlow()

    @Volatile
    private var isTracking = false

    // Step tracking state
    private var totalSteps = 0
    private val stepTimestampsMs = ArrayDeque<Long>()
    private val windowDurationMs = 8_000L // 8-second rolling window for cadence estimation
    private val inactivityTimeoutMs = 3_500L // Reset cadence if no steps for 3.5s

    // Motion intensity & fallback step detection (when TYPE_STEP_DETECTOR is unavailable)
    private var smoothedIntensity = 0f
    private var peakIntensity = 0f
    private var lastStepTimestampMs = 0L
    private var accelStepArmed = false
    private val accelStepThreshold = 12.2f // m/s^2 peak threshold for fallback step detection
    private val accelStepResetThreshold = 9.5f // m/s^2 valley threshold
    private val minStepIntervalMs = 240L // Max ~250 SPM

    private val scope = CoroutineScope(Dispatchers.Default)
    private var periodicDecayJob: Job? = null

    /**
     * Start listening to sensors and estimating cadence.
     */
    fun start() {
        if (isTracking) return
        isTracking = true

        val sm = sensorManager ?: return

        // Register step detector if available
        stepDetectorSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        // Register accelerometer for motion intensity and fallback step detection
        accelerometerSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Start periodic cadence decay job for smooth zeroing on stop/inactivity
        periodicDecayJob = scope.launch {
            while (isActive && isTracking) {
                delay(1000L)
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
     * Reset step count, peak intensity, and metrics.
     */
    fun reset() {
        totalSteps = 0
        stepTimestampsMs.clear()
        smoothedIntensity = 0f
        peakIntensity = 0f
        lastStepTimestampMs = 0L
        _cadenceFlow.value = CadenceData(
            steps = 0,
            cadenceSpm = 0f,
            motionIntensity = 0f,
            peakMotionIntensity = 0f,
            isRunning = false,
            timestampMs = System.currentTimeMillis()
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // Hardware step detector fired
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
        val dynamicAccel = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)

        // Exponential smoothing for motion intensity (0.15 smoothing factor)
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
        totalSteps++
        lastStepTimestampMs = timestampMs

        // Keep rolling timestamps within window
        stepTimestampsMs.addLast(timestampMs)
        pruneOldSteps(timestampMs)

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
        if (now - lastStepTimestampMs > inactivityTimeoutMs) {
            // Decayed to walking/stopped
            smoothedIntensity *= 0.7f
            updateCadenceState(now)
        }
    }

    private fun updateCadenceState(now: Long) {
        val cadenceSpm = calculateCadenceSpm(now)
        val isRunning = cadenceSpm >= 130f || (cadenceSpm >= 110f && smoothedIntensity > 2.5f)

        _cadenceFlow.value = CadenceData(
            steps = totalSteps,
            cadenceSpm = cadenceSpm,
            motionIntensity = smoothedIntensity,
            peakMotionIntensity = peakIntensity,
            isRunning = isRunning,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun calculateCadenceSpm(now: Long): Float {
        if (stepTimestampsMs.size < 2 || (now - lastStepTimestampMs) > inactivityTimeoutMs) {
            return 0f
        }

        val first = stepTimestampsMs.first()
        val last = stepTimestampsMs.last()
        val durationMs = last - first

        if (durationMs < 500L) {
            return 0f
        }

        val stepIntervals = stepTimestampsMs.size - 1
        val spm = (stepIntervals.toFloat() / (durationMs.toFloat() / 60_000f))
        return spm.coerceIn(0f, 300f)
    }
}
