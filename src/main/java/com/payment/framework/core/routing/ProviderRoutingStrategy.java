package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentRequest;

import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for selecting payment providers based on different algorithms.
 * All strategies select directly among adapters (e.g., Stripe vs Adyen for CARD).
 */
public interface ProviderRoutingStrategy {

    /**
     * Select the best adapter for the given request from available adapters.
     *
     * @param request           payment request
     * @param availableAdapters list of healthy adapters (same payment method category)
     * @param metrics           performance metrics
     * @param feeConfig         contract-based fee configuration (optional)
     * @return selected adapter, or empty if none available
     */
    Optional<PSPAdapter> selectAdapter(
            PaymentRequest request,
            List<PSPAdapter> availableAdapters,
            PSPPerformanceMetrics metrics,
            ProviderFeeConfig feeConfig);

    /**
     * Get strategy name for logging and metrics.
     */
    String getStrategyName();
}
