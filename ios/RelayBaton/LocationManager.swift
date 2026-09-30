import Foundation
import CoreLocation
import Combine

struct SplitRecord: Identifiable {
    let id = UUID()
    let splitIndex: Int
    let splitDistanceMeters: Double
    let timeInterval: TimeInterval
    let paceString: String
}

/// Manages CoreLocation GPS tracking for distance, real-time pace, and race split calculations.
class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let locationManager = CLLocationManager()

    @Published var totalDistanceMeters: Double = 0.0
    @Published var currentSpeedKmh: Double = 0.0
    @Published var currentPaceFormatted: String = "--:--"
    @Published var averagePaceFormatted: String = "--:--"
    @Published var isTracking: Bool = false
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var splits: [SplitRecord] = []
    @Published var lastLocation: CLLocation?

    private var previousLocation: CLLocation?
    private var startTime: Date?
    private var splitDistanceInterval: Double = 100.0 // 100m split check (ideal for 4x100m or track relays)
    private var lastSplitDistance: Double = 0.0
    private var lastSplitTime: Date?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.distanceFilter = 2.0 // Update every 2 meters
        locationManager.activityType = .fitness
    }

    func requestPermission() {
        locationManager.requestWhenInUseAuthorization()
    }

    func startTracking() {
        guard !isTracking else { return }
        requestPermission()
        isTracking = true
        startTime = Date()
        lastSplitTime = Date()
        locationManager.startUpdatingLocation()
    }

    func stopTracking() {
        isTracking = false
        locationManager.stopUpdatingLocation()
        currentSpeedKmh = 0.0
        currentPaceFormatted = "--:--"
    }

    func resetMetrics() {
        totalDistanceMeters = 0.0
        currentSpeedKmh = 0.0
        currentPaceFormatted = "--:--"
        averagePaceFormatted = "--:--"
        splits.removeAll()
        previousLocation = nil
        lastLocation = nil
        lastSplitDistance = 0.0
        startTime = Date()
        lastSplitTime = Date()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        DispatchQueue.main.async {
            self.authorizationStatus = manager.authorizationStatus
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard isTracking, let newLocation = locations.last else { return }
        
        // Filter out inaccurate cached locations (> 20 meters horizontal accuracy)
        if newLocation.horizontalAccuracy < 0 || newLocation.horizontalAccuracy > 25 {
            return
        }

        DispatchQueue.main.async {
            self.lastLocation = newLocation

            if let prev = self.previousLocation {
                let deltaDistance = newLocation.distance(from: prev)
                if deltaDistance > 0.5 { // Ignore micro GPS jitter
                    self.totalDistanceMeters += deltaDistance
                    self.checkSplitMilestone()
                }
            }
            self.previousLocation = newLocation

            // Speed in m/s -> km/h
            if newLocation.speed > 0 {
                let speedKmh = newLocation.speed * 3.6
                self.currentSpeedKmh = speedKmh

                // Pace in seconds per km = 1000 / speed (m/s)
                let paceSecondsPerKm = 1000.0 / newLocation.speed
                self.currentPaceFormatted = self.formatPace(secondsPerKm: paceSecondsPerKm)
            } else {
                self.currentSpeedKmh = 0.0
            }

            // Average Pace
            if let start = self.startTime, self.totalDistanceMeters > 10 {
                let elapsed = Date().timeIntervalSince(start)
                let avgSpeedMps = self.totalDistanceMeters / elapsed
                let avgPaceSeconds = 1000.0 / avgSpeedMps
                self.averagePaceFormatted = self.formatPace(secondsPerKm: avgPaceSeconds)
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("LocationManager error: \(error.localizedDescription)")
    }

    private func checkSplitMilestone() {
        if totalDistanceMeters - lastSplitDistance >= splitDistanceInterval {
            let now = Date()
            let splitTimeInterval = now.timeIntervalSince(lastSplitTime ?? (startTime ?? now))
            let splitIndex = splits.count + 1
            let splitPaceSeconds = (splitTimeInterval / (splitDistanceInterval / 1000.0))

            let record = SplitRecord(
                splitIndex: splitIndex,
                splitDistanceMeters: totalDistanceMeters,
                timeInterval: splitTimeInterval,
                paceString: formatPace(secondsPerKm: splitPaceSeconds)
            )
            splits.append(record)
            lastSplitDistance = totalDistanceMeters
            lastSplitTime = now
        }
    }

    private func formatPace(secondsPerKm: Double) -> String {
        guard secondsPerKm > 0 && secondsPerKm < 3600 else { return "--:--" }
        let minutes = Int(secondsPerKm) / 60
        let seconds = Int(secondsPerKm) % 60
        return String(format: "%d:%02d /km", minutes, seconds)
    }
}
