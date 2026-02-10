package com.payment.framework.persistence.entity;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.TransactionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistent entity for payment transactions.
 * Stores all payment attempts for compliance, reconciliation, and analytics.
 */
@Entity
@Table(name = "payment_transactions", indexes = {
    @Index(name = "idx_payment_merchant_ref", columnList = "merchant_reference"),
    @Index(name = "idx_payment_customer_id", columnList = "customer_id"),
    @Index(name = "idx_payment_created_at", columnList = "created_at"),
    @Index(name = "idx_payment_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransactionEntity {

    @Id
    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

    @Column(name = "transaction_id", unique = true)
    private String transactionId;

    @Column(name = "merchant_reference")
    private String merchantReference;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private PaymentProviderType providerType;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (transactionId == null) {
            transactionId = idempotencyKey;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
