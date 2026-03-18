package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Weighted Round-Robin routing strategy.
 * Routes requests based on adapter weights (derived from success rate).
 * Adapters with higher success rates get more traffic.
 */
@Slf4j
@Component
public class WeightedRoundRobinStrategy implements ProviderRoutingStrategy {

    private final Map<String, AtomicInteger> currentIndex = new ConcurrentHashMap<>();

    @Override
    public Optional<PSPAdapter> selectAdapter(
            PaymentRequest request,
            List<PSPAdapter> availableAdapters,
            PSPPerformanceMetrics metrics,
            ProviderFeeConfig feeConfig) {

        if (availableAdapters.isEmpty()) {
            return Optional.empty();
        }

        if (availableAdapters.size() == 1) {
            return Optional.of(availableAdapters.get(0));
        }

        // Calculate weights based on success rate per adapter
        int totalWeight = 0;
        int[] weights = new int[availableAdapters.size()];
        for (int i = 0; i < availableAdapters.size(); i++) {
            double successRate = metrics.getSuccessRate(availableAdapters.get(i).getPSPAdapterName());
            weights[i] = Math.max(1, (int) (successRate * 100));
            totalWeight += weights[i];
        }

        String key = availableAdapters.stream()
                .map(PSPAdapter::getPSPAdapterName)
                .sorted()
                .collect(Collectors.joining(","));
        int selectedIndex = selectWeightedIndex(weights, totalWeight, key);

        PSPAdapter selected = availableAdapters.get(selectedIndex);
        log.debug("WeightedRoundRobin selected adapter={} from {} adapters (weights={})",
                selected.getPSPAdapterName(), availableAdapters.size(), weights);

        return Optional.of(selected);
    }

    private int selectWeightedIndex(int[] weights, int totalWeight, String key) {
        AtomicInteger index = currentIndex.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = index.getAndIncrement() % totalWeight;
        int cumulativeWeight = 0;

        for (int i = 0; i < weights.length; i++) {
            cumulativeWeight += weights[i];
            if (current < cumulativeWeight) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public String getStrategyName() {
        return "WeightedRoundRobin";
    }
}
