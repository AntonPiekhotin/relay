package com.relay.auth.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import java.util.Arrays
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException

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

    /**
     * WebFlux reports a `@Valid` failure as [WebExchangeBindException]. Without this it reaches the
     * catch-all below and a rejected password comes back as a 500 with no hint of which rule failed.
     */
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationException(e: WebExchangeBindException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ResponseErrorDto(
                statusCode = HttpStatus.BAD_REQUEST.value(),
                errorMessage = e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
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
