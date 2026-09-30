import Foundation
import CoreMotion
import Combine
import UIKit

/// Gestiona los sensores de CoreMotion (CMMotionManager y CMPedometer) para el seguimiento de cadencia
/// y el reconocimiento del gesto de traspaso del testigo durante la carrera de relevos.
class MotionManager: ObservableObject {
    private let motionManager = CMMotionManager()
    private let pedometer = CMPedometer()

    @Published var steps: Int = 0
    @Published var cadence: Double = 0.0 // Pasos Por Minuto (PPM)
    @Published var accelerationMagnitude: Double = 0.0
    @Published var handoffGestureDetected: Bool = false
    @Published var isTracking: Bool = false
    @Published var motionStatus: String = "Inmóvil"

    // Umbrales de detección de gestos
    private let handoffThreshold: Double = 2.2 // Umbral de fuerza G para extensión hacia adelante
    private var lastGestureTime: Date = Date.distantPast
    private var sessionStartDate: Date?
    private var stepCountOffset: Int = 0

    // Respaldo de cadencia basado en acelerómetro
    private var accelReadings: [Double] = []
    private var lastPeakTime: Date?
    private var stepIntervals: [TimeInterval] = []

    var onHandoffGesture: (() -> Void)?

    init() {}

    /// Inicia el seguimiento de pasos, cadencia y gestos de traspaso.
    func startTracking() {
        guard !isTracking else { return }
        isTracking = true
        sessionStartDate = Date()
        handoffGestureDetected = false

        startPedometerUpdates()
        startAccelerometerUpdates()
    }

    /// Detiene el seguimiento de los sensores.
    func stopTracking() {
        isTracking = false
        if CMPedometer.isStepCountingAvailable() {
            pedometer.stopUpdates()
        }
        if motionManager.isDeviceMotionAvailable {
            motionManager.stopDeviceMotionUpdates()
        }
        if motionManager.isAccelerometerAvailable {
            motionManager.stopAccelerometerUpdates()
        }
        cadence = 0.0
        motionStatus = "Detenido"
    }

    /// Reinicia todas las métricas acumuladas.
    func resetMetrics() {
        steps = 0
        cadence = 0.0
        accelerationMagnitude = 0.0
        handoffGestureDetected = false
        sessionStartDate = Date()
        stepIntervals.removeAll()
        accelReadings.removeAll()
    }

    // MARK: - CoreMotion Podómetro

    private func startPedometerUpdates() {
        if CMPedometer.isStepCountingAvailable() {
            pedometer.startUpdates(from: Date()) { [weak self] (data: CMPedometerData?, error: Error?) in
                guard let self = self, let data = data, error == nil else { return }

                DispatchQueue.main.async {
                    self.steps = data.numberOfSteps.intValue

                    if let currentCadence = data.currentCadence?.doubleValue {
                        self.cadence = currentCadence * 60.0
                    }

                    if self.cadence > 160 {
                        self.motionStatus = "En Sprint (\(Int(self.cadence)) PPM)"
                    } else if self.cadence > 120 {
                        self.motionStatus = "Corriendo (\(Int(self.cadence)) PPM)"
                    } else if self.cadence > 60 {
                        self.motionStatus = "Trotando (\(Int(self.cadence)) PPM)"
                    } else {
                        self.motionStatus = "Caminando"
                    }
                }
            }
        }
    }

    // MARK: - Acelerómetro y Detección de Gesto

    private func startAccelerometerUpdates() {
        guard motionManager.isDeviceMotionAvailable else {
            if motionManager.isAccelerometerAvailable {
                motionManager.accelerometerUpdateInterval = 0.05 // 20 Hz
                motionManager.startAccelerometerUpdates(to: .main) { [weak self] (data, error) in
                    guard let self = self, let data = data else { return }
                    let mag = sqrt(pow(data.acceleration.x, 2) + pow(data.acceleration.y, 2) + pow(data.acceleration.z, 2))
                    self.processAcceleration(magnitude: mag, userY: data.acceleration.y)
                }
            }
            return
        }

        motionManager.deviceMotionUpdateInterval = 0.05 // 20 Hz
        motionManager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] (motion, error) in
            guard let self = self, let motion = motion else { return }

            let userAccel = motion.userAcceleration
            let totalMag = sqrt(pow(userAccel.x, 2) + pow(userAccel.y, 2) + pow(userAccel.z, 2))

            self.processAcceleration(magnitude: totalMag, userY: userAccel.y)
        }
    }

    private func processAcceleration(magnitude: Double, userY: Double) {
        self.accelerationMagnitude = magnitude

        let now = Date()
        if magnitude > handoffThreshold && now.timeIntervalSince(lastGestureTime) > 2.5 {
            lastGestureTime = now
            handoffGestureDetected = true
            triggerHapticGestureAlert()
            onHandoffGesture?()

            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                self?.handoffGestureDetected = false
            }
        }

        if !CMPedometer.isStepCountingAvailable() {
            detectFallbackStep(magnitude: magnitude, timestamp: now)
        }
    }

    private func detectFallbackStep(magnitude: Double, timestamp: Date) {
        if magnitude > 1.35 {
            if let lastTime = lastPeakTime {
                let delta = timestamp.timeIntervalSince(lastTime)
                if delta > 0.25 && delta < 1.2 {
                    steps += 1
                    let instantCadence = 60.0 / delta
                    stepIntervals.append(instantCadence)
                    if stepIntervals.count > 5 { stepIntervals.removeFirst() }
                    cadence = stepIntervals.reduce(0, +) / Double(stepIntervals.count)
                    lastPeakTime = timestamp
                }
            } else {
                lastPeakTime = timestamp
            }
        }
    }

    private func triggerHapticGestureAlert() {
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.prepare()
        generator.impactOccurred()
    }
}
