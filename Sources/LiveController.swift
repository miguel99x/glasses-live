import AVFoundation
import MWDATCamera
import MWDATCore
import Observation
import SwiftUI
import UIKit

/// Drives the whole app:
///  1. register with Meta AI (one time)
///  2. start a DeviceSession + camera stream from the glasses (preview)
///  3. go live: push those frames, plus mic audio, to an RTMP server
@Observable
@MainActor
final class LiveController {
    // MARK: Published state
    var registrationState: RegistrationState
    var hasActiveDevice = false
    var sessionState: DeviceSessionState = .idle
    var streamState: StreamState = .stopped
    var previewImage: UIImage?
    var broadcastStatus: Broadcaster.Status = .idle
    var audioNote: String = ""
    var liveSince: Date?
    var showError = false
    var errorMessage = ""
    var showPermissionPrompt = false

    let settings = StreamSettings()

    var isRegistered: Bool { registrationState == .registered }
    var isCameraOn: Bool { streamState == .streaming || streamState == .paused }
    var isLive: Bool { broadcastStatus == .live }
    var isBusy: Bool {
        sessionState == .starting || sessionState == .stopping
            || streamState == .starting || streamState == .waitingForDevice || streamState == .stopping
            || broadcastStatus == .connecting
    }

    var statusLine: String {
        if !isRegistered { return "Not connected to Meta AI" }
        if !hasActiveDevice { return "Put on your glasses (and open the hinges)" }
        switch streamState {
        case .streaming: return isLive ? "LIVE" : "Camera on - ready to go live"
        case .paused: return "Paused by glasses"
        case .waitingForDevice: return "Waiting for glasses..."
        case .starting: return "Starting camera..."
        case .stopping: return "Stopping..."
        case .stopped: return "Glasses ready"
        }
    }

    // MARK: Private
    @ObservationIgnored private let wearables: WearablesInterface
    @ObservationIgnored private let deviceSelector: AutoDeviceSelector
    @ObservationIgnored private var deviceSession: DeviceSession?
    @ObservationIgnored private var camera: MWDATCamera.Camera?
    @ObservationIgnored private let sessionTokens = ListenerTokenBag()
    @ObservationIgnored private let streamTokens = ListenerTokenBag()
    @ObservationIgnored private let broadcaster = Broadcaster()
    @ObservationIgnored private let mic = MicCapture()
    @ObservationIgnored private let previewGate = PreviewGate()
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    init(wearables: WearablesInterface) {
        self.wearables = wearables
        self.registrationState = wearables.registrationState
        self.deviceSelector = AutoDeviceSelector(wearables: wearables)

        broadcaster.onStatus = { [weak self] status in
            Task { @MainActor in self?.handleBroadcast(status) }
        }

        tasks.append(Task { [weak self] in
            for await state in wearables.registrationStateStream() {
                self?.registrationState = state
            }
        })
        let selector = deviceSelector
        tasks.append(Task { [weak self] in
            for await deviceId in selector.activeDeviceStream() {
                self?.hasActiveDevice = deviceId != nil
            }
        })
    }

    // MARK: Registration

    func connectMetaAI() {
        Task {
            do {
                try await wearables.startRegistration()
            } catch let error as RegistrationError {
                report(error.description)
            } catch {
                report(error.localizedDescription)
            }
        }
    }

    func disconnectMetaAI() {
        Task {
            await stopEverything()
            do {
                try await wearables.startUnregistration()
            } catch {
                report(error.localizedDescription)
            }
        }
    }

    // MARK: Camera (preview)

    func startCamera() async {
        guard isRegistered else { return report("Connect to Meta AI first.") }
        guard camera == nil else { return }

        do {
            if try await wearables.checkPermissionStatus(.camera) != .granted {
                showPermissionPrompt = true
                return
            }
        } catch {
            return report(error.localizedDescription)
        }
        await openCamera()
    }

    /// The user confirmed the hop over to the Meta AI app.
    func requestCameraPermission() async {
        showPermissionPrompt = false
        do {
            guard try await wearables.requestPermission(.camera) == .granted else {
                return report("Camera permission was not granted in Meta AI.")
            }
        } catch {
            return report(error.localizedDescription)
        }
        await openCamera()
    }

    private func openCamera() async {
        guard let session = await ensureSession() else { return }
        guard camera == nil else { return }

        let resolution: StreamingResolution
        switch settings.quality {
        case .high: resolution = .high
        case .medium: resolution = .medium
        case .low: resolution = .low
        }
        let config = StreamConfiguration(
            videoCodec: .raw,
            resolution: resolution,
            frameRate: UInt(settings.frameRate)
        )

        do {
            guard let newCamera = try session.addCamera(config: config) else {
                return report("Couldn't open the glasses camera. Try again.")
            }
            camera = newCamera
            listen(to: newCamera.stream)
            streamState = .starting
            newCamera.stream.start()
        } catch {
            camera = nil
            report(error.localizedDescription)
        }
    }

    func stopCamera() async {
        await endBroadcast()
        guard let activeCamera = camera else { return }
        streamState = .stopping
        activeCamera.stop()
    }

