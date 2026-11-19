package com.glasseslive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Foreground service so Android doesn't cut the glasses link when the screen turns off. */
class StreamingService : Service() {
  companion object {
    private const val CHANNEL = "streaming"

    fun start(context: Context) {
      context.startForegroundService(Intent(context, StreamingService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, StreamingService::class.java))
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Glasses camera", NotificationManager.IMPORTANCE_LOW))
    val open =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Glasses Live")
            .setContentText("Glasses camera is on")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    return START_NOT_STICKY
  }
}
