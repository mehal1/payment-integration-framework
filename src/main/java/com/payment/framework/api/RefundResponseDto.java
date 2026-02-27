package com.payment.framework.api;

import com.payment.framework.domain.RefundResult;
import com.payment.framework.domain.RefundStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Response body for POST /api/v1/payments/refund.
 */
@Value
@Builder
public class RefundResponseDto {

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

    public static RefundResponseDto from(RefundResult result) {
        if (result == null) {
            throw new IllegalArgumentException("RefundResult cannot be null");
        }
        return RefundResponseDto.builder()
                .idempotencyKey(result.getIdempotencyKey())
                .paymentIdempotencyKey(result.getPaymentIdempotencyKey())
                .providerRefundId(result.getProviderRefundId())
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
