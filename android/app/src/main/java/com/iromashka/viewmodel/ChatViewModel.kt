package com.iromashka.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.*
import com.iromashka.network.ApiService
import com.iromashka.network.WsClient
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

    private var wsClient: WsClient? = null
    private var myPrivKey: java.security.PrivateKey? = null

    private val pubkeyCache = mutableMapOf<Long, String>()

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected.asStateFlow()

    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    // Status tracking
    private val _statuses = MutableStateFlow<Map<Long, String>>(emptyMap())
    val statuses: StateFlow<Map<Long, String>> = _statuses.asStateFlow()

    fun setUserStatus(status: String) {
        viewModelScope.launch {
            wsClient?.sendStatusChange(status)
        }
    }

    fun updateContactStatus(uin: Long, status: String) {
        viewModelScope.launch {
            _statuses.value = _statuses.value + (uin to status)
        }
    }

    // Current chat
    private var _currentChatUin: Long = -1
    private val _messagesState = MutableStateFlow<List<UIMessage>>(emptyList())
    val messagesState: StateFlow<List<UIMessage>> = _messagesState.asStateFlow()

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

    // ── Session ──

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
        val token = Prefs.getToken(ctx)
        if (Prefs.getUin(ctx) <= 0 || token.isEmpty()) return

        wsClient?.disconnect()
        wsClient = WsClient(
            scope = viewModelScope,
            uinProvider = { Prefs.getUin(ctx) },
            tokenProvider = { Prefs.getToken(ctx) }
        ).also { ws ->
            viewModelScope.launch {
                ws.events.collect { event -> handleWsEvent(event) }
            }
            ws.connect()
        }
    }

    private suspend fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Connected -> _wsConnected.value = true
            is WsEvent.Disconnected -> _wsConnected.value = false
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
                wsClient?.disconnect()
                _authFailed.value = true
            }
            is WsEvent.Error -> _wsConnected.value = false
            is WsEvent.StatusChanged -> {
                updateContactStatus(event.senderUin, event.status)
            }
        }
    }

    private suspend fun handleIncomingMessage(envelope: WsEnvelope) {
        val privKey = myPrivKey ?: return
        val myUin = Prefs.getUin(ctx)

        val plaintext = CryptoManager.decryptMessage(envelope.ciphertext, privKey)
            ?: run { Log.w("ChatVM", "Decrypt failed for ${envelope.sender_uin}"); return }

        val isOutgoing = envelope.sender_uin == myUin

        val msg = MessageEntity(
            senderUin = envelope.sender_uin,
            receiverUin = envelope.receiver_uin,
            plaintext = plaintext,
            timestamp = if (envelope.timestamp > 0) envelope.timestamp * 1000 else System.currentTimeMillis(),
            isMine = isOutgoing
        )
        msgDao.insert(msg)

        // Send ack
        wsClient?.sendMsgAck(envelope.sender_uin, envelope.timestamp, "Delivered")
    }

    fun disconnectWs() {
        wsClient?.disconnect()
        wsClient = null
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

                wsClient?.sendPersonalMessage(toUin, ciphertext)

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

    fun sendTyping(toUin: Long, isTyping: Boolean) {
        wsClient?.sendTyping(toUin, isTyping)
    }

    // ── Public key cache ──

    private suspend fun getPublicKey(uin: Long): java.security.PublicKey {
        pubkeyCache[uin]?.let { return CryptoManager.importPublicKey(it) }
        val resp = api.getPubKey(uin)
        pubkeyCache[uin] = resp.pubkey
        return CryptoManager.importPublicKey(resp.pubkey)
    }

    // ── Contact discovery ──

    suspend fun discoverContacts(phones: List<String>): List<DiscoveredContactItem> {
        val token = Prefs.getToken(ctx) ?: return emptyList()
        return runCatching {
            api.discoverContacts("Bearer $token", DiscoverRequest(phones))
        }.getOrElse { emptyList() }
    }

    // ── Groups ──

    private val _groups = MutableStateFlow<List<GroupItem>>(emptyList())
    val groups: StateFlow<List<GroupItem>> = _groups.asStateFlow()

    fun loadGroups() {
        val token = Prefs.getToken(ctx) ?: return
        viewModelScope.launch {
            runCatching {
                _groups.value = api.listGroups("Bearer $token")
            }
        }
    }

    fun createGroup(name: String, memberUins: List<Long>) {
        val token = Prefs.getToken(ctx) ?: return
        viewModelScope.launch {
            runCatching {
                api.createGroup("Bearer $token", CreateGroupRequest(name, memberUins))
                loadGroups()
            }
        }
    }

    fun sendGroupMessage(groupId: Long, text: String) {
        val privKey = myPrivKey ?: return
        val myUin = Prefs.getUin(ctx)

        viewModelScope.launch {
            runCatching {
                val myPub = CryptoManager.importPublicKey(Prefs.getPubKey(ctx))
                val ciphertext = CryptoManager.encryptMessage(text, myPub)
                wsClient?.sendGroupMessage(groupId, ciphertext)
                msgDao.insert(MessageEntity(
                    senderUin = myUin,
                    receiverUin = 0,
                    plaintext = "[Группа #$groupId] $text",
                    timestamp = System.currentTimeMillis(),
                    isMine = true,
                    isGroup = true,
                    groupId = groupId
                ))
            }
        }
    }

    fun addGroupMember(groupId: Long, uin: Long) {
        val token = Prefs.getToken(ctx) ?: return
        viewModelScope.launch {
            runCatching {
                api.addGroupMember("Bearer $token", groupId, AddMemberBody(uin))
            }
        }
    }

    // ── Contacts list ──

    fun getContacts(): Flow<List<Contact>> {
        val myUin = Prefs.getUin(ctx)
        return msgDao.getRecentChats(myUin).map { entities ->
            entities.mapNotNull { e ->
                val partnerUin = if (e.senderUin == myUin) e.receiverUin else e.senderUin
                if (partnerUin <= 0) return@mapNotNull null
                Contact(
                    uin = partnerUin,
                    nickname = "UIN $partnerUin",
                    isOnline = false,
                    unread = 0,
                    lastMessage = e.plaintext.take(50),
                    lastMessageTime = e.timestamp
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectWs()
    }
}
