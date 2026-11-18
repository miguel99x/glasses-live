import Foundation
import Security

/// Where the stream goes. The server URL is prefilled for the big sites;
/// Kick and others hand you a per-account server URL, so use Custom.
enum StreamPlatform: String, CaseIterable, Identifiable {
    case twitch, youtube, facebook, custom

    var id: String { rawValue }

    var label: String {
        switch self {
        case .twitch: return "Twitch"
        case .youtube: return "YouTube"
        case .facebook: return "Facebook"
        case .custom: return "Custom / Kick"
        }
    }

    var defaultServer: String {
        switch self {
        case .twitch: return "rtmp://live.twitch.tv/app"
        case .youtube: return "rtmp://a.rtmp.youtube.com/live2"
        case .facebook: return "rtmps://live-api-s.facebook.com:443/rtmp/"
        case .custom: return ""
        }
    }
}

enum Quality: String, CaseIterable, Identifiable {
    case high, medium, low
    var id: String { rawValue }
    var label: String {
        switch self {
        case .high: return "720p"
        case .medium: return "504p"
        case .low: return "360p"
        }
    }
    /// Portrait size the glasses deliver.
    var sourceSize: CGSize {
        switch self {
        case .high: return CGSize(width: 720, height: 1280)
        case .medium: return CGSize(width: 504, height: 896)
        case .low: return CGSize(width: 360, height: 640)
        }
    }
    var bitRate: Int {
        switch self {
        case .high: return 2_500_000
        case .medium: return 1_500_000
        case .low: return 800_000
        }
    }
}

enum StreamLayout: String, CaseIterable, Identifiable {
    case vertical, horizontal
    var id: String { rawValue }
    var label: String { self == .vertical ? "Vertical 9:16" : "Horizontal 16:9" }
}

enum AudioSource: String, CaseIterable, Identifiable {
    case glasses, phone, none
    var id: String { rawValue }
    var label: String {
        switch self {
        case .glasses: return "Glasses mic"
        case .phone: return "Phone mic"
        case .none: return "No audio"
        }
    }
}

/// Persisted user settings. The stream key lives in the Keychain, never UserDefaults.
@Observable
final class StreamSettings {
    var platform: StreamPlatform
    var server: String
    var quality: Quality
    var frameRate: Int
    var layout: StreamLayout
    var audioSource: AudioSource
    var streamKey: String

    @ObservationIgnored private let defaults = UserDefaults.standard

    init() {
        let d = UserDefaults.standard
        let platform = StreamPlatform(rawValue: d.string(forKey: "platform") ?? "") ?? .twitch
        self.platform = platform
        self.server = d.string(forKey: "server") ?? platform.defaultServer
        self.quality = Quality(rawValue: d.string(forKey: "quality") ?? "") ?? .medium
        let fps = d.integer(forKey: "frameRate")
        self.frameRate = [15, 24, 30].contains(fps) ? fps : 24
        self.layout = StreamLayout(rawValue: d.string(forKey: "layout") ?? "") ?? .vertical
        self.audioSource = AudioSource(rawValue: d.string(forKey: "audioSource") ?? "") ?? .glasses
        self.streamKey = Keychain.load(account: "streamKey") ?? ""
    }

    /// Called when going live and when the app leaves the foreground.
    func save() {
        Keychain.save(streamKey, account: "streamKey")
        defaults.set(platform.rawValue, forKey: "platform")
        defaults.set(server, forKey: "server")
        defaults.set(quality.rawValue, forKey: "quality")
        defaults.set(frameRate, forKey: "frameRate")
        defaults.set(layout.rawValue, forKey: "layout")
        defaults.set(audioSource.rawValue, forKey: "audioSource")
    }

    func selectPlatform(_ newValue: StreamPlatform) {
        platform = newValue
        if newValue != .custom { server = newValue.defaultServer }
    }

    var isReadyToStream: Bool {
        !server.trimmingCharacters(in: .whitespaces).isEmpty
            && !streamKey.trimmingCharacters(in: .whitespaces).isEmpty
    }
}

/// Minimal Keychain wrapper for the stream key.
enum Keychain {
    private static let service = "GlassesLive"

    static func save(_ value: String, account: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty else { return }
        var item = base
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    static func load(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
