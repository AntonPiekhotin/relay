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

/**
 * Statuses map one-to-one onto the error codes the gateway turns these into, so the code a client
 * finally sees is decided here:
 *
 * | 400 | INVALID_REQUEST     |
 * | 403 | NOT_A_PARTICIPANT   |
 * | 404 | CALL_NOT_FOUND      |
 * | 409 | USER_BUSY           |
 * | 422 | INVALID_CALL_STATE  |
 */
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

    /**
     * Two devices settling the same call at once. The loser's version check failed, which means the
     * call is no longer in the state it acted on — the same answer as acting on it too late.
     */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleConcurrentTransition(e: OptimisticLockingFailureException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ResponseErrorDto(
                statusCode = HttpStatus.UNPROCESSABLE_ENTITY.value(),
                errorMessage = listOf("The call was settled by another device")
            )
        )

    /**
     * A constraint reached the controller without the service turning it into something better.
     * In practice that is the `active_calls` key, i.e. somebody is busy — the service normally
     * catches that one and says so precisely.
     */
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
