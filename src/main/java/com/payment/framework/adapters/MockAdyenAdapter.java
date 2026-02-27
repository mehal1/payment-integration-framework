package com.payment.framework.adapters;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.RefundRequest;
import com.payment.framework.domain.RefundResult;
import com.payment.framework.domain.RefundStatus;
import com.payment.framework.domain.TransactionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mock "Adyen" PSP for cross-PSP fraud testing. Simulates card payments (CARD) as Adyen does.
 * Same behaviour as {@link MockPSPAdapter} (fails on amount >= 999999). Differentiated by
 * adapter name so we can test aggregation across multiple PSPs for the same merchant.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.adapters.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockAdyenAdapter implements PSPAdapter {

    private static final BigDecimal FAIL_AMOUNT_THRESHOLD = new BigDecimal("999999");

    @Override
    public PaymentProviderType getProviderType() {
        return PaymentProviderType.CARD;
    }

    @Override
    public PaymentResult execute(PaymentRequest request) {
        log.debug("MockAdyenAdapter executing idempotencyKey={} amount={}", request.getIdempotencyKey(), request.getAmount());

        if (request.getAmount().compareTo(FAIL_AMOUNT_THRESHOLD) >= 0) {
            return PaymentResult.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .providerTransactionId("mock-adyen-fail-" + UUID.randomUUID())
                    .status(TransactionStatus.FAILED)
                    .amount(request.getAmount())
                    .currencyCode(request.getCurrencyCode())
                    .failureCode("MOCK_ADYEN_DECLINED")
                    .message("Simulated decline for high amount")
                    .timestamp(Instant.now())
                    .metadata(Map.of("mock", true, "psp", "MOCK_ADYEN"))
                    .build();
        }

        return PaymentResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .providerTransactionId("mock-adyen-" + UUID.randomUUID())
                .status(TransactionStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message(null)
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true, "psp", "MOCK_ADYEN"))
                .build();
    }

    @Override
    public java.util.Optional<RefundResult> refund(RefundRequest request) {
        log.debug("MockAdyenAdapter refunding refundIdempotencyKey={}, paymentIdempotencyKey={}, amount={}",
                request.getIdempotencyKey(), request.getPaymentIdempotencyKey(), request.getAmount());

        return java.util.Optional.of(RefundResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .paymentIdempotencyKey(request.getPaymentIdempotencyKey())
                .providerRefundId("mock-adyen-refund-" + UUID.randomUUID())
                .status(RefundStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message("Refund processed successfully")
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true, "psp", "MOCK_ADYEN"))
                .build());
    }
}
