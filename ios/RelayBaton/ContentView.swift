import SwiftUI
import CoreLocation

struct ContentView: View {
    @StateObject private var batonManager = BatonManager.shared
    @StateObject private var nfcManager = NFCReaderManager()
    @StateObject private var motionManager = MotionManager()
    @StateObject private var locationManager = LocationManager()

    // Estado del Cronómetro de Carrera
    @State private var isTimerRunning: Bool = false
    @State private var elapsedTime: TimeInterval = 0.0
    @State private var timer: Timer? = nil

    // Hojas Modales y Alertas
    @State private var showingSettings: Bool = false
    @State private var showingCalibration: Bool = false
    @State private var showingSplits: Bool = false
    @State private var alertMessage: String = ""
    @State private var showAlert: Bool = false

    var body: some View {
        ZStack {
            // Fondo Gradiente Oscuro Atlético
            LinearGradient(
                colors: [Color(red: 0.07, green: 0.09, blue: 0.15), Color(red: 0.02, green: 0.03, blue: 0.06)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 20) {
                    // 1. Barra de Encabezado Superior
                    headerView

                    // 2. Tarjeta de Estado del Testigo
                    batonStatusCard

                    // 3. HUD Cronómetro de Carrera
                    timerCard

                    // 4. Cuadrícula de Métricas (Distancia, Ritmo, Cadencia, Velocidad)
                    metricsGrid

                    // 5. Zona de Acción para Traspaso NFC
                    handoffActionZone

                    // 6. Tarjeta de Telemetría y Sensores
                    telemetryCard

                    // 7. Historial Reciente de Traspasos
                    if !batonManager.handoffHistory.isEmpty {
                        historySection
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
        }
        .sheet(isPresented: $showingSettings) {
            SettingsSheet(batonManager: batonManager)
        }
        .sheet(isPresented: $showingCalibration) {
            CalibrationSheet(motionManager: motionManager)
        }
        .sheet(isPresented: $showingSplits) {
            SplitsSheet(splits: locationManager.splits)
        }
        .alert(isPresented: $showAlert) {
            Alert(title: Text("Testigo de Relevos"), message: Text(alertMessage), dismissButton: .default(Text("Aceptar")))
        }
        .onAppear {
            setupHandoffHandlers()
        }
    }

    // MARK: - Subvistas

    private var headerView: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("TESTIGO DE RELEVOS ⚡")
                    .font(.system(size: 19, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                Text("TELEMETRÍA NFC Y SENSORES")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(.cyan.opacity(0.8))
            }

            Spacer()

            // Insignia de Etapa
            HStack(spacing: 4) {
                Image(systemName: "flag.checkered")
                    .font(.caption2)
                Text("ETAPA \(batonManager.currentLegIndex)")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.cyan.opacity(0.2))
            .foregroundColor(.cyan)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.cyan.opacity(0.4), lineWidth: 1)
            )

            // Botón de Calibración
            Button(action: { showingCalibration = true }) {
                Image(systemName: "scope")
                    .foregroundColor(motionManager.isCalibrated ? .cyan : .yellow)
                    .padding(8)
                    .background(Color.white.opacity(0.08))
                    .clipShape(Circle())
            }

            // Botón de Ajustes
            Button(action: { showingSettings = true }) {
                Image(systemName: "gearshape.fill")
                    .foregroundColor(.gray)
                    .padding(8)
                    .background(Color.white.opacity(0.08))
                    .clipShape(Circle())
            }
        }
        .padding(.top, 8)
    }

    private var batonStatusCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Circle()
                    .fill(batonManager.isCarryingBaton ? Color.green : Color.orange)
                    .frame(width: 12, height: 12)
                    .shadow(color: (batonManager.isCarryingBaton ? Color.green : Color.orange).opacity(0.8), radius: 6)

                Text(batonManager.isCarryingBaton ? "TESTIGO EN MANO" : "ESPERANDO EL TRASPASO")
                    .font(.system(size: 13, weight: .heavy, design: .monospaced))
                    .foregroundColor(batonManager.isCarryingBaton ? .green : .orange)

                Spacer()

