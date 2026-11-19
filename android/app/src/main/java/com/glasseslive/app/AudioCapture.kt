package com.glasseslive.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Records the microphone and encodes it to AAC for the stream.
 * "Glasses mic" routes input from the glasses' Bluetooth headset (SCO) link.
 */
class AudioCapture(
    private val context: Context,
    private val onFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
  companion object {
    const val SAMPLE_RATE = 44_100
    private const val TAG = "GlassesLive"
  }

  @Volatile private var running = false
  private var worker: Thread? = null
  private val audioManager = context.getSystemService(AudioManager::class.java)

  /** Returns a short description of the input actually used. */
  @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before going live.
  fun start(source: AudioSource): String {
    stop()
    if (source == AudioSource.NONE) return "No audio"

    var note = "Phone mic"
    val micSource: Int
    if (source == AudioSource.GLASSES) {
      val headset =
          audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
          }
      if (headset != null && audioManager.setCommunicationDevice(headset)) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        note = "Glasses mic"
      } else {
        note = "Glasses mic not found - using phone mic"
      }
      micSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    } else {
      micSource = MediaRecorder.AudioSource.CAMCORDER
    }

    val minBuffer =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record =
        try {
          AudioRecord(micSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (e: Exception) {
          Log.e(TAG, "AudioRecord failed", e)
          restoreRoute()
          return "Mic unavailable - streaming video only"
        }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      restoreRoute()
      return "Mic unavailable - streaming video only"
    }

    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val format =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
          setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
          setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 4)
        }
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    encoder.start()
    record.startRecording()
    running = true

    worker =
        thread(name = "audio-capture") {
          val pcm = ByteArray(2048)
          val info = MediaCodec.BufferInfo()
          try {
            while (running) {
              val read = record.read(pcm, 0, pcm.size)
              if (read <= 0) continue
              val inIndex = encoder.dequeueInputBuffer(10_000)
              if (inIndex >= 0) {
                val input = encoder.getInputBuffer(inIndex)!!
                input.clear()
                input.put(pcm, 0, read)
                encoder.queueInputBuffer(inIndex, 0, read, System.nanoTime() / 1000, 0)
              }
              while (true) {
                val outIndex = encoder.dequeueOutputBuffer(info, 0)
                if (outIndex < 0) break
                val out = encoder.getOutputBuffer(outIndex)
                if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                  onFrame(out, info)
                }
                encoder.releaseOutputBuffer(outIndex, false)
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Audio capture stopped", e)
          } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
          }
        }
    return note
  }

  fun stop() {
    running = false
    worker?.join(500)
    worker = null
    restoreRoute()
  }

  private fun restoreRoute() {
    try {
      audioManager.clearCommunicationDevice()
      audioManager.mode = AudioManager.MODE_NORMAL
    } catch (_: Exception) {}
  }
}
