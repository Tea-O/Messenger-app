package com.example.chat

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chat.Constants.CONFLICT
import com.example.chat.Constants.ERROR
import com.example.chat.Constants.LARGE_PAYLOAD
import com.example.chat.Constants.MESSAGES_LOADED
import com.example.chat.Constants.NEW_IMAGE
import com.example.chat.Constants.NEW_MESSAGES
import com.example.chat.Constants.NOT_FOUND
import com.example.chat.Constants.SEND_IMAGE
import com.example.chat.Constants.SEND_IMAGE_FAILED
import com.example.chat.Constants.SEND_MESSAGE
import com.example.chat.Constants.SEND_MESSAGE_FAILED
import com.example.chat.Constants.SERVER_ERROR
import com.example.chat.Toast.sendToast
import com.example.chat.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    companion object {
        const val CHANNEL_ID = "channel"
        const val TAG = "MainActivity"
        const val MESSAGE_SERVICE_TAG = "ChatService"
    }

    private lateinit var mainActivity: ActivityMainBinding
    private lateinit var recycler: RecyclerView
    private lateinit var messageServiceIntent: Intent
    private val mainHandler = Handler(Looper.getMainLooper())
    private var messageService: ChatService? = null
    private lateinit var notificationManager: NotificationManagerCompat
    private var isBound = false

    private val boundServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binderBridge: ChatService.MyBinder = service as ChatService.MyBinder
            messageService = binderBridge.getService()
            recycler.adapter = ChatAdapter(this@MainActivity, messageService!!.messages)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            messageService = null
        }
    }

    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.getIntExtra("type", -1)) {
                NEW_MESSAGES -> updateMessages(
                    intent.getIntExtra("initialSize", 0),
                    intent.getIntExtra("updatedSize", 0)
                )
                NEW_IMAGE -> updateMessageImage(
                    intent.getIntExtra("position", -1)
                )
                MESSAGES_LOADED -> messageService?.let {
                    recycler.scrollToPosition(it.messages.size - 1)
                }
                SEND_MESSAGE_FAILED, SEND_IMAGE_FAILED, ERROR, NOT_FOUND, CONFLICT, LARGE_PAYLOAD ->
                    sendToast(
                        "${intent.getStringExtra("text")}",
                        this@MainActivity
                    )
                SERVER_ERROR -> sendToast(
                    "Failed to connect to server",
                    this@MainActivity
                )
            }
        }
    }

    private var attachImageChoose = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            data?.data?.let { selectedPhotoUri ->
                val intent = Intent(TAG)
                intent.putExtra("type", SEND_IMAGE)
                intent.putExtra("uri", selectedPhotoUri.toString())
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivity = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainActivity.root)

        notificationManager = NotificationManagerCompat.from(this)

        initRecycler()
        initSendListener()

        messageServiceIntent = Intent(this, ChatService::class.java)
        startService(messageServiceIntent)
        bindService(messageServiceIntent, boundServiceConnection, BIND_AUTO_CREATE)

        mainActivity.attachButton.setOnClickListener {
            imageChoose()
        }

        mainActivity.skipButton.setOnClickListener {
            if (mainActivity.skipButton.visibility == View.VISIBLE) {
                recycler.adapter?.let {
                    recycler.smoothScrollToPosition(it.itemCount - 1)
                }
                mainActivity.skipButton.visibility = View.INVISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(messageReceiver, IntentFilter(MESSAGE_SERVICE_TAG))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(boundServiceConnection)
        }
    }

    private fun initSendListener() {
        mainActivity.sendButton.setOnClickListener {
            sendTextMessage(mainActivity.messageField.text.toString())
        }
    }

    private fun initRecycler() {
        recycler = mainActivity.messagesRecycler

        val manager = LinearLayoutManager(this)

        recycler.apply {
            layoutManager = manager
            adapter = ChatAdapter(this@MainActivity, mutableListOf())
        }

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                recycler.adapter?.let {
                    if (manager.findLastCompletelyVisibleItemPosition() == it.itemCount - 1) {
                        mainActivity.skipButton.visibility = View.INVISIBLE
                    } else {
                        mainActivity.skipButton.visibility = View.VISIBLE
                    }
                }
            }
        })
    }

    private fun imageChoose() {
        val intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        attachImageChoose.launch(intent)
    }

    private fun sendTextMessage(text: String) {
        mainActivity.messageField.setText("")
        val intent = Intent(TAG)
        intent.putExtra("type", SEND_MESSAGE)
        intent.putExtra("text", text)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateMessages(initialSize: Int, updatedSize: Int) {
        mainHandler.post {
            recycler.post {
                recycler.adapter?.notifyItemRangeInserted(initialSize, updatedSize)
            }
            val manager = recycler.layoutManager as LinearLayoutManager
            if (
                initialSize != updatedSize &&
                manager.findLastCompletelyVisibleItemPosition() == initialSize - 1
            ) {
                mainActivity.skipButton.visibility = View.VISIBLE
            }
        }
    }

    private fun updateMessageImage(position: Int) {
        recycler.adapter?.notifyItemChanged(position)
    }

    public fun sendOnChannel(v: View) {

        val KEY_TEXT_REPLY = "key_text_reply"
        var replyLabel: String = resources.getString(R.string.reply_label)
        var remoteInput: RemoteInput =
            RemoteInput.Builder(KEY_TEXT_REPLY).setLabel(replyLabel).build()


        var replyIntent = Intent(this, DirectReplyReceiver::class.java)
        var replyPendingIntent = PendingIntent.getActivity(
            this,
            0, replyIntent, 0
        )


        var replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_baseline_send_24,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        var activityIntent = Intent(this, MainActivity::class.java)
        var contentIntent = PendingIntent.getActivity(
            this,
            0, activityIntent, 0
        )


        //var largeIcon = BitmapFactory.decodeResource(resources, )

        var notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_notifications_24)
            .setContentTitle("1ch")
            .setContentIntent(contentIntent)
            // .addAction(replyAction)
            .build()
        notificationManager.notify(1, notification)
    }
}
