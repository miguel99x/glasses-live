package com.glasseslive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: LiveViewModel by viewModels()
  private var pendingGoLive = false

  // Android permissions: Bluetooth (needed before the Meta SDK starts) and notifications.
  private val startupPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        if (hasBluetoothPermission()) {
          initSdk()
        } else {
          viewModel.report("Glasses Live needs the Nearby devices (Bluetooth) permission to reach your glasses.")
        }
      }

  // Microphone permission, asked the first time you go live with audio.
  private val micPermission =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        if (pendingGoLive) {
          pendingGoLive = false
          viewModel.goLive(result[Manifest.permission.RECORD_AUDIO] == true)
        }
      }

  // Glasses camera permission, granted inside the Meta AI app.
  private val cameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        viewModel.onCameraPermission(result.getOrDefault(PermissionStatus.Denied))
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      LiveScreen(
          viewModel = viewModel,
          onConnect = { viewModel.connectMetaAI(this) },
          onDisconnect = { viewModel.disconnectMetaAI(this) },
          onConfirmCameraPermission = {
            viewModel.dismissPermissionPrompt()
            cameraPermission.launch(Permission.CAMERA)
          },
          onGoLive = ::goLive,
      )
    }

    // Keep the screen on while live.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.ui.collect { state ->
          if (state.isLive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    if (hasBluetoothPermission()) {
      initSdk()
      // Android 13+ asks separately for notifications (used for the "live" notice).
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
        notificationPermission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
      }
    } else {
      // Only Android 12+ gets here: older versions grant Bluetooth at install time.
      val wanted = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        wanted += Manifest.permission.POST_NOTIFICATIONS
      }
      startupPermissions.launch(wanted.toTypedArray())
    }
  }

  private val notificationPermission =
      registerForActivityResult(RequestMultiplePermissions()) {}

  /** Android 10/11 grant Bluetooth at install; Android 12+ need "Nearby devices". */
  private fun hasBluetoothPermission() =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
          hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

  private fun goLive() {
    val needsMic = viewModel.ui.value.settings.audio != AudioSource.NONE
    if (needsMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
      pendingGoLive = true
      micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    } else {
      viewModel.goLive(hasPermission(Manifest.permission.RECORD_AUDIO))
    }
  }

  private var sdkInitialized = false

  private fun initSdk() {
    if (sdkInitialized) return
    Wearables.initialize(this)
        .onSuccess {
          sdkInitialized = true
          viewModel.onSdkReady()
        }
        .onFailure { error, _ ->
          Log.e("GlassesLive", "SDK init failed: ${error.description}")
          viewModel.report("Couldn't start the Meta glasses toolkit: ${error.description}")
        }
  }

  private fun hasPermission(name: String) =
      ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
}
