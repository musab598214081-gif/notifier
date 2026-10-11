import AVFoundation
import UIKit

/// window.FamilyAudio: earpiece / loudspeaker routing for calls.
///   "earpiece"  every call starts here - private, like the phone's dialler
///   "speaker"   only when the person taps Speaker
///   "off"       the call is over: the phone goes back to normal
/// Tells the page where the sound now goes ("earpiece", "speaker", "headset",
/// "bluetooth") through window.familyAudioRoute when that changes.
final class AudioRoute {
    private var wanted = "off"
    private let session = AVAudioSession.sharedInstance()

    init() {
        NotificationCenter.default.addObserver(self, selector: #selector(routeChanged),
                                               name: AVAudioSession.routeChangeNotification, object: nil)
    }

    /// The page already has its answer (see the shim); the real route follows through familyAudioRoute.
    func set(_ route: String) {
        wanted = (route == "speaker" || route == "earpiece") ? route : "off"
        apply()
        WebScreen.js("window.familyAudioRoute && window.familyAudioRoute(\(jsQuote(whereNow())))")
    }

    func release() { wanted = "off"; apply() }

    private func apply() {
        if wanted == "off" {
            try? session.overrideOutputAudioPort(.none)
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            setProximity(false)
            return
        }
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try session.setActive(true)
            /* Headphones always win unless Speaker was tapped - as in a phone call. */
            try session.overrideOutputAudioPort(wanted == "speaker" ? .speaker : .none)
        } catch {}
        /* The screen goes dark while the phone is held to the ear. */
        setProximity(whereNow() == "earpiece")
    }

    func whereNow() -> String {
        if wanted == "off" { return "" }
        guard let p = session.currentRoute.outputs.first?.portType else { return wanted }
        switch p {
        case .builtInReceiver: return "earpiece"
        case .builtInSpeaker: return "speaker"
        case .headphones, .usbAudio: return "headset"
        case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE: return "bluetooth"
        default: return wanted
        }
    }

    @objc private func routeChanged() {
        guard wanted != "off" else { return }
        DispatchQueue.main.async {
            self.setProximity(self.whereNow() == "earpiece")
            WebScreen.js("window.familyAudioRoute && window.familyAudioRoute(\(jsQuote(self.whereNow())))")
        }
    }

    private func setProximity(_ on: Bool) {
        DispatchQueue.main.async { UIDevice.current.isProximityMonitoringEnabled = on }
    }
}
