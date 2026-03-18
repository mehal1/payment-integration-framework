package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
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
 * - Cost (weight: 20%) - uses contract-based fee config when available
 * - Active connections (weight: 10%)
 *
 * Calculates a composite score and selects the adapter with the highest score.
 */
@Slf4j
@Component
public class HybridRoutingStrategy implements ProviderRoutingStrategy {

    @Override
    public Optional<PSPAdapter> selectAdapter(
            PaymentRequest request,
            List<PSPAdapter> availableAdapters,
            PSPPerformanceMetrics metrics,
            ProviderFeeConfig feeConfig) {

        if (availableAdapters.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal amount = request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO;

        PSPAdapter selected = availableAdapters.stream()
                .max(Comparator.comparingDouble(adapter ->
                        calculateScore(adapter, amount, metrics, feeConfig)))
                .orElse(availableAdapters.get(0));

        double score = calculateScore(selected, amount, metrics, feeConfig);
        log.debug("HybridRouting selected adapter={} with score={}", selected.getPSPAdapterName(), score);

        return Optional.of(selected);
    }

    /**
     * Calculate composite score (0.0 to 1.0) for an adapter.
     * Higher score = better adapter.
     */
    private double calculateScore(PSPAdapter adapter, BigDecimal amount,
                                  PSPPerformanceMetrics metrics, ProviderFeeConfig feeConfig) {
        String adapterName = adapter.getPSPAdapterName();
        double successRate = metrics.getSuccessRate(adapterName);
        long avgLatency = metrics.getAverageLatency(adapterName);
        BigDecimal cost = feeConfig.hasFeeConfig(adapterName)
                ? feeConfig.computeCost(adapterName, amount)
                : metrics.getAverageCost(adapterName);
        int activeConnections = metrics.getActiveConnections(adapterName);

        // Normalize latency (inverse: lower latency = higher score)
        double latencyScore = Math.max(0.0, 1.0 - (avgLatency / 5000.0));

        // Normalize cost (inverse: lower cost = higher score)
        double costScore = Math.max(0.0, 1.0 - cost.doubleValue());

        // Normalize connections (inverse: fewer connections = higher score)
        double connectionsScore = Math.max(0.0, 1.0 - (activeConnections / 100.0));

        return (successRate * 0.40) + (latencyScore * 0.30) + (costScore * 0.20) + (connectionsScore * 0.10);
    }

    @Override
    public String getStrategyName() {
        return "Hybrid";
    }
}
