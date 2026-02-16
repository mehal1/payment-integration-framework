package com.payment.framework.api;

/**
 * Thrown when the PSP we would have recommended (or that the client tokenized with) is
 * temporarily unavailable (e.g. circuit open). Cross-PSP failover would use a different
 * token, so we ask the client to call GET /routing/recommend again and re-tokenize with
 * the new recommended PSP.
 */
public class RecommendedPspUnavailableException extends RuntimeException {

    public RecommendedPspUnavailableException(String message) {
        super(message);
    }

    public RecommendedPspUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
