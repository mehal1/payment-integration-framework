package com.payment.framework.api;

/**
 * Thrown when no PSP is available (e.g. all circuits open or all failed after failover).
 * Handler returns HTTP 503 so the client knows to retry later.
 */
public class NoPspAvailableException extends RuntimeException {

    public NoPspAvailableException(String message) {
        super(message);
    }

    public NoPspAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
