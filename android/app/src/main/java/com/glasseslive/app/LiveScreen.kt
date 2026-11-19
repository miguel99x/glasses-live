package com.glasseslive.app

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val LiveRed = Color(0xFFE5282F)

// Neutral dark greys (Material's defaults are purple-tinted).
private val AppColors =
    darkColorScheme(
        primary = LiveRed,
        onPrimary = Color.White,
        background = Color(0xFF111111),
        onBackground = Color.White,
        surface = Color(0xFF111111),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFCCCCCC),
        surfaceContainerLowest = Color(0xFF0C0C0C),
        surfaceContainerLow = Color(0xFF181818),
        surfaceContainer = Color(0xFF1E1E1E),
        surfaceContainerHigh = Color(0xFF262626),
        surfaceContainerHighest = Color(0xFF303030),
        secondaryContainer = Color(0xFF3A3A3A),
        onSecondaryContainer = Color.White,
        outline = Color(0xFF777777),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onConfirmCameraPermission: () -> Unit,
    onGoLive: () -> Unit,
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  var showSettings by remember { mutableStateOf(false) }

  MaterialTheme(colorScheme = AppColors) {
    Scaffold(
        topBar = {
          TopAppBar(
              title = { Text("Glasses Live") },
              actions = {
                IconButton(onClick = { showSettings = true }, enabled = !ui.isLive) {
                  Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
              },
          )
        },
    ) { padding ->
      Column(
          modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // The preview takes whatever height is left, so the buttons always fit on screen.
        Preview(viewModel, ui, Modifier.weight(1f))
        if (ui.videoInfo.isNotEmpty() && !ui.isLive) {
          Text(ui.videoInfo, style = MaterialTheme.typography.labelSmall, color = Color.Gray, textAlign = TextAlign.Center)
        }
        Text(
            ui.statusLine,
            style = MaterialTheme.typography.titleMedium,
            color = if (ui.isLive) LiveRed else MaterialTheme.colorScheme.onBackground,
        )
        if (ui.isLive && ui.audioNote.isNotEmpty()) {
          Text(ui.audioNote, style = MaterialTheme.typography.bodySmall)
        }
        Controls(viewModel, ui, onConnect,
            onGoLive = { if (ui.settings.isReady) onGoLive() else showSettings = true }) { showSettings = true }
      }
    }

    if (showSettings) {
      ModalBottomSheet(onDismissRequest = { showSettings = false }) {
        SettingsSheet(viewModel, ui.settings, onDisconnect = {
          showSettings = false
          onDisconnect()
        })
      }
    }

    ui.error?.let { message ->
      AlertDialog(
          onDismissRequest = viewModel::clearError,
          confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
          title = { Text("Something went wrong") },
          text = { Text(message) },
      )
    }

    if (ui.askCameraPermission) {
      AlertDialog(
          onDismissRequest = viewModel::dismissPermissionPrompt,
          confirmButton = { TextButton(onClick = onConfirmCameraPermission) { Text("Open Meta AI") } },
          dismissButton = { TextButton(onClick = viewModel::dismissPermissionPrompt) { Text("Cancel") } },
          title = { Text("Allow camera in Meta AI?") },
          text = { Text("Meta AI will open so you can let Glasses Live use your glasses camera. Choose \"Allow always\" so you only do this once.") },
      )
    }
  }
}

@Composable
private fun Preview(viewModel: LiveViewModel, ui: LiveUiState, modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
              .clip(RoundedCornerShape(16.dp))
              .background(Color.Black),
      contentAlignment = Alignment.Center,
  ) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
          SurfaceView(context).apply {
            holder.addCallback(
                object : SurfaceHolder.Callback {
                  override fun surfaceCreated(holder: SurfaceHolder) = viewModel.setPreviewSurface(holder.surface)
                  override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
                  override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.setPreviewSurface(null)
                },
            )
          }
        },
    )
    if (!ui.hasVideo) {
      Text(
          if (ui.isBusy) "Connecting..." else "Glasses camera is off",
          color = Color.White.copy(alpha = 0.7f),
      )
    }
    if (ui.isLive) {
      LiveBadge(ui.liveSinceMs, Modifier.align(Alignment.TopStart).padding(12.dp))
    }
  }
}

