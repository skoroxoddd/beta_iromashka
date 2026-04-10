package com.iromashka.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index("senderUin"), Index("receiverUin"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderUin: Long,
    val receiverUin: Long,
    val plaintext: String,
    val timestamp: Long,
    val isMine: Boolean,
    val isGroup: Boolean = false,
    val groupId: Long? = null
)

@Entity(tableName = "contacts", indices = [Index("nickname")])
data class ContactEntity(
    @PrimaryKey val uin: Long,
    val nickname: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "group_messages", indices = [Index("groupId"), Index("timestamp")])
data class GroupMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val senderUin: Long,
    val plaintext: String,
    val timestamp: Long,
    val isMine: Boolean
)
