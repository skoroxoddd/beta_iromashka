package com.iromashka.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatUin = :chatUin ORDER BY timestamp ASC LIMIT 100")
    fun getMessages(chatUin: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatUin = :chatUin ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(chatUin: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: MessageEntity): Long

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
}

@Dao
interface GroupMessageDao {

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getMessages(groupId: Long): Flow<List<GroupMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: GroupMessageEntity): Long

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)
}
