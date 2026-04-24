package com.iromashka.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index("chatUin"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatUin: Long,       // UIN of the other side
    val senderUin: Long,
    val receiverUin: Long,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isE2E: Boolean = true
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uin: Long,
    val nickname: String,
    val status: String = "Offline",
    val lastSeen: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
)
