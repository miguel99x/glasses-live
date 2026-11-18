@preconcurrency import AVFoundation
import CoreMedia
import Foundation
import HaishinKit
import RTMPHaishinKit
import VideoToolbox

/// One piece of media headed for the encoder.
enum MediaSample: @unchecked Sendable {
    case video(CMSampleBuffer)
    case audio(AVAudioPCMBuffer, AVAudioTime)
}

/// Feeds frames and audio into an RTMP publish in strict arrival order.
///
/// The glasses SDK and AVAudioEngine call back on their own threads, so they
/// only `submit` into an AsyncStream; one consumer task drains it into the
/// HaishinKit stream. That keeps timestamps monotonic (spawning a Task per
/// frame could reorder them).
final class Broadcaster: @unchecked Sendable {
    struct Config: Sendable {
        var url: String
        var key: String
        var outputSize: CGSize
        var letterbox: Bool
        var bitRate: Int
        var frameRate: Int
        var includeAudio: Bool
    }

    enum Status: Equatable, Sendable {
        case idle
        case connecting
        case live
        case failed(String)
    }

    private let lock = NSLock()
    private var continuation: AsyncStream<MediaSample>.Continuation?
    private var connection: RTMPConnection?
    private var stream: RTMPStream?
    private var drainTask: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?

    /// Reports status changes (connection drops, server rejects) to the UI.
    var onStatus: (@Sendable (Status) -> Void)?

    var isActive: Bool {
        lock.lock(); defer { lock.unlock() }
        return continuation != nil
    }

    func start(_ config: Config) async throws {
        await stop()
        onStatus?(.connecting)

        let connection = RTMPConnection()
        let stream = RTMPStream(connection: connection)

        let video = VideoCodecSettings(
            videoSize: config.outputSize,
            bitRate: config.bitRate,
            profileLevel: kVTProfileLevel_H264_Main_AutoLevel as String,
            scalingMode: config.letterbox ? .letterbox : .trim,
            maxKeyFrameIntervalDuration: 2,
            expectedFrameRate: Double(config.frameRate)
        )
        try await stream.setVideoSettings(video)
        try await stream.setAudioSettings(AudioCodecSettings(bitRate: 128_000))

        _ = try await connection.connect(config.url)
        _ = try await stream.publish(config.key)

        let (media, continuation) = AsyncStream<MediaSample>.makeStream(
            bufferingPolicy: .bufferingNewest(90)
        )

        lock.lock()
        self.connection = connection
        self.stream = stream
        self.continuation = continuation
        lock.unlock()

        drainTask = Task.detached(priority: .userInitiated) {
            for await sample in media {
                switch sample {
                case .video(let buffer):
                    await stream.append(buffer)
                case .audio(let buffer, let when):
                    await stream.append(buffer, when: when)
                }
            }
        }

        let report = onStatus
        statusTask = Task.detached {
            for await status in await stream.status {
                let code = status.code
                if code.contains("Failed") || code.contains("Rejected") || code.contains("BadName") {
                    report?(.failed(code))
                }
            }
        }
        onStatus?(.live)
    }

    /// Thread-safe; called from SDK / audio threads.
    func submit(_ sample: MediaSample) {
        lock.lock()
        let continuation = self.continuation
        lock.unlock()
        continuation?.yield(sample)
    }

    func stop() async {
        lock.lock()
        let continuation = self.continuation
        let stream = self.stream
        let connection = self.connection
        let drainTask = self.drainTask
        let statusTask = self.statusTask
        self.continuation = nil
        self.stream = nil
        self.connection = nil
        self.drainTask = nil
        self.statusTask = nil
        lock.unlock()

        continuation?.finish()
        drainTask?.cancel()
        statusTask?.cancel()
        if let stream {
            _ = try? await stream.close()
        }
        if let connection {
            try? await connection.close()
        }
        onStatus?(.idle)
    }
}
