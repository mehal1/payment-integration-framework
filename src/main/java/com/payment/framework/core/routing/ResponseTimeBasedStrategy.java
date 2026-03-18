package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Response Time-Based routing strategy.
 * Routes requests to the adapter with the lowest average latency.
 * Prioritizes speed over cost or other factors.
 */
@Slf4j
@Component
public class ResponseTimeBasedStrategy implements ProviderRoutingStrategy {

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
                .min(Comparator.comparingLong(a -> metrics.getAverageLatency(a.getPSPAdapterName())))
                .orElse(availableAdapters.get(0));

        log.debug("ResponseTimeBased selected adapter={} with avg latency={}ms",
                selected.getPSPAdapterName(), metrics.getAverageLatency(selected.getPSPAdapterName()));

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "ResponseTimeBased";
    }
}
