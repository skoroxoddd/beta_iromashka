package com.iromashka.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatUin = :chatUin ORDER BY timestamp ASC LIMIT 500")
    fun getMessages(chatUin: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatUin = :chatUin ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getMessagesPaged(chatUin: Long, limit: Int, offset: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE chatUin = :chatUin")
    suspend fun getMessageCount(chatUin: Long): Int

    @Query("SELECT * FROM messages WHERE chatUin = :chatUin ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(chatUin: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE timestamp = :ts AND senderUin = :senderUin AND receiverUin = :receiverUin LIMIT 1")
    suspend fun getByTimestampAndUins(ts: Long, senderUin: Long, receiverUin: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: MessageEntity): Long

    @Query("UPDATE messages SET isRead = 1 WHERE chatUin = :chatUin AND isOutgoing = 1 AND timestamp <= :until")
    suspend fun markReadUntil(chatUin: Long, until: Long)

    @Query("UPDATE messages SET text = :text, isEdited = 1 WHERE timestamp = :ts AND senderUin = :senderUin AND receiverUin = :receiverUin")
    suspend fun updateText(ts: Long, senderUin: Long, receiverUin: Long, text: String)

    @Query("DELETE FROM messages WHERE timestamp = :ts AND senderUin = :senderUin AND receiverUin = :receiverUin")
    suspend fun deleteByTs(ts: Long, senderUin: Long, receiverUin: Long)

    @Query("DELETE FROM messages WHERE chatUin = :chatUin")
    suspend fun deleteChat(chatUin: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY nickname ASC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE uin = :uin")
    suspend fun getByUin(uin: Long): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE uin = :uin")
    suspend fun deleteByUin(uin: Long)

    @Query("UPDATE contacts SET status = :status, lastSeen = :lastSeen WHERE uin = :uin")
    suspend fun updateStatus(uin: Long, status: String, lastSeen: Long = System.currentTimeMillis())

    @Query("UPDATE contacts SET status = 'Offline'")
    suspend fun resetAllToOffline()
}

/** One-shot cleanup helper: drop legacy placeholder rows. */
@Dao
interface MaintenanceDao {
    @Query("DELETE FROM messages WHERE text = '[не удалось расшифровать]'")
    suspend fun purgeUndecryptablePlaceholders(): Int
}

@Dao
interface GroupMessageDao {

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getMessages(groupId: Long): Flow<List<GroupMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: GroupMessageEntity): Long

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)

    @Query("DELETE FROM group_messages")
    suspend fun clearAll()
}