                Text(batonManager.currentBaton?.teamId ?? "EQUIPO-ALFA")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.white.opacity(0.1))
                    .foregroundColor(.white)
                    .cornerRadius(8)
            }

            Divider().background(Color.white.opacity(0.1))

            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("CORREDOR ACTUAL")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.gray)
                    Text(batonManager.currentRunnerName)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.white)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("TOKEN DE FIRMA")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.gray)
                    Text(batonManager.currentBaton?.signatureToken ?? "NINGUNO")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundColor(.cyan)
                }
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.white.opacity(0.05))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(
                            (batonManager.isCarryingBaton ? Color.green : Color.orange).opacity(0.3),
                            lineWidth: 1.5
                        )
        )
        )
    }

    private var timerCard: some View {
        VStack(spacing: 12) {
            Text(formattedElapsedTime)
                .font(.system(size: 46, weight: .black, design: .monospaced))
                .foregroundColor(.white)
                .shadow(color: .cyan.opacity(0.3), radius: 8)

            HStack(spacing: 16) {
                Button(action: toggleRaceTimer) {
                    HStack {
                        Image(systemName: isTimerRunning ? "pause.fill" : "play.fill")
                        Text(isTimerRunning ? "PAUSAR" : "INICIAR CARRERA")
                    }
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundColor(.black)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 10)
                    .background(isTimerRunning ? Color.yellow : Color.green)
                    .cornerRadius(24)
                }

                Button(action: resetRaceTimer) {
                    HStack {
                        Image(systemName: "arrow.counterclockwise")
                        Text("REINICIAR")
                    }
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.white.opacity(0.12))
                    .cornerRadius(24)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.white.opacity(0.04))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.1), lineWidth: 1))
        )
    }

    private var metricsGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            MetricCard(
                title: "DISTANCIA",
                value: String(format: "%.1f m", locationManager.totalDistanceMeters),
                icon: "figure.run",
                tintColor: .cyan
            )

            MetricCard(
                title: "RITMO",
                value: locationManager.currentPaceFormatted,
                icon: "speedometer",
                tintColor: .mint
            )

            MetricCard(
                title: "CADENCIA",
                value: "\(Int(motionManager.cadence)) PPM",
                icon: "shoeprints.fill",
                tintColor: .orange
            )

            MetricCard(
                title: "VELOCIDAD",
                value: String(format: "%.1f km/h", locationManager.currentSpeedKmh),
                icon: "bolt.fill",
                tintColor: .purple
            )
        }
    }

    private var handoffActionZone: some View {
        VStack(spacing: 14) {
            // Banner de Detección de Gesto de Extensión
            if motionManager.handoffGestureDetected {
                HStack {
                    Image(systemName: "hand.raised.fill")
                    Text("¡GESTO DE EXTENSIÓN DETECTADO - LISTO PARA TOCAR!")
                        .font(.system(size: 11, weight: .black))
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 14)
                .background(Color.purple)
                .foregroundColor(.white)
                .cornerRadius(10)
                .transition(.scale.combined(with: .opacity))
            }

            // Botón Principal de Acción NFC
            Button(action: triggerNfcHandoff) {
                HStack(spacing: 12) {
                    Image(systemName: "wave.3.forward.circle.fill")
                        .font(.system(size: 28))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(batonManager.isCarryingBaton ? "PASAR TESTIGO (TOCAR / RELEVO)" : "RECIBIR TESTIGO (TOCAR / RELEVO)")
                            .font(.system(size: 15, weight: .black, design: .rounded))
                        Text(nfcManager.isNfcSupported ? "AID ISO 7816: F072656C61793031" : "Traspaso Directo / Tap (Modo Sideload)")
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .opacity(0.8)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(
                    LinearGradient(
                        colors: batonManager.isCarryingBaton ? [Color.green, Color.mint] : [Color.blue, Color.cyan],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .foregroundColor(.black)
                .cornerRadius(18)
                .shadow(
                    color: (batonManager.isCarryingBaton ? Color.green : Color.cyan).opacity(0.4),
                    radius: 10,
                    y: 4
                )
            }
            // Botón Manual / Simulado de Traspaso (Útil para pruebas sin certificado de pago)
            Button(action: triggerManualHandoff) {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                    Text("PASAR TESTIGO / AVANZAR ETAPA (MANUAL)")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Color.yellow.opacity(0.12))
                .foregroundColor(.yellow)
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.yellow.opacity(0.35), lineWidth: 1)
                )
            }

            // Mensaje de Estado
            Text(batonManager.statusMessage)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 18)
                .fill(Color.white.opacity(0.04))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.white.opacity(0.12), lineWidth: 1))
        )
    }

    private var telemetryCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "sensor.tag.radiowaves.forward.fill")
                    .foregroundColor(.cyan)
                Text("TELEMETRÍA DE SENSORES")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.gray)
                Spacer()
                Text(motionManager.motionStatus)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(.cyan)
            }

            Divider().background(Color.white.opacity(0.1))

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("ACELERACIÓN")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(.gray)
                    Text(String(format: "%.2f G", motionManager.accelerationMagnitude))
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }

                Spacer()

                VStack(alignment: .leading, spacing: 2) {
                    Text("PASOS TOTALES")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(.gray)
                    Text("\(motionManager.steps)")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                }

                Spacer()

                Button(action: { showingSplits = true }) {
                    HStack(spacing: 4) {
                        Image(systemName: "list.bullet")
                        Text("PARCIALES (\(locationManager.splits.count))")
                    }
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.cyan)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.cyan.opacity(0.15))
                    .cornerRadius(8)
                }
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(Color.white.opacity(0.03))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.08), lineWidth: 1))
        )
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("HISTORIAL DE TRASPASOS")
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(.gray)

            ForEach(batonManager.handoffHistory) { event in
                HStack {
                    Circle()
                        .fill(eventColor(event))
                        .frame(width: 8, height: 8)

                    Text(event.displayTitle)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.white)

                    Spacer()

                    Text(timeString(from: event))
                        .font(.system(size: 11, weight: .regular, design: .monospaced))
                        .foregroundColor(.gray)
                }
                .padding(10)
                .background(Color.white.opacity(0.03))
                .cornerRadius(8)
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(Color.white.opacity(0.02))
        )
    }

    // MARK: - Lógica y Acciones

    private func triggerNfcHandoff() {
        if nfcManager.isNfcSupported {
            nfcManager.beginHandoffSession(
                isCarrying: batonManager.isCarryingBaton,
                baton: batonManager.currentBaton,
                onReceived: { incoming in
                    batonManager.onBatonReceivedIn(incomingBaton: incoming)
                },
                onPassed: { passed in
                    batonManager.onBatonTransferredOut()
                },
                onError: { errorStr in
                    batonManager.onHandshakeFailed(reason: errorStr)
                }
            )
        } else {
            // Sideload con cuenta gratuita: ejecutar traspaso directo de inmediato
            triggerManualHandoff()
        }
    }

    private func triggerManualHandoff() {
        if batonManager.isCarryingBaton {
            batonManager.onBatonTransferredOut(info: "Traspaso manual")
            triggerHapticClick()
        } else {
            batonManager.prepareNextLeg()
            triggerHapticClick()
        }
    }

    private func triggerHapticClick() {
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.prepare()
        generator.impactOccurred()
    }

    private func setupHandoffHandlers() {
        motionManager.onHandoffGesture = {
            // Callback opcional de gesto
        }
    }

    private func toggleRaceTimer() {
        if isTimerRunning {
            timer?.invalidate()
            timer = nil
            isTimerRunning = false
            motionManager.stopTracking()
            locationManager.stopTracking()
        } else {
            isTimerRunning = true
            motionManager.startTracking()
            locationManager.startTracking()
            timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
                DispatchQueue.main.async {
                    self.elapsedTime += 0.05
                }
            }
        }
    }

    private func resetRaceTimer() {
        timer?.invalidate()
        timer = nil
        isTimerRunning = false
        elapsedTime = 0.0
        motionManager.resetMetrics()
        locationManager.resetMetrics()
    }

    private var formattedElapsedTime: String {
        let totalSeconds = Int(elapsedTime)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        let tenths = Int((elapsedTime.truncatingRemainder(dividingBy: 1)) * 100)
        return String(format: "%02d:%02d.%02d", minutes, seconds, tenths)
    }

    private func eventColor(_ event: HandoffEvent) -> Color {
        switch event {
        case .batonSent: return .blue
        case .batonReceived: return .green
        case .handshakeError: return .red
        }
    }

    private func timeString(from event: HandoffEvent) -> String {
        let date: Date
        switch event {
        case .batonSent(_, let d): date = d
        case .batonReceived(_, let d): date = d
        case .handshakeError(_, let d): date = d
        }
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: date)
    }
}

