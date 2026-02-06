package com.payment.framework.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Canonical result of a payment operation. Normalized from provider-specific
 * responses so that orchestration, retries, and compliance handling are
 * provider-agnostic.
 */
@Value
@Builder
@JsonDeserialize(builder = PaymentResult.PaymentResultBuilder.class)
@JsonPOJOBuilder(withPrefix = "")
public class PaymentResult {

    /** Idempotency key from the request (for deduplication). */
    String idempotencyKey;

    /** Provider's transaction/payment ID (for refunds and reconciliation). */
    String providerTransactionId;

    /** Normalized status. */
    TransactionStatus status;

    /** Amount (e.g. authorized amount). */
    BigDecimal amount;

    /** ISO 4217 currency. */
    String currencyCode;

    /** Human-readable or machine code for failure reason. */
    String failureCode;

    /** Optional message for logging/support. */
    String message;

    /** When the result was produced (provider or framework). */
    Instant timestamp;

    /** Raw or extended data from provider (e.g. 3DS, risk score). */
    Map<String, Object> metadata;

    public boolean isSuccess() {
        return status == TransactionStatus.SUCCESS || status == TransactionStatus.CAPTURED || status == TransactionStatus.PENDING;
    }

    public boolean isTerminalFailure() {
        return status == TransactionStatus.FAILED || status == TransactionStatus.CANCELLED;
    }
}
