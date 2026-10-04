package com.example.sensores_prueba1.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Data model for GPS position, speed, distance, and pace metrics.
 */
data class GpsData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speedMps: Float = 0f,
    val speedKmh: Float = 0f,
    val totalDistanceMeters: Float = 0f,
    val currentPaceMinPerKm: Float = 0f,
    val accuracyMeters: Float = 0f,
    val isGpsFixed: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Precision GPS Tracker for runners.
 * Filters stationary GPS noise, applies noise radius deadband thresholds,
 * eliminates teleport spikes, and calculates smooth speed and pace telemetry.
 */
class GpsTracker(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _gpsFlow = MutableStateFlow(GpsData())
    val gpsFlow: StateFlow<GpsData> = _gpsFlow.asStateFlow()

    @Volatile
    private var isTracking = false

    private var previousLocation: Location? = null
    private var totalDistanceMeters = 0f
    private var lastFixTimeMs = 0L
    private var smoothedSpeedMps = 0f

    // Precision and Noise Rejection Constants
    private val minTimeMs = 1000L // 1 second update interval
    private val minDistanceMeters = 0.0f // Allow fine listener callbacks; deadband filtered mathematically
    private val maxRealisticRunningSpeedMps = 12.5f // ~45 km/h upper bound to filter GPS teleport glitches
    private val maxAcceptableAccuracyMeters = 20.0f // Ignore fixes with poor accuracy (> 20m)
    private val minStationarySpeedMps = 0.6f // Stationary threshold (~2.16 km/h)
    private val minMovementDeadbandMeters = 2.8f // Minimum displacement noise radius

    /**
     * Start requesting location updates if permissions are granted.
     * @return true if location listener was successfully registered, false if missing permissions or manager.
     */
    fun startTracking(): Boolean {
        if (isTracking) return true

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return false
        }

        val lm = locationManager ?: return false
        isTracking = true

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    this
                )
            }

            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    this
                )
            }

            // Seed initial position anchor if high accuracy lastKnown is available
            val lastKnown = if (hasFine) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }

            if (lastKnown != null && lastKnown.hasAccuracy() && lastKnown.accuracy <= maxAcceptableAccuracyMeters && previousLocation == null) {
                previousLocation = lastKnown
                lastFixTimeMs = System.currentTimeMillis()
                _gpsFlow.value = GpsData(
                    latitude = lastKnown.latitude,
                    longitude = lastKnown.longitude,
                    altitude = lastKnown.altitude,
                    speedMps = 0f,
                    speedKmh = 0f,
                    totalDistanceMeters = 0f,
                    currentPaceMinPerKm = 0f,
                    accuracyMeters = lastKnown.accuracy,
                    isGpsFixed = true,
                    timestampMs = System.currentTimeMillis()
                )
            }

            return true
        } catch (e: SecurityException) {
            isTracking = false
            return false
        }
    }

    /**
     * Stop location updates.
     */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Reset accumulated distance and GPS metrics to strictly 0.
     */
    fun reset() {
        previousLocation = null
        totalDistanceMeters = 0f
        lastFixTimeMs = 0L
        smoothedSpeedMps = 0f
        _gpsFlow.value = GpsData()
    }

    override fun onLocationChanged(location: Location) {
        if (!isTracking) return

        val nowMs = System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999.0f

        // 1. Ignore fixes with poor accuracy (> 20m)
        val isValidAccuracy = accuracy in 0.001f..maxAcceptableAccuracyMeters
        if (!isValidAccuracy) {
            _gpsFlow.value = _gpsFlow.value.copy(
                accuracyMeters = accuracy,
                isGpsFixed = false,
                timestampMs = nowMs
            )
            return
        }

        // 2. Chipset hardware speed evaluation
        val hasChipSpeed = location.hasSpeed() && location.speed >= 0f
        val chipSpeedMps = if (hasChipSpeed) location.speed else 0f
        val isStationaryByChip = hasChipSpeed && chipSpeedMps < minStationarySpeedMps

        val prev = previousLocation
        if (prev == null) {
            // First valid fix establishes anchor baseline without accumulating distance
            previousLocation = location
            lastFixTimeMs = nowMs
            smoothedSpeedMps = if (hasChipSpeed && chipSpeedMps >= minStationarySpeedMps && chipSpeedMps <= maxRealisticRunningSpeedMps) {
                chipSpeedMps
            } else {
                0f
            }
        } else {
            val dist = prev.distanceTo(location)
            val timeDeltaSec = if (location.time > 0 && prev.time > 0 && location.time > prev.time) {
                (location.time - prev.time) / 1000.0f
            } else {
                (nowMs - lastFixTimeMs).coerceAtLeast(1000L) / 1000.0f
            }

            val impliedSpeed = if (timeDeltaSec > 0.05f) dist / timeDeltaSec else 0f

            // Deadband noise threshold: displacement must exceed the GPS noise radius
            val deadband = maxOf(minMovementDeadbandMeters, accuracy * 0.70f)

            // Teleport / speed spike check (> 12.5 m/s)
            val isTeleportSpike = impliedSpeed > maxRealisticRunningSpeedMps || (hasChipSpeed && chipSpeedMps > maxRealisticRunningSpeedMps)

            if (isTeleportSpike) {
                // Reject teleport / speed spike; re-anchor to new point without accumulating distance
                previousLocation = location
                lastFixTimeMs = nowMs
                smoothedSpeedMps = 0f
            } else if (isStationaryByChip || dist < deadband || impliedSpeed < 0.5f) {
                // Stationary or GPS jitter within noise deadband
                // Do NOT accumulate distance; decay smoothed speed to 0; keep previous anchor
                smoothedSpeedMps = 0f
            } else {
                // Genuine rhythmic displacement outside the noise deadband radius
                totalDistanceMeters += dist
                previousLocation = location
                lastFixTimeMs = nowMs

                val instantSpeed = if (hasChipSpeed && chipSpeedMps >= minStationarySpeedMps) chipSpeedMps else impliedSpeed
                val boundedSpeed = instantSpeed.coerceIn(0.5f, maxRealisticRunningSpeedMps)

                smoothedSpeedMps = if (smoothedSpeedMps < 0.5f) {
                    boundedSpeed
                } else {
                    0.35f * boundedSpeed + 0.65f * smoothedSpeedMps
                }
            }
        }

        val finalSpeedMps = if (smoothedSpeedMps >= 0.5f) smoothedSpeedMps else 0f
        val speedKmh = finalSpeedMps * 3.6f
        val paceMinPerKm = if (finalSpeedMps >= 0.5f) {
            (1000f / (finalSpeedMps * 60f)).coerceIn(2.0f, 30.0f)
        } else {
            0f
        }

        _gpsFlow.value = GpsData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speedMps = finalSpeedMps,
            speedKmh = speedKmh,
            totalDistanceMeters = totalDistanceMeters,
            currentPaceMinPerKm = paceMinPerKm,
            accuracyMeters = accuracy,
            isGpsFixed = true,
            timestampMs = nowMs
        )
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _gpsFlow.value = _gpsFlow.value.copy(isGpsFixed = false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        /**
         * Formats pace float (e.g. 5.5 min/km) to "5:30 /km".
         */
        fun formatPace(paceMinPerKm: Float): String {
            if (paceMinPerKm <= 0.1f || paceMinPerKm > 30f) return "--:-- /km"
            val minutes = paceMinPerKm.toInt()
            val seconds = ((paceMinPerKm - minutes) * 60).toInt()
            return String.format(Locale.US, "%d:%02d /km", minutes, seconds)
        }

        /**
         * Formats meters into human-readable distance (e.g. "1.24 km" or "450 m").
         */
        fun formatDistance(distanceMeters: Float): String {
            return if (distanceMeters >= 1000f) {
                String.format(Locale.US, "%.2f km", distanceMeters / 1000f)
            } else {
                String.format(Locale.US, "%.0f m", distanceMeters)
            }
        }
    }
}
