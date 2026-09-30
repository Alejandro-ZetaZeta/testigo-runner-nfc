# RelayBaton - iOS Companion App ⚡

RelayBaton is a companion iOS application designed for the Relay Race sensor tracking system. It interfaces natively with Android devices running Host Card Emulation (HCE) via ISO 7816-4 APDU commands over NFC, tracks runner motion and cadence using CoreMotion, and calculates live pace, distance, and splits using CoreLocation GPS.

---

## 📱 Architecture & Key Components

### 1. NFC Reader Manager (`NFCReaderManager.swift`)
- Built on Apple's `CoreNFC` framework with `NFCTagReaderSession` (`pollingOption: [.iso14443]`).
- Interacts with ISO 7816-4 tags and Android devices emulating the proprietary Relay AID:
  - **AID**: `F072656C61793031` ("relay01" with `F0` proprietary prefix)
- **APDU Protocol Handshake**:
  1. `00 A4 04 00 08 F0 72 65 6C 61 79 30 31 00` (SELECT AID) -> Expects `90 00`
  2. **Pass Baton**: `80 20 00 00 [Lc] [JSON Payload] 00` -> Expects `90 00`
  3. **Fetch Baton**: `80 10 00 00 00` -> Expects `[JSON Payload] + [90 00]`
- Performs haptic feedback (`UINotificationFeedbackGenerator`) upon successful handoff.

### 2. Motion Manager (`MotionManager.swift`)
- Leverages `CoreMotion` (`CMPedometer` and `CMMotionManager`).
- Tracks:
  - Real-time cadence (Steps Per Minute - SPM).
  - Step counter for the current leg.
  - **Handoff Extension Gesture Detection**: Analyzes 3-axis user acceleration vectors for forward arm extension spikes (>2.2G) indicating the runner is reaching out to hand off the baton.

### 3. Location Manager (`LocationManager.swift`)
- Leverages `CoreLocation` (`CLLocationManager`) configured for fitness/navigation accuracy.
- Computes:
  - Distance traveled (meters).
  - Instantaneous and average pace (min/km).
  - Speed (km/h).
  - Automatic split milestones (e.g. 100m splits for relay tracks).

### 4. SwiftUI Race HUD (`ContentView.swift`)
- High-contrast athletic dark mode HUD interface matching the Android experience.
- Features:
  - Baton ownership status badge (Green for carrying / Orange for waiting).
  - Stopwatch / Chronometer with millisecond precision.
  - Telemetry grid (Distance, Pace, Cadence, Speed).
  - Dynamic NFC Action button with visual feedback.
  - Handoff timeline event logger.
  - Settings sheet for runner configuration and split sheet inspection.

---

## 🛠 Building & CI/CD

### Remote Building with GitHub Actions
Pushing to `main` or triggering manually via `workflow_dispatch` will run `.github/workflows/ios-build.yml` on a `macos-14` runner:
- Compiles `ios/RelayBaton.xcodeproj` with `xcodebuild`.
- Generates and uploads `RelayBaton.app` and `RelayBaton.ipa` as downloadable artifacts.

### Local / CLI Building with `builder.exe` (MobAI iOS Builder)
Run from the repository root:
```powershell
builder.exe ios build
```
Or initialize and check signing:
```powershell
builder.exe signing setup
```

---

## 🔒 Permissions & Entitlements

Configured in `Info.plist` and `RelayBaton.entitlements`:
- `NFCReaderUsageDescription`
- `NSMotionUsageDescription`
- `NSLocationWhenInUseUsageDescription`
- `com.apple.developer.nfc.readersession.iso7816.select-identifiers` -> `["F072656C61793031"]`
- `com.apple.developer.nfc.readersession.formats` -> `["TAG", "PACE"]`
