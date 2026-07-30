package com.relay.notification.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class NotificationExceptionHandler {

    @ExceptionHandler(RelayException::class)
    fun handleRelayException(e: RelayException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(e.statusCode).body(
            ResponseErrorDto(
                statusCode = e.statusCode,
                errorMessage = listOf(e.message ?: "No message available")
            )
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ResponseErrorDto(
                statusCode = HttpStatus.BAD_REQUEST.value(),
                errorMessage = e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
            )
        )

    @ExceptionHandler(Exception::class)
    fun handleGenericException(e: Exception): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ResponseErrorDto(
                statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                errorMessage = listOf(e.message ?: "No message available")
            )
        )
}