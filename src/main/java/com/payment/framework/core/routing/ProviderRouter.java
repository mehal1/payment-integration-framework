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
 * Intelligent payment gateway router that selects the best payment gateway adapter based on routing strategy.
 * Supports multiple payment gateways of the same type (e.g., multiple CARD gateways: Stripe, Adyen).
 * Automatically filters out payment gateways with open circuit breakers.
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
     * Select the best payment gateway adapter for the given request.
     * Filters out payment gateways with open circuit breakers and applies routing strategy.
     * Note: Currently supports one adapter per provider type. For multiple payment gateways
     * of the same type (e.g., Stripe and Adyen both CARD), extend PaymentProviderType
     * or add gateway identifier.
     *
     * @param request payment request
     * @return selected payment adapter, or empty if no healthy payment gateway available
     */
    public Optional<PaymentGatewayAdapter> selectProvider(PaymentRequest request) {
        PaymentProviderType requestedType = request.getProviderType();
        List<PaymentGatewayAdapter> availableAdapters = adaptersByType.getOrDefault(requestedType, new ArrayList<>());

        if (availableAdapters.isEmpty()) {
            log.warn("No payment adapters available for provider type={}", requestedType);
            return Optional.empty();
        }

        // If only one adapter, return it (no routing needed)
        if (availableAdapters.size() == 1) {
            PaymentGatewayAdapter adapter = availableAdapters.get(0);
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(requestedType.name());
            if (cb.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN) {
                log.warn("Only payment adapter for type={} has open circuit breaker", requestedType);
                return Optional.empty();
            }
            return Optional.of(adapter);
        }

        // Multiple adapters: filter healthy and apply routing strategy
        List<PaymentProviderType> healthyProviders = availableAdapters.stream()
                .map(PaymentGatewayAdapter::getProviderType)
                .distinct()
                .filter(providerType -> {
                    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(providerType.name());
                    boolean isOpen = cb.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
                    if (isOpen) {
                        log.debug("Filtering out payment gateway={} due to open circuit breaker", providerType);
                    }
                    return !isOpen;
                })
                .collect(Collectors.toList());

        if (healthyProviders.isEmpty()) {
            log.warn("All payment gateways for type={} have open circuit breakers", requestedType);
            return Optional.empty();
        }

        // Apply routing strategy to select best payment gateway
        Optional<PaymentProviderType> selectedType = routingStrategy.selectProvider(
                request, healthyProviders, metrics);

        if (selectedType.isEmpty()) {
            log.warn("Routing strategy returned no payment gateway for type={}", requestedType);
            return Optional.empty();
        }

        // Find adapter for selected payment gateway type
        PaymentGatewayAdapter selectedAdapter = availableAdapters.stream()
                .filter(a -> a.getProviderType() == selectedType.get())
                .findFirst()
                .orElse(null);

        if (selectedAdapter == null) {
            log.error("Selected payment gateway type={} but no adapter found", selectedType.get());
            return Optional.empty();
        }

        log.info("Router selected payment gateway={} using strategy={} for request idempotencyKey={}",
                selectedType.get(), routingStrategy.getStrategyName(), request.getIdempotencyKey());

        return Optional.of(selectedAdapter);
    }

    /**
     * Get all available payment gateways for a given type (including unhealthy ones).
     */
    public List<PaymentProviderType> getAvailableProviders(PaymentProviderType type) {
        return adaptersByType.getOrDefault(type, new ArrayList<>()).stream()
                .map(PaymentGatewayAdapter::getProviderType)
                .collect(Collectors.toList());
    }
}
