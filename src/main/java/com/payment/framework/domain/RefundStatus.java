package com.payment.framework.domain;

/**
 * Status of a refund operation.
 */
public enum RefundStatus {
    /** Refund is pending processing. */
    PENDING,
    /** Refund completed successfully. */
    SUCCESS,
    /** Refund failed. */
    FAILED,
    /** Refund was cancelled before completion. */
    CANCELLED
}
