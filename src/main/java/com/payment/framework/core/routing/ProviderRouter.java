package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Picks which PSP adapter to use based on cost, speed, or round-robin.
 * Skips PSP adapters that are currently broken.
 */
@Slf4j
@Service
public class ProviderRouter {

    private final Map<PaymentProviderType, List<PSPAdapter>> adaptersByType;
    private final PSPPerformanceMetrics metrics;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ProviderRoutingStrategy routingStrategy;
    private final ProviderFeeConfig feeConfig;

    public ProviderRouter(
            List<PSPAdapter> adapters,
            PSPPerformanceMetrics metrics,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProviderRoutingStrategy routingStrategy,
            ProviderFeeConfig feeConfig) {
        this.metrics = metrics;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.routingStrategy = routingStrategy;
        this.feeConfig = feeConfig;
        log.info("Initializing ProviderRouter with {} PSP adapters", adapters.size());
        if (adapters.isEmpty()) {
            log.warn("No PSP adapters found! Make sure adapters are annotated with @Component and implement PSPAdapter");
        }
        this.adaptersByType = adapters.stream()
                .collect(Collectors.groupingBy(PSPAdapter::getProviderType));
        log.info("Registered PSP adapter types: {}", adaptersByType.keySet());
    }

    /**
     * Find a good PSP for this payment. Skips broken ones and uses our routing rules.
     */
    public Optional<PSPAdapter> selectProvider(PaymentRequest request) {
        PaymentProviderType requestedType = request.getProviderType();
        List<PSPAdapter> availableAdapters = adaptersByType.getOrDefault(requestedType, new ArrayList<>());

        if (availableAdapters.isEmpty()) {
            log.warn("No PSP adapters available for provider type={}", requestedType);
            return Optional.empty();
        }

        // Filter adapters by per-PSP adapter circuit breaker (not per-provider-type)
        // This allows failover: if Stripe fails, Adyen (also CARD) can still be used
        List<PSPAdapter> healthyAdapters = availableAdapters.stream()
                .filter(adapter -> {
                    String pspAdapterName = adapter.getPSPAdapterName();
                    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(pspAdapterName);
                    boolean isOpen = cb.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
                    if (isOpen) {
                        log.debug("Filtering out PSP adapter={} (type={}) due to open circuit breaker", 
                                pspAdapterName, adapter.getProviderType());
                    }
                    return !isOpen;
                })
                .collect(Collectors.toList());

        if (healthyAdapters.isEmpty()) {
            log.warn("All PSPs for type={} have open circuit breakers", requestedType);
            return Optional.empty();
        }

        // If only one healthy adapter, return it (no routing needed)
        if (healthyAdapters.size() == 1) {
            PSPAdapter adapter = healthyAdapters.get(0);
            log.debug("Single healthy adapter available: PSP adapter={}, type={}", 
                    adapter.getPSPAdapterName(), adapter.getProviderType());
            return Optional.of(adapter);
        }

        // For testing: if request specifies adapter name in metadata, use that adapter
        if (request.getProviderPayload() != null && request.getProviderPayload().containsKey("testAdapterName")) {
            String requestedAdapterName = (String) request.getProviderPayload().get("testAdapterName");
            Optional<PSPAdapter> testAdapter = healthyAdapters.stream()
                    .filter(a -> a.getPSPAdapterName().equals(requestedAdapterName))
                    .findFirst();
            if (testAdapter.isPresent()) {
                log.debug("Using test-specified adapter: {}", requestedAdapterName);
                return testAdapter;
            }
        }

        // Use adapter-level selection (supports cost-based routing with multiple PSPs of same type)
        Optional<PSPAdapter> selectedAdapter = routingStrategy.selectAdapter(
                request, healthyAdapters, metrics, feeConfig);

        if (selectedAdapter.isEmpty()) {
            log.warn("Routing strategy returned no PSP for type={}", requestedType);
            return Optional.empty();
        }

        log.info("Router selected PSP adapter={} (type={}) using strategy={} for request idempotencyKey={}",
                selectedAdapter.get().getPSPAdapterName(), selectedAdapter.get().getProviderType(),
                routingStrategy.getStrategyName(), request.getIdempotencyKey());

        return selectedAdapter;
    }

    public List<PaymentProviderType> getAvailableProviders(PaymentProviderType type) {
        return adaptersByType.getOrDefault(type, new ArrayList<>()).stream()
                .map(PSPAdapter::getProviderType)
                .collect(Collectors.toList());
    }
}
