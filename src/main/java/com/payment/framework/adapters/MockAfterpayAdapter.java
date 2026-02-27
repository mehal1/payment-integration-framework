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
 * Mock BNPL (Afterpay-style) adapter. Simulates buy-now-pay-later: no card BIN/last4 in response.
 * Fails on amount >= 888888 so BNPL flow can be tested separately from card mocks.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.adapters.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockAfterpayAdapter implements PSPAdapter {

    private static final BigDecimal FAIL_AMOUNT_THRESHOLD = new BigDecimal("888888");

    @Override
    public PaymentProviderType getProviderType() {
        return PaymentProviderType.BNPL;
    }

    @Override
    public PaymentResult execute(PaymentRequest request) {
        log.debug("MockAfterpayAdapter executing idempotencyKey={} amount={} (BNPL, no card data)", request.getIdempotencyKey(), request.getAmount());

        if (request.getAmount().compareTo(FAIL_AMOUNT_THRESHOLD) >= 0) {
            return PaymentResult.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .providerTransactionId("afterpay-mock-fail-" + UUID.randomUUID())
                    .status(TransactionStatus.FAILED)
                    .amount(request.getAmount())
                    .currencyCode(request.getCurrencyCode())
                    .failureCode("MOCK_AFTERPAY_DECLINED")
                    .message("Simulated BNPL decline for high amount")
                    .timestamp(Instant.now())
                    .metadata(Map.of("mock", true, "psp", "MOCK_AFTERPAY"))
                    .cardBin(null)
                    .cardLast4(null)
                    .networkToken(null)
                    .par(null)
                    .cardFingerprint(null)
                    .build();
        }

        return PaymentResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .providerTransactionId("afterpay-mock-" + UUID.randomUUID())
                .status(TransactionStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message(null)
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true, "psp", "MOCK_AFTERPAY"))
                .cardBin(null)
                .cardLast4(null)
                .networkToken(null)
                .par(null)
                .cardFingerprint(null)
                .build();
    }

    @Override
    public java.util.Optional<RefundResult> refund(RefundRequest request) {
        log.debug("MockAfterpayAdapter refunding refundIdempotencyKey={}, paymentIdempotencyKey={}, amount={}",
                request.getIdempotencyKey(), request.getPaymentIdempotencyKey(), request.getAmount());

        return java.util.Optional.of(RefundResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .paymentIdempotencyKey(request.getPaymentIdempotencyKey())
                .providerRefundId("afterpay-mock-refund-" + UUID.randomUUID())
                .status(RefundStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message("Refund processed successfully")
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true, "psp", "MOCK_AFTERPAY"))
                .build());
    }
}
