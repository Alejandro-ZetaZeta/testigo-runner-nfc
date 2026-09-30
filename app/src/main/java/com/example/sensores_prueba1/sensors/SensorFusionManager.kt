package com.example.sensores_prueba1.sensors

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Unified telemetry metrics combining Cadence, GPS, and Gesture handoff data.
 */
data class SensorMetrics(
    val gps: GpsData = GpsData(),
    val cadence: CadenceData = CadenceData(),
    val lastGesture: HandoffGestureEvent? = null,
    val gestureTriggerCount: Int = 0,
    val isTracking: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val estimatedCaloriesKcal: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Centralized Sensor Fusion Manager that coordinates [CadenceDetector], [HandoffGestureDetector],
 * and [GpsTracker] into a unified [StateFlow] of [SensorMetrics].
 */
class SensorFusionManager(private val context: Context) {

    val cadenceDetector = CadenceDetector(context)
    val handoffGestureDetector = HandoffGestureDetector(context)
    val gpsTracker = GpsTracker(context)

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var fusionJob: Job? = null
    private var timerJob: Job? = null

    private val _metrics = MutableStateFlow(SensorMetrics())
    val metrics: StateFlow<SensorMetrics> = _metrics.asStateFlow()

    private val _handoffEvents = MutableSharedFlow<HandoffGestureEvent>(extraBufferCapacity = 16)
    val handoffEvents: SharedFlow<HandoffGestureEvent> = _handoffEvents.asSharedFlow()

    private var gestureCount = 0
    private var lastGestureEvent: HandoffGestureEvent? = null
    private var elapsedSeconds = 0L

    @Volatile
    private var isTracking = false

    init {
        // Collect handoff gestures
        managerScope.launch {
            handoffGestureDetector.handoffGestureFlow.collect { gesture ->
                gestureCount++
                lastGestureEvent = gesture
                _handoffEvents.tryEmit(gesture)
                updateMetricsState(
                    gps = gpsTracker.gpsFlow.value,
                    cadence = cadenceDetector.cadenceFlow.value
                )
            }
        }
    }

    /**
     * Starts tracking all motion, cadence, and GPS sensors.
     * @return true if tracking started successfully
     */
    fun startTracking(): Boolean {
        if (isTracking) return true
        isTracking = true

        cadenceDetector.start()
        handoffGestureDetector.start()
        val gpsStarted = gpsTracker.startTracking()

        // Combine GPS and Cadence flows into unified metrics
        fusionJob?.cancel()
        fusionJob = managerScope.launch {
            combine(gpsTracker.gpsFlow, cadenceDetector.cadenceFlow) { gps, cadence ->
                Pair(gps, cadence)
            }.collect { (gps, cadence) ->
                updateMetricsState(gps, cadence)
            }
        }

        // Active tracking duration ticker
        timerJob?.cancel()
        timerJob = managerScope.launch {
            while (isActive && isTracking) {
                delay(1000L)
                elapsedSeconds++
                updateMetricsState(
                    gps = gpsTracker.gpsFlow.value,
                    cadence = cadenceDetector.cadenceFlow.value
                )
            }
        }

        return gpsStarted
    }

    /**
     * Stops sensor collection and pauses active timers.
     */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false

        cadenceDetector.stop()
        handoffGestureDetector.stop()
        gpsTracker.stopTracking()

        fusionJob?.cancel()
        fusionJob = null
        timerJob?.cancel()
        timerJob = null

        _metrics.value = _metrics.value.copy(isTracking = false)
    }

    /**
     * Resets all accumulated stats (distance, steps, time, gesture count).
     */
    fun resetMetrics() {
        cadenceDetector.reset()
        handoffGestureDetector.reset()
        gpsTracker.reset()

        gestureCount = 0
        lastGestureEvent = null
        elapsedSeconds = 0L

        _metrics.value = SensorMetrics()
    }

    /**
     * Cleans up resources when no longer needed.
     */
    fun destroy() {
        stopTracking()
        managerScope.cancel()
    }

    private fun updateMetricsState(gps: GpsData, cadence: CadenceData) {
        val calories = estimateCalories(gps.totalDistanceMeters, cadence.steps, elapsedSeconds)

        _metrics.value = SensorMetrics(
            gps = gps,
            cadence = cadence,
            lastGesture = lastGestureEvent,
            gestureTriggerCount = gestureCount,
            isTracking = isTracking,
            elapsedSeconds = elapsedSeconds,
            estimatedCaloriesKcal = calories,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Simple MET-based calorie estimation (assuming ~70kg average runner weight).
     */
    private fun estimateCalories(distanceMeters: Float, steps: Int, seconds: Long): Float {
        if (seconds == 0L) return 0f
        val distanceKm = distanceMeters / 1000f
        // Standard running burns ~1.036 kcal per kg per km (~72 kcal per km for 70kg)
        val distanceCalories = distanceKm * 72.5f
        // Step backup (approx 0.04 kcal/step)
        val stepCalories = steps * 0.04f
        return maxOf(distanceCalories, stepCalories)
    }

    companion object {
        @Volatile
        private var instance: SensorFusionManager? = null

        fun getInstance(context: Context): SensorFusionManager {
            return instance ?: synchronized(this) {
                instance ?: SensorFusionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
