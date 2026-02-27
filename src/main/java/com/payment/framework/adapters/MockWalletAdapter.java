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
 * Mock wallet (Apple Pay / Google Pay style) adapter. Returns DPAN-style last4 (different from physical card).
 * Fails on amount >= 777777 so wallet flow can be tested separately.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.adapters.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockWalletAdapter implements PSPAdapter {

    private static final BigDecimal FAIL_AMOUNT_THRESHOLD = new BigDecimal("777777");
    private static final String MOCK_DPAN_BIN = "555555";
    private static final String MOCK_DPAN_LAST4 = "4321";

    @Override
    public PaymentProviderType getProviderType() {
        return PaymentProviderType.WALLET;
    }

    @Override
    public PaymentResult execute(PaymentRequest request) {
        log.debug("MockWalletAdapter executing idempotencyKey={} amount={} (wallet DPAN last4={})", request.getIdempotencyKey(), request.getAmount(), MOCK_DPAN_LAST4);

        if (request.getAmount().compareTo(FAIL_AMOUNT_THRESHOLD) >= 0) {
            return PaymentResult.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .providerTransactionId("wallet-mock-fail-" + UUID.randomUUID())
                    .status(TransactionStatus.FAILED)
                    .amount(request.getAmount())
                    .currencyCode(request.getCurrencyCode())
                    .failureCode("MOCK_WALLET_DECLINED")
                    .message("Simulated wallet decline for high amount")
                    .timestamp(Instant.now())
                    .metadata(Map.of("mock", true, "psp", "MOCK_WALLET"))
                    .cardBin(MOCK_DPAN_BIN)
                    .cardLast4(MOCK_DPAN_LAST4)
                    .networkToken(null)
                    .par(null)
                    .cardFingerprint(null)
                    .build();
        }

        return PaymentResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .providerTransactionId("wallet-mock-" + UUID.randomUUID())
                .status(TransactionStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message(null)
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true, "psp", "MOCK_WALLET"))
                .cardBin(MOCK_DPAN_BIN)
                .cardLast4(MOCK_DPAN_LAST4)
                .networkToken(null)
                .par(null)
                .cardFingerprint(null)
                .build();
    }

    @Override
    public java.util.Optional<RefundResult> refund(RefundRequest request) {
        log.debug("MockWalletAdapter refunding refundIdempotencyKey={}, paymentIdempotencyKey={}, amount={}",
                request.getIdempotencyKey(), request.getPaymentIdempotencyKey(), request.getAmount());

        return java.util.Optional.of(RefundResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .paymentIdempotencyKey(request.getPaymentIdempotencyKey())
                .providerRefundId("wallet-mock-refund-" + UUID.randomUUID())
                .status(RefundStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message("Refund processed successfully")
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true, "psp", "MOCK_WALLET"))
                .build());
    }
}
