package com.payment.framework.core;

import com.payment.framework.core.routing.ProviderPerformanceMetrics;
import com.payment.framework.core.routing.ProviderRouter;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Central orchestrator for payment execution with intelligent payment gateway routing and failover.
 * Resolves the best payment gateway adapter using routing strategies, applies idempotency, circuit breaker,
 * and retry. Automatically fails over to alternative payment gateways when primary gateway fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOrchestrator {

    private static final String RETRY_INSTANCE = "payment";

    private final List<PaymentGatewayAdapter> adapters;
    private final IdempotencyService idempotencyService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ProviderRouter providerRouter;
    private final ProviderPerformanceMetrics metrics;

    @Value("${payment.routing.failover.enabled:true}")
    private boolean failoverEnabled;

    @Value("${payment.routing.failover.max-attempts:3}")
    private int maxFailoverAttempts;

    private Map<PaymentProviderType, PaymentGatewayAdapter> adapterByType;

    @jakarta.annotation.PostConstruct
    void init() {
        log.info("Initializing PaymentOrchestrator with {} payment gateway adapters", adapters.size());
        if (adapters.isEmpty()) {
            log.warn("No payment gateway adapters found! Make sure adapters are annotated with @Component and implement PaymentGatewayAdapter");
        }
        adapterByType = adapters.stream()
                .collect(Collectors.toMap(PaymentGatewayAdapter::getProviderType, a -> a));
        log.info("Registered payment gateway adapters: {}", adapterByType.keySet());
        log.info("PaymentOrchestrator configuration: failoverEnabled={}, maxFailoverAttempts={}", 
                failoverEnabled, maxFailoverAttempts);
        if (maxFailoverAttempts <= 0) {
            log.error("maxFailoverAttempts is {} - this will prevent payment execution! Check configuration.", maxFailoverAttempts);
        }
    }

    /**
     * Execute payment for the given request with intelligent routing and automatic failover.
     * Uses idempotency key for deduplication, selects best payment gateway using routing strategy,
     * and automatically fails over to alternative payment gateways if primary fails.
     */
    public PaymentResult execute(PaymentRequest request) {
        String idempotencyKey = request.getIdempotencyKey();
        PaymentProviderType requestedType = request.getProviderType();
        
        log.info("Executing payment: idempotencyKey={}, providerType={}, amount={}, failoverEnabled={}, maxFailoverAttempts={}", 
                idempotencyKey, requestedType, request.getAmount(), failoverEnabled, maxFailoverAttempts);

        // Check idempotency first
        try {
            Optional<PaymentResult> cached = idempotencyService.getCachedResult(idempotencyKey);
            if (cached.isPresent()) {
                PaymentResult cachedResult = cached.get();
                log.info("Returning cached result for idempotencyKey={}, status={}, amount={}", 
                        idempotencyKey, cachedResult.getStatus(), cachedResult.getAmount());
                // Validate cached result
                if (cachedResult.getIdempotencyKey() == null || cachedResult.getStatus() == null) {
                    log.error("Cached result has null required fields! idempotencyKey={}, status={}, amount={}. " +
                            "This suggests Redis contains corrupted data. Clearing cache and proceeding with new execution.",
                            cachedResult.getIdempotencyKey(), cachedResult.getStatus(), cachedResult.getAmount());
                    // Don't return corrupted cached result - proceed with new execution
                } else {
                    return cachedResult;
                }
            }
        } catch (Exception e) {
            log.error("Error checking idempotency for idempotencyKey={}", idempotencyKey, e);
            // Continue with execution even if idempotency check fails
        }

        // Try primary provider with failover
        log.info("Calling executeWithFailover for idempotencyKey={}, providerType={}", idempotencyKey, requestedType);
        PaymentResult result;
        try {
            result = executeWithFailover(request, requestedType);
        } catch (Exception e) {
            log.error("Exception in executeWithFailover for idempotencyKey={}, providerType={}", 
                    idempotencyKey, requestedType, e);
            throw e;
        }
        
        if (result == null) {
            log.error("executeWithFailover returned null for idempotencyKey={}, providerType={}", 
                    idempotencyKey, requestedType);
            throw new IllegalStateException("Payment execution failed: result is null");
        }
        
        log.debug("Payment execution result: idempotencyKey={}, status={}, providerTransactionId={}", 
                result.getIdempotencyKey(), result.getStatus(), result.getProviderTransactionId());
        
        // Store result for idempotency
        idempotencyService.storeResult(idempotencyKey, result);
        
        return result;
    }

    /**
     * Execute payment with automatic failover to alternative providers.
     */
    private PaymentResult executeWithFailover(PaymentRequest request, PaymentProviderType requestedType) {
        log.info("executeWithFailover: requestedType={}, maxFailoverAttempts={}, failoverEnabled={}", 
                requestedType, maxFailoverAttempts, failoverEnabled);
        List<PaymentGatewayAdapter> attemptedAdapters = new ArrayList<>();
        Exception lastException = null;

        for (int attempt = 0; attempt < maxFailoverAttempts; attempt++) {
            log.info("Failover attempt {} of {}", attempt + 1, maxFailoverAttempts);
            // Select adapter using routing strategy
            Optional<PaymentGatewayAdapter> adapterOpt = providerRouter.selectProvider(request);
            
            if (adapterOpt.isEmpty()) {
                log.warn("No healthy payment gateway available for type={} after {} attempts", 
                        requestedType, attempt);
                PaymentResult failureResult = buildFailureResult(request, "NO_PROVIDER_AVAILABLE", 
                        "No healthy payment gateway available");
                log.debug("Built failure result: status={}, idempotencyKey={}", 
                        failureResult.getStatus(), failureResult.getIdempotencyKey());
                return failureResult;
            }

            PaymentGatewayAdapter adapter = adapterOpt.get();
            PaymentProviderType providerType = adapter.getProviderType();

            // Skip if already attempted
            if (attemptedAdapters.contains(adapter)) {
                continue;
            }
            attemptedAdapters.add(adapter);

            long startTime = System.currentTimeMillis();
            metrics.incrementActiveConnections(providerType);

            try {
                log.info("Executing payment with adapter: providerType={}, adapterClass={}", 
                        providerType, adapter.getClass().getSimpleName());
                PaymentResult result = executeWithProvider(request, adapter, providerType);
                
                if (result == null) {
                    log.error("executeWithProvider returned null for providerType={}, idempotencyKey={}", 
                            providerType, request.getIdempotencyKey());
                    throw new IllegalStateException("Payment adapter returned null result");
                }
                
                // Validate result has required fields
                if (result.getIdempotencyKey() == null || result.getStatus() == null) {
                    log.error("PaymentResult has null required fields: idempotencyKey={}, status={}, amount={}, timestamp={}", 
                            result.getIdempotencyKey(), result.getStatus(), result.getAmount(), result.getTimestamp());
                    throw new IllegalStateException("Payment adapter returned invalid result with null required fields");
                }
                
                long latencyMs = System.currentTimeMillis() - startTime;

                // Record metrics
                if (result.getStatus() == TransactionStatus.SUCCESS || 
                    result.getStatus() == TransactionStatus.CAPTURED) {
                    BigDecimal cost = extractCost(result);
                    metrics.recordSuccess(providerType, latencyMs, cost);
                } else {
                    metrics.recordFailure(providerType, latencyMs);
                }

                log.info("Payment executed successfully with provider={} (attempt={}, latency={}ms)",
                        providerType, attempt + 1, latencyMs);
                
                return result;

            } catch (CallNotPermittedException e) {
                long latencyMs = System.currentTimeMillis() - startTime;
                metrics.recordFailure(providerType, latencyMs);
                log.warn("Circuit open for provider={}, attempting failover (attempt={})",
                        providerType, attempt + 1);
                lastException = e;
                
                if (!failoverEnabled || attempt >= maxFailoverAttempts - 1) {
                    break;
                }
                // Continue to next provider

            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - startTime;
                metrics.recordFailure(providerType, latencyMs);
                log.error("Provider execution failed for provider={} (attempt={})",
                        providerType, attempt + 1, e);
                lastException = e;
                
                if (!failoverEnabled || attempt >= maxFailoverAttempts - 1) {
                    break;
                }
                // Continue to next provider
            } finally {
                metrics.decrementActiveConnections(providerType);
            }
        }

        // All payment gateways failed
        log.error("All payment gateway attempts failed for type={} after {} attempts",
                requestedType, attemptedAdapters.size());
        PaymentResult failureResult = buildFailureResult(request, "ALL_PROVIDERS_FAILED",
                "All payment gateways failed: " + attemptedAdapters.size() + " gateways attempted");
        log.debug("Built final failure result: status={}, idempotencyKey={}, message={}", 
                failureResult.getStatus(), failureResult.getIdempotencyKey(), failureResult.getMessage());
        return failureResult;
    }

    /**
     * Execute payment with a specific payment gateway adapter (with circuit breaker and retry).
     */
    private PaymentResult executeWithProvider(
            PaymentRequest request, 
            PaymentGatewayAdapter adapter, 
            PaymentProviderType providerType) {
        
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(providerType.name());
        Retry retry = retryRegistry.retry(RETRY_INSTANCE);
        Supplier<PaymentResult> supplier = () -> adapter.execute(request);
        Supplier<PaymentResult> withRetry = Retry.decorateSupplier(retry, supplier);
        Supplier<PaymentResult> withCb = CircuitBreaker.decorateSupplier(cb, withRetry);

        return withCb.get();
    }

    /**
     * Extract cost from payment result metadata (if available).
     */
    private BigDecimal extractCost(PaymentResult result) {
        if (result.getMetadata() != null && result.getMetadata().containsKey("cost")) {
            Object costObj = result.getMetadata().get("cost");
            if (costObj instanceof BigDecimal) {
                return (BigDecimal) costObj;
            } else if (costObj instanceof Number) {
                return BigDecimal.valueOf(((Number) costObj).doubleValue());
            }
        }
        return BigDecimal.ZERO; // Default: no cost tracking
    }

    public Optional<PaymentGatewayAdapter> getAdapter(PaymentProviderType type) {
        return Optional.ofNullable(adapterByType.get(type));
    }

    private static PaymentResult buildFailureResult(PaymentRequest request, String code, String message) {
        if (request == null) {
            throw new IllegalArgumentException("PaymentRequest cannot be null when building failure result");
        }
        try {
            PaymentResult result = PaymentResult.builder()
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
            
            // Validate the result was built correctly
            if (result.getIdempotencyKey() == null || result.getStatus() == null || result.getAmount() == null) {
                log.error("buildFailureResult created invalid result: idempotencyKey={}, status={}, amount={}, failureCode={}, message={}", 
                        result.getIdempotencyKey(), result.getStatus(), result.getAmount(), result.getFailureCode(), result.getMessage());
                throw new IllegalStateException("Failed to build valid PaymentResult - required fields are null");
            }
            
            log.debug("buildFailureResult: idempotencyKey={}, status={}, failureCode={}, message={}", 
                    result.getIdempotencyKey(), result.getStatus(), result.getFailureCode(), result.getMessage());
            return result;
        } catch (Exception e) {
            log.error("Exception building failure result for idempotencyKey={}, code={}, message={}", 
                    request.getIdempotencyKey(), code, message, e);
            throw new IllegalStateException("Failed to build PaymentResult", e);
        }
    }
}
