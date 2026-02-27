package com.payment.framework.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Result of a refund operation. Standardized across all PSPs.
 */
@Value
@Builder
@JsonDeserialize(builder = RefundResult.RefundResultBuilder.class)
@JsonPOJOBuilder(withPrefix = "")
public class RefundResult {

    /** Idempotency key for this refund. */
    String idempotencyKey;

    /** Original payment's idempotency key. */
    String paymentIdempotencyKey;

    /** Provider's refund transaction ID. */
    String providerRefundId;

    /** Status of the refund. */
    RefundStatus status;

    /** Amount refunded. */
    BigDecimal amount;

    /** Currency code. */
    String currencyCode;

    /** Failure code if refund failed. */
    String failureCode;

    /** Failure message if refund failed. */
    String message;

    /** Timestamp when refund was processed. */
    Instant timestamp;

    /** Additional metadata from provider. */
    Map<String, Object> metadata;

    public boolean isSuccess() {
        return status == RefundStatus.SUCCESS || status == RefundStatus.PENDING;
    }

    public boolean isTerminalFailure() {
        return status == RefundStatus.FAILED;
    }
}
