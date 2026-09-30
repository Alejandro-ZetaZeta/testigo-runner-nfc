import Foundation

/// Data model representing the digital baton payload exchanged over NFC.
/// Fully compatible with Android RelayBaton JSON payload.
struct BatonData: Codable, Equatable, Identifiable {
    var id: String { "\(raceId)-\(teamId)-leg\(legIndex)" }
    
    var raceId: String
    var teamId: String
    var legIndex: Int
    var runnerName: String
    var timestampMs: Int64
    var signatureToken: String

    init(
        raceId: String = "RACE-2026-ALPHA",
        teamId: String = "TEAM-ALPHA",
        legIndex: Int = 1,
        runnerName: String = "Runner 1 (iOS)",
        timestampMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        signatureToken: String = "SIG-INIT-001"
    ) {
        self.raceId = raceId
        self.teamId = teamId
        self.legIndex = legIndex
        self.runnerName = runnerName
        self.timestampMs = timestampMs
        self.signatureToken = signatureToken
    }

    func toJsonData() -> Data? {
        let encoder = JSONEncoder()
        return try? encoder.encode(self)
    }

    func toJsonString() -> String {
        guard let data = toJsonData(), let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }

    static func fromJsonString(_ json: String) -> BatonData? {
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(BatonData.self, from: data)
    }

    static func fromJsonData(_ data: Data) -> BatonData? {
        return try? JSONDecoder().decode(BatonData.self, from: data)
    }
}
