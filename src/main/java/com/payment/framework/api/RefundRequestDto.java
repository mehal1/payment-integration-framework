package com.payment.framework.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request body for POST /api/v1/payments/refund.
 */
@Data
public class RefundRequestDto {

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    @NotBlank(message = "paymentIdempotencyKey is required")
    private String paymentIdempotencyKey;

    /** Null means full refund. */
    @DecimalMin("0.01")
    private BigDecimal amount;

    @NotBlank(message = "currencyCode is required")
    private String currencyCode;

    private String reason;

    private String merchantReference;
}
