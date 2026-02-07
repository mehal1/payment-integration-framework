package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Hybrid routing strategy that combines multiple factors:
 * - Success rate (weight: 40%)
 * - Average latency (weight: 30%)
 * - Cost (weight: 20%)
 * - Active connections (weight: 10%)
 * 
 * Calculates a composite score and selects the provider with the highest score.
 */
@Slf4j
@Component
public class HybridRoutingStrategy implements ProviderRoutingStrategy {

    @Override
    public Optional<PaymentProviderType> selectProvider(
            PaymentRequest request,
            List<PaymentProviderType> availableProviders,
            ProviderPerformanceMetrics metrics) {

        if (availableProviders.isEmpty()) {
            return Optional.empty();
        }

        // Calculate composite score for each provider
        PaymentProviderType selected = availableProviders.stream()
                .max(Comparator.comparingDouble(provider -> calculateScore(provider, metrics)))
                .orElse(availableProviders.get(0));

        double score = calculateScore(selected, metrics);
        log.debug("HybridRouting selected provider={} with score={}", selected, score);

        return Optional.of(selected);
    }

    /**
     * Calculate composite score (0.0 to 1.0) for a provider.
     * Higher score = better provider.
     */
    private double calculateScore(PaymentProviderType provider, ProviderPerformanceMetrics metrics) {
        double successRate = metrics.getSuccessRate(provider);
        long avgLatency = metrics.getAverageLatency(provider);
        BigDecimal avgCost = metrics.getAverageCost(provider);
        int activeConnections = metrics.getActiveConnections(provider);

        // Normalize latency (inverse: lower latency = higher score)
        // Assume max latency of 5000ms for normalization
        double latencyScore = Math.max(0.0, 1.0 - (avgLatency / 5000.0));

        // Normalize cost (inverse: lower cost = higher score)
        // Assume max cost of $1.00 for normalization
        double costScore = Math.max(0.0, 1.0 - avgCost.doubleValue());

        // Normalize connections (inverse: fewer connections = higher score)
        // Assume max connections of 100 for normalization
        double connectionsScore = Math.max(0.0, 1.0 - (activeConnections / 100.0));

        // Weighted composite score
        double score = (successRate * 0.40) +
                      (latencyScore * 0.30) +
                      (costScore * 0.20) +
                      (connectionsScore * 0.10);

        return score;
    }

    @Override
    public String getStrategyName() {
        return "Hybrid";
    }
}
