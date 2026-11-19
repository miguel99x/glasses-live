package com.glasseslive.app

import android.app.Activity
import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveUiState(
    val sdkReady: Boolean = false,
    val registration: RegistrationState = RegistrationState.UNAVAILABLE,
    val hasActiveDevice: Boolean = false,
    val sessionState: DeviceSessionState = DeviceSessionState.IDLE,
    val streamState: StreamState = StreamState.STOPPED,
    val hasVideo: Boolean = false,
    val broadcast: Publisher.Status = Publisher.Status.Idle,
    val liveSinceMs: Long? = null,
    val audioNote: String = "",
    val settings: StreamSettings = StreamSettings(),
    val error: String? = null,
    val askCameraPermission: Boolean = false,
) {
  val isRegistered: Boolean
    get() = registration == RegistrationState.REGISTERED || registration == RegistrationState.UNREGISTERING
  val isCameraOn: Boolean get() = streamState == StreamState.STREAMING || streamState == StreamState.PAUSED
  val isLive: Boolean get() = broadcast == Publisher.Status.Live
  val isBusy: Boolean
    get() = sessionState == DeviceSessionState.STARTING || streamState == StreamState.STARTING ||
        streamState == StreamState.STARTED || streamState == StreamState.STOPPING ||
        broadcast == Publisher.Status.Connecting

  val statusLine: String
    get() = when {
      !sdkReady -> "Allow Bluetooth to continue"
      !isRegistered -> "Not connected to Meta AI"
      !hasActiveDevice -> "Put on your glasses (and open the hinges)"
      isLive -> "LIVE"
      streamState == StreamState.STREAMING -> "Camera on - ready to go live"
      streamState == StreamState.PAUSED -> "Paused by glasses"
      isBusy -> "Starting..."
      else -> "Glasses ready"
    }
}

class LiveViewModel(application: Application) : AndroidViewModel(application) {
  private val store = SettingsStore(application)
  private val _ui = MutableStateFlow(LiveUiState(settings = store.load()))
  val ui: StateFlow<LiveUiState> = _ui.asStateFlow()

  private val selector = AutoDeviceSelector()
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var sessionJobs = mutableListOf<Job>()
  private var streamJobs = mutableListOf<Job>()

  // All frame work happens on one thread so decoders see frames in order.
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  @Volatile private var previewSurface: Surface? = null
  private var previewDecoder: HevcDecoder? = null
  private var streamDecoder: HevcDecoder? = null
  private var encoder: H264Encoder? = null
  private var lastConfig: ByteArray? = null
  @Volatile private var sps: ByteBuffer? = null
  @Volatile private var pps: ByteBuffer? = null

  private val publisher = Publisher { status -> onBroadcast(status) }
  private val audio =
      AudioCapture(application) { buffer, info -> publisher.sendAudio(buffer, info) }

  /** Called by the activity once Bluetooth permission is granted and the SDK is initialized. */
  fun onSdkReady() {
    if (_ui.value.sdkReady) return
    _ui.update { it.copy(sdkReady = true) }
    viewModelScope.launch { Wearables.registrationState.collect { s -> _ui.update { it.copy(registration = s) } } }
    viewModelScope.launch {
      selector.activeDeviceFlow().collect { d -> _ui.update { it.copy(hasActiveDevice = d != null) } }
    }
  }

  // ---- Registration -------------------------------------------------------

  fun connectMetaAI(activity: Activity) = Wearables.startRegistration(activity)

  fun disconnectMetaAI(activity: Activity) {
    stopCamera()
    Wearables.startUnregistration(activity)
  }

  // ---- Camera -------------------------------------------------------------

