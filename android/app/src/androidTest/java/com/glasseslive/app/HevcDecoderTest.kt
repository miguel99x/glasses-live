package com.glasseslive.app

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes a real HEVC stream with the phone's encoder and checks that [HevcDecoder] draws it,
 * including the case where the parameter sets arrive glued to the first keyframe.
 */
@RunWith(AndroidJUnit4::class)
class HevcDecoderTest {
  private val w = 640
  private val h = 360

  /** Encoded access units in Annex-B form; the first is the codec config (VPS/SPS/PPS). */
  private fun encodeHevc(frames: Int): List<ByteArray> {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, 24)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10) // one keyframe, like a long glasses GOP
    }
    val enc = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC) } catch (e: Exception) { null }
    if (enc == null) {
      // Phones/emulators without an HEVC encoder replay a clip recorded on one that has it.
      val units = loadSample()
      assumeTrue("No HEVC encoder and no sample clip", units != null)
      return units!!
    }
    enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    enc.start()
    val out = ArrayList<ByteArray>()
    val info = MediaCodec.BufferInfo()
    var fed = 0
    var done = false
    while (!done) {
      if (fed <= frames) {
        val i = enc.dequeueInputBuffer(10_000)
        if (i >= 0) {
          if (fed == frames) {
            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          } else {
            val img = enc.getInputImage(i)!!
            for ((p, plane) in img.planes.withIndex()) {
              val buf = plane.buffer
              val value = if (p == 0) ((fed * 9) % 200 + 30).toByte() else 128.toByte()
              while (buf.hasRemaining()) buf.put(value)
            }
            enc.queueInputBuffer(i, 0, w * h * 3 / 2, fed * 41_666L, 0)
          }
          fed++
        }
      }
      val o = enc.dequeueOutputBuffer(info, 10_000)
      if (o >= 0) {
        val b = enc.getOutputBuffer(o)!!
        if (info.size > 0) {
          val bytes = ByteArray(info.size)
          b.position(info.offset)
          b.get(bytes)
          out += bytes
        }
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
        enc.releaseOutputBuffer(o, false)
      }
    }
    enc.stop()
    enc.release()
    saveSample(out)
    return out
  }

  // Sample format: [4-byte big-endian length][access unit] repeated.
  private fun saveSample(units: List<ByteArray>) {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(ctx.getExternalFilesDir(null), "sample_hevc.bin")
    DataOutputStream(file.outputStream()).use { out -> units.forEach { out.writeInt(it.size); out.write(it) } }
  }

  private fun loadSample(): List<ByteArray>? {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val stream = try { assets.open("sample_hevc.bin") } catch (e: Exception) { return null }
    val units = ArrayList<ByteArray>()
    DataInputStream(stream).use { input ->
      while (input.available() > 0) {
        val bytes = ByteArray(input.readInt())
        input.readFully(bytes)
        units += bytes
      }
    }
    return units
  }

  private fun decodeAndCount(units: List<ByteArray>): Pair<Long, Int> {
    val thread = HandlerThread("reader").apply { start() }
    val reader = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 4)
    val drawn = AtomicInteger()
    reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); drawn.incrementAndGet() }, Handler(thread.looper))
    val dec = HevcDecoder(w, h, reader.surface)
    assertTrue("decoder should start", dec.start())
    units.forEachIndexed { i, u -> dec.feed(u, 1_000_000L + i * 41_666L) }
    Thread.sleep(500)
    val rendered = dec.framesRendered
    dec.stop()
    reader.close()
    thread.quitSafely()
    return rendered to drawn.get()
  }

  @Test
  fun decodesWhenConfigComesSeparately() {
    val units = encodeHevc(48)
    val (rendered, drawn) = decodeAndCount(units)
    assertTrue("rendered=$rendered drawn=$drawn", rendered >= 30 && drawn > 0)
  }

  @Test
  fun decodesWhenConfigIsGluedToKeyframe() {
    val units = encodeHevc(48)
    val glued = listOf(units[0] + units[1]) + units.drop(2)
    val (rendered, drawn) = decodeAndCount(glued)
    assertTrue("rendered=$rendered drawn=$drawn", rendered >= 30 && drawn > 0)
  }

  @Test
  fun decoderFeedsH264Encoder() {
    val units = encodeHevc(48)
    var sps: ByteBuffer? = null
    val outFrames = AtomicInteger()
    val enc = H264Encoder(w, h, 1_000_000, 24, onConfig = { s, _ -> sps = s }, onFrame = { _, _ -> outFrames.incrementAndGet() })
    assertTrue(enc.start())
    val dec = HevcDecoder(w, h, enc.inputSurface!!)
    assertTrue(dec.start())
    units.forEachIndexed { i, u -> dec.feed(u, 1_000_000L + i * 41_666L) }
    Thread.sleep(1500)
    dec.stop()
    enc.stop()
    assertNotNull("encoder should report SPS/PPS", sps)
    assertTrue("h264 frames=${outFrames.get()}", outFrames.get() > 10)
  }
}
