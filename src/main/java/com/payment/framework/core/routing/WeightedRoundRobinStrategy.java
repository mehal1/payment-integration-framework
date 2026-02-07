package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Weighted Round-Robin routing strategy.
 * Routes requests based on provider weights (derived from success rate and performance).
 * Providers with higher success rates get more traffic.
 */
@Slf4j
@Component
public class WeightedRoundRobinStrategy implements ProviderRoutingStrategy {

    private final Map<PaymentProviderType, AtomicInteger> currentIndex = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentProviderType> selectProvider(
            PaymentRequest request,
            List<PaymentProviderType> availableProviders,
            ProviderPerformanceMetrics metrics) {

        if (availableProviders.isEmpty()) {
            return Optional.empty();
        }

        if (availableProviders.size() == 1) {
            return Optional.of(availableProviders.get(0));
        }

        // Calculate weights based on success rate (higher success rate = higher weight)
        int totalWeight = 0;
        int[] weights = new int[availableProviders.size()];
        for (int i = 0; i < availableProviders.size(); i++) {
            PaymentProviderType provider = availableProviders.get(i);
            double successRate = metrics.getSuccessRate(provider);
            // Weight = success rate * 100 (0-100 scale)
            // Minimum weight of 1 to ensure all providers get some traffic
            weights[i] = Math.max(1, (int) (successRate * 100));
            totalWeight += weights[i];
        }

        // Select provider using weighted round-robin
        int selectedIndex = selectWeightedIndex(availableProviders, weights, totalWeight);
        PaymentProviderType selected = availableProviders.get(selectedIndex);

        log.debug("WeightedRoundRobin selected provider={} from {} providers (weights={})",
                selected, availableProviders.size(), weights);

        return Optional.of(selected);
    }

    private int selectWeightedIndex(List<PaymentProviderType> providers, int[] weights, int totalWeight) {
        // Get current index for this provider set (use first provider as key)
        PaymentProviderType key = providers.get(0);
        AtomicInteger index = currentIndex.computeIfAbsent(key, k -> new AtomicInteger(0));

        // Simple round-robin with weights
        int current = index.getAndIncrement() % totalWeight;
        int cumulativeWeight = 0;

        for (int i = 0; i < weights.length; i++) {
            cumulativeWeight += weights[i];
            if (current < cumulativeWeight) {
                return i;
            }
        }

        return 0; // Fallback
    }

    @Override
    public String getStrategyName() {
        return "WeightedRoundRobin";
    }
}
