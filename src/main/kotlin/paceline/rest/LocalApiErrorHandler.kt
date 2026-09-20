package paceline.rest

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class LocalApiErrorHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(exception: ResponseStatusException): ResponseEntity<LocalApiErrorResponse> {
        val status = exception.statusCode
        return ResponseEntity
            .status(status)
            .body(
                LocalApiErrorResponse(
                    code = "HTTP_${status.value()}",
                    message = exception.reason ?: status.toString(),
                ),
            )
    }
}

data class LocalApiErrorResponse(
    val code: String,
    val message: String,
)
