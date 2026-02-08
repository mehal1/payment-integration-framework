package com.payment.framework.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * What happened when we tried to charge someone. Standardized so all gateways look the same.
 */
@Value
@Builder
@JsonDeserialize(builder = PaymentResult.PaymentResultBuilder.class)
@JsonPOJOBuilder(withPrefix = "")
public class PaymentResult {

    String idempotencyKey;
    String providerTransactionId;
    TransactionStatus status;
    BigDecimal amount;
    String currencyCode;
    String failureCode;
    String message;
    Instant timestamp;
    Map<String, Object> metadata;

    public boolean isSuccess() {
        return status == TransactionStatus.SUCCESS || status == TransactionStatus.CAPTURED || status == TransactionStatus.PENDING;
    }

    public boolean isTerminalFailure() {
        return status == TransactionStatus.FAILED || status == TransactionStatus.CANCELLED;
    }
}
