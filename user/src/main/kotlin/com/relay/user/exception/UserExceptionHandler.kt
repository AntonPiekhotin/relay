package com.relay.user.exception

import com.relay.common.dto.ResponseErrorDto
import com.relay.common.exception.RelayException
import java.util.Arrays
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException

@RestControllerAdvice
class UserExceptionHandler {

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
     * Spring's own request-binding failures. Without these they fall through to the catch-all below
     * and are reported as 500s, which tells a client to retry a request that can only ever fail:
     * a missing `file` part, a body that is not multipart, or `page=abc`.
     *
     * [HttpMessageNotReadableException] is the one that carries the required fields of a `PUT`: a
     * body missing `lastName` fails in Jackson, before Bean Validation ever sees the object, because
     * the property is non-nullable in Kotlin.
     */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestPartException::class,
        MissingServletRequestParameterException::class,
        HttpMediaTypeNotSupportedException::class,
        MethodArgumentTypeMismatchException::class
    )
    fun handleBadRequest(e: Exception): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ResponseErrorDto(
                statusCode = HttpStatus.BAD_REQUEST.value(),
                errorMessage = listOf(e.message ?: "Malformed request")
            )
        )

    /**
     * The container's multipart limit tripped before [com.relay.user.service.AvatarService] could
     * check the size itself — same answer either way, so a client sees one status for "too big".
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(e: MaxUploadSizeExceededException): ResponseEntity<ResponseErrorDto> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ResponseErrorDto(
                statusCode = HttpStatus.PAYLOAD_TOO_LARGE.value(),
                errorMessage = listOf("Upload exceeds the maximum allowed size")
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