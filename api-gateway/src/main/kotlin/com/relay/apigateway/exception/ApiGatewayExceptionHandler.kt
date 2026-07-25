package com.relay.apigateway.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.MalformedJwtException
import java.util.Arrays
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController

@RestController
class ApiGatewayExceptionHandler {

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

    @ExceptionHandler(MalformedJwtException::class)
    fun handleMalformedJwtException(e: MalformedJwtException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED.value()).body(
            ResponseErrorDto(
                statusCode = HttpStatus.UNAUTHORIZED.value(),
                errorMessage = listOf("Invalid token: $e")
            )
        )

    @ExceptionHandler(ExpiredJwtException::class)
    fun handleExpiredJwtException(e: ExpiredJwtException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED.value()).body(
            ResponseErrorDto(
                statusCode = HttpStatus.UNAUTHORIZED.value(),
                errorMessage = listOf("Token has expired: $e")
            )
        )

    @ExceptionHandler(Exception::class)
    fun handleGenericException(e: Exception): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR.value()).body(
            ResponseErrorDto(
                statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                errorMessage = listOf("Internal server error: $e"),
                stackTrace = Arrays.stream(e.stackTrace)
                    .map(StackTraceElement::toString)
                    .toList()
            )
        )

}