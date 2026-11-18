@preconcurrency import AVFoundation
import Foundation

/// Captures microphone audio for the stream.
///
/// "Glasses mic" works the same way Meta's CameraAccess sample does it: the
/// glasses show up as a Bluetooth HFP headset, so we ask iOS to route input
/// from that port. "Phone mic" forces the built-in mic instead.
final class MicCapture: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private var running = false

    /// Returns a short note about which input is actually in use.
    @discardableResult
    func start(source: AudioSource, onBuffer: @escaping @Sendable (AVAudioPCMBuffer, AVAudioTime) -> Void) throws -> String {
        stop()
        guard source != .none else { return "No audio" }

        let session = AVAudioSession.sharedInstance()
        switch source {
        case .glasses:
            try session.setCategory(.playAndRecord, mode: .videoRecording, options: [.allowBluetoothHFP, .mixWithOthers])
        case .phone, .none:
            try session.setCategory(.playAndRecord, mode: .videoRecording, options: [.mixWithOthers, .defaultToSpeaker])
        }
        try session.setActive(true)

        let wantedPort: AVAudioSession.Port = source == .glasses ? .bluetoothHFP : .builtInMic
        if let input = session.availableInputs?.first(where: { $0.portType == wantedPort }) {
            try session.setPreferredInput(input)
        }

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, when in
            // The engine may reuse its buffer, so hand the encoder a copy.
            guard let copy = Self.copy(buffer) else { return }
            onBuffer(copy, when)
        }
        engine.prepare()
        try engine.start()
        running = true

        let active = session.currentRoute.inputs.first
        if source == .glasses, active?.portType != .bluetoothHFP {
            return "Glasses mic not available - using \(active?.portName ?? "phone mic")"
        }
        return active?.portName ?? source.label
    }

    func stop() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private static func copy(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else { return nil }
        copy.frameLength = buffer.frameLength
        let channels = Int(buffer.format.channelCount)
        let frames = Int(buffer.frameLength)
        if let src = buffer.floatChannelData, let dst = copy.floatChannelData {
            for c in 0..<channels { dst[c].update(from: src[c], count: frames) }
        } else if let src = buffer.int16ChannelData, let dst = copy.int16ChannelData {
            for c in 0..<channels { dst[c].update(from: src[c], count: frames) }
        } else if let src = buffer.int32ChannelData, let dst = copy.int32ChannelData {
            for c in 0..<channels { dst[c].update(from: src[c], count: frames) }
        } else {
            return nil
        }
        return copy
    }
}
