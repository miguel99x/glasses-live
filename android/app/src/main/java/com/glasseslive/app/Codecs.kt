package com.glasseslive.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "GlassesLive"

/** One NAL unit inside an Annex-B buffer: [start, end) includes its start code. */
private class Nal(val start: Int, val end: Int, val type: Int)

private const val NAL_VPS = 32
private const val NAL_SPS = 33
private const val NAL_PPS = 34

private fun isIrap(type: Int) = type in 16..21

private fun isParamSet(type: Int) = type == NAL_VPS || type == NAL_SPS || type == NAL_PPS

/** Splits an Annex-B HEVC buffer (00 00 01 / 00 00 00 01 start codes) into NAL units. */
private fun splitNals(data: ByteArray): List<Nal> {
  val starts = ArrayList<Int>() // index of each start code's first byte
  val headers = ArrayList<Int>() // index of each NAL header byte
  var i = 0
  while (i + 2 < data.size) {
    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
      val codeStart = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
      starts += codeStart
      headers += i + 3
      i += 3
    } else {
      i++
    }
  }
  val out = ArrayList<Nal>(starts.size)
  for (k in starts.indices) {
    val header = headers[k]
    if (header >= data.size) continue
    val end = if (k + 1 < starts.size) starts[k + 1] else data.size
    out += Nal(starts[k], end, (data[header].toInt() shr 1) and 0x3F)
  }
  return out
}

/** Remembers the latest VPS/SPS/PPS seen in the glasses' stream. */
class HevcParamSets {
  private val sets = HashMap<Int, ByteArray>()

  @Synchronized
  fun offer(data: ByteArray) {
    for (n in splitNals(data)) if (isParamSet(n.type)) sets[n.type] = data.copyOfRange(n.start, n.end)
  }

  @Synchronized fun snapshot(): Map<Int, ByteArray> = HashMap(sets)

  @Synchronized fun clear() = sets.clear()
}

/**
 * Decodes the glasses' compressed HEVC frames straight onto a Surface.
 * Used twice: once for the on-screen preview and once to feed the H.264 encoder.
 *
 * Like Meta's sample app, it finds keyframes and parameter sets by reading the NAL units itself,
 * waits for a keyframe before decoding, and prefers a software decoder: some phone hardware
 * HEVC decoders (Samsung Exynos, e.g. Galaxy S9) turn this stream into garbage.
 */
class HevcDecoder(private val width: Int, private val height: Int, private val surface: Surface) {
  companion object {
    private val BLOCKED = setOf("OMX.Exynos.hevc.dec", "c2.mtk.hevc.decoder")

    /** Usable HEVC decoders on this phone, software first. */
    fun candidates(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
              !info.isEncoder && info.name !in BLOCKED &&
                  info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
            .sortedBy { if (it.isSoftwareOnly) 0 else 1 }
            .map { it.name }
  }

  private var codec: MediaCodec? = null
  var codecName: String = "none"
    private set
  private val paramSets = HashMap<Int, ByteArray>()
  private var active = false
  @Volatile var framesRendered = 0L
    private set

  fun start(): Boolean {
    val names = candidates().ifEmpty { listOf("") }
    for (name in names) {
      var c: MediaCodec? = null
      try {
        c = if (name.isEmpty()) MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            else MediaCodec.createByCodecName(name)
        c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height), surface, null, 0)
        c.start()
        codec = c
        codecName = c.name
        Log.i(TAG, "HEVC decoder: $codecName (${width}x$height)")
        return true
      } catch (e: Exception) {
        Log.w(TAG, "HEVC decoder $name failed to start", e)
        try { c?.release() } catch (_: Exception) {}
      }
    }
    return false
  }

  /** Hands over parameter sets seen before this decoder existed. */
  fun prime(sets: Map<Int, ByteArray>) {
    for ((type, bytes) in sets) paramSets.putIfAbsent(type, bytes)
  }

  /** Must be called from one thread, in frame order. */
  fun feed(data: ByteArray, ptsUs: Long) {
    val c = codec ?: return
    val nals = splitNals(data)
    if (nals.isEmpty()) return

    var configChanged = false
    var keyframe = false
    val picture = java.io.ByteArrayOutputStream(data.size)
    for (n in nals) {
      when {
        isParamSet(n.type) -> {
          val bytes = data.copyOfRange(n.start, n.end)
          if (!bytes.contentEquals(paramSets[n.type])) configChanged = true
          paramSets[n.type] = bytes
        }
        else -> {
          if (isIrap(n.type)) keyframe = true
          picture.write(data, n.start, n.end - n.start)
        }
      }
    }

    try {
      if (!active) {
        // Start on the first keyframe once all three parameter sets are known.
        if (!keyframe || paramSets.size < 3) return
        queue(c, config(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        active = true
      } else if (configChanged && paramSets.size == 3) {
        queue(c, config(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
      }
      if (picture.size() > 0) {
        queue(c, picture.toByteArray(), ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      }
      drain(c)
    } catch (e: Exception) {
      Log.w(TAG, "HEVC decode error", e)
    }
  }

  private fun config(): ByteArray = paramSets[NAL_VPS]!! + paramSets[NAL_SPS]!! + paramSets[NAL_PPS]!!

  private fun queue(c: MediaCodec, bytes: ByteArray, ptsUs: Long, flags: Int) {
    // Keep output moving so an input buffer frees up; dropping input would corrupt later frames.
    var index = -1
    for (attempt in 0 until 10) {
      index = c.dequeueInputBuffer(20_000)
      if (index >= 0) break
      drain(c)
    }
    if (index < 0) {
      Log.w(TAG, "HEVC decoder busy - frame dropped")
      return
    }
    val input = c.getInputBuffer(index) ?: return
    input.clear()
    input.put(bytes)
    c.queueInputBuffer(index, 0, bytes.size, ptsUs, flags)
  }

  private fun drain(c: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    while (true) {
      val out = c.dequeueOutputBuffer(info, 0)
      if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
      if (out < 0) break
      // render = true keeps the frame's timestamp, which the H.264 encoder uses.
      val render = info.size > 0
      c.releaseOutputBuffer(out, render)
      if (render) framesRendered++
    }
  }

  fun stop() {
    try { codec?.stop() } catch (_: Exception) {}
    try { codec?.release() } catch (_: Exception) {}
    codec = null
    active = false
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
