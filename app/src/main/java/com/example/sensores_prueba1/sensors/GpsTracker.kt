package com.example.sensores_prueba1.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
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
 * Tracks runner GPS position, calculates cumulative distance, instant speed, and running pace.
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

    // Minimum update interval and distance
    private val minTimeMs = 1000L // 1 second
    private val minDistanceMeters = 0.5f // 0.5 meters
    private val maxRealisticRunningSpeedMps = 15.0f // ~54 km/h upper bound to filter GPS teleport glitches

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

            // Seed with last known location if available
            val lastKnown = if (hasFine) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }

            lastKnown?.let { onLocationChanged(it) }

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
     * Reset accumulated distance and GPS metrics.
     */
    fun reset() {
        previousLocation = null
        totalDistanceMeters = 0f
        lastFixTimeMs = 0L
        _gpsFlow.value = GpsData()
    }

    override fun onLocationChanged(location: Location) {
        if (!isTracking) return

        val nowMs = System.currentTimeMillis()
        val accuracy = location.accuracy

        // Ignore fixes with very low accuracy (> 35m) for distance accumulation
        val isValidAccuracy = accuracy in 0.001f..35.0f

        var calculatedSpeedMps = if (location.hasSpeed() && location.speed >= 0f) {
            location.speed
        } else {
            0f
        }

        previousLocation?.let { prev ->
            val dist = prev.distanceTo(location)
            val timeDeltaSec = (location.time - prev.time).coerceAtLeast(1) / 1000.0f

            // Filter out GPS jumps & teleports
            val impliedSpeed = if (timeDeltaSec > 0) dist / timeDeltaSec else 0f
            if (dist > 0.5f && isValidAccuracy && impliedSpeed < maxRealisticRunningSpeedMps) {
                totalDistanceMeters += dist

                if (!location.hasSpeed() || calculatedSpeedMps == 0f) {
                    calculatedSpeedMps = impliedSpeed
                }
            }
        }

        previousLocation = location
        lastFixTimeMs = nowMs

        val speedKmh = calculatedSpeedMps * 3.6f
        val paceMinPerKm = if (calculatedSpeedMps > 0.4f) {
            (1000f / (calculatedSpeedMps * 60f))
        } else {
            0f
        }

        _gpsFlow.value = GpsData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speedMps = calculatedSpeedMps,
            speedKmh = speedKmh,
            totalDistanceMeters = totalDistanceMeters,
            currentPaceMinPerKm = paceMinPerKm,
            accuracyMeters = accuracy,
            isGpsFixed = isValidAccuracy,
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
         * Formats pace float (e.g. 5.5 min/km) to "5:30 min/km".
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
