package com.payment.framework.domain;

/**
 * Standard lifecycle states for a payment transaction across all providers.
 * Aligns with PCI-DSS and financial reporting requirements for consistent
 * audit trails and failure classification.
 */
public enum TransactionStatus {
    /** Request accepted and pending processing (e.g. 3DS, async capture). */
    PENDING,
    /** Successfully authorized (card/auth) or completed (transfer). */
    SUCCESS,
    /** Captured/settled for card payments; final for other methods. */
    CAPTURED,
    /** Reversed or refunded. */
    REVERSED,
    /** Declined by provider or failed validation. */
    FAILED,
    /** Timed out or cancelled before completion. */
    CANCELLED,
    /** Unknown; requires reconciliation. */
    UNKNOWN
}
