package com.iromashka.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.iromashka.App
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.*
import com.iromashka.network.ApiService
import com.iromashka.network.WsEvent
import com.iromashka.storage.*

enum class MessageStatus { Sending, Sent, Delivered, Read }

data class UIMessage(
    val id: Long = 0,
    val senderUin: Long,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageStatus? = null
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val api = ApiService.api
    private val db = AppDatabase.getInstance(ctx)
    private val msgDao = db.messageDao()
    private val wsHolder get() = App.instance.wsHolder

    private var myPrivKey: java.security.PrivateKey? = null
    private val pubkeyCache = mutableMapOf<Long, String>()

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected.asStateFlow()

    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    private val _statuses = MutableStateFlow<Map<Long, String>>(emptyMap())
    val statuses: StateFlow<Map<Long, String>> = _statuses.asStateFlow()

    // Current chat
    private var _currentChatUin: Long = -1
    private val _messagesState = MutableStateFlow<List<UIMessage>>(emptyList())
    val messagesState: StateFlow<List<UIMessage>> = _messagesState.asStateFlow()

    init {
        // Listen to WsHolder events
        viewModelScope.launch {
            wsHolder.events.collect { event -> handleWsEvent(event) }
        }
    }

    fun setUserStatus(status: String) {
        wsHolder.sendStatusChange(status)
    }

    fun updateContactStatus(uin: Long, status: String) {
        viewModelScope.launch {
            _statuses.value = _statuses.value + (uin to status)
        }
    }

    fun openChat(chatUin: Long) {
        _currentChatUin = chatUin
        viewModelScope.launch {
            msgDao.getMessages(chatUin).collect { list ->
                _messagesState.value = list.map { e ->
                    UIMessage(
                        id = e.id,
                        senderUin = e.senderUin,
                        text = e.plaintext,
                        timestamp = e.timestamp,
                        isOutgoing = e.isMine,
                        status = if (e.isMine) MessageStatus.Delivered else null
                    )
                }
            }
        }
    }

    fun initSession(pin: String): Boolean {
        val wrapped = Prefs.getWrappedPriv(ctx)
        if (wrapped.isEmpty()) {
            Log.e("ChatVM", "No wrapped private key")
            return false
        }
        return runCatching {
            myPrivKey = CryptoManager.unwrapPrivateKey(wrapped, pin)
            true
        }.getOrElse { false }
    }

    fun connectWs() {
        val uin = Prefs.getUin(ctx)
        val token = Prefs.getToken(ctx)
        if (uin <= 0 || token.isEmpty()) return
        wsHolder.connect(uin, token)
    }

    private suspend fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Connected -> {
                Log.d("ChatVM", "WS connected")
                _wsConnected.value = true
            }
            is WsEvent.Disconnected -> {
                Log.d("ChatVM", "WS disconnected")
                _wsConnected.value = false
            }
            is WsEvent.AuthFailed -> {
                _wsConnected.value = false
                _authFailed.value = true
            }
            is WsEvent.MessageReceived -> handleIncomingMessage(event.envelope)
            is WsEvent.TypingReceived -> { /* handled by UI */ }
            is WsEvent.ReadReceiptReceived -> { /* update status */ }
            is WsEvent.MsgAckReceived -> { /* update delivery status */ }
            is WsEvent.Kicked -> {
                Log.w("ChatVM", "Kicked: ${event.reason}")
                _authFailed.value = true
            }
            is WsEvent.Error -> {
                Log.e("ChatVM", "WS error: ${event.msg}")
                _wsConnected.value = false
            }
            is WsEvent.StatusChanged -> {
                updateContactStatus(event.senderUin, event.status)
            }
        }
    }

    private suspend fun handleIncomingMessage(envelope: WsEnvelope) {
        val privKey = myPrivKey
        if (privKey == null) {
            // No key — save encrypted text
            val msg = MessageEntity(
                senderUin = envelope.sender_uin,
                receiverUin = envelope.receiver_uin,
                plaintext = envelope.ciphertext,
                timestamp = if (envelope.timestamp > 0) envelope.timestamp * 1000 else System.currentTimeMillis(),
                isMine = false
            )
            msgDao.insert(msg)
            return
        }

        val plaintext = CryptoManager.decryptMessage(envelope.ciphertext, privKey)
            ?: run { Log.w("ChatVM", "Decrypt failed for ${envelope.sender_uin}"); return }

        val myUin = Prefs.getUin(ctx)
        val isOutgoing = envelope.sender_uin == myUin

        val msg = MessageEntity(
            senderUin = envelope.sender_uin,
            receiverUin = envelope.receiver_uin,
            plaintext = plaintext,
            timestamp = if (envelope.timestamp > 0) envelope.timestamp * 1000 else System.currentTimeMillis(),
            isMine = isOutgoing
        )
        msgDao.insert(msg)
        wsHolder.sendReadReceipt(envelope.sender_uin, envelope.timestamp)
    }

    fun disconnectWs() {
        _wsConnected.value = false
    }

    // ── Sending ──

    fun sendMessage(toUin: Long, text: String) {
        val privKey = myPrivKey ?: return
        val myUin = Prefs.getUin(ctx)

        viewModelScope.launch {
            runCatching {
                val recipPub = getPublicKey(toUin)
                val ciphertext = CryptoManager.encryptMessage(text, recipPub)
                wsHolder.sendMessage(toUin, ciphertext)

                msgDao.insert(MessageEntity(
                    senderUin = myUin,
                    receiverUin = toUin,
                    plaintext = text,
                    timestamp = System.currentTimeMillis(),
                    isMine = true
                ))
            }.onFailure { e ->
                Log.e("ChatVM", "Send failed: ${e.message}")
            }
        }
    }

    // ── Contacts ──

    fun getContacts(): Flow<List<Contact>> = db.contactDao().getAllContacts()

    fun addContact(uin: Long, nickname: String) {
        viewModelScope.launch {
            db.contactDao().insert(ContactEntity(uin, nickname))
        }
    }

    fun removeContact(uin: Long) {
        viewModelScope.launch {
            db.contactDao().delete(uin)
        }
    }

    fun discoverContacts(phones: List<String>): Flow<List<DiscoveredContactItem>> = flow {
        emit(api.discoverContacts(phones))
    }

    // ── Groups ──

    private val _groups = MutableStateFlow<List<GroupItem>>(emptyList())
    val groups: StateFlow<List<GroupItem>> = _groups.asStateFlow()

    fun loadGroups() {
        viewModelScope.launch {
            runCatching {
                _groups.value = api.listGroups("Bearer ${Prefs.getToken(ctx)}")
            }
        }
    }

    // ── PubKey ──

    private suspend fun getPublicKey(uin: Long): String {
        pubkeyCache[uin]?.let { return it }
        return runCatching {
            val resp = api.getPubKey(uin)
            pubkeyCache[uin] = resp.pubkey
            resp.pubkey
        }.getOrElse { "" }
    }
}
