package paceline.rest

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(exception: ResponseStatusException): ResponseEntity<ApiErrorResponse> {
        val status = exception.statusCode
        return ResponseEntity
            .status(status)
            .body(
                ApiErrorResponse(
                    code = "HTTP_${status.value()}",
                    message = exception.reason ?: status.toString(),
                ),
            )
    }
}

data class ApiErrorResponse(
    val code: String,
    val message: String,
)
