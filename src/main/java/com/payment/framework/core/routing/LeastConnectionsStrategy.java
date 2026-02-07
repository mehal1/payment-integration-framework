package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Least Connections routing strategy.
 * Routes requests to the provider with the fewest active connections.
 * Useful for load balancing and preventing provider overload.
 */
@Slf4j
@Component
public class LeastConnectionsStrategy implements ProviderRoutingStrategy {

    @Override
    public Optional<PaymentProviderType> selectProvider(
            PaymentRequest request,
            List<PaymentProviderType> availableProviders,
            ProviderPerformanceMetrics metrics) {

        if (availableProviders.isEmpty()) {
            return Optional.empty();
        }

        // Select provider with least active connections
        PaymentProviderType selected = availableProviders.stream()
                .min(Comparator.comparingInt(metrics::getActiveConnections))
                .orElse(availableProviders.get(0));

        log.debug("LeastConnections selected provider={} with {} active connections",
                selected, metrics.getActiveConnections(selected));

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "LeastConnections";
    }
}
