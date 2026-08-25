package com.relay.common.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SendMessageRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val clientMessageId: String,

    @field:NotBlank
    val dialogId: String,

    @field:NotBlank
    val senderId: String,

    @field:NotBlank
    @field:Size(max = 4000)
    val text: String
)