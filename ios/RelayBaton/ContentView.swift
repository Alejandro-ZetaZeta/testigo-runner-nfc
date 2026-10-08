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
            SettingsSheet(batonManager: batonManager, onSave: {
                if batonManager.currentLegIndex > 1 {
                    resetRaceTimer()
                }
            })
                .preferredColorScheme(.dark)
                .sheetPresentationBackground()
        }
        .sheet(isPresented: $showingCalibration) {
            CalibrationSheet(motionManager: motionManager)
                .preferredColorScheme(.dark)
                .sheetPresentationBackground()
        }
        .sheet(isPresented: $showingSplits) {
            SplitsSheet(splits: locationManager.splits)
                .preferredColorScheme(.dark)
                .sheetPresentationBackground()
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

                Text(batonManager.isCarryingBaton ? "PORTANDO EL TESTIGO (ETAPA \(batonManager.currentLegIndex))" : "ESPERANDO TESTIGO (ETAPA \(batonManager.currentLegIndex))")
                    .font(.system(size: 13, weight: .heavy, design: .monospaced))
                    .foregroundColor(batonManager.isCarryingBaton ? .green : .orange)

                Spacer()

                Text(batonManager.isCarryingBaton ? "PORTADOR" : "EN ESPERA NFC")
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background((batonManager.isCarryingBaton ? Color.green : Color.orange).opacity(0.18))
                    .foregroundColor(batonManager.isCarryingBaton ? .green : .orange)
                    .cornerRadius(8)
            }

            Divider().background(Color.white.opacity(0.1))

            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("CORREDOR ASIGNADO")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.gray)
                    Text(batonManager.currentRunnerName)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.white)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("ID DE CARRERA")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.gray)
                    Text(batonManager.currentBaton?.raceId ?? "CARRERA-2026-ALFA")
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

    private var timerStatusColor: Color {
        if isTimerRunning {
            return .green
        } else if batonManager.isCarryingBaton {
            return .cyan
        } else {
            return .orange
        }
    }

    private var timerStatusText: String {
        if isTimerRunning {
            return "EN CARRERA"
        } else if batonManager.isCarryingBaton {
            return "LISTO"
        } else {
            return "EN ESPERA (STANDBY)"
        }
    }

    private var timerSubtext: String {
        if isTimerRunning {
            return "Cronómetro activo • Transmitiendo telemetría en vivo"
        } else if batonManager.isCarryingBaton {
            return "Presiona INICIAR ETAPA para arrancar la carrera con el testigo"
        } else {
            return "Bloqueado: La etapa arrancará automáticamente al recibir el testigo por NFC."
        }
    }

    private var timerCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 6) {
                Circle()
                    .fill(timerStatusColor)
                    .frame(width: 8, height: 8)
                Text(timerStatusText)
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(timerStatusColor)
            }

            Text(formattedElapsedTime)
                .font(.system(size: 46, weight: .black, design: .monospaced))
                .foregroundColor(.white)
                .shadow(color: timerStatusColor.opacity(0.3), radius: 8)

            Text(timerSubtext)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 10)

            HStack(spacing: 16) {
                if isTimerRunning {
                    Button(action: pauseRaceTimer) {
                        HStack {
                            Image(systemName: "pause.fill")
                            Text("PAUSAR ETAPA")
                        }
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundColor(.black)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 10)
                        .background(Color.yellow)
                        .cornerRadius(24)
                    }
                } else if batonManager.isCarryingBaton {
                    // Portador (Etapa 1): botón habilitado para inicio manual
                    Button(action: startRaceTimer) {
                        HStack {
                            Image(systemName: "play.fill")
                            Text("INICIAR ETAPA")
                        }
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundColor(.black)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 10)
                        .background(Color.green)
                        .cornerRadius(24)
                    }
                } else {
                    // Relevista en espera (Etapa 2+): BLOQUEADO en standby, no se permite arranque manual
                    HStack(spacing: 8) {
                        Image(systemName: "lock.fill")
                        Text("ESPERANDO TESTIGO NFC...")
                    }
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundColor(Color(red: 1.0, green: 0.88, blue: 0.51))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(Color.orange.opacity(0.18))
                    .cornerRadius(24)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.orange.opacity(0.4), lineWidth: 1)
                    )
                }

                if isTimerRunning || elapsedTime > 0 {
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
            // Banner de Periodo de Separación Obligatorio (5s)
            if batonManager.isHandoffInCooldown {
                HStack(spacing: 8) {
                    Image(systemName: "hourglass")
                        .foregroundColor(.yellow)
                    Text("TIEMPO DE SEPARACIÓN: \(batonManager.cooldownRemainingSeconds)s")
                        .font(.system(size: 11, weight: .black, design: .monospaced))
                        .foregroundColor(.yellow)
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 14)
                .background(Color.yellow.opacity(0.18))
                .cornerRadius(10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color.yellow.opacity(0.4), lineWidth: 1)
                )
                .transition(.scale.combined(with: .opacity))
            }

            // Banner de Detección de Gesto de Extensión
            if motionManager.handoffGestureDetected && !batonManager.isHandoffInCooldown {
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

            // Botón Principal de Acción NFC Automático
            Button(action: triggerNfcHandoff) {
                HStack(spacing: 12) {
                    Image(systemName: batonManager.isHandoffInCooldown ? "hourglass" : (batonManager.isCarryingBaton ? "wave.3.forward.circle.fill" : "wave.3.backward.circle.fill"))
                        .font(.system(size: 28))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(batonManager.isHandoffInCooldown
                             ? "SEPARACIÓN ACTIVA (\(batonManager.cooldownRemainingSeconds)s)"
                             : (batonManager.isCarryingBaton ? "PASAR TESTIGO (NFC RELEVO)" : "RECIBIR TESTIGO (ESCANEAR NFC)"))
                            .font(.system(size: 15, weight: .black, design: .rounded))
                        Text(batonManager.isHandoffInCooldown
                             ? "Sepárate de tu compañero para evitar re-captura"
                             : (batonManager.isCarryingBaton
                                ? "Acerca el teléfono del relevista para transferir"
                                : "Acerca el teléfono del portador • Inicio 100% automático"))
                            .font(.system(size: 10, weight: .medium))
                            .opacity(0.85)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(
                    batonManager.isHandoffInCooldown
                    ? LinearGradient(colors: [Color.gray.opacity(0.4), Color.gray.opacity(0.3)], startPoint: .leading, endPoint: .trailing)
                    : LinearGradient(
                        colors: batonManager.isCarryingBaton ? [Color.green, Color.mint] : [Color.orange, Color.yellow],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .foregroundColor(batonManager.isHandoffInCooldown ? Color(white: 0.7) : .black)
                .cornerRadius(18)
                .shadow(
                    color: (batonManager.isHandoffInCooldown ? Color.clear : (batonManager.isCarryingBaton ? Color.green : Color.orange)).opacity(0.4),
                    radius: 10,
                    y: 4
                )
            }
            .disabled(batonManager.isHandoffInCooldown)

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
        guard nfcManager.isNfcSupported else {
            alertMessage = "NFC requiere un dispositivo iPhone físico compatible."
            showAlert = true
            return
        }

        nfcManager.beginHandoffSession(
            isCarrying: batonManager.isCarryingBaton,
            baton: batonManager.currentBaton,
            onReceived: { incoming in
                handleBatonReceivedAutomatically(incoming)
            },
            onPassed: { passed in
                handleBatonPassedAutomatically(passed)
            },
            onError: { errorStr in
                batonManager.onHandshakeFailed(reason: errorStr)
            }
        )
    }

    private func handleBatonReceivedAutomatically(_ incomingBaton: BatonData) {
        batonManager.onBatonReceivedIn(incomingBaton: incomingBaton)
        triggerHapticSuccess()

        // El cronómetro y los sensores arrancan de forma 100% AUTOMÁTICA
        if !isTimerRunning {
            startRaceTimer()
        }
    }

    private func handleBatonPassedAutomatically(_ baton: BatonData) {
        batonManager.onBatonTransferredOut(info: "Traspaso NFC completado")
        triggerHapticSuccess()

        // El cronómetro y los sensores se pausan de forma 100% AUTOMÁTICA al entregar el testigo
        if isTimerRunning {
            pauseRaceTimer()
        }
    }

    private func triggerHapticSuccess() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
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

    private func startRaceTimer() {
        guard !isTimerRunning else { return }
        isTimerRunning = true
        motionManager.startTracking()
        locationManager.startTracking()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
            DispatchQueue.main.async {
                self.elapsedTime += 0.05
            }
        }
    }

    private func pauseRaceTimer() {
        guard isTimerRunning else { return }
        timer?.invalidate()
        timer = nil
        isTimerRunning = false
        motionManager.stopTracking()
        locationManager.stopTracking()
    }

    private func toggleRaceTimer() {
        if isTimerRunning {
            pauseRaceTimer()
        } else if batonManager.isCarryingBaton {
            startRaceTimer()
        }
    }

    private func resetRaceTimer() {
        pauseRaceTimer()
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
    var onSave: (() -> Void)? = nil
    @Environment(\.dismiss) var dismiss

    @State private var runnerName: String = ""
    @State private var teamId: String = ""
    @State private var raceId: String = ""
    @State private var legIndex: Int = 1

    private var isInitialRunner: Bool {
        legIndex == 1
    }

    var body: some View {
        NavigationView {
            ZStack {
                // Fondo Liquid Glass con transparencia aceptable (~94% de opacidad para gran legibilidad y contraste)
                Color(red: 0.05, green: 0.07, blue: 0.13).opacity(0.94)
                    .ignoresSafeArea()

                LinearGradient(
                    colors: [
                        Color(red: 0.08, green: 0.11, blue: 0.20).opacity(0.94),
                        Color(red: 0.04, green: 0.06, blue: 0.11).opacity(0.96)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        // Encabezado
                        HStack(spacing: 10) {
                            Image(systemName: "gearshape.2.fill")
                                .font(.title3)
                                .foregroundColor(.cyan)

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Sincronización Pre-Inicio")
                                    .font(.system(size: 18, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                                Text("Sincroniza el ID de Grupo y asigna tu Etapa.")
                                    .font(.system(size: 12))
                                    .foregroundColor(Color(white: 0.75))
                            }
                        }
                        .padding(.top, 8)

                        Divider().background(Color.white.opacity(0.2))

                        // Campo: ID de Carrera / Grupo
                        VStack(alignment: .leading, spacing: 6) {
                            Text("ID DE CARRERA / GRUPO")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.cyan)
                            TextField("ID de Carrera", text: $raceId)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                                .padding(12)
                                .background(Color.white.opacity(0.08))
                                .cornerRadius(10)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(Color.cyan.opacity(0.4), lineWidth: 1)
                                )
                        }

                        // Campo: ID del Equipo
                        VStack(alignment: .leading, spacing: 6) {
                            Text("ID DEL EQUIPO")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.cyan)
                            TextField("ID del Equipo", text: $teamId)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                                .padding(12)
                                .background(Color.white.opacity(0.08))
                                .cornerRadius(10)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(Color.cyan.opacity(0.4), lineWidth: 1)
                                )
                        }

                        // Campo: Nombre del Corredor
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NOMBRE DEL CORREDOR")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.cyan)
                            TextField("Nombre del Corredor", text: $runnerName)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                                .padding(12)
                                .background(Color.white.opacity(0.08))
                                .cornerRadius(10)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(Color.cyan.opacity(0.4), lineWidth: 1)
                                )
                        }

                        // Selector de Etapa (Leg Index)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("NÚMERO DE ETAPA / RELEVO")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.cyan)

                            HStack {
                                Text("Etapa asignada")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(.white)

                                Spacer()

                                Button(action: {
                                    if legIndex > 1 {
                                        legIndex -= 1
                                        if runnerName.hasPrefix("Corredor ") {
                                            runnerName = "Corredor \(legIndex)"
                                        }
                                    }
                                }) {
                                    Text("-")
                                        .font(.system(size: 20, weight: .bold))
                                        .frame(width: 36, height: 36)
                                        .background(Color.white.opacity(0.12))
                                        .foregroundColor(.white)
                                        .clipShape(Circle())
                                }

                                Text("\(legIndex)")
                                    .font(.system(size: 18, weight: .bold, design: .monospaced))
                                    .foregroundColor(.cyan)
                                    .frame(width: 36)

                                Button(action: {
                                    if legIndex < 12 {
                                        legIndex += 1
                                        if runnerName.hasPrefix("Corredor ") {
                                            runnerName = "Corredor \(legIndex)"
                                        }
                                    }
                                }) {
                                    Text("+")
                                        .font(.system(size: 20, weight: .bold))
                                        .frame(width: 36, height: 36)
                                        .background(Color.white.opacity(0.12))
                                        .foregroundColor(.white)
                                        .clipShape(Circle())
                                }
                            }
                            .padding(12)
                            .background(Color.white.opacity(0.06))
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.white.opacity(0.15), lineWidth: 1)
                            )
                        }

                        // Tarjeta Dinámica de Rol Asignado
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Image(systemName: isInitialRunner ? "figure.run" : "wave.3.forward.circle.fill")
                                    .foregroundColor(isInitialRunner ? .green : .orange)

                                Text(isInitialRunner ? "CORREDOR INICIAL (ETAPA 1)" : "RELEVISTA EN ESPERA (ETAPA \(legIndex))")
                                    .font(.system(size: 12, weight: .bold, design: .rounded))
                                    .foregroundColor(isInitialRunner ? Color(red: 0.4, green: 0.95, blue: 0.68) : Color(red: 1.0, green: 0.88, blue: 0.51))

                                Spacer()

                                Text(isInitialRunner ? "PORTADOR" : "EN ESPERA NFC")
                                    .font(.system(size: 10, weight: .bold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background((isInitialRunner ? Color.green : Color.orange).opacity(0.25))
                                    .foregroundColor(isInitialRunner ? .green : .orange)
                                    .cornerRadius(6)
                            }

                            Text(isInitialRunner ? "Inicia con el testigo en mano. Podrás pulsar 'INICIAR CARRERA' manualmente." : "Inicio bloqueado. El cronómetro iniciará automáticamente al recibir el testigo vía NFC del corredor previo.")
                                .font(.system(size: 11))
                                .foregroundColor(Color(white: 0.8))
                        }
                        .padding(14)
                        .background((isInitialRunner ? Color.green : Color.orange).opacity(0.15))
                        .cornerRadius(14)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke((isInitialRunner ? Color.green : Color.orange).opacity(0.5), lineWidth: 1.2)
                        )

                        // Botones de Acción
                        HStack(spacing: 12) {
                            Button(action: { dismiss() }) {
                                Text("Cancelar")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(Color(white: 0.75))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                                    .background(Color.white.opacity(0.08))
                                    .cornerRadius(20)
                            }

                            Button(action: saveSettings) {
                                Text("Sincronizar y Guardar")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(.black)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                                    .background(
                                        LinearGradient(
                                            colors: [Color.cyan, Color.mint],
                                            startPoint: .leading,
                                            endPoint: .trailing
                                        )
                                    )
                                    .cornerRadius(20)
                                    .shadow(color: Color.cyan.opacity(0.4), radius: 8, y: 2)
                            }
                        }
                        .padding(.top, 10)
                    }
                    .padding(20)
                }
            }
            .navigationBarHidden(true)
            .onAppear {
                runnerName = batonManager.currentRunnerName
                teamId = batonManager.currentBaton?.teamId ?? "EQUIPO-ALFA"
                raceId = batonManager.currentBaton?.raceId ?? "CARRERA-2026-ALFA"
                legIndex = batonManager.currentLegIndex
            }
        }
    }

    private func saveSettings() {
        batonManager.setupGroupRunner(
            raceId: raceId,
            teamId: teamId,
            runnerName: runnerName,
            legIndex: legIndex
        )
        onSave?()
        dismiss()
    }
}

