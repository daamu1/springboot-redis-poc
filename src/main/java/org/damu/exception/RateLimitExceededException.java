package org.damu.exception;

/**
 * Domain exception for rate limit violation.
 * Service throws domain exceptions.
 * Controller catches them and maps to HTTP status codes.
 * This keeps HTTP concerns OUT of the service layer.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}