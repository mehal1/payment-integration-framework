package com.payment.framework.persistence.service;

import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.RefundResult;
import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.persistence.entity.PaymentEventEntity;
import com.payment.framework.persistence.entity.PaymentTransactionEntity;
import com.payment.framework.persistence.entity.RefundEntity;
import com.payment.framework.persistence.repository.PaymentEventRepository;
import com.payment.framework.persistence.repository.PaymentTransactionRepository;
import com.payment.framework.persistence.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Service for persisting payment transactions and events to PostgreSQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentPersistenceService {

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentEventRepository eventRepository;
    private final RefundRepository refundRepository;

    /**
     * Persists a payment transaction (creates or updates).
     * Handles duplicate key violations gracefully (idempotency enforced at database level).
     */
    @Transactional
    public void persistTransaction(PaymentRequest request, PaymentResult result) {
        try {
            Optional<PaymentTransactionEntity> existingOpt = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
            
            PaymentTransactionEntity entity;
            if (existingOpt.isPresent()) {
                entity = existingOpt.get();
                // Update existing transaction
                entity.setStatus(result.getStatus());
                entity.setProviderTransactionId(result.getProviderTransactionId());
                entity.setFailureCode(result.getFailureCode());
                entity.setFailureMessage(result.getMessage());
                entity.setUpdatedAt(Instant.now());
            } else {
                // Extract adapter name from payment result metadata (stored by PaymentOrchestrator)
                String adapterName = result.getMetadata() != null && result.getMetadata().containsKey("adapterName")
                        ? result.getMetadata().get("adapterName").toString()
                        : null;

                // Create new transaction
                entity = PaymentTransactionEntity.builder()
                        .idempotencyKey(request.getIdempotencyKey())
                        .merchantReference(request.getMerchantReference())
                        .customerId(request.getCustomerId())
                        .amount(request.getAmount())
                        .currencyCode(request.getCurrencyCode())
                        .providerType(request.getProviderType())
                        .providerTransactionId(result.getProviderTransactionId())
                        .status(result.getStatus())
                        .failureCode(result.getFailureCode())
                        .failureMessage(result.getMessage())
                        .correlationId(request.getCorrelationId())
                        .adapterName(adapterName)
                        .build();
            }
            
            transactionRepository.save(entity);
            log.debug("Persisted payment transaction: idempotencyKey={}, status={}", 
                    request.getIdempotencyKey(), result.getStatus());
        } catch (DataIntegrityViolationException e) {
            // Duplicate key violation - another thread/process already persisted this transaction
            log.warn("Duplicate idempotency key detected (database constraint): idempotencyKey={}. " +
                    "This is expected in concurrent scenarios. Transaction already exists.", 
                    request.getIdempotencyKey());
            // Don't throw - this is idempotent behavior
        } catch (Exception e) {
            log.error("Failed to persist payment transaction: idempotencyKey={}", 
                    request.getIdempotencyKey(), e);
            // Don't throw - persistence failure shouldn't break payment flow
        }
    }

    /**
     * Persists a payment event (audit trail).
     */
    @Transactional
    public void persistEvent(PaymentEvent event) {
        try {
            PaymentEventEntity entity = PaymentEventEntity.builder()
                    .eventId(event.getEventId())
                    .idempotencyKey(event.getIdempotencyKey())
                    .correlationId(event.getCorrelationId())
                    .eventType(event.getEventType())
                    .providerType(event.getProviderType() != null ? event.getProviderType().name() : null)
                    .providerTransactionId(event.getProviderTransactionId())
                    .status(event.getStatus())
                    .amount(event.getAmount())
                    .currencyCode(event.getCurrencyCode())
                    .failureCode(event.getFailureCode())
                    .message(event.getMessage())
                    .merchantReference(event.getMerchantReference())
                    .customerId(event.getCustomerId())
                    .timestamp(event.getTimestamp())
                    .build();
            
            eventRepository.save(entity);
            log.debug("Persisted payment event: eventId={}, eventType={}", 
                    event.getEventId(), event.getEventType());
        } catch (Exception e) {
            log.error("Failed to persist payment event: eventId={}", event.getEventId(), e);
            // Don't throw - persistence failure shouldn't break event flow
        }
    }

    /**
     * Checks if a transaction exists by idempotency key.
     */
    @Transactional(readOnly = true)
    public boolean transactionExists(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent();
    }

    /**
     * Retrieves a transaction by idempotency key.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentTransactionEntity> getTransaction(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * Persists a refund transaction.
     */
    @Transactional
    public void persistRefund(String refundIdempotencyKey, RefundResult result) {
        try {
            Optional<RefundEntity> existingOpt = refundRepository.findByRefundIdempotencyKey(refundIdempotencyKey);

            RefundEntity entity;
            if (existingOpt.isPresent()) {
                entity = existingOpt.get();
                // Update existing refund
                entity.setStatus(result.getStatus());
                entity.setProviderRefundId(result.getProviderRefundId());
                entity.setFailureCode(result.getFailureCode());
                entity.setFailureMessage(result.getMessage());
                entity.setUpdatedAt(Instant.now());
            } else {
                // Create new refund
                entity = RefundEntity.builder()
                        .refundIdempotencyKey(refundIdempotencyKey)
                        .paymentIdempotencyKey(result.getPaymentIdempotencyKey())
                        .providerRefundId(result.getProviderRefundId())
                        .status(result.getStatus())
                        .amount(result.getAmount())
                        .currencyCode(result.getCurrencyCode())
                        .failureCode(result.getFailureCode())
                        .failureMessage(result.getMessage())
                        .correlationId(result.getMetadata() != null && result.getMetadata().containsKey("correlationId")
                                ? result.getMetadata().get("correlationId").toString()
                                : null)
                        .build();
            }

            refundRepository.save(entity);
            log.debug("Persisted refund: refundIdempotencyKey={}, status={}",
                    refundIdempotencyKey, result.getStatus());
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate refund idempotency key detected: refundIdempotencyKey={}. " +
                    "This is expected in concurrent scenarios.", refundIdempotencyKey);
        } catch (Exception e) {
            log.error("Failed to persist refund: refundIdempotencyKey={}", refundIdempotencyKey, e);
        }
    }

    /**
     * Retrieves a refund by idempotency key.
     */
    @Transactional(readOnly = true)
    public Optional<RefundEntity> getRefund(String refundIdempotencyKey) {
        return refundRepository.findByRefundIdempotencyKey(refundIdempotencyKey);
    }
}