struct CalibrationSheet: View {
    @ObservedObject var motionManager: MotionManager
    @Environment(\.dismiss) var dismiss
    @State private var statusMessage: String = "Coloca el dispositivo en reposo o sostenlo con firmeza durante 3 segundos."

    var body: some View {
        NavigationView {
            ZStack {
                Color(red: 0.05, green: 0.07, blue: 0.13).opacity(0.94)
                    .ignoresSafeArea()

                VStack(spacing: 24) {
                    Image(systemName: "scope")
                        .font(.system(size: 60))
                        .foregroundColor(.yellow)
                        .padding(.top, 20)

                    Text("Calibración de Sensores")
                        .font(.title2.bold())
                        .foregroundColor(.white)

                    Text(statusMessage)
                        .font(.subheadline)
                        .foregroundColor(Color(white: 0.8))
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
            ZStack {
                Color(red: 0.05, green: 0.07, blue: 0.13).opacity(0.94)
                    .ignoresSafeArea()

                List(splits) { split in
                    HStack {
                        Text("Parcial #\(split.splitIndex)")
                            .font(.headline)
                            .foregroundColor(.white)
                        Spacer()
                        VStack(alignment: .trailing) {
                            Text(String(format: "%.0f m en %.1fs", split.splitDistanceMeters, split.timeInterval))
                                .font(.subheadline)
                                .foregroundColor(Color(white: 0.85))
                            Text("Ritmo: \(split.paceString)")
                                .font(.caption)
                                .foregroundColor(.cyan)
                        }
                    }
                    .listRowBackground(Color.white.opacity(0.06))
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("Tiempos Parciales")
            .navigationBarItems(trailing: Button("Listo") { dismiss() })
        }
    }
}

// MARK: - Extensiones de Apariencia Liquid Glass
extension View {
    @ViewBuilder
    func sheetPresentationBackground() -> some View {
        if #available(iOS 16.4, *) {
            self.presentationBackground {
                Color(red: 0.05, green: 0.07, blue: 0.13).opacity(0.94)
            }
        } else {
            self
        }
    }
}
