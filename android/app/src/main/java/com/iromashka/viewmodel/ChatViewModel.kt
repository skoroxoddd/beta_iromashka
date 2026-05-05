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
import com.iromashka.network.GroupSendRequest
import com.iromashka.network.GroupKeyEntry
import com.iromashka.network.SetGroupKeysRequest
import com.iromashka.network.ApiService
import com.iromashka.network.WsClient
import com.iromashka.network.WsEvent
import com.iromashka.storage.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
    private val pubkeyCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected

    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed

    val contacts: Flow<List<ContactEntity>> = contactDao.getAll()

    // Groups
    private val _groups = MutableStateFlow<List<GroupItemDb>>(emptyList())
    val groups: StateFlow<List<GroupItemDb>> = _groups

    data class GroupItemDb(val id: Long, val name: String)

    // Group key cache
    private val groupKeyCache = java.util.concurrent.ConcurrentHashMap<Long, javax.crypto.SecretKey>()

    private val _typingUsers = MutableStateFlow<Set<Long>>(emptySet())
    val typingUsers: StateFlow<Set<Long>> = _typingUsers

    // Current chat messages (StateFlow for ChatScreen)
    private var _currentChatUin: Long = -1
    private val _messagesState = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesState: StateFlow<List<ChatMessage>> = _messagesState
    private var chatCollectionJob: Job? = null
    private val ttlJobs = mutableMapOf<Long, Job>()

    private val _scrollToBottom = MutableStateFlow(0L)
    val scrollToBottom: StateFlow<Long> = _scrollToBottom

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
        chatCollectionJob?.cancel()
        _currentChatUin = chatUin
        chatCollectionJob = viewModelScope.launch {
            msgDao.getMessages(chatUin).collect { list ->
                if (_currentChatUin == chatUin) {
                    _messagesState.value = list.map { e ->
                        ChatMessage(
                            messageId = e.id,
                            senderUin = e.senderUin,
                            receiverUin = e.receiverUin,
                            text = if (e.isEdited) e.text + "  · изм." else e.text,
                            timestamp = e.timestamp,
                            isOutgoing = e.isOutgoing,
                            isE2E = e.isE2E,
                            status = when {
                                !e.isOutgoing -> null
                                e.isRead -> MessageStatus.Read
                                else -> MessageStatus.Sent
                            }
                        )
                    }
                }
            }
        }
        syncHistoryForChat(chatUin)
    }

    // Try refresh JWT inline. Returns new token on success, null on failure.
    // Used by sync paths that hit 401 mid-session.
    private suspend fun tryRefreshTokenSync(): String? {
        val refresh = Prefs.getRefreshToken(ctx)
        if (refresh.isEmpty()) return null
        return runCatching {
            val resp = api.refresh("Bearer $refresh", com.iromashka.network.RefreshRequest(refresh))
            Prefs.updateToken(ctx, resp.token)
            Prefs.updateRefreshToken(ctx, resp.refresh_token)
            Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
            android.util.Log.i("ChatVM", "JWT refreshed inline")
            resp.token
        }.onFailure { android.util.Log.w("ChatVM", "Inline JWT refresh failed: ${it.message}") }
            .getOrNull()
    }

    private suspend fun fetchSyncedWithRetry(token: String, since: Long, limit: Int = 500): Pair<String, List<com.iromashka.network.SyncedMessageItem>>? {
        var bearer = token
        return runCatching {
            api.getSyncedMessages("Bearer $bearer", since = since, limit = limit)
        }.fold(
            onSuccess = { Pair(bearer, it) },
            onFailure = { e ->
                val is401 = (e as? retrofit2.HttpException)?.code() == 401
                if (!is401) {
                    android.util.Log.w("ChatVM", "sync failed (no retry): ${e.message}")
                    return null
                }
                android.util.Log.w("ChatVM", "sync got 401 — refreshing token and retrying")
                val fresh = tryRefreshTokenSync() ?: return null
                bearer = fresh
                runCatching {
                    api.getSyncedMessages("Bearer $bearer", since = since, limit = limit)
                }.onFailure { android.util.Log.w("ChatVM", "sync retry failed: ${it.message}") }
                    .getOrNull()?.let { Pair(bearer, it) }
            }
        )
    }

    // Fetch history from server for a specific chat
    private fun syncHistoryForChat(chatUin: Long) {
        val myUin = Prefs.getUin(ctx)
        val token = Prefs.getToken(ctx)
        val privKey = myPrivKey ?: return
        if (token.isEmpty()) return

        viewModelScope.launch {
            runCatching {
                // Get latest local timestamp to only fetch newer messages
                val lastLocal = msgDao.getLastMessage(chatUin)?.timestamp ?: 0L
                val pair = fetchSyncedWithRetry(token, since = lastLocal) ?: return@runCatching
                val items = pair.second
                // Filter only messages related to this chat
                val relevant = items.filter {
                    (it.sender_uin == chatUin && it.receiver_uin == myUin) ||
                    (it.sender_uin == myUin && it.receiver_uin == chatUin)
                }
                android.util.Log.d("ChatVM", "syncHistoryForChat $chatUin since=$lastLocal items=${items.size} relevant=${relevant.size}")
                for (item in relevant) {
                    val isOutgoing = item.sender_uin == myUin
                    val plaintext: String? = if (item.sender_uin == 0L) {
                        // System message — plaintext
                        item.ciphertext
                    } else if (isOutgoing) {
                        // F3: outgoing — prefer sender_ciphertext (encrypted to my own pubkey).
                        item.sender_ciphertext
                            ?.let { runCatching { CryptoManager.decryptMessage(it, privKey) }.getOrNull() }
                            ?: runCatching { CryptoManager.decryptMessage(item.ciphertext, privKey) }.getOrNull()
                    } else {
                        runCatching { CryptoManager.decryptMessage(item.ciphertext, privKey) }.getOrNull()
                    }
                    if (plaintext == null) continue
                    // Only insert if not already present (by timestamp+uin)
                    val existing = msgDao.getByTimestampAndUins(item.timestamp, item.sender_uin, item.receiver_uin)
                    if (existing == null) {
                        msgDao.insertMessage(MessageEntity(
                            chatUin = chatUin,
                            senderUin = item.sender_uin,
                            receiverUin = item.receiver_uin,
                            text = plaintext,
                            timestamp = item.timestamp,
                            isOutgoing = isOutgoing,
                            isE2E = true
                        ))
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("ChatVM", "History sync failed: ${e.message}")
            }
        }
    }

    // -- Session init

    private val _e2eError = MutableStateFlow<String?>(null)
    val e2eError: StateFlow<String?> = _e2eError

    private val _initInProgress = MutableStateFlow(false)
    val initInProgress: StateFlow<Boolean> = _initInProgress

    fun init(pin: String, onResult: (Boolean) -> Unit) {
        val wrappedPriv = Prefs.getWrappedPriv(ctx)
        if (wrappedPriv.isEmpty()) {
            android.util.Log.e("ChatVM", "No wrapped private key found")
            _e2eError.value = "Ключи не найдены. Перерегистрируйтесь."
            onResult(false)
            return
        }
        _initInProgress.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                CryptoManager.unwrapPrivateKey(wrappedPriv, pin)
            }.onFailure {
                android.util.Log.e("ChatVM", "E2E init failed: ${it.message}")
                _e2eError.value = "Неверный PIN или ключ повреждён"
            }.getOrNull()
            withContext(Dispatchers.Main) {
                myPrivKey = result
                _initInProgress.value = false
                onResult(result != null)
            }
        }
    }

    // Used when wrappedPriv is missing (EncryptedSharedPreferences cleared, reinstall, etc.)
    // Recovers the key from server via login API, then unlocks
    fun initWithServerRecovery(pin: String, onResult: (Boolean) -> Unit) {
        val uin = Prefs.getUin(ctx)
        val wrappedPriv = Prefs.getWrappedPriv(ctx)

        // Fast path: key exists locally
        if (wrappedPriv.isNotEmpty()) {
            init(pin, onResult)
            return
        }

        // Key missing — recover from server
        viewModelScope.launch {
            val ok = runCatching {
                val resp = api.login(com.iromashka.model.LoginRequest(uin, pin))
                val recovered = resp.encrypted_key
                    ?: run {
                        // No PIN-wrapped key on server, no local copy — auto-reset identity and bootstrap fresh.
                        android.util.Log.w("ChatVM", "Auto-reset: server has identity but no wrapped key for UIN $uin")
                        runCatching {
                            val ch = api.identityResetChallenge("Bearer ${resp.token}")
                            api.identityReset("Bearer ${resp.token}", com.iromashka.network.IdentityResetRequest(uin, pin, ch.nonce))
                        }
                        // Generate new keypair locally
                        val kp = CryptoManager.generateKeyPair()
                        val pubB64 = CryptoManager.exportPublicKey(kp.public)
                        val wrapped = CryptoManager.wrapPrivateKey(kp.private, pin)
                        Prefs.updateToken(ctx, resp.token)
                        Prefs.updateRefreshToken(ctx, resp.refresh_token)
                        Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
                        Prefs.updateWrappedPriv(ctx, wrapped)
                        Prefs.updatePubKey(ctx, pubB64)
                        // Push pubkey + save wrapped to server so next device can unwrap
                        runCatching { api.updatePubkey("Bearer ${resp.token}", com.iromashka.network.UpdatePubkeyRequest(pubB64)) }
                        runCatching { api.saveUserKey("Bearer ${resp.token}", com.iromashka.network.SaveKeyRequest(wrapped, "")) }
                        myPrivKey = kp.private
                        return@runCatching true
                    }

                Prefs.updateToken(ctx, resp.token)
                Prefs.updateRefreshToken(ctx, resp.refresh_token)
                Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
                Prefs.updateWrappedPriv(ctx, recovered)

                myPrivKey = CryptoManager.unwrapPrivateKey(recovered, pin)
                android.util.Log.i("ChatVM", "Key recovered from server for UIN $uin")
                true
            }.onFailure {
                android.util.Log.e("ChatVM", "Server key recovery failed: ${it.message}")
                _e2eError.value = "Неверный PIN или ключ не найден на сервере"
            }.getOrDefault(false)
            onResult(ok)
        }
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
                        is WsEvent.Connected    -> {
                            _wsConnected.value = true
                            runCatching { contactDao.resetAllToOffline() }
                            runCatching {
                                val n = db.maintenanceDao().purgeUndecryptablePlaceholders()
                                if (n > 0) android.util.Log.i("ChatVM", "purged $n undecryptable placeholders")
                            }
                            ensureBotContact()
                            syncAllHistory()
                            syncPhoneContacts()
                        }
                        is WsEvent.Disconnected -> _wsConnected.value = false
                        is WsEvent.AuthFailed   -> {
                            _wsConnected.value = false
                            _authFailed.value = true
                        }
                        is WsEvent.MessageReceived -> handleIncoming(event.envelope)
                        is WsEvent.GroupMessageReceived -> handleGroupIncoming(event)
                        is WsEvent.TypingReceived -> {
                            if (event.typing.is_typing) {
                                _typingUsers.value += event.typing.sender_uin
                            } else {
                                _typingUsers.value -= event.typing.sender_uin
                            }
                        }
                        is WsEvent.ReadReceiptReceived -> {
                            // event.receiverUin = author of the messages that got read
                            // event.senderUin   = the reader (peer)
                            val myUin = Prefs.getUin(ctx)
                            if (event.receiverUin == myUin) {
                                msgDao.markReadUntil(event.senderUin, event.readUntil)
                            }
                        }
                        is WsEvent.MessageDeleted -> {
                            msgDao.deleteByTs(event.timestamp, event.senderUin, event.receiverUin)
                        }
                        is WsEvent.MessageEdited -> {
                            val privKey = myPrivKey
                            val plain = if (privKey != null) {
                                runCatching { CryptoManager.decryptMessage(event.ciphertext, privKey) }.getOrNull()
                                    ?: "[не удалось расшифровать]"
                            } else event.ciphertext
                            msgDao.updateText(event.timestamp, event.senderUin, event.receiverUin, plain)
                        }
                        is WsEvent.PubkeyChanged -> {
                            val myUin = Prefs.getUin(ctx)
                            if (event.uin == myUin) {
                                android.util.Log.w("ChatVM", "pubkey_changed for SELF UIN $myUin — ignoring (would invalidate own cache)")
                            } else {
                                pubkeyCache.remove(event.uin)
                                android.util.Log.i("ChatVM", "pubkey_changed → invalidated cache for ${event.uin}")
                            }
                        }
                        is WsEvent.UserStatusReceived -> {
                            android.util.Log.d("ChatVM", "UserStatus uin=${event.uin} status=${event.status}")
                            runCatching {
                                val existing = contactDao.getByUin(event.uin)
                                if (existing == null) {
                                    contactDao.insert(ContactEntity(event.uin, "UIN ${event.uin}", status = event.status, lastSeen = System.currentTimeMillis()))
                                } else {
                                    contactDao.updateStatus(event.uin, event.status, System.currentTimeMillis())
                                }
                            }.onFailure { android.util.Log.w("ChatVM", "updateStatus failed: ${it.message}") }
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
        val privKey = myPrivKey ?: return
        val token = Prefs.getToken(ctx)

        viewModelScope.launch {
            runCatching {
                // Get or create group key
                val groupKey = getOrCreateGroupKey(groupId, token, privKey)
                    ?: throw IllegalStateException("Не удалось получить ключ группы")

                // Encrypt message with group key
                val ciphertext = com.iromashka.crypto.GroupCrypto.encryptWithGroupKey(text, groupKey)

                // Send via HTTP API (more reliable than WS for groups)
                api.sendGroupMessage("Bearer $token", groupId, GroupSendRequest(ciphertext))

                // Save locally
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

    private suspend fun getOrCreateGroupKey(groupId: Long, token: String, privKey: java.security.PrivateKey): javax.crypto.SecretKey? {
        // Check cache first
        groupKeyCache[groupId]?.let { return it }

        // Try to fetch from server
        runCatching {
            val resp = api.getGroupKey("Bearer $token", groupId)
            val decrypted = com.iromashka.crypto.GroupCrypto.decryptGroupKey(resp.encrypted_key, privKey)
            if (decrypted != null) {
                val key = com.iromashka.crypto.GroupCrypto.importGroupKey(decrypted)
                groupKeyCache[groupId] = key
                return key
            }
        }

        // No key on server — create and distribute
        return distributeGroupKey(groupId, token, privKey)
    }

    private suspend fun distributeGroupKey(groupId: Long, token: String, privKey: java.security.PrivateKey): javax.crypto.SecretKey? {
        val myUin = Prefs.getUin(ctx)

        // Generate new group key
        val groupKey = com.iromashka.crypto.GroupCrypto.generateGroupKey()
        val groupKeyB64 = com.iromashka.crypto.GroupCrypto.exportGroupKey(groupKey)

        // Get group members
        val members = runCatching { api.getGroupMembers("Bearer $token", groupId) }.getOrNull() ?: return null

        // Encrypt key for each member
        val keys = mutableListOf<GroupKeyEntry>()

        // Include self
        val allUins = (members.map { it.uin } + myUin).distinct()

        for (uin in allUins) {
            val pubKeyResp = runCatching { api.getPubKey("Bearer $token", uin) }.getOrNull() ?: continue
            val encrypted = com.iromashka.crypto.GroupCrypto.encryptGroupKeyForMember(groupKeyB64, pubKeyResp.pubkey)
            if (encrypted != null) {
                keys.add(GroupKeyEntry(uin, encrypted))
            }
        }

        if (keys.isEmpty()) return null

        // Upload to server
        runCatching {
            api.setGroupKeys("Bearer $token", groupId, SetGroupKeysRequest(keys))
        }.onFailure {
            android.util.Log.e("ChatVM", "Failed to distribute group key: ${it.message}")
            return null
        }

        groupKeyCache[groupId] = groupKey
        return groupKey
    }

    // -- Message handling

    private suspend fun handleIncoming(env: WsEnvelope) {
        val myUin = Prefs.getUin(ctx)
        val privKey = myPrivKey
        if (privKey == null) {
            android.util.Log.e("ChatVM", "handleIncoming: privKey is NULL — message dropped sender=${env.sender_uin} ts=${env.timestamp}")
            return
        }
        android.util.Log.i("ChatVM", "handleIncoming sender=${env.sender_uin} receiver=${env.receiver_uin} ct_len=${env.ciphertext.length} ts=${env.timestamp}")

        // System messages (sender_uin=0) are plaintext — no E2E decryption needed
        val raw = if (env.sender_uin == 0L) {
            env.ciphertext
        } else runCatching {
            CryptoManager.decryptMessage(env.ciphertext, privKey)
        }.onFailure { e ->
            android.util.Log.w("ChatVM", "handleIncoming decrypt FAILED: ${e.message}")
        }.getOrElse { "[не удалось расшифровать]" } ?: run {
            android.util.Log.w("ChatVM", "handleIncoming decrypt returned null")
            "[не удалось расшифровать]"
        }
        android.util.Log.d("ChatVM", "handleIncoming decrypted len=${raw.length}")

        // Unwrap D5 envelope: {"t":"...","_ttl":N,...}
        var displayText = raw
        var ttlSec = 0
        runCatching {
            if (raw.startsWith("{")) {
                val obj = org.json.JSONObject(raw)
                if (obj.has("t")) {
                    displayText = obj.optString("t", raw)
                    ttlSec = obj.optInt("_ttl", 0)
                }
            }
        }

        val chatUin = if (env.sender_uin == myUin) env.receiver_uin else env.sender_uin
        // Use server-provided timestamp so msgs ordering matches server, and dedup by ts works.
        val msgTs = if (env.timestamp > 0) env.timestamp else System.currentTimeMillis()
        val existing = msgDao.getByTimestampAndUins(msgTs, env.sender_uin, env.receiver_uin)
        if (existing != null) {
            android.util.Log.d("ChatVM", "handleIncoming dup ts=$msgTs — skip insert")
            return
        }
        msgDao.insertMessage(MessageEntity(
            chatUin = chatUin,
            senderUin = env.sender_uin,
            receiverUin = env.receiver_uin,
            text = displayText,
            timestamp = msgTs,
            isOutgoing = env.sender_uin == myUin,
            isE2E = true
        ))
        android.util.Log.i("ChatVM", "handleIncoming inserted chat=$chatUin ts=$msgTs ttl=$ttlSec")
        if (ttlSec > 0) scheduleTtlDelete(chatUin, msgTs, ttlSec)
    }

    private suspend fun handleGroupIncoming(event: WsEvent.GroupMessageReceived) {
        val myUin = Prefs.getUin(ctx)
        val privKey = myPrivKey ?: return
        val token = Prefs.getToken(ctx)

        // Skip own messages (already saved locally when sent)
        if (event.senderUin == myUin) return

        // Get group key and decrypt
        val groupKey = getOrCreateGroupKey(event.groupId, token, privKey)
        val plaintext = if (groupKey != null) {
            com.iromashka.crypto.GroupCrypto.decryptWithGroupKey(event.ciphertext, groupKey)
                ?: "[не удалось расшифровать]"
        } else {
            "[нет ключа группы]"
        }

        // Get sender nickname
        val senderNick = runCatching {
            contactDao.getByUin(event.senderUin)?.nickname
        }.getOrNull() ?: "UIN ${event.senderUin}"

        grpDao.insertMessage(GroupMessageEntity(
            groupId = event.groupId,
            senderUin = event.senderUin,
            senderNickname = senderNick,
            text = plaintext,
            timestamp = event.timestamp
        ))
    }

    private fun scheduleTtlDelete(chatUin: Long, ts: Long, ttlSec: Int) {
        ttlJobs[ts]?.cancel()
        ttlJobs[ts] = viewModelScope.launch {
            kotlinx.coroutines.delay(ttlSec * 1000L)
            msgDao.deleteByTs(ts, chatUin, Prefs.getUin(ctx))
            msgDao.deleteByTs(ts, Prefs.getUin(ctx), chatUin)
            ttlJobs.remove(ts)
        }
    }

    fun sendMessage(toUin: Long, text: String) {
        val myUin = Prefs.getUin(ctx)
        val privKey = myPrivKey
        if (privKey == null) {
            android.util.Log.e("ChatVM", "sendMessage failed: myPrivKey is null, E2E not initialized")
            _e2eError.value = "E2E не инициализирован. Перезайдите в приложение."
            return
        }
        val token = Prefs.getToken(ctx)

        // Wrap into D5 reply/ttl envelope if chat has TTL set
        val ttlSec = Prefs.getChatTtlSec(ctx, toUin)
        val payloadText = if (ttlSec > 0) {
            val obj = org.json.JSONObject()
            obj.put("t", text)
            obj.put("_ttl", ttlSec)
            obj.toString()
        } else text

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                // D1 single-identity: один identity_key на UIN, любой девайс получателя
                // распакует общим privkey. MULTI per-device fan-out больше не нужен —
                // он только плодил дубли в synced_messages (×N stale device_id).
                val cached = pubkeyCache[toUin]
                val pubKeyB64 = cached ?: api.getPubKey("Bearer $token", toUin).pubkey.also { pubkeyCache[toUin] = it }
                val recipPub = CryptoManager.importPublicKey(pubKeyB64)
                val ciphertext = CryptoManager.encryptMessage(payloadText, recipPub)

                val nowMs = System.currentTimeMillis()

                wsClient?.sendMessage(WsEnvelope(
                    sender_uin = myUin,
                    receiver_uin = toUin,
                    ciphertext = ciphertext,
                    timestamp = nowMs
                ))

                // Encrypt for self (sender_ciphertext) so PWA/other devices can decrypt outgoing history
                val myPubKeyB64 = Prefs.getPubKey(ctx)
                if (myPubKeyB64.isNotEmpty()) {
                    runCatching {
                        val myPub = CryptoManager.importPublicKey(myPubKeyB64)
                        val senderCiphertext = CryptoManager.encryptMessage(payloadText, myPub)
                        api.saveSyncedMessage(
                            "Bearer $token",
                            com.iromashka.network.SaveSyncedMessageRequest(
                                sender_uin = myUin,
                                receiver_uin = toUin,
                                ciphertext = ciphertext,
                                sender_ciphertext = senderCiphertext,
                                timestamp = nowMs
                            )
                        )
                        android.util.Log.d("ChatVM", "Saved sender_ciphertext for sync to $toUin")
                    }.onFailure { e ->
                        android.util.Log.w("ChatVM", "Failed to save sender_ciphertext: ${e.message}")
                    }
                }

                msgDao.insertMessage(MessageEntity(
                    chatUin = toUin,
                    senderUin = myUin,
                    receiverUin = toUin,
                    text = text,
                    timestamp = nowMs,
                    isOutgoing = true,
                    isE2E = true
                ))
                android.util.Log.d("ChatVM", "Inserted local msg to $toUin at $nowMs")
                _scrollToBottom.value = System.currentTimeMillis()
                if (ttlSec > 0) scheduleTtlDelete(toUin, nowMs, ttlSec)
            }.onFailure { err ->
                android.util.Log.e("ChatVM", "SendMessage failed: ${err.message}")
                _wsConnected.value = false
                _e2eError.value = "Ошибка отправки: ${err.message}"
            }
        }
    }

    fun resetIdentity(pin: String, onResult: (Boolean, String?) -> Unit) {
        val token = Prefs.getToken(ctx)
        val myUin = Prefs.getUin(ctx)
        if (token.isEmpty() || myUin <= 0) { onResult(false, "Не авторизован"); return }
        viewModelScope.launch {
            runCatching {
                val ch = api.identityResetChallenge("Bearer $token")
                api.identityReset("Bearer $token", com.iromashka.network.IdentityResetRequest(myUin, pin, ch.nonce))
            }.onSuccess {
                // Wipe local identity so next login bootstraps fresh
                Prefs.updateWrappedPriv(ctx, "")
                Prefs.updatePubKey(ctx, "")
                myPrivKey = null
                pubkeyCache.clear()
                // Clear all messages since they can't be decrypted with new key
                msgDao.deleteAll()
                grpDao.clearAll()
                disconnectWs()
                onResult(true, null)
            }.onFailure { e ->
                val msg = when {
                    e.message?.contains("401") == true -> "Неверный PIN"
                    e.message?.contains("403") == true -> "Доступ запрещён"
                    else -> "Ошибка: ${e.message}"
                }
                onResult(false, msg)
            }
        }
    }

    fun markChatRead(chatUin: Long) {
        val myUin = Prefs.getUin(ctx)
        if (chatUin <= 0 || myUin <= 0) return
        val now = System.currentTimeMillis()
        wsClient?.sendReadReceipt(senderUin = chatUin, readerUin = myUin, readUntil = now)
    }

    fun deleteMessageForAll(receiverUin: Long, timestamp: Long) {
        val token = Prefs.getToken(ctx); if (token.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resp = ApiService.okHttpClient.newCall(
                    okhttp3.Request.Builder()
                        .url("https://iromashka.ru/api/messages/delete")
                        .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(),
                            com.google.gson.Gson().toJson(mapOf("receiver_uin" to receiverUin, "timestamp" to timestamp))))
                        .header("Authorization", "Bearer $token")
                        .build()
                ).execute()
                resp.close()
            }.onFailure { android.util.Log.e("ChatVM", "delete: ${it.message}") }
        }
    }

    fun editMessageForAll(receiverUin: Long, timestamp: Long, newText: String) {
        val token = Prefs.getToken(ctx); if (token.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cached = pubkeyCache[receiverUin]
                val pub = CryptoManager.importPublicKey(cached ?: api.getPubKey("Bearer $token", receiverUin).pubkey.also { pubkeyCache[receiverUin] = it })
                val ct = CryptoManager.encryptMessage(newText, pub)
                val resp = ApiService.okHttpClient.newCall(
                    okhttp3.Request.Builder()
                        .url("https://iromashka.ru/api/messages/edit")
                        .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(),
                            com.google.gson.Gson().toJson(mapOf("receiver_uin" to receiverUin, "timestamp" to timestamp, "ciphertext" to ct))))
                        .header("Authorization", "Bearer $token")
                        .build()
                ).execute()
                resp.close()
            }.onFailure { android.util.Log.e("ChatVM", "edit: ${it.message}") }
        }
    }

    fun sendTyping(toUin: Long, isTyping: Boolean) {
        wsClient?.sendTyping(toUin, isTyping)
    }

    private val _myStatus = MutableStateFlow("Online")
    val myStatus: StateFlow<String> = _myStatus

    fun setMyStatus(status: String) {
        _myStatus.value = status
        wsClient?.sendStatus(status)
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

    fun syncPhoneContacts() {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val phones = loadPhoneContacts()
                if (phones.isEmpty()) return@runCatching
                phones.chunked(100).forEach { batch ->
                    val results = api.discoverContacts("Bearer $token", com.iromashka.network.DiscoverRequest(batch))
                    for (r in results) {
                        val uin = r.uin.toLong()
                        if (contactDao.getByUin(uin) == null) {
                            contactDao.insert(ContactEntity(uin, r.nickname.ifBlank { r.phone }))
                        }
                    }
                }
            }.onFailure { android.util.Log.w("ChatVM", "syncPhoneContacts failed: ${it.message}") }
        }
    }

    private fun loadPhoneContacts(): List<String> {
        val phones = mutableListOf<String>()
        val cursor = ctx.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return phones
        cursor.use {
            val col = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val raw = it.getString(col) ?: continue
                val normalized = normalizePhone(raw)
                if (normalized != null) phones.add(normalized)
            }
        }
        return phones.distinct()
    }

    private fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.length < 7) return null
        return if (digits.startsWith("+")) digits
        else if (digits.startsWith("8") && digits.length == 11) "+7${digits.drop(1)}"
        else if (digits.startsWith("7") && digits.length == 11) "+$digits"
        else "+$digits"
    }

    data class DiscoveredResult(val uin: Long, val phone: String, val nickname: String)

    // -- Contacts

    /**
     * Проверка существования UIN на сервере. true = есть pubkey (юзер
     * зарегистрирован), false = 404/нет ключа, null = сетевая/прочая ошибка
     * (UI покажет другой текст).
     */
    suspend fun validateUinExists(uin: Long): Boolean? {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) return null
        val resp = runCatching { api.getPubKey("Bearer $token", uin) }
        return resp.fold(
            onSuccess = { it.pubkey.isNotBlank() },
            onFailure = { e ->
                val code = (e as? retrofit2.HttpException)?.code()
                if (code == 404) false else null
            }
        )
    }

    fun addContact(uin: Long, nickname: String) {
        viewModelScope.launch {
            contactDao.insert(ContactEntity(uin, nickname))
        }
    }

    fun renameContact(uin: Long, nickname: String) {
        viewModelScope.launch {
            contactDao.renameByUin(uin, nickname.trim())
        }
    }

    fun deleteContact(uin: Long) {
        viewModelScope.launch {
            contactDao.deleteByUin(uin)
        }
    }

    private fun ensureBotContact() {
        viewModelScope.launch {
            val existing = contactDao.getByUin(100000L)
            if (existing == null) {
                contactDao.insert(ContactEntity(100000L, "АйРомашка Бот"))
            }
        }
    }

    // Sync all history from server on connect, add senders to contacts if missing
    private fun syncAllHistory() {
        val myUin = Prefs.getUin(ctx)
        val token = Prefs.getToken(ctx)
        val privKey = myPrivKey ?: return
        if (token.isEmpty()) return

        viewModelScope.launch {
            runCatching {
                val pair = fetchSyncedWithRetry(token, since = 0, limit = 1000) ?: return@runCatching
                val items = pair.second
                android.util.Log.d("ChatVM", "syncAllHistory items=${items.size}")
                for (item in items) {
                    val chatUin = if (item.sender_uin == myUin) item.receiver_uin else item.sender_uin
                    val isOutgoing = item.sender_uin == myUin

                    val plaintext: String? = if (isOutgoing) {
                        // F3: prefer sender-copy ciphertext for outgoing.
                        item.sender_ciphertext
                            ?.let { runCatching { CryptoManager.decryptMessage(it, privKey) }.getOrNull() }
                            ?: runCatching { CryptoManager.decryptMessage(item.ciphertext, privKey) }.getOrNull()
                    } else {
                        runCatching { CryptoManager.decryptMessage(item.ciphertext, privKey) }.getOrNull()
                    }
                    // Skip messages we can't decrypt (старая ротация privkey — текст
                    // уже не восстановим). Раньше вставляли "[не удалось расшифровать]"
                    // под каждое — после re-sync чат заполнялся плейсхолдерами.
                    if (plaintext == null) continue

                    val existing = msgDao.getByTimestampAndUins(item.timestamp, item.sender_uin, item.receiver_uin)
                    if (existing == null) {
                        msgDao.insertMessage(MessageEntity(
                            chatUin = chatUin,
                            senderUin = item.sender_uin,
                            receiverUin = item.receiver_uin,
                            text = plaintext,
                            timestamp = item.timestamp,
                            isOutgoing = isOutgoing,
                            isE2E = true
                        ))
                    }

                    // Auto-add contact if not in list
                    if (contactDao.getByUin(chatUin) == null && chatUin != myUin) {
                        contactDao.insert(ContactEntity(chatUin, "UIN $chatUin"))
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("ChatVM", "syncAllHistory failed: ${e.message}")
            }
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

