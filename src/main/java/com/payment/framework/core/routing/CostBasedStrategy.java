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
 * Cost-Based routing strategy.
 * Routes requests to the provider with the lowest cost per transaction.
 * Considers both transaction fees and success rate (failed transactions waste fees).
 */
@Slf4j
@Component
public class CostBasedStrategy implements ProviderRoutingStrategy {

    @Override
    public Optional<PaymentProviderType> selectProvider(
            PaymentRequest request,
            List<PaymentProviderType> availableProviders,
            ProviderPerformanceMetrics metrics) {

        if (availableProviders.isEmpty()) {
            return Optional.empty();
        }

        // Calculate effective cost = average cost / success rate
        // Higher success rate = lower effective cost (failed transactions waste fees)
        PaymentProviderType selected = availableProviders.stream()
                .min(Comparator.comparing(provider -> {
                    BigDecimal avgCost = metrics.getAverageCost(provider);
                    double successRate = metrics.getSuccessRate(provider);
                    // Effective cost = cost per successful transaction
                    // If success rate is 0, use very high cost
                    if (successRate == 0.0) {
                        return BigDecimal.valueOf(Double.MAX_VALUE);
                    }
                    return avgCost.divide(BigDecimal.valueOf(successRate), 4, java.math.RoundingMode.HALF_UP);
                }))
                .orElse(availableProviders.get(0));

        BigDecimal effectiveCost = metrics.getAverageCost(selected)
                .divide(BigDecimal.valueOf(Math.max(0.01, metrics.getSuccessRate(selected))), 4, java.math.RoundingMode.HALF_UP);

        log.debug("CostBased selected provider={} with effective cost={} (avgCost={}, successRate={})",
                selected, effectiveCost, metrics.getAverageCost(selected), metrics.getSuccessRate(selected));

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "CostBased";
    }
}
