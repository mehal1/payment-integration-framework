package com.payment.framework.core.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Contract-based fee configuration for PSP adapters.
 * Enables cost-based routing using known fee structures (percent + fixed) from merchant-PSP contracts
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "payment.fees")
public class ProviderFeeConfig {

    /**
     * Fee structure per adapter name (e.g., MockStripeAdapter, MockAdyenAdapter).
     * Key: adapter name (getPSPAdapterName()), Value: percent and fixed fee.
     */
    private Map<String, AdapterFee> adapters = new HashMap<>();

    /**
     * Compute cost for a transaction with the given adapter.
     *
     * @param adapterName PSP adapter name (e.g., MockStripeAdapter)
     * @param amount      transaction amount
     * @return computed cost, or BigDecimal.ZERO if no config for this adapter
     */
    public BigDecimal computeCost(String adapterName, BigDecimal amount) {
        AdapterFee fee = adapters.get(adapterName);
        if (fee == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal percentCost = amount.multiply(fee.getPercent().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        return percentCost.add(fee.getFixed()).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Check if fee config exists for the given adapter.
     */
    public boolean hasFeeConfig(String adapterName) {
        return adapters.containsKey(adapterName);
    }

    public Map<String, AdapterFee> getAdapters() {
        return adapters;
    }

    public void setAdapters(Map<String, AdapterFee> adapters) {
        this.adapters = adapters != null ? adapters : new HashMap<>();
        log.info("Loaded fee config for {} adapter(s): {}", this.adapters.size(), this.adapters.keySet());
    }

    /**
     * Fee structure for an adapter: percent (e.g., 2.9) + fixed (e.g., 0.30).
     */
    public static class AdapterFee {
        private BigDecimal percent = BigDecimal.ZERO;
        private BigDecimal fixed = BigDecimal.ZERO;

        public BigDecimal getPercent() {
            return percent;
        }

        public void setPercent(BigDecimal percent) {
            this.percent = percent != null ? percent : BigDecimal.ZERO;
        }

        public BigDecimal getFixed() {
            return fixed;
        }

        public void setFixed(BigDecimal fixed) {
            this.fixed = fixed != null ? fixed : BigDecimal.ZERO;
        }
    }
}
