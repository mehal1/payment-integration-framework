package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Cost-Based routing strategy.
 * Routes requests to the provider with the lowest cost per transaction.
 * Uses contract-based fee config when available.
 * Falls back to historical average cost when no fee config exists.
 * Considers success rate: effective cost = cost / success rate (failed transactions waste fees).
 */
@Slf4j
@Component
public class CostBasedStrategy implements ProviderRoutingStrategy {

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
                .min(Comparator.comparing(adapter -> {
                    String name = adapter.getPSPAdapterName();
                    BigDecimal cost = feeConfig.hasFeeConfig(name)
                            ? feeConfig.computeCost(name, amount)
                            : metrics.getAverageCost(name);
                    double successRate = metrics.getSuccessRate(name);
                    if (successRate == 0.0) {
                        return BigDecimal.valueOf(Double.MAX_VALUE);
                    }
                    return cost.divide(BigDecimal.valueOf(successRate), 4, RoundingMode.HALF_UP);
                }))
                .orElse(availableAdapters.get(0));

        String selectedName = selected.getPSPAdapterName();
        BigDecimal cost = feeConfig.hasFeeConfig(selectedName)
                ? feeConfig.computeCost(selectedName, amount)
                : metrics.getAverageCost(selectedName);
        double successRate = Math.max(0.01, metrics.getSuccessRate(selectedName));
        BigDecimal effectiveCost = cost.divide(BigDecimal.valueOf(successRate), 4, RoundingMode.HALF_UP);

        log.debug("CostBased selected adapter={} with effective cost={} (cost={}, successRate={})",
                selected.getPSPAdapterName(), effectiveCost, cost, successRate);

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "CostBased";
    }
}
