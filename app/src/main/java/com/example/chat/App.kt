package com.example.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    val CHANNEL_ID = "channel"

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var channel: NotificationChannel = NotificationChannel(
                CHANNEL_ID,
                "Channel1",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "This channel" }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

        }
    }
}