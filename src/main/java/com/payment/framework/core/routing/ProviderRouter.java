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
    private final ProviderPerformanceMetrics metrics;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ProviderRoutingStrategy routingStrategy;

    public ProviderRouter(
            List<PSPAdapter> adapters,
            ProviderPerformanceMetrics metrics,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProviderRoutingStrategy routingStrategy) {
        this.metrics = metrics;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.routingStrategy = routingStrategy;
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

        List<PaymentProviderType> healthyProviderTypes = healthyAdapters.stream()
                .map(PSPAdapter::getProviderType)
                .distinct()
                .collect(Collectors.toList());

        Optional<PaymentProviderType> selectedType = routingStrategy.selectProvider(
                request, healthyProviderTypes, metrics);

        if (selectedType.isEmpty()) {
            log.warn("Routing strategy returned no PSP for type={}", requestedType);
            return Optional.empty();
        }

        // For testing: if request specifies adapter name in metadata, use that adapter
        PSPAdapter selectedAdapter = null;
        if (request.getProviderPayload() != null && request.getProviderPayload().containsKey("testAdapterName")) {
            String requestedAdapterName = (String) request.getProviderPayload().get("testAdapterName");
            selectedAdapter = healthyAdapters.stream()
                    .filter(a -> a.getPSPAdapterName().equals(requestedAdapterName))
                    .findFirst()
                    .orElse(null);
            if (selectedAdapter != null) {
                log.debug("Using test-specified adapter: {}", requestedAdapterName);
            }
        }
        
        // Default: select first adapter matching the selected type
        if (selectedAdapter == null) {
            selectedAdapter = healthyAdapters.stream()
                    .filter(a -> a.getProviderType() == selectedType.get())
                    .findFirst()
                    .orElse(null);
        }

        if (selectedAdapter == null) {
            log.error("Selected PSP type={} but no healthy adapter found", selectedType.get());
            return Optional.empty();
        }

        log.info("Router selected PSP adapter={} (type={}) using strategy={} for request idempotencyKey={}",
                selectedAdapter.getPSPAdapterName(), selectedType.get(), routingStrategy.getStrategyName(), 
                request.getIdempotencyKey());

        return Optional.of(selectedAdapter);
    }

    public List<PaymentProviderType> getAvailableProviders(PaymentProviderType type) {
        return adaptersByType.getOrDefault(type, new ArrayList<>()).stream()
                .map(PSPAdapter::getProviderType)
                .collect(Collectors.toList());
    }
}
