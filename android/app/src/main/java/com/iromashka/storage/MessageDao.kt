package com.iromashka.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE (senderUin = :chatUin OR receiverUin = :chatUin) AND isGroup = 0 ORDER BY timestamp ASC LIMIT 200")
    fun getMessages(chatUin: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE (senderUin = :chatUin OR receiverUin = :chatUin) ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(chatUin: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(msg: MessageEntity): Long

    @Query("DELETE FROM messages WHERE (senderUin = :chatUin OR receiverUin = :chatUin)")
    suspend fun deleteChat(chatUin: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT
                CASE WHEN senderUin = :myUin THEN receiverUin ELSE senderUin END as partner,
                MAX(timestamp) as max_ts
            FROM messages
            GROUP BY partner
        ) latest ON (
            CASE WHEN m.senderUin = :myUin THEN m.receiverUin ELSE m.senderUin END = latest.partner
            AND m.timestamp = latest.max_ts
        )
        ORDER BY m.timestamp DESC
    """)
    fun getRecentChats(myUin: Long = 0L): Flow<List<MessageEntity>>
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
    suspend fun clearAllGroups()
}
