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

    func updateCarryingStatus(_ carrying: Bool) {
        isCarryingBaton = carrying
        statusMessage = carrying ? "Portando el Testigo - Listo para Correr / Traspasar" : "Esperando el Testigo Entrante"
    }

    func setBaton(_ baton: BatonData, carrying: Bool) {
        currentBaton = baton
        currentLegIndex = baton.legIndex
        isCarryingBaton = carrying
        statusMessage = carrying ? "Testigo activo (Etapa \(baton.legIndex))" : "Testigo entregado (Etapa \(baton.legIndex))"
    }

    func onBatonTransferredOut(info: String = "") {
        guard let baton = currentBaton else { return }
        isCarryingBaton = false
        statusMessage = "¡Testigo transferido al siguiente corredor!"
        let event = HandoffEvent.batonSent(baton: baton, timestamp: Date())
        handoffHistory.insert(event, at: 0)
    }

    func onBatonReceivedIn(incomingBaton: BatonData) {
        currentBaton = incomingBaton
        currentLegIndex = incomingBaton.legIndex + 1
        isCarryingBaton = true
        statusMessage = "¡Testigo recibido! ¡Corre la Etapa \(currentLegIndex)!"
        let event = HandoffEvent.batonReceived(baton: incomingBaton, timestamp: Date())
        handoffHistory.insert(event, at: 0)
    }

    func onHandshakeFailed(reason: String) {
        statusMessage = "Error de traspaso: \(reason)"
        let event = HandoffEvent.handshakeError(reason: reason, timestamp: Date())
        handoffHistory.insert(event, at: 0)
    }

    func prepareNextLeg() {
        guard var baton = currentBaton else { return }
        baton.legIndex += 1
        baton.runnerName = currentRunnerName
        baton.timestampMs = Int64(Date().timeIntervalSince1970 * 1000)
        baton.signatureToken = "SIG-ETAPA\(baton.legIndex)-\(UUID().uuidString.prefix(6))"
        currentBaton = baton
        currentLegIndex = baton.legIndex
        isCarryingBaton = true
        statusMessage = "Etapa \(baton.legIndex) preparada"
    }
}
