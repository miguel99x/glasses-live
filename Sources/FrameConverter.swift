@preconcurrency import AVFoundation
import CoreMedia
import CoreVideo
import MWDATCamera
import UIKit

/// Turns a glasses `VideoFrame` into an uncompressed video `CMSampleBuffer`
/// stamped with the host clock, which is what HaishinKit's encoder expects.
enum FrameConverter {
    static func sampleBuffer(from frame: VideoFrame) -> CMSampleBuffer? {
        guard let pixelBuffer = pixelBuffer(from: frame) else { return nil }
        let pts = CMClockGetTime(CMClockGetHostTimeClock())
        return makeSampleBuffer(pixelBuffer: pixelBuffer, pts: pts)
    }

    /// With the `.raw` codec the SDK's sample buffer normally carries a decoded
    /// pixel buffer. If it doesn't, fall back to rendering the frame's UIImage.
    static func pixelBuffer(from frame: VideoFrame) -> CVPixelBuffer? {
        if let imageBuffer = CMSampleBufferGetImageBuffer(frame.sampleBuffer) {
            return imageBuffer
        }
        guard let image = frame.makeUIImage() else { return nil }
        return render(image)
    }

    private static func render(_ image: UIImage) -> CVPixelBuffer? {
        let cgImage: CGImage
        if let existing = image.cgImage {
            cgImage = existing
        } else {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let rendered = UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
                image.draw(at: .zero)
            }
            guard let made = rendered.cgImage else { return nil }
            cgImage = made
        }

        let width = cgImage.width
        let height = cgImage.height
        let attrs: [CFString: Any] = [
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var buffer: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, width, height,
                                  kCVPixelFormatType_32BGRA, attrs as CFDictionary, &buffer) == kCVReturnSuccess,
              let pixelBuffer = buffer else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(pixelBuffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        return pixelBuffer
    }

    static func makeSampleBuffer(pixelBuffer: CVPixelBuffer, pts: CMTime) -> CMSampleBuffer? {
        var formatDescription: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        ) == noErr, let formatDescription else { return nil }

        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sampleBuffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        ) == noErr else { return nil }
        return sampleBuffer
    }
}
