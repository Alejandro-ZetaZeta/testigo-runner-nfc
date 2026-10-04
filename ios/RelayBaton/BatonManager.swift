import Foundation
import Combine

enum HandoffEvent: Identifiable {
    case batonSent(baton: BatonData, timestamp: Date)
    case batonReceived(baton: BatonData, timestamp: Date)
    case handshakeError(reason: String, timestamp: Date)

    var id: String {
        switch self {
        case .batonSent(let b, let t):
            return "sent-\(b.legIndex)-\(t.timeIntervalSince1970)"
        case .batonReceived(let b, let t):
            return "recv-\(b.legIndex)-\(t.timeIntervalSince1970)"
        case .handshakeError(let r, let t):
            return "err-\(r)-\(t.timeIntervalSince1970)"
        }
    }

    var displayTitle: String {
        switch self {
        case .batonSent(let b, _):
            return "Testigo Entregado (Etapa \(b.legIndex))"
        case .batonReceived(let b, _):
            return "Testigo Recibido (Etapa \(b.legIndex))"
        case .handshakeError(let r, _):
            return "Error de Traspaso: \(r)"
        }
    }
}

/// Coordinador central del estado de la carrera de relevos en iOS.
@MainActor
class BatonManager: ObservableObject {
    @Published var currentBaton: BatonData? = BatonData(
        raceId: "CARRERA-2026-ALFA",
        teamId: "EQUIPO-ALFA",
        legIndex: 1,
        runnerName: "Corredor 1 (iOS)",
        signatureToken: "SIG-INIT-001"
    )
    @Published var isCarryingBaton: Bool = true
    @Published var handoffHistory: [HandoffEvent] = []
    @Published var currentRunnerName: String = "Corredor 1 (iOS)"
    @Published var currentLegIndex: Int = 1
    @Published var statusMessage: String = "Listo para la Carrera"

    // Instancia singleton
    static let shared = BatonManager()

    init() {}

    /// Sincronización Pre-Inicio del dispositivo en el grupo de relevos.
    /// La portación inicial se determina estrictamente por la etapa:
    /// - Etapa 1 (Corredor Inicial) -> isCarryingBaton = true
    /// - Etapas 2+ (Relevistas en Espera) -> isCarryingBaton = false (Modo Standby bloqueado)
    func setupGroupRunner(raceId: String, teamId: String, runnerName: String, legIndex: Int) {
        let isInitial = (legIndex == 1)
        let initialToken = isInitial
            ? "SIG-START-LEG1-\(Int(Date().timeIntervalSince1970) % 10000)"
            : "SIG-WAITING-LEG\(legIndex)"

        let cleanRunner = runnerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "Corredor \(legIndex)"
            : runnerName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanTeam = teamId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "EQUIPO-ALFA"
            : teamId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanRace = raceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "CARRERA-2026-ALFA"
            : raceId.trimmingCharacters(in: .whitespacesAndNewlines)

        let baton = BatonData(
            raceId: cleanRace,
            teamId: cleanTeam,
            legIndex: legIndex,
            runnerName: cleanRunner,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            signatureToken: initialToken
        )

        currentBaton = baton
        currentLegIndex = legIndex
        currentRunnerName = cleanRunner
        isCarryingBaton = isInitial
        statusMessage = isInitial
            ? "Portador Inicial (Etapa 1) - Listo para arrancar la carrera"
            : "Relevista en Espera (Etapa \(legIndex)) - En espera automática por NFC"
    }

    func updateCarryingStatus(_ carrying: Bool) {
        isCarryingBaton = carrying
        statusMessage = carrying ? "Portando el Testigo (Etapa \(currentLegIndex))" : "En espera de relevo NFC (Etapa \(currentLegIndex))"
    }

    func setBaton(_ baton: BatonData, carrying: Bool) {
        currentBaton = baton
        currentLegIndex = baton.legIndex
        isCarryingBaton = carrying
        statusMessage = carrying ? "Testigo activo (Etapa \(baton.legIndex))" : "En espera de relevo NFC (Etapa \(baton.legIndex))"
    }

    func onBatonTransferredOut(info: String = "") {
        guard let baton = currentBaton else { return }
        guard isCarryingBaton else { return } // Evitar eventos duplicados
        isCarryingBaton = false
        statusMessage = "🎉 ¡Traspaso completado! Testigo transferido a la Etapa \(baton.legIndex + 1)"
        let event = HandoffEvent.batonSent(baton: baton, timestamp: Date())
        handoffHistory.insert(event, at: 0)
    }

    func onBatonReceivedIn(incomingBaton: BatonData) {
        // 1. Validación estricta de ID de Carrera / Grupo
        if let current = currentBaton,
           !current.raceId.isEmpty,
           !incomingBaton.raceId.isEmpty,
           current.raceId.caseInsensitiveCompare(incomingBaton.raceId) != .orderedSame {
            onHandshakeFailed(reason: "Carrera incompatible: '\(incomingBaton.raceId)' (esperado: '\(current.raceId)')")
            return
        }

        // 2. Validación de equipo si ambos están presentes
        if let current = currentBaton,
           !current.teamId.isEmpty,
           !incomingBaton.teamId.isEmpty,
           current.teamId.caseInsensitiveCompare(incomingBaton.teamId) != .orderedSame {
            onHandshakeFailed(reason: "Equipo incompatible: '\(incomingBaton.teamId)' (esperado: '\(current.teamId)')")
            return
        }

        // 3. Si ya está portando el testigo para esta misma etapa o superior, ignorar duplicado
        if isCarryingBaton, let current = currentBaton, current.legIndex >= (incomingBaton.legIndex + 1) {
            return
        }

        let targetLeg = max(incomingBaton.legIndex + 1, currentLegIndex)
        let runner = currentRunnerName.isEmpty ? "Corredor \(targetLeg)" : currentRunnerName

        let activeBaton = BatonData(
            raceId: incomingBaton.raceId,
            teamId: incomingBaton.teamId,
            legIndex: targetLeg,
            runnerName: runner,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            signatureToken: "SIG-RECV-LEG\(targetLeg)-\(Int(Date().timeIntervalSince1970) % 10000)"
        )

        currentBaton = activeBaton
        currentLegIndex = targetLeg
        isCarryingBaton = true
        statusMessage = "🔥 ¡Testigo recibido! Etapa \(targetLeg) activa: ¡CORRE!"
        let event = HandoffEvent.batonReceived(baton: activeBaton, timestamp: Date())
        handoffHistory.insert(event, at: 0)
    }

    func onHandshakeFailed(reason: String) {
        statusMessage = "⚠️ Evento NFC: \(reason)"
        let event = HandoffEvent.handshakeError(reason: reason, timestamp: Date())
        handoffHistory.insert(event, at: 0)
    }
}
