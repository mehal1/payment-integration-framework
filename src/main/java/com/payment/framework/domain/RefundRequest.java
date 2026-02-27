package com.payment.framework.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Request to refund a payment. Supports full and partial refunds.
 */
@Value
@Builder
public class RefundRequest {

    /** Idempotency key for this refund (separate from payment idempotency key). Required. */
    @NotBlank
    String idempotencyKey;

    /** Original payment's idempotency key. Required to identify which payment to refund. */
    @NotBlank
    String paymentIdempotencyKey;

    /** Amount to refund. If null, refunds the full payment amount. */
    @DecimalMin("0.01")
    BigDecimal amount;

    /** Currency code (must match original payment). */
    @NotBlank
    String currencyCode;

    /** Reason for refund (optional, for audit purposes). */
    String reason;

    /** Merchant reference for this refund. */
    String merchantReference;

    /** Correlation ID for tracking. */
    String correlationId;
}