@Composable
private fun LiveBadge(sinceMs: Long?, modifier: Modifier = Modifier) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1000)
    }
  }
  val seconds = ((now - (sinceMs ?: now)) / 1000).coerceAtLeast(0)
  val time =
      if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
      else "%02d:%02d".format(seconds / 60, seconds % 60)
  Row(
      modifier = modifier.background(LiveRed, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).background(Color.White, CircleShape))
    Spacer(Modifier.width(6.dp))
    Text("LIVE  $time", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun Controls(
    viewModel: LiveViewModel,
    ui: LiveUiState,
    onConnect: () -> Unit,
    onGoLive: () -> Unit,
    openSettings: () -> Unit,
) {
  if (!ui.sdkReady) return
  if (!ui.isRegistered) {
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect to Meta AI") }
    Text(
        "Meta AI opens so you can approve this app. Turn on Developer Mode in Meta AI first.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    return
  }
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
        onClick = { if (ui.isCameraOn) viewModel.stopCamera() else viewModel.startCamera() },
        enabled = !ui.isBusy && !ui.isLive,
        modifier = Modifier.weight(1f),
    ) {
      Text(if (ui.isCameraOn) "Camera off" else "Camera on")
    }
    Button(
        onClick = { if (ui.isLive) viewModel.endBroadcast() else onGoLive() },
        enabled = ui.isCameraOn && (ui.isLive || !ui.isBusy),
        colors = ButtonDefaults.buttonColors(containerColor = LiveRed, contentColor = Color.White),
        modifier = Modifier.weight(1f),
    ) {
      Text(if (ui.isLive) "End stream" else "Go live")
    }
  }
  if (!ui.settings.isReady) {
    TextButton(onClick = openSettings) { Text("Add your stream key in Settings") }
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(viewModel: LiveViewModel, s: StreamSettings, onDisconnect: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Where to stream", style = MaterialTheme.typography.titleMedium)
    ChipRow(StreamPlatform.entries, s.platform, { it.label }) { p ->
      viewModel.updateSettings { it.copy(platform = p, server = if (p == StreamPlatform.CUSTOM) it.server else p.defaultServer) }
    }
    OutlinedTextField(
        value = s.server,
        onValueChange = { v -> viewModel.updateSettings { it.copy(server = v) } },
        label = { Text("Server URL (rtmp:// or rtmps://)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = s.streamKey,
        onValueChange = { v -> viewModel.updateSettings { it.copy(streamKey = v) } },
        label = { Text("Stream key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Copy the key from your site's creator dashboard. It stays on this phone. For Kick, choose Custom and paste Kick's server URL and key.",
        style = MaterialTheme.typography.bodySmall,
    )

    Text("Video", style = MaterialTheme.typography.titleMedium)
    ChipRow(Quality.entries, s.quality, { it.label }) { q -> viewModel.updateSettings { it.copy(quality = q) } }
    ChipRow(listOf(15, 24, 30), s.frameRate, { "$it fps" }) { f -> viewModel.updateSettings { it.copy(frameRate = f) } }
    Text(
        "Lower settings often look sharper because the glasses send video over Bluetooth. Changes apply next time you turn the camera on.",
        style = MaterialTheme.typography.bodySmall,
    )

    Text("Audio", style = MaterialTheme.typography.titleMedium)
    ChipRow(AudioSource.entries, s.audio, { it.label }) { a -> viewModel.updateSettings { it.copy(audio = a) } }

    TextButton(onClick = onDisconnect) { Text("Disconnect from Meta AI", color = LiveRed) }
    Spacer(Modifier.size(24.dp))
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
  androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEach { option ->
      FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
    }
  }
}
