package com.relay.dto

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

data class ResponseErrorDto(

    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    val time: LocalDateTime = LocalDateTime.now(),
    val statusCode: Int,
    val errorMessage: List<String>,
    val stackTrace: List<String>? = null
)