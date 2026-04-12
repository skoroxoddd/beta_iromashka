package com.iromashka

import android.app.Application
import com.iromashka.network.WsClient
import com.iromashka.network.WsEvent
import com.iromashka.storage.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.util.Log

class App : Application() {

    val wsHolder = WsHolder()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

class WsHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: WsClient? = null

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    var isConnected = false
        private set

    fun connect(uin: Long, token: String) {
        if (isConnected) {
            Log.d("WsHolder", "Already connected")
            return
        }
        Log.d("WsHolder", "Connecting WS for UIN $uin")
        client = WsClient(
            scope = scope,
            uinProvider = { uin },
            tokenProvider = { token }
        ).also { ws ->
            scope.launch {
                ws.events.collect { event ->
                    _events.emit(event)
                    when (event) {
                        is WsEvent.Connected -> isConnected = true
                        is WsEvent.Disconnected -> isConnected = false
                        is WsEvent.AuthFailed -> isConnected = false
                        is WsEvent.Kicked -> isConnected = false
                        is WsEvent.Error -> isConnected = false
                        else -> {}
                    }
                }
            }
            ws.connect()
        }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        isConnected = false
    }

    fun sendMessage(receiverUin: Long, ciphertext: String) {
        client?.sendPersonalMessage(receiverUin, ciphertext)
    }

    fun sendTyping(toUin: Long, isTyping: Boolean) {
        client?.sendTyping(toUin, isTyping)
    }

    fun sendReadReceipt(senderUin: Long, readUntil: Long) {
        client?.sendMsgAck(senderUin, readUntil, "Delivered")
    }

    fun sendStatusChange(status: String) {
        client?.sendStatusChange(status)
    }
}