  fun startCamera() {
    if (camera != null) return
    viewModelScope.launch {
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            if (status == PermissionStatus.Granted) openSession()
            else _ui.update { it.copy(askCameraPermission = true) }
          }
          .onFailure { error, _ -> report(error.description) }
    }
  }

  fun dismissPermissionPrompt() = _ui.update { it.copy(askCameraPermission = false) }

  /** Result of the Meta AI camera-permission screen. */
  fun onCameraPermission(status: PermissionStatus) {
    if (status == PermissionStatus.Granted) openSession()
    else report("Camera permission was not granted in Meta AI.")
  }

  private fun openSession() {
    session?.let { existing ->
      if (_ui.value.sessionState == DeviceSessionState.STARTED) addCamera(existing)
      return
    }
    Wearables.createSession(selector)
        .onSuccess { created ->
          session = created
          sessionJobs += viewModelScope.launch {
            created.state.collect { state ->
              _ui.update { it.copy(sessionState = state) }
              when (state) {
                DeviceSessionState.STARTED -> if (camera == null) addCamera(created)
                DeviceSessionState.STOPPED -> cleanupSession()
                else -> Unit
              }
            }
          }
          sessionJobs += viewModelScope.launch {
            created.errors.collect { error -> report(error.description) }
          }
          created.start()
        }
        .onFailure { error, _ -> report(error.description) }
  }

  private fun addCamera(target: DeviceSession) {
    val s = _ui.value.settings
    StreamingService.start(getApplication())
    target.addCamera(
            StreamConfiguration(videoQuality = s.quality.sdk, frameRate = s.frameRate, compressVideo = true))
        .onSuccess { added ->
          camera = added
          val stream = added.stream
          streamJobs += viewModelScope.launch(frameDispatcher) { stream.videoStream.collect { onFrame(it) } }
          streamJobs += viewModelScope.launch {
            var wasActive = false
            stream.state.collect { state ->
              _ui.update { it.copy(streamState = state) }
              val terminal = state == StreamState.STOPPED || state == StreamState.CLOSED
              if (!terminal) wasActive = true
              else if (wasActive) {
                wasActive = false
                onStreamEnded()
              }
            }
          }
          streamJobs += viewModelScope.launch { stream.errorStream.collect { e -> Log.w("GlassesLive", e.description) } }
          stream.start().onFailure { error, _ ->
            report(error.description)
            onStreamEnded()
          }
        }
        .onFailure { error, _ ->
          StreamingService.stop(getApplication())
          report(error.description)
        }
  }

  fun stopCamera() {
    endBroadcast()
    camera?.stop()
  }

  private fun onStreamEnded() {
    endBroadcast()
    streamJobs.forEach { it.cancel() }
    streamJobs.clear()
    camera = null
    session?.removeCamera()
    viewModelScope.launch(frameDispatcher) { releaseVideoPipeline() }
    _ui.update { it.copy(hasVideo = false) }
    StreamingService.stop(getApplication())
  }

  private fun cleanupSession() {
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    session = null
    if (camera != null) onStreamEnded()
  }

  // ---- Frames (frameDispatcher thread) -------------------------------------

  private fun onFrame(frame: VideoFrame) {
    if (!frame.isCompressed) return
    val buffer = frame.buffer
    val bytes = ByteArray(buffer.remaining())
    val pos = buffer.position()
    buffer.get(bytes)
    buffer.position(pos)
    // Stamp with the phone clock so video and mic audio share one timeline.
    val pts = System.nanoTime() / 1000

    if (frame.isCodecConfig) lastConfig = bytes

    if (encoder == null && !frame.isCodecConfig) startVideoPipeline(frame.width, frame.height)

    val surface = previewSurface
    if (previewDecoder == null && surface != null && surface.isValid && frame.width > 0) {
      previewDecoder = HevcDecoder(frame.width, frame.height, surface).takeIf { it.start() }
      previewDecoder?.prime(lastConfig)
    }
    previewDecoder?.feed(bytes, pts, frame.isCodecConfig)
    streamDecoder?.feed(bytes, pts, frame.isCodecConfig)

    if (!_ui.value.hasVideo && !frame.isCodecConfig) _ui.update { it.copy(hasVideo = true) }
  }

  private fun startVideoPipeline(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val s = _ui.value.settings
    val enc =
        H264Encoder(
            width, height, s.quality.bitRate, s.frameRate,
            onConfig = { spsBuf, ppsBuf ->
              sps = copyOf(spsBuf)
              pps = copyOf(ppsBuf)
            },
            onFrame = { buf, info -> publisher.sendVideo(buf, info) },
        )
    if (!enc.start()) {
      report("This phone's video encoder couldn't start.")
      return
    }
    encoder = enc
    val surface = enc.inputSurface ?: return
    streamDecoder = HevcDecoder(width, height, surface).takeIf { it.start() }
    streamDecoder?.prime(lastConfig)
  }

  private fun releaseVideoPipeline() {
    previewDecoder?.stop()
    previewDecoder = null
    streamDecoder?.stop()
    streamDecoder = null
    encoder?.stop()
    encoder = null
    lastConfig = null
    sps = null
    pps = null
  }

  fun setPreviewSurface(surface: Surface?) {
    previewSurface = surface
    viewModelScope.launch(frameDispatcher) {
      if (surface == null) {
        previewDecoder?.stop()
        previewDecoder = null
      }
    }
  }

  // ---- Going live -------------------------------------------------------------

  fun goLive(hasMicPermission: Boolean) {
    val s = _ui.value.settings
    store.save(s)
    val state = _ui.value
    if (!state.isCameraOn) return report("Turn on the glasses camera first.")
    if (!s.isReady) return report("Add your server URL and stream key in Settings.")
    val enc = encoder
    val spsBuf = sps
    val ppsBuf = pps
    if (enc == null || spsBuf == null || ppsBuf == null) {
      return report("Video is still warming up. Try again in a second.")
    }
    val withAudio = s.audio != AudioSource.NONE && hasMicPermission
    publisher.start(s.publishUrl, enc.width, enc.height, spsBuf, ppsBuf, withAudio)
    val note =
        when {
          s.audio == AudioSource.NONE -> "No audio"
          !hasMicPermission -> "Microphone permission denied - video only"
          else -> audio.start(s.audio)
        }
    _ui.update { it.copy(audioNote = note) }
  }

  fun endBroadcast() {
    audio.stop()
    publisher.stop()
    _ui.update { it.copy(broadcast = Publisher.Status.Idle, liveSinceMs = null) }
  }

  private fun onBroadcast(status: Publisher.Status) {
    _ui.update {
      it.copy(
          broadcast = status,
          liveSinceMs = if (status == Publisher.Status.Live) (it.liveSinceMs ?: System.currentTimeMillis()) else null,
      )
    }
    when (status) {
      Publisher.Status.Live -> encoder?.requestKeyframe()
      is Publisher.Status.Failed -> {
        audio.stop()
        report("Stream server: ${status.reason}. Check the server URL and stream key.")
      }
      else -> Unit
    }
  }

  // ---- Settings & errors ------------------------------------------------------

  fun updateSettings(transform: (StreamSettings) -> StreamSettings) {
    _ui.update { it.copy(settings = transform(it.settings)) }
    store.save(_ui.value.settings)
  }

  fun report(message: String) = _ui.update { it.copy(error = message) }

  fun clearError() = _ui.update { it.copy(error = null) }

  override fun onCleared() {
    endBroadcast()
    camera?.stop()
    session?.stop()
    releaseVideoPipeline()
  }

  private fun copyOf(src: ByteBuffer): ByteBuffer {
    val dup = src.duplicate()
    dup.rewind()
    return ByteBuffer.allocateDirect(dup.remaining()).put(dup).apply { flip() }
  }
}
