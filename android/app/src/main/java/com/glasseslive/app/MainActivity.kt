package com.glasseslive.app

import android.Manifest
import android.content.pm.PackageManager
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
        if (result[Manifest.permission.BLUETOOTH_CONNECT] == true || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
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
    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
      initSdk()
    } else {
      startupPermissions.launch(
          arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    }
  }

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
