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
 * Orchestrates refund processing: idempotency checks, cumulative amount validation,
 * PSP adapter resolution, and refund execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOrchestrator {

    private final IdempotencyService idempotencyService;
    private final PaymentOrchestrator paymentOrchestrator;
    private final PaymentPersistenceService persistenceService;
    private final RefundRepository refundRepository;

    public RefundResult execute(RefundRequest request) {
        String refundIdempotencyKey = request.getIdempotencyKey();
        String paymentIdempotencyKey = request.getPaymentIdempotencyKey();

        log.info("Executing refund: refundIdempotencyKey={}, paymentIdempotencyKey={}, amount={}",
                refundIdempotencyKey, paymentIdempotencyKey, request.getAmount());

        try {
            Optional<RefundResult> cachedRefund = getCachedRefund(refundIdempotencyKey);
            if (cachedRefund.isPresent()) {
                log.info("Returning existing refund for refundIdempotencyKey={}, status={}",
                        refundIdempotencyKey, cachedRefund.get().getStatus());
                return cachedRefund.get();
            }
        } catch (Exception e) {
            log.error("Error checking refund idempotency for refundIdempotencyKey={}", refundIdempotencyKey, e);
        }

        PaymentResult originalPayment = validateAndGetPayment(paymentIdempotencyKey);

        BigDecimal refundAmount = request.getAmount() != null
                ? request.getAmount()
                : originalPayment.getAmount();

        if (refundAmount.compareTo(originalPayment.getAmount()) > 0) {
            log.error("Refund amount {} exceeds payment amount {} for paymentIdempotencyKey={}",
                    refundAmount, originalPayment.getAmount(), paymentIdempotencyKey);
            RefundResult failureResult = buildFailureResult(request, "REFUND_AMOUNT_EXCEEDED",
                    "Refund amount exceeds original payment amount");
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        // Cumulative check: sum of all successful refunds must not exceed original payment
        BigDecimal totalRefunded = refundRepository.sumRefundedAmountByPayment(
                paymentIdempotencyKey, RefundStatus.SUCCESS);
        if (totalRefunded == null) {
            totalRefunded = BigDecimal.ZERO;
        }

        BigDecimal totalAfterRefund = totalRefunded.add(refundAmount);
        if (totalAfterRefund.compareTo(originalPayment.getAmount()) > 0) {
            BigDecimal remainingRefundable = originalPayment.getAmount().subtract(totalRefunded);
            log.error("Cumulative refund would exceed payment. amount={}, alreadyRefunded={}, requested={}, remaining={}, paymentKey={}",
                    originalPayment.getAmount(), totalRefunded, refundAmount, remainingRefundable, paymentIdempotencyKey);
            RefundResult failureResult = buildFailureResult(request, "REFUND_LIMIT_EXCEEDED",
                    String.format("Cumulative refund limit exceeded. Already refunded: %s, Remaining: %s",
                            totalRefunded, remainingRefundable));
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        // Build request with resolved amount so adapters always receive a non-null amount
        RefundRequest resolvedRequest = RefundRequest.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .paymentIdempotencyKey(request.getPaymentIdempotencyKey())
                .amount(refundAmount)
                .currencyCode(request.getCurrencyCode())
                .reason(request.getReason())
                .merchantReference(request.getMerchantReference())
                .correlationId(request.getCorrelationId())
                .build();

        Optional<PSPAdapter> adapterOpt = findAdapterForPayment(originalPayment);

        if (adapterOpt.isEmpty()) {
            log.error("No PSP adapter found for refund. providerTransactionId={}",
                    originalPayment.getProviderTransactionId());
            RefundResult failureResult = buildFailureResult(resolvedRequest, "ADAPTER_NOT_FOUND",
                    "Cannot find PSP adapter for original payment");
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        PSPAdapter adapter = adapterOpt.get();

        if (adapter.refund(resolvedRequest).isEmpty()) {
            log.error("PSP adapter {} does not support refunds", adapter.getPSPAdapterName());
            RefundResult failureResult = buildFailureResult(resolvedRequest, "REFUND_NOT_SUPPORTED",
                    "PSP adapter does not support refunds");
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }

        try {
            RefundResult result = adapter.refund(resolvedRequest).orElseThrow(() ->
                    new IllegalStateException("Adapter returned empty refund result"));

            if (result == null || result.getStatus() == null) {
                log.error("Invalid refund result from adapter");
                RefundResult failureResult = buildFailureResult(request, "INVALID_RESULT",
                        "Adapter returned invalid refund result");
                storeRefundResult(refundIdempotencyKey, failureResult);
                return failureResult;
            }

            log.info("Refund executed: refundIdempotencyKey={}, status={}, providerRefundId={}",
                    refundIdempotencyKey, result.getStatus(), result.getProviderRefundId());

            storeRefundResult(refundIdempotencyKey, result);
            return result;

        } catch (Exception e) {
            log.error("Refund execution failed for refundIdempotencyKey={}", refundIdempotencyKey, e);
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 900) {
                errorMsg = errorMsg.substring(0, 900);
            }
            RefundResult failureResult = buildFailureResult(resolvedRequest, "REFUND_EXECUTION_FAILED",
                    "Refund execution failed: " + errorMsg);
            storeRefundResult(refundIdempotencyKey, failureResult);
            return failureResult;
        }
    }

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

    private void storeRefundResult(String refundIdempotencyKey, RefundResult result) {
        persistenceService.persistRefund(refundIdempotencyKey, result);
    }

    /**
     * Looks up original payment from Redis or database. Throws if not found or not refundable.
     */
    private PaymentResult validateAndGetPayment(String paymentIdempotencyKey) {
        Optional<PaymentResult> cachedPayment = idempotencyService.getCachedResult(paymentIdempotencyKey);
        if (cachedPayment.isPresent()) {
            PaymentResult payment = cachedPayment.get();
            validatePaymentRefundable(payment);
            return payment;
        }

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

    private void validatePaymentRefundable(PaymentResult payment) {
        if (!payment.isSuccess()) {
            throw new IllegalArgumentException("Cannot refund failed payment: " + payment.getStatus());
        }
        if (payment.getStatus() == com.payment.framework.domain.TransactionStatus.REVERSED) {
            throw new IllegalArgumentException("Payment already reversed/refunded");
        }
    }

    /**
     * Resolves the PSP adapter that handled the original payment using adapter_name from metadata/DB.
     * Falls back to providerType if adapter name is unavailable.
     */
    private Optional<PSPAdapter> findAdapterForPayment(PaymentResult payment) {
        if (payment.getMetadata() != null && payment.getMetadata().containsKey("adapterName")) {
            String adapterName = payment.getMetadata().get("adapterName").toString();
            return paymentOrchestrator.getAdapterByName(adapterName);
        }
        
        com.payment.framework.domain.PaymentProviderType providerType = 
                payment.getMetadata() != null && payment.getMetadata().containsKey("providerType")
                        ? com.payment.framework.domain.PaymentProviderType.valueOf(
                                payment.getMetadata().get("providerType").toString())
                        : com.payment.framework.domain.PaymentProviderType.CARD;
        
        return paymentOrchestrator.getAdapter(providerType);
    }

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
