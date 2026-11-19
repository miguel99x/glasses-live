package com.glasseslive.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "GlassesLive"

/** True if this HEVC access unit contains a keyframe (IRAP NAL types 16-21). */
fun isHevcKeyframe(data: ByteArray): Boolean {
  var i = 0
  while (i + 4 < data.size) {
    val isStart3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
    if (isStart3) {
      val type = (data[i + 3].toInt() shr 1) and 0x3F
      if (type in 16..21) return true
      i += 3
    } else {
      i++
    }
  }
  return false
}

/**
 * Decodes the glasses' compressed HEVC frames straight onto a Surface.
 * Used twice: once for the on-screen preview and once to feed the H.264 encoder.
 * Waits for codec config + a keyframe before rendering, so it can start mid-stream.
 */
class HevcDecoder(private val width: Int, private val height: Int, private val surface: Surface) {
  private var codec: MediaCodec? = null
  private var configBytes: ByteArray? = null
  private var gotKeyframe = false

  fun start(): Boolean =
      try {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        codec =
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
              configure(format, surface, null, 0)
              start()
            }
        true
      } catch (e: Exception) {
        Log.e(TAG, "HEVC decoder start failed", e)
        codec = null
        false
      }

  /** Remember the parameter sets so a decoder created later can be primed. */
  fun prime(config: ByteArray?) {
    if (config != null) feed(config, 0, isConfig = true)
  }

  /** Must be called from one thread, in frame order. */
  fun feed(data: ByteArray, ptsUs: Long, isConfig: Boolean) {
    val c = codec ?: return
    if (isConfig) {
      configBytes = data
    } else {
      if (!gotKeyframe) {
        if (configBytes == null || !isHevcKeyframe(data)) return
        gotKeyframe = true
      }
    }
    try {
      val index = c.dequeueInputBuffer(10_000)
      if (index >= 0) {
        val input = c.getInputBuffer(index) ?: return
        input.clear()
        input.put(data)
        val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        c.queueInputBuffer(index, 0, data.size, ptsUs, flags)
      }
      drain(c)
    } catch (e: Exception) {
      Log.w(TAG, "HEVC decode error", e)
    }
  }

  private fun drain(c: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    while (true) {
      val out = c.dequeueOutputBuffer(info, 0)
      if (out < 0) break
      // Render with the frame's own timestamp so the encoder keeps it.
      c.releaseOutputBuffer(out, info.presentationTimeUs * 1000)
    }
  }

  fun stop() {
    try {
      codec?.stop()
    } catch (_: Exception) {}
    try {
      codec?.release()
    } catch (_: Exception) {}
    codec = null
  }
}

/** Hardware H.264 encoder that takes frames on an input Surface. */
class H264Encoder(
    val width: Int,
    val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val onConfig: (sps: ByteBuffer, pps: ByteBuffer) -> Unit,
    private val onFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
  private var codec: MediaCodec? = null
  private val thread = HandlerThread("h264-encoder").apply { start() }
  var inputSurface: Surface? = null
    private set

  fun start(): Boolean =
      try {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
              setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
              setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
              setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
              setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
              setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
              setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(
            object : MediaCodec.Callback() {
              override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

              override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                  val buffer = codec.getOutputBuffer(index)
                  if (buffer != null && info.size > 0 &&
                      info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    onFrame(buffer, info)
                  }
                } finally {
                  codec.releaseOutputBuffer(index, false)
                }
              }

              override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "H.264 encoder error", e)
              }

              override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")
                if (sps != null && pps != null) onConfig(sps, pps)
              }
            },
            Handler(thread.looper),
        )
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        true
      } catch (e: Exception) {
        Log.e(TAG, "H.264 encoder start failed", e)
        false
      }

  /** Ask for an immediate keyframe (used when going live mid-stream). */
  fun requestKeyframe() {
    try {
      codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    } catch (_: Exception) {}
  }

  fun stop() {
    try {
      codec?.stop()
    } catch (_: Exception) {}
    try {
      codec?.release()
    } catch (_: Exception) {}
    codec = null
    inputSurface?.release()
    inputSurface = null
    thread.quitSafely()
  }
}
