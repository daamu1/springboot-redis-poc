package org.damu.exception.handler;

import org.damu.exception.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * Catch RateLimitExceededException thrown by OrderService
     * and map it to HTTP 429 Too Many Requests.
     * <p>
     * This is where HTTP status codes belong — in the controller,
     * NOT buried inside a service method's return value.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", ex.getMessage()));
    }
}
