package com.glasseslive.app

import android.media.MediaCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/** Thin wrapper over RootEncoder's RtmpClient for pre-encoded H.264 + AAC. */
class Publisher(private val onStatus: (Status) -> Unit) {
  sealed interface Status {
    data object Idle : Status
    data object Connecting : Status
    data object Live : Status
    data class Failed(val reason: String) : Status
  }

  @Volatile var isLive = false
    private set

  private val client =
      RtmpClient(
          object : ConnectChecker {
            override fun onConnectionStarted(url: String) = onStatus(Status.Connecting)

            override fun onConnectionSuccess() {
              isLive = true
              onStatus(Status.Live)
            }

            override fun onConnectionFailed(reason: String) {
              isLive = false
              onStatus(Status.Failed(reason))
            }

            override fun onDisconnect() {
              isLive = false
              onStatus(Status.Idle)
            }

            override fun onAuthError() {
              isLive = false
              onStatus(Status.Failed("Server rejected the stream key"))
            }

            override fun onAuthSuccess() = Unit
          },
      )

  fun start(url: String, width: Int, height: Int, sps: ByteBuffer, pps: ByteBuffer, withAudio: Boolean) {
    client.setVideoCodec(VideoCodec.H264)
    client.setVideoResolution(width, height)
    client.setVideoInfo(sps, pps, null)
    client.setOnlyVideo(!withAudio)
    if (withAudio) client.setAudioInfo(AudioCapture.SAMPLE_RATE, false)
    client.connect(url)
  }

  fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (isLive) client.sendVideo(buffer, info)
  }

  fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (isLive) client.sendAudio(buffer, info)
  }

  fun stop() {
    isLive = false
    if (client.isStreaming) client.disconnect()
  }
}
