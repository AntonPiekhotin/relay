package com.relay.auth.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import java.util.Arrays
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class AuthExceptionHandler {

    @ExceptionHandler(RelayException::class)
    fun handleMyWalletFlowException(e: RelayException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(e.statusCode).body(
            ResponseErrorDto(
                statusCode = e.statusCode,
                errorMessage = listOf(e.message ?: "No message available"),
                stackTrace = Arrays.stream(e.stackTrace)
                    .map(StackTraceElement::toString)
                    .toList()
            )
        )

    @ExceptionHandler(Exception::class)
    fun handleGenericException(e: Exception): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(500).body(
            ResponseErrorDto(
                statusCode = 500,
                errorMessage = listOf(e.message ?: "No message available"),
                stackTrace = Arrays.stream(e.stackTrace)
                    .map(StackTraceElement::toString)
                    .toList()
            )
        )
}
