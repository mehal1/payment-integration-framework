package com.payment.framework.adapters;

import com.payment.framework.core.PaymentGatewayAdapter;
import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mock payment gateway adapter for integration testing and demos. Simulates success
 * or failure based on a simple rule (e.g. amount threshold). In production,
 * replace with real adapters (Stripe, Adyen, etc.) implementing {@link PaymentGatewayAdapter}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.adapters.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockPaymentGatewayAdapter implements PaymentGatewayAdapter {

    /** Simulate failure for amounts above this (e.g. for testing retries/circuit breaker). */
    private static final java.math.BigDecimal FAIL_AMOUNT_THRESHOLD = new java.math.BigDecimal("999999");

    @Override
    public PaymentProviderType getProviderType() {
        return PaymentProviderType.MOCK;
    }

    @Override
    public PaymentResult execute(PaymentRequest request) {
        log.info("Mock adapter executing request idempotencyKey={} amount={} {}",
                request.getIdempotencyKey(), request.getAmount(), request.getCurrencyCode());

        if (request.getAmount().compareTo(FAIL_AMOUNT_THRESHOLD) >= 0) {
            return PaymentResult.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .providerTransactionId("mock-fail-" + UUID.randomUUID())
                    .status(TransactionStatus.FAILED)
                    .amount(request.getAmount())
                    .currencyCode(request.getCurrencyCode())
                    .failureCode("MOCK_DECLINED")
                    .message("Simulated decline for high amount")
                    .timestamp(Instant.now())
                    .metadata(Map.of("mock", true))
                    .build();
        }

        return PaymentResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .providerTransactionId("mock-" + UUID.randomUUID())
                .status(TransactionStatus.SUCCESS)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message(null)
                .timestamp(Instant.now())
                .metadata(Map.of("mock", true))
                .build();
    }
}
