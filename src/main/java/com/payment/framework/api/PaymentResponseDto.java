package com.payment.framework.api;

import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * REST API response for a payment execution.
 */
@Value
@Builder
public class PaymentResponseDto {

    String idempotencyKey;
    String providerTransactionId;
    TransactionStatus status;
    BigDecimal amount;
    String currencyCode;
    String failureCode;
    String message;
    Instant timestamp;
    Map<String, Object> metadata;

    public static PaymentResponseDto from(PaymentResult result) {
        if (result == null) {
            throw new IllegalArgumentException("PaymentResult cannot be null");
        }
        return PaymentResponseDto.builder()
                .idempotencyKey(result.getIdempotencyKey())
                .providerTransactionId(result.getProviderTransactionId())
                .status(result.getStatus())
                .amount(result.getAmount())
                .currencyCode(result.getCurrencyCode())
                .failureCode(result.getFailureCode())
                .message(result.getMessage())
                .timestamp(result.getTimestamp())
                .metadata(result.getMetadata())
                .build();
    }
}
