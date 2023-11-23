package com.example.chat

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.LruCache
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import com.example.chat.Constants.USERNAME
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.math.roundToInt


class ChatService : Service() {
    companion object {
        const val TAG = "ChatService"
        const val MAIN_ACTIVITY_TAG = "MainActivity"
    }

    private val cacheSize = 14 * 1024 * 1024
    private var memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val failedMessagesDatabase by lazy {
        FailedMessagesDatabase.getDatabase(this).failedMessagesDAO()
    }


    private val networkHelper = NetworkHelper()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var messagesInterval = 1500L
    private val semaphore = Semaphore(1)

    //private val receiveMessageHandler = Handler(Looper.myLooper()!!)
    private val messagesDatabase by lazy {
        MessagesDatabase.getDatabase(this).messagesDAO()
    }
    private val receiveMapper = JsonMapper
        .builder()
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .build()
        .registerModule(KotlinModule.Builder().build())
    private val sendMapper = JsonMapper
        .builder()
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .build()
        .registerModule(KotlinModule.Builder().build())

    private var messageReceiver = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            updateMessages()
            delay(messagesInterval)
        }
    }

    private var messageImageUpdater = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            updateImages()
            delay(messagesInterval + 600L)
        }
    }

    private val sendMessageListener: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.getIntExtra("type", -1)) {
                SEND_MESSAGE -> scope.launch {
                    prepareAndSendTextMessage(
                        intent.getStringExtra("text") ?: ""
                    )
                }
                SEND_IMAGE -> scope.launch {
                    prepareAndSendImageMessage(
                        Uri.parse(intent.getStringExtra("uri")) ?: Uri.EMPTY
                    )
                }
            }
        }
    }

    private var failedMessagesSender = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            sendFailedMessages()
            delay(messagesInterval)
        }
    }

    val messages: MutableList<Message> = LinkedList()

    inner class MyBinder : Binder() {
        fun getService() = this@ChatService
    }

    override fun onCreate() {
        super.onCreate()
        loadMessages()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(sendMessageListener, IntentFilter(MAIN_ACTIVITY_TAG))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder {
        return MyBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        messageReceiver.cancel()
        messageImageUpdater.cancel()
        failedMessagesSender.cancel()
        scope.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sendMessageListener)
    }

    private fun addBitmapToMemoryCache(key: String?, bitmap: Bitmap?) {
        if (getBitmapFromMemCache(key) == null) {
            memoryCache.put(key, bitmap)
        }
    }

    private fun getBitmapFromMemCache(key: String?): Bitmap? {
        return memoryCache[key]
    }

    private fun loadMessages() {
        scope.launch {
            withContext(Dispatchers.IO) {
                semaphore.acquire()
                messagesDatabase.getAllMessages().forEach {
                    messages += it.toMessage()
                }
                sendIntent(MESSAGES_LOADED)
                messageReceiver.start()
                messageImageUpdater.start()
                failedMessagesSender.start()
                semaphore.release()
            }
        }
    }

    private fun compressImage(image: Bitmap?): Bitmap? {
        image ?: return null
        val ratio = image.width.toDouble() / image.height.toDouble()
        return Bitmap.createScaledBitmap(image, 400, (400 / ratio).roundToInt(), false)
    }


    private fun getImageFromCache(imageId: Long?): Bitmap? {
        return try {
            val file = File(cacheDir, "$imageId.png")
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getFullImage(position: Int): Bitmap? {
        return getImageFromCache(messagesDatabase.getMessageItemIdById(messages[position].id!!))
    }

    private suspend fun updateMessages() {
        withContext(Dispatchers.IO) {
            semaphore.acquire()
            val lastKnownMessageId = if (messages.isEmpty()) 0 else messages.last().id!!
            val response = try {
                networkHelper.getLastMessages(lastKnownMessageId)
            } catch (e: Exception) {
                null
            }
            val newMessages = response?.first ?: emptyList()
            val initialSize = messages.size
            newMessages.forEach {
                val imageId = Date().time
                try {
                    messagesDatabase.insertMessage(it.toEntity(imageId))
                    if (it.data.Image != null) {
                        it.data.Image.bitmap = compressImage(getImageFromCache(imageId))
                    }
                    messages += it
                } catch (e: Exception) {
                }
            }
            if (newMessages.isNotEmpty()) {
                val updatedSize = messages.size
                val intent = Intent(TAG)
                intent.putExtra("type", NEW_MESSAGES)
                intent.putExtra("initialSize", initialSize)
                intent.putExtra("updatedSize", updatedSize)
                LocalBroadcastManager.getInstance(this@ChatService).sendBroadcast(intent)
            }
            if ((response?.second ?: 500) >= 500) {
                sendIntent(SERVER_ERROR)
            }
            semaphore.release()
        }
    }

    private suspend fun updateImages() {
        withContext(Dispatchers.IO) {
            semaphore.acquire()
            messages.forEachIndexed { index, it ->
                it.data.Image ?: return@forEachIndexed
                if (it.data.Image.bitmap == null) {
                    try {
                        val imageId = messagesDatabase.getMessageItemIdById(it.id!!)
                        var image = getImageFromCache(imageId)
                        if (image != null) {
                            messages[index].data.Image!!.bitmap = image
                        } else {
                            writeImageToCache(it, imageId)
                            image = getImageFromCache(imageId)
                        }
                        messages[index].data.Image!!.bitmap = compressImage(image)
                        val intent = Intent(TAG)
                        intent.putExtra("type", NEW_IMAGE)
                        intent.putExtra("position", index)
                        LocalBroadcastManager.getInstance(this@ChatService).sendBroadcast(intent)
                    } catch (e: Exception) {
                        return@forEachIndexed
                    }
                }
            }
            semaphore.release()
        }
    }

    private fun writeImageToCache(message: Message, imageId: Long) {
        val response = networkHelper.downloadFullImage(message.data.Image!!.link)
        val image = response.first
        image ?: return
        val file =
            File(this@ChatService.cacheDir, "$imageId.png").also { it.createNewFile() }
        val bos = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 0, bos)
        val bitmapData = bos.toByteArray()
        FileOutputStream(file).use {
            with(it) {
                write(bitmapData)
                flush()
            }
        }
    }

    private suspend fun prepareAndSendImageMessage(uri: Uri) {
        if (uri == Uri.EMPTY) {
            sendIntent(SEND_IMAGE_FAILED, "Uri is empty")
            return
        }

        val image = getImageFromStorage(uri)

        if (image == null) {
            sendIntent(SEND_IMAGE_FAILED, "Image is null")
            return
        }

        val code = Date().time.toString()
        val file = try {
            getTempFile(image, code)
        } catch (e: Exception) {
            sendIntent(SEND_IMAGE_FAILED, "Can't create temp file")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val responseCode = networkHelper.sendImageMessage(file)
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    when (responseCode) {
                        in 500..599 -> sendIntent(SERVER_ERROR)
                        404 -> sendIntent(NOT_FOUND, "User not found")
                        409 -> sendIntent(CONFLICT, "Image already exists")
                        413 -> sendIntent(LARGE_PAYLOAD, "Image is too big")
                        else -> sendIntent(
                            SEND_IMAGE_FAILED,
                            "Unknown error, http code: $responseCode"
                        )
                    }
                }
                scope.launch {
                    updateMessages()
                }
            } catch (e: Exception) {
                failedMessagesDatabase.insertFailedMessage(
                    FailedMessagesEntity(
                        0,
                        USERNAME,
                        "1@channel",
                        null,
                        uri.toString()
                    )
                )
            } finally {
                file.delete()
            }
        }
    }

    private fun getTempFile(image: Bitmap, code: String): File {
        val file = File(this.cacheDir, "${code}temp.png")
        file.createNewFile()
        val bos = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 0, bos)
        val bitmapData = bos.toByteArray()
        val fos = FileOutputStream(file)
        fos.write(bitmapData)
        fos.flush()
        fos.close()
        return file
    }

    private fun getImageFromStorage(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT < 28) {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(
                    this.contentResolver,
                    uri
                )
            } else {
                val source = ImageDecoder.createSource(this.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun prepareAndSendTextMessage(
        text: String,
        from: String = USERNAME,
        to: String = "1@channel"
    ) {
        if (text.isNotEmpty()) {
            val message = Message(
                from,
                to,
                Data(Text = Text(text)),
                Date().time.toString()
            )
            kotlin.runCatching {
                sendMapper.writeValueAsString(message).replaceFirst("text", "Text")
            }.onSuccess { json ->
                withContext(Dispatchers.IO) {
                    try {
                        println(json)
                        val responseCode = networkHelper.sendTextMessage(json)
                        if (responseCode != 200) {
                            when (responseCode) {
                                in 500..599 -> sendIntent(SERVER_ERROR)
                                404 -> sendIntent(NOT_FOUND, "User not found")
                                413 -> sendIntent(LARGE_PAYLOAD, "Message is too big")
                                else -> sendIntent(
                                    SEND_MESSAGE_FAILED,
                                    "Unknown error, http code: $responseCode"
                                )
                            }
                        }
                        scope.launch {
                            updateMessages()
                        }
                    } catch (e: Exception) {
                        failedMessagesDatabase.insertFailedMessage(message.toFailedEntity(null))
                    }
                }
            }
        } else {
            sendIntent(ERROR, "Message can't be empty")
        }
    }

    private fun Message.toFailedEntity(path: String?): FailedMessagesEntity {
        return FailedMessagesEntity(
            0,
            this.from,
            this.to,
            this.data.Text?.text,
            path
        )
    }

    private suspend fun sendFailedMessages() {
        withContext(Dispatchers.IO) {
            val failedMessages = failedMessagesDatabase.getAllFailedMessages()
            failedMessages.forEach {
                if (it.imagePath != null) {
                    prepareAndSendImageMessage(Uri.parse(it.imagePath))
                } else {
                    prepareAndSendTextMessage(it.text!!, it.from, it.to)
                }
                failedMessagesDatabase.deleteFailedMessage(it)
            }
        }
    }

    private fun sendIntent(type: Int, text: String = "") {
        val intent = Intent(TAG)
        intent.putExtra("type", type)
        intent.putExtra("text", text)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /*private fun sendTextMessage(json: String) {
        val url = URL("http://213.189.221.170:8008/1ch")
        val connection = url.openConnection() as HttpURLConnection
        val message = json.toByteArray(StandardCharsets.UTF_8)
        val outLength = message.size
        connection.apply {
            requestMethod = "POST"
            doInput = true
            connectTimeout = 2000
        }

        connection.setFixedLengthStreamingMode(outLength)
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.connect()
        connection.outputStream.use { os -> os.write(message) }
        Log.i(TAG, "send message response code: ${connection.responseCode}")
        if (connection.responseCode != 200) {
            sendIntent(SEND_MESSAGE_FAILED, connection.responseCode.toString())
        }
        connection.disconnect()
        updateMessages()
    }*/

    /*private fun getImages(messages: MutableList<Message>) {
        messages.forEach { message ->
            if (message.data.Image?.link?.isNotEmpty() == true) {
                message.data.Image.bitmap = downloadThumbImage(message.data.Image.link)
            }
        }
    }

    private fun downloadFullImage(link: String): Bitmap? {
        val url = URL("http://213.189.221.170:8008/img/$link")
        return downloadImage(url)
    }

    private fun downloadThumbImage(link: String): Bitmap? {
        val url = URL("http://213.189.221.170:8008/thumb/$link")
        return downloadImage(url)
    }*/

    private fun downloadImage(url: URL): Bitmap? {
        return try {
            val photo = url.openStream().use {
                BitmapFactory.decodeStream(it)
            }
            return photo
        } catch (e: Exception) {
            null
        }
    }

    private fun MessageEntity.toMessage(): Message {
        return if (this.text != null) {
            Message(
                id = this.id,
                from = this.from,
                to = this.to,
                data = Data(Text = Text(this.text)),
                time = this.time
            )
        } else {
            Message(
                id = this.id,
                from = this.from,
                to = this.to,
                data = Data(
                    Image = Image(
                        link = this.link!!,
                        bitmap = null
                    )
                ),
                time = this.time
            )
        }
    }

    private fun Message.toEntity(imageId: Long): MessageEntity {
        if (this.data.Text != null) {
            return MessageEntity(
                this.id!!,
                null,
                this.from,
                this.to,
                this.data.Text.text,
                null,
                this.time
            )
        } else {
            val entity = MessageEntity(
                this.id!!,
                imageId,
                this.from,
                this.to,
                null,
                this.data.Image!!.link,
                this.time
            )
            return try {
                writeImageToCache(this, imageId)
                entity
            } catch (e: Exception) {
                entity
            }
        }
    }
}