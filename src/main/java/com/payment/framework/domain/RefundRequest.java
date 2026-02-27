package com.payment.framework.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Refund request. If amount is null, the full payment amount is refunded.
 */
@Value
@Builder
public class RefundRequest {

    @NotBlank
    String idempotencyKey;

    @NotBlank
    String paymentIdempotencyKey;

    /** Null means full refund. */
    @DecimalMin("0.01")
    BigDecimal amount;

    @NotBlank
    String currencyCode;

    String reason;

    String merchantReference;

    String correlationId;
}
