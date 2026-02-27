package com.payment.framework.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Standardized refund result across all PSPs.
 */
@Value
@Builder
@JsonDeserialize(builder = RefundResult.RefundResultBuilder.class)
public class RefundResult {

    String idempotencyKey;
    String paymentIdempotencyKey;
    String providerRefundId;
    RefundStatus status;
    BigDecimal amount;
    String currencyCode;
    String failureCode;
    String message;
    Instant timestamp;
    Map<String, Object> metadata;

    public boolean isSuccess() {
        return status == RefundStatus.SUCCESS || status == RefundStatus.PENDING;
    }

    public boolean isTerminalFailure() {
        return status == RefundStatus.FAILED;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class RefundResultBuilder {}
}
