import Foundation
import CoreNFC
import UIKit

/// Gestiona las sesiones de lectura de etiquetas ISO 7816-4 con CoreNFC para el traspaso del testigo.
///
/// Se comunica con el servicio HCE de Android (RelayBatonHceService)
/// u otro dispositivo compatible con ISO 7816-4 usando el AID F072656C61793031.
class NFCReaderManager: NSObject, ObservableObject, NFCTagReaderSessionDelegate {
    
    // AID del Testigo: "F072656C61793031"
    static let relayAidBytes: [UInt8] = [0xF0, 0x72, 0x65, 0x6C, 0x61, 0x79, 0x30, 0x31]
    static let relayAidHex = "F072656C61793031"

    // Constantes APDU
    static let CLA_PROPRIETARY: UInt8 = 0x80
    static let INS_GET_BATON: UInt8 = 0x10    // 80 10 00 00 - Solicitar testigo al corredor entrante
    static let INS_PASS_BATON: UInt8 = 0x20   // 80 20 00 00 - Enviar testigo al corredor en espera
    static let INS_PING: UInt8 = 0x01         // 80 01 00 00 - Ping de proximidad

    @Published var isScanning: Bool = false
    @Published var lastStatus: String = "Inactivo"
    @Published var lastError: String? = nil

    private var session: NFCTagReaderSession?
    private var isCarryingBaton: Bool = true
    private var outboundBaton: BatonData?

    // Callbacks
    var onBatonReceived: ((BatonData) -> Void)?
    var onBatonPassed: ((BatonData) -> Void)?
    var onError: ((String) -> Void)?

    override init() {
        super.init()
    }

    /// Comprueba si la lectura de etiquetas NFC está disponible en este dispositivo iOS.
    var isNfcSupported: Bool {
        NFCTagReaderSession.readingAvailable
    }

    /// Inicia una sesión NFC para PASAR el testigo actual o RECIBIR el testigo entrante.
    func beginHandoffSession(
        isCarrying: Bool,
        baton: BatonData?,
        onReceived: @escaping (BatonData) -> Void,
        onPassed: @escaping (BatonData) -> Void,
        onError: @escaping (String) -> Void
    ) {
        guard NFCTagReaderSession.readingAvailable else {
            onError("NFC no está disponible en este dispositivo iOS o simulador.")
            return
        }

        self.isCarryingBaton = isCarrying
        self.outboundBaton = baton
        self.onBatonReceived = onReceived
        self.onBatonPassed = onPassed
        self.onError = onError

        session = NFCTagReaderSession(
            pollingOption: [.iso14443],
            delegate: self,
            queue: DispatchQueue.global(qos: .userInitiated)
        )

        session?.alertMessage = isCarrying
            ? "🏃 Acerca el dispositivo de tu compañero para PASAR el testigo..."
            : "🏃 Acerca el dispositivo de tu compañero para RECIBIR el testigo..."
        
        DispatchQueue.main.async {
            self.isScanning = true
            self.lastStatus = "Buscando compañero de relevo..."
            self.lastError = nil
        }

        session?.begin()
    }

