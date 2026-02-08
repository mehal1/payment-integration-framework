package com.payment.framework.core.routing;

import com.payment.framework.core.PaymentGatewayAdapter;
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
 * Picks which gateway to use based on cost, speed, or round-robin.
 * Skips gateways that are currently broken.
 */
@Slf4j
@Service
public class ProviderRouter {

    private final Map<PaymentProviderType, List<PaymentGatewayAdapter>> adaptersByType;
    private final ProviderPerformanceMetrics metrics;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ProviderRoutingStrategy routingStrategy;

    public ProviderRouter(
            List<PaymentGatewayAdapter> adapters,
            ProviderPerformanceMetrics metrics,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProviderRoutingStrategy routingStrategy) {
        this.metrics = metrics;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.routingStrategy = routingStrategy;
        log.info("Initializing ProviderRouter with {} payment gateway adapters", adapters.size());
        if (adapters.isEmpty()) {
            log.warn("No payment gateway adapters found! Make sure adapters are annotated with @Component and implement PaymentGatewayAdapter");
        }
        this.adaptersByType = adapters.stream()
                .collect(Collectors.groupingBy(PaymentGatewayAdapter::getProviderType));
        log.info("Registered payment gateway adapter types: {}", adaptersByType.keySet());
    }

    /**
     * Find a good gateway for this payment. Skips broken ones and uses our routing rules.
     */
    public Optional<PaymentGatewayAdapter> selectProvider(PaymentRequest request) {
        PaymentProviderType requestedType = request.getProviderType();
        List<PaymentGatewayAdapter> availableAdapters = adaptersByType.getOrDefault(requestedType, new ArrayList<>());

        if (availableAdapters.isEmpty()) {
            log.warn("No payment adapters available for provider type={}", requestedType);
            return Optional.empty();
        }

        // Filter adapters by per-gateway circuit breaker (not per-provider-type)
        // This allows failover: if Stripe fails, Adyen (also CARD) can still be used
        List<PaymentGatewayAdapter> healthyAdapters = availableAdapters.stream()
                .filter(adapter -> {
                    String gatewayName = adapter.getGatewayName();
                    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(gatewayName);
                    boolean isOpen = cb.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
                    if (isOpen) {
                        log.debug("Filtering out payment gateway={} (type={}) due to open circuit breaker", 
                                gatewayName, adapter.getProviderType());
                    }
                    return !isOpen;
                })
                .collect(Collectors.toList());

        if (healthyAdapters.isEmpty()) {
            log.warn("All payment gateways for type={} have open circuit breakers", requestedType);
            return Optional.empty();
        }

        // If only one healthy adapter, return it (no routing needed)
        if (healthyAdapters.size() == 1) {
            PaymentGatewayAdapter adapter = healthyAdapters.get(0);
            log.debug("Single healthy adapter available: gateway={}, type={}", 
                    adapter.getGatewayName(), adapter.getProviderType());
            return Optional.of(adapter);
        }

        // Multiple healthy adapters: apply routing strategy
        // Convert to provider types for routing strategy (strategy works with types)
        List<PaymentProviderType> healthyProviderTypes = healthyAdapters.stream()
                .map(PaymentGatewayAdapter::getProviderType)
                .distinct()
                .collect(Collectors.toList());

        Optional<PaymentProviderType> selectedType = routingStrategy.selectProvider(
                request, healthyProviderTypes, metrics);

        if (selectedType.isEmpty()) {
            log.warn("Routing strategy returned no payment gateway for type={}", requestedType);
            return Optional.empty();
        }

        // Find adapter for selected payment gateway type
        // If multiple adapters have same type, pick first healthy one
        // (In future, routing strategy could select specific gateway)
        PaymentGatewayAdapter selectedAdapter = healthyAdapters.stream()
                .filter(a -> a.getProviderType() == selectedType.get())
                .findFirst()
                .orElse(null);

        if (selectedAdapter == null) {
            log.error("Selected payment gateway type={} but no healthy adapter found", selectedType.get());
            return Optional.empty();
        }

        log.info("Router selected payment gateway={} (type={}) using strategy={} for request idempotencyKey={}",
                selectedAdapter.getGatewayName(), selectedType.get(), routingStrategy.getStrategyName(), 
                request.getIdempotencyKey());

        return Optional.of(selectedAdapter);
    }

    public List<PaymentProviderType> getAvailableProviders(PaymentProviderType type) {
        return adaptersByType.getOrDefault(type, new ArrayList<>()).stream()
                .map(PaymentGatewayAdapter::getProviderType)
                .collect(Collectors.toList());
    }
}
