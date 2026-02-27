package com.payment.framework.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * REST API request body for refunding a payment.
 */
@Data
public class RefundRequestDto {

    /** Idempotency key for this refund (separate from payment idempotency key). Required. */
    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    /** Original payment's idempotency key. Required. */
    @NotBlank(message = "paymentIdempotencyKey is required")
    private String paymentIdempotencyKey;

    /** Amount to refund. If null, refunds the full payment amount. */
    @DecimalMin("0.01")
    private BigDecimal amount;

    /** Currency code (must match original payment). */
    @NotBlank(message = "currencyCode is required")
    private String currencyCode;

    /** Reason for refund (optional). */
    private String reason;

    /** Merchant reference for this refund. */
    private String merchantReference;
}