    // MARK: - NFCTagReaderSessionDelegate

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        DispatchQueue.main.async {
            self.lastStatus = "Campo NFC activo. Esperando contacto..."
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        DispatchQueue.main.async {
            self.isScanning = false
            let nfcError = error as? NFCReaderError
            if nfcError?.code != .readerSessionInvalidationErrorUserCanceled {
                self.lastError = error.localizedDescription
                self.lastStatus = "Sesión finalizada: \(error.localizedDescription)"
                self.onError?(error.localizedDescription)
            } else {
                self.lastStatus = "Sesión cancelada por el usuario."
            }
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let firstTag = tags.first else {
            session.restartPolling()
            return
        }

        guard case let .iso7816(iso7816Tag) = firstTag else {
            session.invalidate(errorMessage: "La etiqueta detectada no es compatible con ISO 7816.")
            return
        }

        session.connect(to: firstTag) { [weak self] (error: Error?) in
            guard let self = self else { return }

            if let error = error {
                session.invalidate(errorMessage: "Conexión fallida: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.onError?(error.localizedDescription)
                }
                return
            }

            self.performApduExchange(session: session, tag: iso7816Tag)
        }
    }

    // MARK: - Intercambio APDU ISO 7816

    private func performApduExchange(session: NFCTagReaderSession, tag: NFCISO7816Tag) {
        // Paso 1: SELECT AID APDU explícito (00 A4 04 00 08 F072656C61793031 00)
        let aidData = Data(Self.relayAidBytes)
        guard let selectApdu = NFCISO7816APDU(
            instructionClass: 0x00,
            instructionCode: 0xA4,
            p1Parameter: 0x04,
            p2Parameter: 0x00,
            data: aidData,
            expectedResponseBodyLength: -1
        ) else {
            session.invalidate(errorMessage: "Error al construir APDU SELECT AID.")
            return
        }

        tag.sendCommand(apdu: selectApdu) { [weak self] (responseData: Data, sw1: UInt8, sw2: UInt8, error: Error?) in
            guard let self = self else { return }

            if let error = error {
                session.invalidate(errorMessage: "SELECT AID falló: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.onError?(error.localizedDescription)
                }
                return
            }

            guard sw1 == 0x90 && sw2 == 0x00 else {
                let statusHex = String(format: "%02X%02X", sw1, sw2)
                session.invalidate(errorMessage: "AID de relevo rechazado (Estado: 0x\(statusHex))")
                DispatchQueue.main.async {
                    self.onError?("AID rechazado con estado 0x\(statusHex)")
                }
                return
            }

            // Paso 2: Ejecutar GET_BATON (80 10) o PASS_BATON (80 20)
            if self.isCarryingBaton {
                self.executePassBaton(session: session, tag: tag)
            } else {
                self.executeGetBaton(session: session, tag: tag)
            }
        }
    }

    /// Envía el testigo al corredor en espera (Comando: 80 20 00 00 [Lc] [Payload] 00).
    private func executePassBaton(session: NFCTagReaderSession, tag: NFCISO7816Tag) {
        guard let baton = outboundBaton,
              let payloadData = baton.toJsonString().data(using: .utf8) else {
            session.invalidate(errorMessage: "No hay datos del testigo disponibles para pasar.")
            return
        }

        guard let passApdu = NFCISO7816APDU(
            instructionClass: Self.CLA_PROPRIETARY,
            instructionCode: Self.INS_PASS_BATON,
            p1Parameter: 0x00,
            p2Parameter: 0x00,
            data: payloadData,
            expectedResponseBodyLength: -1
        ) else {
            session.invalidate(errorMessage: "Error al construir APDU PASS_BATON.")
            return
        }

        tag.sendCommand(apdu: passApdu) { [weak self] (responseData: Data, sw1: UInt8, sw2: UInt8, error: Error?) in
            guard let self = self else { return }

            if let error = error {
                session.invalidate(errorMessage: "Error en PASS_BATON: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.onError?(error.localizedDescription)
                }
                return
            }

            if sw1 == 0x90 && sw2 == 0x00 {
                DispatchQueue.main.async {
                    self.triggerHapticSuccess()
                    self.lastStatus = "¡Testigo entregado con éxito!"
                    self.onBatonPassed?(baton)
                }
                session.alertMessage = "✅ ¡Testigo entregado con éxito!"
                session.invalidate()
            } else {
                let statusHex = String(format: "%02X%02X", sw1, sw2)
                session.invalidate(errorMessage: "El dispositivo receptor rechazó el testigo (0x\(statusHex))")
                DispatchQueue.main.async {
                    self.onError?("Rechazo de entrega: 0x\(statusHex)")
                }
            }
        }
    }

    /// Obtiene el testigo del corredor entrante (Comando: 80 10 00 00 00).
    private func executeGetBaton(session: NFCTagReaderSession, tag: NFCISO7816Tag) {
        guard let getApdu = NFCISO7816APDU(
            instructionClass: Self.CLA_PROPRIETARY,
            instructionCode: Self.INS_GET_BATON,
            p1Parameter: 0x00,
            p2Parameter: 0x00,
            data: Data(),
            expectedResponseBodyLength: -1
        ) else {
            session.invalidate(errorMessage: "Error al construir APDU GET_BATON.")
            return
        }

        tag.sendCommand(apdu: getApdu) { [weak self] (responseData: Data, sw1: UInt8, sw2: UInt8, error: Error?) in
            guard let self = self else { return }

            if let error = error {
                session.invalidate(errorMessage: "Error en GET_BATON: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.onError?(error.localizedDescription)
                }
                return
            }

            if sw1 == 0x90 && sw2 == 0x00 {
                if let jsonString = String(data: responseData, encoding: .utf8),
                   let incomingBaton = BatonData.fromJsonString(jsonString) {
                    DispatchQueue.main.async {
                        self.triggerHapticSuccess()
                        self.lastStatus = "¡Testigo recibido! Etapa \(incomingBaton.legIndex + 1)"
                        self.onBatonReceived?(incomingBaton)
                    }
                    session.alertMessage = "✅ ¡Testigo recibido! ¡Corre, corre, corre!"
                    session.invalidate()
                } else {
                    session.invalidate(errorMessage: "JSON de testigo con formato incorrecto.")
                    DispatchQueue.main.async {
                        self.onError?("Error al analizar el payload JSON del testigo entrante.")
                    }
                }
            } else {
                let statusHex = String(format: "%02X%02X", sw1, sw2)
                session.invalidate(errorMessage: "No se pudo obtener el testigo (0x\(statusHex))")
                DispatchQueue.main.async {
                    self.onError?("GET_BATON falló: 0x\(statusHex)")
                }
            }
        }
    }

    private func triggerHapticSuccess() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
    }
}
