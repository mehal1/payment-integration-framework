package com.payment.framework.persistence.entity;

import com.payment.framework.domain.TransactionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistent entity for payment events.
 * Complete audit trail of all payment lifecycle events for compliance.
 */
@Entity
@Table(name = "payment_events", indexes = {
    @Index(name = "idx_event_idempotency_key", columnList = "idempotency_key"),
    @Index(name = "idx_event_merchant_ref", columnList = "merchant_reference"),
    @Index(name = "idx_event_timestamp", columnList = "timestamp"),
    @Index(name = "idx_event_type", columnList = "event_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "provider_type")
    private String providerType;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TransactionStatus status;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "merchant_reference")
    private String merchantReference;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (timestamp == null) {
            timestamp = createdAt;
        }
    }
}