// MARK: - Componentes Auxiliares

struct MetricCard: View {
    let title: String
    let value: String
    let icon: String
    let tintColor: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundColor(tintColor)
                Text(title)
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .foregroundColor(.gray)
            }

            Text(value)
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundColor(.white)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(Color.white.opacity(0.04))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(tintColor.opacity(0.2), lineWidth: 1))
        )
    }
}

struct SettingsSheet: View {
    @ObservedObject var batonManager: BatonManager
    @Environment(\.dismiss) var dismiss

    @State private var runnerName: String = ""
    @State private var teamId: String = ""
    @State private var raceId: String = ""
    @State private var legIndex: Int = 1
    @State private var isCarrying: Bool = true

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Detalles del Corredor")) {
                    TextField("Nombre del Corredor", text: $runnerName)
                    TextField("ID del Equipo", text: $teamId)
                    TextField("ID de Carrera", text: $raceId)
                    Stepper("Número de Etapa: \(legIndex)", value: $legIndex, in: 1...10)
                    Toggle("Portando el Testigo Inicialmente", isOn: $isCarrying)
                }

                Section {
                    Button("Guardar y Actualizar Testigo") {
                        batonManager.currentRunnerName = runnerName
                        batonManager.currentLegIndex = legIndex
                        let baton = BatonData(
                            raceId: raceId,
                            teamId: teamId,
                            legIndex: legIndex,
                            runnerName: runnerName,
                            signatureToken: "SIG-MANUAL-\(legIndex)"
                        )
                        batonManager.setBaton(baton, carrying: isCarrying)
                        dismiss()
                    }
                    .font(.headline)
                    .foregroundColor(.cyan)
                }
            }
            .navigationTitle("Configuración de Carrera")
            .navigationBarItems(trailing: Button("Listo") { dismiss() })
            .onAppear {
                runnerName = batonManager.currentRunnerName
                teamId = batonManager.currentBaton?.teamId ?? "EQUIPO-ALFA"
                raceId = batonManager.currentBaton?.raceId ?? "CARRERA-2026-ALFA"
                legIndex = batonManager.currentLegIndex
                isCarrying = batonManager.isCarryingBaton
            }
        }
    }
}

