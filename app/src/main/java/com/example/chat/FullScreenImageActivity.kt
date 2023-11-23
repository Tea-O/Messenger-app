package com.example.chat

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.example.chat.databinding.ActivityFullScreenImagesBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class FullScreenImageActivity : AppCompatActivity() {
    private lateinit var fullScreenImageBinding: ActivityFullScreenImagesBinding
    private lateinit var messageServiceIntent: Intent
    private val scope = CoroutineScope(Dispatchers.IO)
    private var messageService: ChatService? = null
    //private lateinit var bitmap: Bitmap
    private var isBound = false

    private val boundServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binderBridge: ChatService.MyBinder = service as ChatService.MyBinder
            messageService = binderBridge.getService()
            val position = intent.getIntExtra("messagePosition", -1)
            if (position != -1) {
                scope.launch {
                    val image = withContext(Dispatchers.IO) {
                        messageService!!.getFullImage(position)
                    }
                    withContext(Dispatchers.Main) {
                        fullScreenImageBinding.fullScreenImage.setImageBitmap(image)
                    }
                }
            }
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            messageService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullScreenImageBinding = ActivityFullScreenImagesBinding.inflate(layoutInflater)
        setContentView(fullScreenImageBinding.root)

        messageServiceIntent = Intent(this, ChatService::class.java)
        startService(messageServiceIntent)
        bindService(messageServiceIntent, boundServiceConnection, BIND_AUTO_CREATE)
    }


    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(boundServiceConnection)
        }
    }
}