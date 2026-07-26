package com.relay.message.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import java.util.Arrays
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class MessageExceptionHandler {

    @ExceptionHandler(RelayException::class)
    fun handleRelayException(e: RelayException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(e.statusCode).body(
            ResponseErrorDto(
                statusCode = e.statusCode,
                errorMessage = listOf(e.message ?: "No message available"),
                stackTrace = e.stackTraceAsStrings()
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

    /**
     * Two sends racing on the same clientMessageId. The pre-check in MessageService covers the
     * ordinary retry; this is the constraint catching the concurrent case, and it cannot be
     * recovered into "return the existing message" here because the persistence context is
     * already invalid. A 409 tells the caller the message is stored and to re-read it.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDuplicate(e: DataIntegrityViolationException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ResponseErrorDto(
                statusCode = HttpStatus.CONFLICT.value(),
                errorMessage = listOf("Message already exists for this clientMessageId")
            )
        )

    @ExceptionHandler(Exception::class)
    fun handleGenericException(e: Exception): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ResponseErrorDto(
                statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                errorMessage = listOf(e.message ?: "No message available"),
                stackTrace = e.stackTraceAsStrings()
            )
        )

    private fun Throwable.stackTraceAsStrings(): List<String> =
        Arrays.stream(stackTrace)
            .map(StackTraceElement::toString)
            .toList()
}