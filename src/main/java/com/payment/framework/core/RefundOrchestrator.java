package com.payment.framework.core;

import com.payment.framework.api.NoPspAvailableException;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.RefundRequest;
import com.payment.framework.domain.RefundResult;
import com.payment.framework.domain.RefundStatus;
import com.payment.framework.persistence.repository.RefundRepository;
import com.payment.framework.persistence.service.PaymentPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles refund execution with idempotency and PSP routing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOrchestrator {

    private final IdempotencyService idempotencyService;
    private final PaymentOrchestrator paymentOrchestrator;
    private final PaymentPersistenceService persistenceService;
    private final RefundRepository refundRepository;

    /**
     * Process a refund - checks idempotency, validates payment, and executes refund.
     */
    public RefundResult execute(RefundRequest request) {
        String refundIdempotencyKey = request.getIdempotencyKey();
        String paymentIdempotencyKey = request.getPaymentIdempotencyKey();

        log.info("Executing refund: refundIdempotencyKey={}, paymentIdempotencyKey={}, amount={}",
                refundIdempotencyKey, paymentIdempotencyKey, request.getAmount());

        // Check refund idempotency (separate from payment idempotency)
        try {
            Optional<RefundResult> cachedRefund = getCachedRefund(refundIdempotencyKey);
            if (cachedRefund.isPresent()) {
                RefundResult cached = cachedRefund.get();
                log.info("Returning cached refund result for refundIdempotencyKey={}, status={}",
                        refundIdempotencyKey, cached.getStatus());
                return cached;
            }
        } catch (Exception e) {
            log.error("Error checking refund idempotency for refundIdempotencyKey={}", refundIdempotencyKey, e);
        }

        // Validate original payment exists and is refundable
        PaymentResult originalPayment = validateAndGetPayment(paymentIdempotencyKey);

        // Determine refund amount (use full payment amount if not specified)
        BigDecimal refundAmount = request.getAmount() != null
                ? request.getAmount()
                : originalPayment.getAmount();

        // Validate refund amount doesn't exceed payment amount (single refund check)
        if (refundAmount.compareTo(originalPayment.getAmount()) > 0) {
            log.error("Refund amount {} exceeds payment amount {} for paymentIdempotencyKey={}",
                    refundAmount, originalPayment.getAmount(), paymentIdempotencyKey);
            RefundResult failureResult = buildFailureResult(request, "REFUND_AMOUNT_EXCEEDED",
                    "Refund amount exceeds original payment amount");
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        // Validate cumulative refunds don't exceed payment amount
        // Check total already refunded (sum of all successful refunds for this payment)
        BigDecimal totalRefunded = refundRepository.sumRefundedAmountByPayment(
                paymentIdempotencyKey, RefundStatus.SUCCESS);
        if (totalRefunded == null) {
            totalRefunded = BigDecimal.ZERO;
        }

        BigDecimal totalAfterRefund = totalRefunded.add(refundAmount);
        if (totalAfterRefund.compareTo(originalPayment.getAmount()) > 0) {
            BigDecimal remainingRefundable = originalPayment.getAmount().subtract(totalRefunded);
            log.error("Refund would exceed payment limit. Payment amount: {}, Already refunded: {}, " +
                            "Requested refund: {}, Remaining refundable: {} for paymentIdempotencyKey={}",
                    originalPayment.getAmount(), totalRefunded, refundAmount, remainingRefundable, paymentIdempotencyKey);
            RefundResult failureResult = buildFailureResult(request, "REFUND_LIMIT_EXCEEDED",
                    String.format("Refund would exceed payment limit. Already refunded: %s, Remaining refundable: %s",
                            totalRefunded, remainingRefundable));
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        // Get the PSP adapter that processed the original payment
        // We need to find which adapter was used for the original payment
        // For now, we'll try to get adapter from payment result metadata or use a default approach
        Optional<PSPAdapter> adapterOpt = findAdapterForPayment(originalPayment);

        if (adapterOpt.isEmpty()) {
            log.error("Cannot find PSP adapter for refund. Original payment providerTransactionId={}",
                    originalPayment.getProviderTransactionId());
            RefundResult failureResult = buildFailureResult(request, "ADAPTER_NOT_FOUND",
                    "Cannot find PSP adapter for original payment");
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        PSPAdapter adapter = adapterOpt.get();

        // Check if adapter supports refunds
        if (adapter.refund(request).isEmpty()) {
            log.error("PSP adapter {} does not support refunds", adapter.getPSPAdapterName());
            RefundResult failureResult = buildFailureResult(request, "REFUND_NOT_SUPPORTED",
                    "PSP adapter does not support refunds");
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        // Execute refund
        try {
            RefundResult result = adapter.refund(request).orElseThrow(() ->
                    new IllegalStateException("Adapter returned empty refund result"));

            if (result == null || result.getStatus() == null) {
                log.error("Invalid refund result from adapter");
                RefundResult failureResult = buildFailureResult(request, "INVALID_RESULT",
                        "Adapter returned invalid refund result");
                storeRefundResult(refundIdempotencyKey, failureResult);
                return failureResult;
            }

            log.info("Refund executed successfully: refundIdempotencyKey={}, status={}, providerRefundId={}",
                    refundIdempotencyKey, result.getStatus(), result.getProviderRefundId());

            // Store refund result for idempotency
            storeRefundResult(refundIdempotencyKey, result);

            return result;

        } catch (Exception e) {
            log.error("Refund execution failed for refundIdempotencyKey={}", refundIdempotencyKey, e);
            RefundResult failureResult = buildFailureResult(request, "REFUND_EXECUTION_FAILED",
                    "Refund execution failed: " + e.getMessage());
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }
    }

    /**
     * Check refund idempotency cache (database lookup).
     */
    private Optional<RefundResult> getCachedRefund(String refundIdempotencyKey) {
        return persistenceService.getRefund(refundIdempotencyKey)
                .map(entity -> RefundResult.builder()
                        .idempotencyKey(entity.getRefundIdempotencyKey())
                        .paymentIdempotencyKey(entity.getPaymentIdempotencyKey())
                        .providerRefundId(entity.getProviderRefundId())
                        .status(entity.getStatus())
                        .amount(entity.getAmount())
                        .currencyCode(entity.getCurrencyCode())
                        .failureCode(entity.getFailureCode())
                        .message(entity.getFailureMessage())
                        .timestamp(entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now())
                        .metadata(null)
                        .build());
    }

    /**
     * Store refund result for idempotency.
     */
    private void storeRefundResult(String refundIdempotencyKey, RefundResult result) {
        // Store in database (idempotency handled at DB level)
        persistenceService.persistRefund(refundIdempotencyKey, result);
    }

    /**
     * Validate payment exists and is refundable.
     */
    private PaymentResult validateAndGetPayment(String paymentIdempotencyKey) {
        // Check idempotency cache first
        Optional<PaymentResult> cachedPayment = idempotencyService.getCachedResult(paymentIdempotencyKey);
        if (cachedPayment.isPresent()) {
            PaymentResult payment = cachedPayment.get();
            validatePaymentRefundable(payment);
            return payment;
        }

        // Check database
        Optional<com.payment.framework.persistence.entity.PaymentTransactionEntity> entityOpt =
                persistenceService.getTransaction(paymentIdempotencyKey);

        if (entityOpt.isEmpty()) {
            throw new IllegalArgumentException("Payment not found: " + paymentIdempotencyKey);
        }

        com.payment.framework.persistence.entity.PaymentTransactionEntity entity = entityOpt.get();

        Map<String, Object> metadata = new HashMap<>();
        if (entity.getAdapterName() != null) {
            metadata.put("adapterName", entity.getAdapterName());
        }
        if (entity.getProviderType() != null) {
            metadata.put("providerType", entity.getProviderType().name());
        }

        PaymentResult payment = PaymentResult.builder()
                .idempotencyKey(entity.getIdempotencyKey())
                .providerTransactionId(entity.getProviderTransactionId())
                .status(entity.getStatus())
                .amount(entity.getAmount())
                .currencyCode(entity.getCurrencyCode())
                .failureCode(entity.getFailureCode())
                .message(entity.getFailureMessage())
                .timestamp(entity.getCreatedAt())
                .metadata(metadata)
                .build();

        validatePaymentRefundable(payment);
        return payment;
    }

    /**
     * Validate that payment can be refunded.
     */
    private void validatePaymentRefundable(PaymentResult payment) {
        if (!payment.isSuccess()) {
            throw new IllegalArgumentException("Cannot refund failed payment: " + payment.getStatus());
        }
        if (payment.getStatus() == com.payment.framework.domain.TransactionStatus.REVERSED) {
            throw new IllegalArgumentException("Payment already reversed/refunded");
        }
    }

    /**
     * Find the PSP adapter that processed the original payment.
     * Uses adapter name stored in payment metadata.
     */
    private Optional<PSPAdapter> findAdapterForPayment(PaymentResult payment) {
        // Get adapter name from payment metadata (stored during payment execution)
        if (payment.getMetadata() != null && payment.getMetadata().containsKey("adapterName")) {
            String adapterName = payment.getMetadata().get("adapterName").toString();
            // Find adapter by name from PaymentOrchestrator
            return paymentOrchestrator.getAdapterByName(adapterName);
        }
        
        // Fallback: try to get adapter by provider type
        // This is less reliable but works if metadata is missing
        com.payment.framework.domain.PaymentProviderType providerType = 
                payment.getMetadata() != null && payment.getMetadata().containsKey("providerType")
                        ? com.payment.framework.domain.PaymentProviderType.valueOf(
                                payment.getMetadata().get("providerType").toString())
                        : com.payment.framework.domain.PaymentProviderType.CARD;
        
        return paymentOrchestrator.getAdapter(providerType);
    }

    /**
     * Build failure refund result.
     */
    private RefundResult buildFailureResult(RefundRequest request, String failureCode, String message) {
        return RefundResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .paymentIdempotencyKey(request.getPaymentIdempotencyKey())
                .providerRefundId(null)
                .status(RefundStatus.FAILED)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(failureCode)
                .message(message)
                .timestamp(Instant.now())
                .metadata(null)
                .build();
    }
}
