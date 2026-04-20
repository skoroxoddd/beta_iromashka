package com.iromashka.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "group_messages",
    indices = [Index("groupId"), Index("timestamp")]
)
data class GroupMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val senderUin: Long,
    val senderNickname: String = "",
    val text: String,
    val timestamp: Long,
    val ciphertext: String = ""
)
