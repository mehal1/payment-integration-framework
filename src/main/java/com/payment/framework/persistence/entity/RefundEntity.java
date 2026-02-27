package com.payment.framework.persistence.entity;

import com.payment.framework.domain.RefundStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistent entity for refund transactions.
 */
@Entity
@Table(name = "refunds", indexes = {
    @Index(name = "idx_refund_payment_key", columnList = "payment_idempotency_key"),
    @Index(name = "idx_refund_merchant_ref", columnList = "merchant_reference"),
    @Index(name = "idx_refund_created_at", columnList = "created_at"),
    @Index(name = "idx_refund_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundEntity {

    @Id
    @Column(name = "refund_idempotency_key", unique = true, nullable = false)
    private String refundIdempotencyKey;

    @Column(name = "payment_idempotency_key", nullable = false)
    private String paymentIdempotencyKey;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefundStatus status;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "merchant_reference")
    private String merchantReference;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
