package com.iromashka.model

import com.google.gson.annotations.SerializedName

data class TypingEvent(
    @SerializedName("sender_uin") val sender_uin: Long = 0,
    @SerializedName("receiver_uin") val receiver_uin: Long = 0,
    @SerializedName("is_typing") val is_typing: Boolean = false
)
