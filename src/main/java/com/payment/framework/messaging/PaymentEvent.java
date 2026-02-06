package com.payment.framework.messaging;

import com.payment.framework.domain.TransactionStatus;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event emitted to Kafka for every payment attempt (success or failure). Used for:
 * <ul>
 *   <li>Audit and compliance (immutable log of attempts)</li>
 *   <li>Downstream analytics and ML (Project 2: failure patterns, fraud signals)</li>
 *   <li>Reconciliation and monitoring</li>
 * </ul>
 */
@Value
@Builder
@Jacksonized  // Industry standard: Lombok automatically configures Jackson for builder deserialization
public class PaymentEvent {

    String eventId;
    String idempotencyKey;
    String correlationId;
    String providerType;
    String providerTransactionId;
    TransactionStatus status;
    BigDecimal amount;
    String currencyCode;
    String failureCode;
    String message;
    String merchantReference;
    Instant timestamp;
    /** Event type: PAYMENT_REQUESTED, PAYMENT_COMPLETED, PAYMENT_FAILED */
    String eventType;
}
