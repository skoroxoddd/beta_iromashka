package com.iromashka.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.*
import com.iromashka.network.GroupMessageRequest
import com.iromashka.network.ApiService
import com.iromashka.network.WsClient
import com.iromashka.network.WsEvent
import com.iromashka.storage.*

enum class MessageStatus { Sent, Delivered, Read }

data class ChatMessage(
    val messageId: Long,
    val senderUin: Long,
    val receiverUin: Long,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isE2E: Boolean = true,
    val status: MessageStatus? = null,
    val senderNickname: String = ""
) {
    fun formattedTime(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val api = ApiService.api
    private val db = AppDatabase.getInstance(ctx)
    private val msgDao = db.messageDao()
    private val contactDao = db.contactDao()
    private val grpDao = db.groupMessageDao()

    private var wsClient: WsClient? = null
    private var myPrivKey: java.security.PrivateKey? = null

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected

    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed

    val contacts: Flow<List<ContactEntity>> = contactDao.getAll()

    // Groups
    private val _groups = MutableStateFlow<List<GroupItemDb>>(emptyList())
    val groups: StateFlow<List<GroupItemDb>> = _groups

    data class GroupItemDb(val id: Long, val name: String)

    private val _typingUsers = MutableStateFlow<Set<Long>>(emptySet())
    val typingUsers: StateFlow<Set<Long>> = _typingUsers

    // Current chat messages (StateFlow for ChatScreen)
    private var _currentChatUin: Long = -1
    private val _messagesState = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesState: StateFlow<List<ChatMessage>> = _messagesState

    // Market
    var marketUin: Long? by mutableStateOf(null)
    var marketPrice: Int by mutableStateOf(0)
    var marketLoading: Boolean by mutableStateOf(false)
    var marketError: String? by mutableStateOf(null)

    fun requestUinPurchase(length: Int, mask: String) {
        marketLoading = true
        marketError = null
        marketUin = null
        viewModelScope.launch {
            runCatching {
                // Placeholder: generate a preview UIN matching the mask
                val preview = mask.replace("*", (0..9).random().toString())
                marketUin = preview.toLongOrNull()
                marketPrice = when (length) { 6 -> 500; 7 -> 300; 8 -> 200; else -> 100 }
            }.onFailure {
                marketError = "Ошибка: ${it.message}"
            }
            marketLoading = false
        }
    }

    fun openChat(chatUin: Long) {
        _currentChatUin = chatUin
        viewModelScope.launch {
            msgDao.getMessages(chatUin).collect { list ->
                _messagesState.value = list.map { e ->
                    ChatMessage(
                        messageId = e.id,
                        senderUin = e.senderUin,
                        receiverUin = e.receiverUin,
                        text = e.text,
                        timestamp = e.timestamp,
                        isOutgoing = e.isOutgoing,
                        isE2E = e.isE2E,
                        status = if (e.isOutgoing) MessageStatus.Sent else null
                    )
                }
            }
        }
    }

    // -- Session init

    private val _e2eError = MutableStateFlow<String?>(null)
    val e2eError: StateFlow<String?> = _e2eError

    fun init(pin: String): Boolean {
        val wrappedPriv = Prefs.getWrappedPriv(ctx)
        if (wrappedPriv.isEmpty()) {
            android.util.Log.e("ChatVM", "No wrapped private key found")
            _e2eError.value = "Ключи не найдены. Перерегистрируйтесь."
            return false
        }
        return runCatching {
            myPrivKey = CryptoManager.unwrapPrivateKey(wrappedPriv, pin)
            true
        }.onFailure {
            android.util.Log.e("ChatVM", "E2E init failed: ${it.message}")
            _e2eError.value = "Неверный PIN или ключ повреждён"
        }.getOrDefault(false)
    }

    fun connectWs() {
        val uin = Prefs.getUin(ctx)
        val token = Prefs.getToken(ctx)
        if (uin < 0 || token.isEmpty()) return

        wsClient?.disconnect()
        wsClient = WsClient(uin, token, viewModelScope).also { ws ->
            viewModelScope.launch {
                ws.events.collect { event ->
                    when (event) {
                        is WsEvent.Connected    -> _wsConnected.value = true
                        is WsEvent.Disconnected -> _wsConnected.value = false
                        is WsEvent.AuthFailed   -> {
                            _wsConnected.value = false
                            _authFailed.value = true
                        }
                        is WsEvent.MessageReceived -> handleIncoming(event.envelope)
                        is WsEvent.TypingReceived -> {
                            if (event.typing.is_typing) {
                                _typingUsers.value += event.typing.sender_uin
                            } else {
                                _typingUsers.value -= event.typing.sender_uin
                            }
                        }
                        is WsEvent.Error -> _wsConnected.value = false
                    }
                }
            }
            ws.connect()
        }
        loadGroups()
    }

    fun disconnectWs() {
        wsClient?.disconnect()
        wsClient = null
        _wsConnected.value = false
    }


    // JWT token refresh
    fun refreshToken() {
        val refreshToken = Prefs.getRefreshToken(ctx)
        if (refreshToken.isEmpty() || !Prefs.isLoggedIn(ctx)) return
        viewModelScope.launch {
            runCatching {
                val resp = api.refresh("Bearer $refreshToken", com.iromashka.network.RefreshRequest(refreshToken))
                Prefs.updateToken(ctx, resp.token)
                Prefs.updateRefreshToken(ctx, resp.refresh_token)
                Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
                disconnectWs()
                connectWs()
            }.onFailure { _ ->
                android.util.Log.e("ChatVM", "Token refresh failed")
                _authFailed.value = true
            }
        }
    }

    fun shouldRefreshToken(): Boolean {
        val ts = Prefs.getTokenTimestamp(ctx)
        val age = System.currentTimeMillis() - ts
        return age > 50 * 60 * 1000L
    }

    // -- Groups

    fun loadGroups() {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val list = api.listGroups("Bearer $token")
                _groups.value = list.map { GroupItemDb(it.id, it.name) }
            }.onFailure { e ->
                android.util.Log.w("ChatVM", "Failed to load groups: ${e.message}")
            }
        }
    }

    fun createGroup(name: String, memberUins: List<Long>) {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                api.createGroup("Bearer $token", com.iromashka.network.CreateGroupRequest(name, memberUins))
                loadGroups()
            }.onFailure {
                android.util.Log.e("ChatVM", "Failed to create group: ${it.message}")
            }
        }
    }

    fun addGroupMember(groupId: Long, uin: Long) {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                api.addGroupMember("Bearer $token", groupId, com.iromashka.network.AddMemberBody(uin))
            }.onFailure {
                android.util.Log.e("ChatVM", "Failed to add member: ${it.message}")
            }
        }
    }

    // -- Group messages (local DB only)

    fun getGroupMessages(groupId: Long): Flow<List<ChatMessage>> {
        return grpDao.getMessages(groupId).map { list ->
            list.map { e ->
                ChatMessage(
                    messageId = e.id,
                    senderUin = e.senderUin,
                    receiverUin = 0,
                    text = e.text,
                    timestamp = e.timestamp,
                    isOutgoing = e.senderUin == Prefs.getUin(ctx),
                    isE2E = true,
                    status = if (e.senderUin == Prefs.getUin(ctx)) MessageStatus.Sent else null,
                    senderNickname = e.senderNickname
                )
            }
        }
    }

    fun sendGroupMessage(groupId: Long, text: String) {
        val myUin = Prefs.getUin(ctx)
        @Suppress("UNUSED_VARIABLE") val privKey = myPrivKey ?: return

        viewModelScope.launch {
            runCatching {
                val pubKeyResp = api.getPubKey(myUin)
                val recipPub = CryptoManager.importPublicKey(pubKeyResp.pubkey)
                val ciphertext = CryptoManager.encryptMessage(text, recipPub)

                wsClient?.sendGroupMessage(
                    GroupMessageRequest(group_id = groupId, senderUin = myUin, ciphertext = ciphertext)
                )

                grpDao.insertMessage(GroupMessageEntity(
                    groupId = groupId,
                    senderUin = myUin,
                    senderNickname = Prefs.getNickname(ctx),
                    text = text,
                    timestamp = System.currentTimeMillis()
                ))
            }.onFailure { err ->
                android.util.Log.e("ChatVM", "SendGroupMessage failed: ${err.message}")
            }
        }
    }

    // -- Message handling

    private suspend fun handleIncoming(env: WsEnvelope) {
        val myUin = Prefs.getUin(ctx)
        val privKey = myPrivKey ?: return

        val plaintext = runCatching {
            CryptoManager.decryptMessage(env.ciphertext, privKey)
        }.getOrElse { "[не удалось расшифровать]" } ?: "[не удалось расшифровать]"

        val chatUin = if (env.sender_uin == myUin) env.receiver_uin else env.sender_uin

        msgDao.insertMessage(MessageEntity(
            chatUin = chatUin,
            senderUin = env.sender_uin,
            receiverUin = env.receiver_uin,
            text = plaintext,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            isE2E = true
        ))
    }

    fun sendMessage(toUin: Long, text: String) {
        val myUin = Prefs.getUin(ctx)
        @Suppress("UNUSED_VARIABLE") val privKey = myPrivKey ?: return

        viewModelScope.launch {
            runCatching {
                val pubKeyResp = api.getPubKey(toUin)
                val recipPub = CryptoManager.importPublicKey(pubKeyResp.pubkey)
                val ciphertext = CryptoManager.encryptMessage(text, recipPub)

                wsClient?.sendMessage(WsEnvelope(
                    sender_uin = myUin,
                    receiver_uin = toUin,
                    ciphertext = ciphertext,
                    timestamp = 0
                ))

                msgDao.insertMessage(MessageEntity(
                    chatUin = toUin,
                    senderUin = myUin,
                    receiverUin = toUin,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = true,
                    isE2E = true
                ))
            }.onFailure { err ->
                android.util.Log.e("ChatVM", "SendMessage failed: ${err.message}")
                _wsConnected.value = false
                _e2eError.value = "Ошибка отправки: ${err.message}"
            }
        }
    }

    fun sendTyping(toUin: Long, isTyping: Boolean) {
        wsClient?.sendTyping(toUin, isTyping)
    }

    // -- Phone discovery

    fun discoverContacts(phones: List<String>, onResult: (List<DiscoveredResult>) -> Unit) {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val results = api.discoverContacts("Bearer $token", com.iromashka.network.DiscoverRequest(phones))
                onResult(results.map { DiscoveredResult(it.uin.toLong(), it.phone, it.nickname) })
            }.onFailure {
                onResult(emptyList())
            }
        }
    }

    data class DiscoveredResult(val uin: Long, val phone: String, val nickname: String)

    // -- Contacts

    fun addContact(uin: Long, nickname: String) {
        viewModelScope.launch {
            contactDao.insert(ContactEntity(uin, nickname))
        }
    }

    fun removeContact(uin: Long) {
        viewModelScope.launch {
            contactDao.deleteByUin(uin)
        }
    }

    fun getMessages(chatUin: Long): Flow<List<MessageEntity>> = msgDao.getMessages(chatUin)

    override fun onCleared() {
        super.onCleared()
        disconnectWs()
    }
}
