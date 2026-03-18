package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Least Connections routing strategy.
 * Routes requests to the adapter with the fewest active connections.
 * Useful for load balancing and preventing adapter overload.
 */
@Slf4j
@Component
public class LeastConnectionsStrategy implements ProviderRoutingStrategy {

    @Override
    public Optional<PSPAdapter> selectAdapter(
            PaymentRequest request,
            List<PSPAdapter> availableAdapters,
            PSPPerformanceMetrics metrics,
            ProviderFeeConfig feeConfig) {

        if (availableAdapters.isEmpty()) {
            return Optional.empty();
        }

        PSPAdapter selected = availableAdapters.stream()
                .min(Comparator.comparingInt(a -> metrics.getActiveConnections(a.getPSPAdapterName())))
                .orElse(availableAdapters.get(0));

        log.debug("LeastConnections selected adapter={} with {} active connections",
                selected.getPSPAdapterName(), metrics.getActiveConnections(selected.getPSPAdapterName()));

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "LeastConnections";
    }
}
