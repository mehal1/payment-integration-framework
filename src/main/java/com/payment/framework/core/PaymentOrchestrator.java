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
     * Handles payment execution, picks the best PSP, and switches to backups if one fails.
     */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOrchestrator {

    private static final String RETRY_INSTANCE = "payment";

    private final List<PSPAdapter> adapters;
    private final IdempotencyService idempotencyService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ProviderRouter providerRouter;
    private final ProviderPerformanceMetrics metrics;

    @Value("${payment.routing.failover.enabled:true}")
    private boolean failoverEnabled;

    @Value("${payment.routing.failover.max-attempts:3}")
    private int maxFailoverAttempts;

    private Map<PaymentProviderType, PSPAdapter> adapterByType;

    @jakarta.annotation.PostConstruct
    void init() {
        log.info("Initializing PaymentOrchestrator with {} PSP adapters", adapters.size());
        if (adapters.isEmpty()) {
            log.warn("No PSP adapters found! Make sure adapters are annotated with @Component and implement PSPAdapter");
        }
        // Multiple adapters can share a type (e.g. MockStripe + MockAdyen both CARD); keep first for getAdapter(type).
        adapterByType = adapters.stream()
                .collect(Collectors.toMap(PSPAdapter::getProviderType, a -> a, (first, second) -> first));
        log.info("Registered PSP adapters: {}", adapterByType.keySet());
        log.info("PaymentOrchestrator configuration: failoverEnabled={}, maxFailoverAttempts={}", 
                failoverEnabled, maxFailoverAttempts);
        if (maxFailoverAttempts <= 0) {
            log.error("maxFailoverAttempts is {} - this will prevent payment execution! Check configuration.", maxFailoverAttempts);
        }
    }

    /**
     * Process a payment - checks for duplicates, picks a PSP, and tries backups if needed.
     */
    public PaymentResult execute(PaymentRequest request) {
        String idempotencyKey = request.getIdempotencyKey();
        PaymentProviderType requestedType = request.getProviderType();
        
        log.info("Executing payment: idempotencyKey={}, providerType={}, amount={}, failoverEnabled={}, maxFailoverAttempts={}", 
                idempotencyKey, requestedType, request.getAmount(), failoverEnabled, maxFailoverAttempts);

        try {
            Optional<PaymentResult> cached = idempotencyService.getCachedResult(idempotencyKey);
            if (cached.isPresent()) {
                PaymentResult cachedResult = cached.get();
                log.info("Returning cached result for idempotencyKey={}, status={}, amount={}", 
                        idempotencyKey, cachedResult.getStatus(), cachedResult.getAmount());
                if (cachedResult.getIdempotencyKey() == null || cachedResult.getStatus() == null) {
                    log.error("Cached result has null required fields! idempotencyKey={}, status={}, amount={}. " +
                            "This suggests Redis contains corrupted data. Clearing cache and proceeding with new execution.",
                            cachedResult.getIdempotencyKey(), cachedResult.getStatus(), cachedResult.getAmount());
                } else {
                    return cachedResult;
                }
            }
        } catch (Exception e) {
            log.error("Error checking idempotency for idempotencyKey={}", idempotencyKey, e);
        }

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
        
        idempotencyService.storeResult(idempotencyKey, result);
        
        return result;
    }

    /**
     * Try payment with different PSP adapters until one works or we run out of options.
     */
    private PaymentResult executeWithFailover(PaymentRequest request, PaymentProviderType requestedType) {
        log.info("executeWithFailover: requestedType={}, maxFailoverAttempts={}, failoverEnabled={}", 
                requestedType, maxFailoverAttempts, failoverEnabled);
        List<PSPAdapter> attemptedAdapters = new ArrayList<>();
        Exception lastException = null;

        for (int attempt = 0; attempt < maxFailoverAttempts; attempt++) {
            log.info("Failover attempt {} of {}", attempt + 1, maxFailoverAttempts);
            Optional<PSPAdapter> adapterOpt = providerRouter.selectProvider(request);
            
            if (adapterOpt.isEmpty()) {
                log.warn("No healthy PSP available for type={} after {} attempts", 
                        requestedType, attempt);
                PaymentResult failureResult = buildFailureResult(request, "NO_PROVIDER_AVAILABLE", 
                        "No healthy PSP available");
                log.debug("Built failure result: status={}, idempotencyKey={}", 
                        failureResult.getStatus(), failureResult.getIdempotencyKey());
                return failureResult;
            }

            PSPAdapter adapter = adapterOpt.get();
            PaymentProviderType providerType = adapter.getProviderType();

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
                
                if (result.getIdempotencyKey() == null || result.getStatus() == null) {
                    log.error("PaymentResult has null required fields: idempotencyKey={}, status={}, amount={}, timestamp={}", 
                            result.getIdempotencyKey(), result.getStatus(), result.getAmount(), result.getTimestamp());
                    throw new IllegalStateException("Payment adapter returned invalid result with null required fields");
                }
                
                long latencyMs = System.currentTimeMillis() - startTime;

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

        log.error("All PSP attempts failed for type={} after {} attempts",
                requestedType, attemptedAdapters.size());
        PaymentResult failureResult = buildFailureResult(request, "ALL_PROVIDERS_FAILED",
                "All PSPs failed: " + attemptedAdapters.size() + " PSPs attempted");
        log.debug("Built final failure result: status={}, idempotencyKey={}, message={}", 
                failureResult.getStatus(), failureResult.getIdempotencyKey(), failureResult.getMessage());
        return failureResult;
    }

    /**
     * Actually call the PSP with retries and circuit breaker protection.
     * Each PSP gets its own circuit breaker so one failure doesn't block others.
     */
    private PaymentResult executeWithProvider(
            PaymentRequest request, 
            PSPAdapter adapter, 
            PaymentProviderType providerType) {
        
        // Use PSP adapter name for circuit breaker (per-PSP adapter, not per-provider-type)
        // This allows failover: if Stripe fails, Adyen (also CARD) can still be used
        String pspAdapterName = adapter.getPSPAdapterName();
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(pspAdapterName);
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
        return BigDecimal.ZERO;
    }

    public Optional<PSPAdapter> getAdapter(PaymentProviderType type) {
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
