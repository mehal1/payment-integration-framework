package com.payment.framework.messaging;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.TransactionStatus;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What happened with a payment - sent to Kafka so other services can react.
 */
@Value
@Builder
@Jacksonized
public class PaymentEvent {

    String eventId;
    String idempotencyKey;
    String correlationId;
    PaymentProviderType providerType;
    String providerTransactionId;
    TransactionStatus status;
    BigDecimal amount;
    String currencyCode;
    String failureCode;
    String message;
    String merchantReference;
    String customerId;
    Instant timestamp;
    String eventType;
}
