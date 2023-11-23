package com.example.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        var message = intent.getStringExtra("toastMessage")
    }
}