struct CalibrationSheet: View {
    @ObservedObject var motionManager: MotionManager
    @Environment(\.dismiss) var dismiss
    @State private var statusMessage: String = "Coloca el dispositivo en reposo o sostenlo con firmeza durante 3 segundos."

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Image(systemName: "scope")
                    .font(.system(size: 60))
                    .foregroundColor(.yellow)
                    .padding(.top, 20)

                Text("Calibración de Sensores")
                    .font(.title2.bold())

                Text(statusMessage)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                if motionManager.isCalibrating {
                    ProgressView(value: motionManager.calibrationProgress, total: 1.0)
                        .progressViewStyle(LinearProgressViewStyle(tint: .yellow))
                        .padding(.horizontal, 32)
                }

                Spacer()

                Button(action: {
                    statusMessage = "Calibrando punto cero y ruido de sensores..."
                    motionManager.calibrateSensors { success in
                        statusMessage = "✅ ¡Sensores calibrados exitosamente!"
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                            dismiss()
                        }
                    }
                }) {
                    Text(motionManager.isCalibrating ? "Calibrando..." : "Iniciar Calibración")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.yellow)
                        .foregroundColor(.black)
                        .cornerRadius(12)
                }
                .disabled(motionManager.isCalibrating)
                .padding(.horizontal, 24)
                .padding(.bottom, 20)
            }
            .navigationTitle("Calibrar")
            .navigationBarItems(trailing: Button("Cerrar") { dismiss() })
        }
    }
}

struct SplitsSheet: View {
    let splits: [SplitRecord]
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationView {
            List(splits) { split in
                HStack {
                    Text("Parcial #\(split.splitIndex)")
                        .font(.headline)
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text(String(format: "%.0f m en %.1fs", split.splitDistanceMeters, split.timeInterval))
                            .font(.subheadline)
                        Text("Ritmo: \(split.paceString)")
                            .font(.caption)
                            .foregroundColor(.cyan)
                    }
                }
            }
            .navigationTitle("Tiempos Parciales")
            .navigationBarItems(trailing: Button("Listo") { dismiss() })
        }
    }
}
