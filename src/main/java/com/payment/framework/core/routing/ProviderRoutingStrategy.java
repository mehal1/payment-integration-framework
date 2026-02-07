package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;

import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for selecting payment providers based on different algorithms.
 * Implementations can use weighted round-robin, least connections, cost-based, or ML-based routing.
 */
public interface ProviderRoutingStrategy {

    /**
     * Select the best provider for the given request from available providers.
     *
     * @param request payment request
     * @param availableProviders list of available provider types (same payment method category)
     * @param metrics performance metrics for all providers
     * @return selected provider type, or empty if no provider available
     */
    Optional<PaymentProviderType> selectProvider(
            PaymentRequest request,
            List<PaymentProviderType> availableProviders,
            ProviderPerformanceMetrics metrics
    );

    /**
     * Get strategy name for logging and metrics.
     */
    String getStrategyName();
}
