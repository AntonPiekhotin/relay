package com.relay.call.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import java.util.Arrays
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class CallExceptionHandler {

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

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleConcurrentTransition(e: OptimisticLockingFailureException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ResponseErrorDto(
                statusCode = HttpStatus.UNPROCESSABLE_ENTITY.value(),
                errorMessage = listOf("The call was settled by another device")
            )
        )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleConflict(e: DataIntegrityViolationException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ResponseErrorDto(
                statusCode = HttpStatus.CONFLICT.value(),
                errorMessage = listOf("A participant is already in a call")
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
