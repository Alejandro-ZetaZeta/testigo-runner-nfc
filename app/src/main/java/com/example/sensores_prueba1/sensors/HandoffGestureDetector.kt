package com.example.sensores_prueba1.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Data event emitted when a handoff gesture is recognized.
 */
data class HandoffGestureEvent(
    val peakAcceleration: Float,
    val pitchAngleDeg: Float,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Detects the baton handoff gesture (a sudden forward thrust + tilt/flick) performed by runners.
 * Listens to accelerometer data and emits [HandoffGestureEvent] via SharedFlow and optional callback.
 */
class HandoffGestureDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometerSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _handoffGestureFlow = MutableSharedFlow<HandoffGestureEvent>(extraBufferCapacity = 16)
    val handoffGestureFlow: SharedFlow<HandoffGestureEvent> = _handoffGestureFlow.asSharedFlow()

    var onHandoffGestureDetected: ((HandoffGestureEvent) -> Unit)? = null

    @Volatile
    private var isTracking = false

    // Detection thresholds & parameters
    // Acceleration spike threshold: ~18.0 m/s^2 (~1.8G)
    private val thrustAccelThreshold = 18.0f

    // Pitch tilt range during handoff forward flick: 10 to 85 degrees
    private val minPitchDeg = 10.0f
    private val maxPitchDeg = 85.0f

    // Cooldown between triggers to prevent multi-triggering from single flick (1.8 seconds)
    private val cooldownMs = 1800L
    private var lastTriggerTimestampMs = 0L

    // Low-pass filter for gravity estimation (to compute tilt angle)
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.8f

    fun start() {
        if (isTracking) return
        isTracking = true
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        accelerometerSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        sensorManager?.unregisterListener(this)
    }

    fun reset() {
        lastTriggerTimestampMs = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Gravity estimation using low-pass filter
        gravityX = alpha * gravityX + (1 - alpha) * x
        gravityY = alpha * gravityY + (1 - alpha) * y
        gravityZ = alpha * gravityZ + (1 - alpha) * z

        // Calculate pitch angle in degrees (phone tilted forward/backward)
        val pitchDeg = (atan2(gravityY.toDouble(), sqrt((gravityX * gravityX + gravityZ * gravityZ).toDouble())) * (180.0 / Math.PI)).toFloat()

        // Total instantaneous acceleration magnitude
        val totalMagnitude = sqrt(x * x + y * y + z * z)

        val now = SystemClock.elapsedRealtime()

        // Check conditions for forward thrust / flick gesture:
        // 1. High acceleration magnitude exceeding thrust threshold
        // 2. Cooldown period elapsed
        // 3. Pitch tilt is oriented in the forward handoff position
        if (totalMagnitude >= thrustAccelThreshold && (now - lastTriggerTimestampMs >= cooldownMs)) {
            val absPitch = kotlin.math.abs(pitchDeg)
            if (absPitch in minPitchDeg..maxPitchDeg || totalMagnitude >= 22.0f) {
                lastTriggerTimestampMs = now

                val gestureEvent = HandoffGestureEvent(
                    peakAcceleration = totalMagnitude,
                    pitchAngleDeg = pitchDeg,
                    timestampMs = System.currentTimeMillis()
                )

                _handoffGestureFlow.tryEmit(gestureEvent)
                onHandoffGestureDetected?.invoke(gestureEvent)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