    /// Creates (or reuses) a DeviceSession and waits until it's started.
    private func ensureSession() async -> DeviceSession? {
        if let session = deviceSession, session.state == .started { return session }
        if deviceSession == nil {
            do {
                let session = try wearables.createSession(deviceSelector: deviceSelector)
                deviceSession = session
                session.statePublisher.listen { [weak self] state in
                    Task { @MainActor in self?.handleSession(state) }
                }.store(in: sessionTokens)
                session.errorPublisher.listen { [weak self] error in
                    Task { @MainActor in self?.report(error.localizedDescription) }
                }.store(in: sessionTokens)
                sessionState = .starting
                try session.start()
            } catch {
                deviceSession = nil
                sessionState = .idle
                report(error.localizedDescription)
                return nil
            }
        }
        // Wait up to 20 s for the glasses to accept the session.
        for _ in 0..<200 {
            if let session = deviceSession, session.state == .started { return session }
            if deviceSession == nil { return nil }
            try? await Task.sleep(for: .milliseconds(100))
        }
        report("Glasses didn't respond. Make sure they're on, connected, and Developer Mode is enabled in Meta AI.")
        return nil
    }

    private func handleSession(_ state: DeviceSessionState) {
        sessionState = state
        if state == .stopped {
            sessionTokens.clear()
            deviceSession = nil
            camera = nil
            streamState = .stopped
            Task { await endBroadcast() }
        }
    }

    private func listen(to stream: MWDATCamera.Stream) {
        let broadcaster = self.broadcaster
        let gate = self.previewGate

        stream.statePublisher.listen { [weak self] state in
            Task { @MainActor in self?.handleStream(state) }
        }.store(in: streamTokens)

        stream.errorPublisher.listen { [weak self] error in
            Task { @MainActor in self?.report(error.localizedDescription) }
        }.store(in: streamTokens)

        stream.videoFramePublisher.listen { [weak self] frame in
            // Off the main thread: hand the frame to the encoder first.
            if broadcaster.isActive, let sample = FrameConverter.sampleBuffer(from: frame) {
                broadcaster.submit(.video(sample))
            }
            // Refresh the on-screen preview ~12 times a second.
            guard gate.shouldUpdate(), let image = frame.makeUIImage() else { return }
            Task { @MainActor in self?.previewImage = image }
        }.store(in: streamTokens)
    }

    private func handleStream(_ state: StreamState) {
        streamState = state
        if state == .stopped {
            streamTokens.clear()
            camera = nil
            previewImage = nil
            Task { await endBroadcast() }
        }
    }

    // MARK: Going live

    func goLive() async {
        settings.save()
        guard isCameraOn else { return report("Turn on the glasses camera first.") }
        guard settings.isReadyToStream else { return report("Add your server URL and stream key in Settings.") }

        let outputSize: CGSize
        let source = settings.quality.sourceSize
        switch settings.layout {
        case .vertical: outputSize = source
        case .horizontal: outputSize = CGSize(width: source.height, height: source.width)
        }

        let config = Broadcaster.Config(
            url: settings.server.trimmingCharacters(in: .whitespacesAndNewlines),
            key: settings.streamKey.trimmingCharacters(in: .whitespacesAndNewlines),
            outputSize: outputSize,
            letterbox: settings.layout == .horizontal,
            bitRate: settings.quality.bitRate,
            frameRate: settings.frameRate,
            includeAudio: settings.audioSource != .none
        )

        do {
            try await broadcaster.start(config)
        } catch {
            await broadcaster.stop()
            return report("Couldn't connect to the stream server: \(error.localizedDescription)")
        }

        if settings.audioSource != .none {
            let broadcaster = self.broadcaster
            do {
                audioNote = try mic.start(source: settings.audioSource) { buffer, when in
                    broadcaster.submit(.audio(buffer, when))
                }
            } catch {
                audioNote = "Mic unavailable - streaming video only"
            }
        } else {
            audioNote = "No audio"
        }
        UIApplication.shared.isIdleTimerDisabled = true
    }

    func endBroadcast() async {
        mic.stop()
        if broadcaster.isActive || broadcastStatus != .idle {
            await broadcaster.stop()
        }
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func stopEverything() async {
        await stopCamera()
        deviceSession?.stop()
    }

    private func handleBroadcast(_ status: Broadcaster.Status) {
        broadcastStatus = status
        switch status {
        case .live:
            if liveSince == nil { liveSince = Date() }
        case .failed(let code):
            liveSince = nil
            report("Stream server ended the stream (\(code)). Check your stream key.")
            Task { await endBroadcast() }
        case .idle, .connecting:
            liveSince = nil
        }
    }

    // MARK: Errors

    func report(_ message: String) {
        errorMessage = message
        showError = true
    }
}

/// Lets roughly every Nth frame through to the SwiftUI preview.
final class PreviewGate: @unchecked Sendable {
    private let lock = NSLock()
    private var last = Date.distantPast

    func shouldUpdate() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        guard now.timeIntervalSince(last) >= 1.0 / 12.0 else { return false }
        last = now
        return true
    }
}
