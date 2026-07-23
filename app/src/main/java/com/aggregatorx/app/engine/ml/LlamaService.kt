package com.aggregatorx.app.engine.ml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aggregatorx.app.R

class LlamaService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(42, notification())
        ModelDownloadManager.ensureModel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "On-device model", NotificationManager.IMPORTANCE_MIN))
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("AggregatorX model ready")
        .setContentText("On-device analysis is running")
        .setSilent(true)
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "llama_model_service"
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, LlamaService::class.java))
        }
    }
}
