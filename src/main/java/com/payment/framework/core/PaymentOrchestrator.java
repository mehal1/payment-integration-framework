package com.payment.framework.core;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Central orchestrator for payment execution. Resolves the correct gateway by
 * provider type, applies idempotency, circuit breaker, and retry, and returns
 * a normalized result. This is the single entry point for "execute payment"
 * used by the API and event consumers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOrchestrator {

    private static final String RETRY_INSTANCE = "payment";

    private final List<PaymentGateway> gateways;
    private final IdempotencyService idempotencyService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    private Map<PaymentProviderType, PaymentGateway> gatewayByType;

    @jakarta.annotation.PostConstruct
    void init() {
        gatewayByType = gateways.stream()
                .collect(Collectors.toMap(PaymentGateway::getProviderType, g -> g));
    }

    /**
     * Execute payment for the given request. Uses idempotency key for
     * deduplication, then delegates to the provider-specific gateway with
     * circuit breaker and retry.
     */
    public PaymentResult execute(PaymentRequest request) {
        String idempotencyKey = request.getIdempotencyKey();
        PaymentProviderType type = request.getProviderType();

        Optional<PaymentResult> cached = idempotencyService.getCachedResult(idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        PaymentGateway gateway = gatewayByType.get(type);
        if (gateway == null) {
            log.warn("No gateway registered for provider type={}", type);
            return buildFailureResult(request, "UNSUPPORTED_PROVIDER", "Provider not configured");
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(type.name());
        Retry retry = retryRegistry.retry(RETRY_INSTANCE);
        Supplier<PaymentResult> supplier = () -> gateway.execute(request);
        Supplier<PaymentResult> withRetry = Retry.decorateSupplier(retry, supplier);
        Supplier<PaymentResult> withCb = CircuitBreaker.decorateSupplier(cb, withRetry);

        try {
            PaymentResult result = withCb.get();
            idempotencyService.storeResult(idempotencyKey, result);
            return result;
        } catch (CallNotPermittedException e) {
            log.warn("Circuit open for provider={}", type);
            PaymentResult fallback = buildFailureResult(request, "CIRCUIT_OPEN",
                    "Payment provider temporarily unavailable");
            idempotencyService.storeResult(idempotencyKey, fallback);
            return fallback;
        }
    }

    public Optional<PaymentGateway> getGateway(PaymentProviderType type) {
        return Optional.ofNullable(gatewayByType.get(type));
    }

    private static PaymentResult buildFailureResult(PaymentRequest request, String code, String message) {
        return PaymentResult.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .providerTransactionId(null)
                .status(TransactionStatus.FAILED)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(code)
                .message(message)
                .timestamp(Instant.now())
                .metadata(null)
                .build();
    }
}
