package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Response Time-Based routing strategy.
 * Routes requests to the provider with the lowest average latency.
 * Prioritizes speed over cost or other factors.
 */
@Slf4j
@Component
public class ResponseTimeBasedStrategy implements ProviderRoutingStrategy {

    @Override
    public Optional<PaymentProviderType> selectProvider(
            PaymentRequest request,
            List<PaymentProviderType> availableProviders,
            ProviderPerformanceMetrics metrics) {

        if (availableProviders.isEmpty()) {
            return Optional.empty();
        }

        // Select provider with lowest average latency
        PaymentProviderType selected = availableProviders.stream()
                .min(Comparator.comparingLong(metrics::getAverageLatency))
                .orElse(availableProviders.get(0));

        log.debug("ResponseTimeBased selected provider={} with avg latency={}ms",
                selected, metrics.getAverageLatency(selected));

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "ResponseTimeBased";
    }
}
