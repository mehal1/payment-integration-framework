package com.payment.framework.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

/**
 * Canonical payment request used across the framework. All provider adapters
 * receive this and map to their native format, ensuring a single contract
 * for validation, idempotency, and audit.
 */
@Value
@Builder
public class PaymentRequest {

    /** Idempotency key: same key returns same result (critical for retries). */
    @NotBlank
    String idempotencyKey;

    /** Target provider for this request. */
    @NotNull
    PaymentProviderType providerType;

    /** Amount in the minor unit (e.g. cents). */
    @NotNull
    @DecimalMin("0.01")
    BigDecimal amount;

    /** ISO 4217 currency code. */
    @NotBlank
    String currencyCode;

    /** Merchant reference for reconciliation. */
    String merchantReference;

    /** Customer identifier (optional, for tokenization or compliance). */
    String customerId;

    /** Provider-specific payload (e.g. token, payment method id). */
    Map<String, Object> providerPayload;

    /** Correlation ID for distributed tracing across services. */
    String correlationId;

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }
}
