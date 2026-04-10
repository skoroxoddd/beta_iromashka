package com.iromashka.model

data class TypingEvent(
    val sender_uin: Long,
    val receiver_uin: Long,
    val is_typing: Boolean
)
