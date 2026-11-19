package com.glasseslive.app

import android.content.Context
import com.meta.wearable.dat.camera.types.VideoQuality

enum class StreamPlatform(val label: String, val defaultServer: String) {
  TWITCH("Twitch", "rtmp://live.twitch.tv/app"),
  YOUTUBE("YouTube", "rtmp://a.rtmp.youtube.com/live2"),
  FACEBOOK("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp"),
  CUSTOM("Custom / Kick", ""),
}

enum class Quality(val label: String, val width: Int, val height: Int, val bitRate: Int, val sdk: VideoQuality) {
  HIGH("720p", 720, 1280, 2_500_000, VideoQuality.HIGH),
  MEDIUM("504p", 504, 896, 1_500_000, VideoQuality.MEDIUM),
  LOW("360p", 360, 640, 800_000, VideoQuality.LOW),
}

enum class AudioSource(val label: String) {
  GLASSES("Glasses mic"),
  PHONE("Phone mic"),
  NONE("No audio"),
}

data class StreamSettings(
    val platform: StreamPlatform = StreamPlatform.TWITCH,
    val server: String = StreamPlatform.TWITCH.defaultServer,
    val streamKey: String = "",
    val quality: Quality = Quality.MEDIUM,
    val frameRate: Int = 24,
    val audio: AudioSource = AudioSource.GLASSES,
) {
  val isReady: Boolean get() = server.isNotBlank() && streamKey.isNotBlank()

  /** RTMP URL with the key appended, the way RTMP servers expect it. */
  val publishUrl: String
    get() = server.trim().trimEnd('/') + "/" + streamKey.trim()
}

/** Stores settings in the app's private storage (never leaves the phone). */
class SettingsStore(context: Context) {
  private val prefs = context.getSharedPreferences("glasseslive", Context.MODE_PRIVATE)

  fun load(): StreamSettings {
    val platform = enumOr(prefs.getString("platform", null), StreamPlatform.TWITCH)
    return StreamSettings(
        platform = platform,
        server = prefs.getString("server", null) ?: platform.defaultServer,
        streamKey = prefs.getString("streamKey", "") ?: "",
        quality = enumOr(prefs.getString("quality", null), Quality.MEDIUM),
        frameRate = prefs.getInt("frameRate", 24).takeIf { it in listOf(15, 24, 30) } ?: 24,
        audio = enumOr(prefs.getString("audio", null), AudioSource.GLASSES),
    )
  }

  fun save(s: StreamSettings) {
    prefs.edit()
        .putString("platform", s.platform.name)
        .putString("server", s.server)
        .putString("streamKey", s.streamKey)
        .putString("quality", s.quality.name)
        .putInt("frameRate", s.frameRate)
        .putString("audio", s.audio.name)
        .apply()
  }

  private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
      enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
