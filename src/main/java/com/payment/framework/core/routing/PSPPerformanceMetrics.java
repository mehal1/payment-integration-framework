package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks performance metrics for each PSP (Payment Service Provider) to enable intelligent routing.
 * Metrics include success rate, average latency, and active connections.
 * Thread-safe implementation using atomic operations.
 */
@Slf4j
@Component
public class PSPPerformanceMetrics {

    private static final int WINDOW_SIZE = 1000; // Track last 1000 transactions per provider
    private static final long METRICS_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private final Map<PaymentProviderType, ProviderMetrics> metrics = new ConcurrentHashMap<>();
    private final Map<String, ProviderMetrics> adapterMetrics = new ConcurrentHashMap<>();
    private final Map<String, PaymentProviderType> adapterToType = new ConcurrentHashMap<>();

    /**
     * Record a successful transaction (adapter-level for accurate per-PSP routing).
     */
    public void recordSuccess(String adapterName, PaymentProviderType providerType, long latencyMs) {
        adapterToType.put(adapterName, providerType);
        ProviderMetrics m = adapterMetrics.computeIfAbsent(adapterName, k -> new ProviderMetrics());
        m.recordSuccess(latencyMs);
        // Also update provider-type metrics for backward compatibility
        ProviderMetrics typeM = metrics.computeIfAbsent(providerType, k -> new ProviderMetrics());
        typeM.recordSuccess(latencyMs);
        log.debug("Recorded success for adapter={}, type={}, latency={}ms", adapterName, providerType, latencyMs);
    }

    /**
     * Record a failed transaction.
     */
    public void recordFailure(String adapterName, PaymentProviderType providerType, long latencyMs) {
        adapterToType.put(adapterName, providerType);
        ProviderMetrics m = adapterMetrics.computeIfAbsent(adapterName, k -> new ProviderMetrics());
        m.recordFailure(latencyMs);
        ProviderMetrics typeM = metrics.computeIfAbsent(providerType, k -> new ProviderMetrics());
        typeM.recordFailure(latencyMs);
        log.debug("Recorded failure for adapter={}, type={}, latency={}ms", adapterName, providerType, latencyMs);
    }

    /**
     * Increment active connection count (when starting a request).
     */
    public void incrementActiveConnections(String adapterName, PaymentProviderType providerType) {
        adapterToType.put(adapterName, providerType);
        ProviderMetrics m = adapterMetrics.computeIfAbsent(adapterName, k -> new ProviderMetrics());
        m.incrementActiveConnections();
        ProviderMetrics typeM = metrics.computeIfAbsent(providerType, k -> new ProviderMetrics());
        typeM.incrementActiveConnections();
    }

    /**
     * Decrement active connection count (when request completes).
     */
    public void decrementActiveConnections(String adapterName, PaymentProviderType providerType) {
        ProviderMetrics m = adapterMetrics.get(adapterName);
        if (m != null) m.decrementActiveConnections();
        ProviderMetrics typeM = metrics.get(providerType);
        if (typeM != null) typeM.decrementActiveConnections();
    }

    /**
     * Get success rate (0.0 to 1.0) for the adapter (per-PSP).
     */
    public double getSuccessRate(String adapterName) {
        ProviderMetrics m = adapterMetrics.get(adapterName);
        if (m == null) return 0.0;
        return m.getSuccessRate();
    }

    /**
     * Get average latency in milliseconds for the adapter.
     */
    public long getAverageLatency(String adapterName) {
        ProviderMetrics m = adapterMetrics.get(adapterName);
        if (m == null) return Long.MAX_VALUE;
        return m.getAverageLatency();
    }

    /**
     * Get average cost per transaction for the adapter.
     */
    public BigDecimal getAverageCost(String adapterName) {
        return BigDecimal.ZERO;
    }

    /**
     * Get current active connection count for the adapter.
     */
    public int getActiveConnections(String adapterName) {
        ProviderMetrics m = adapterMetrics.get(adapterName);
        if (m == null) return 0;
        return m.getActiveConnections();
    }

    /**
     * Get success rate for provider type (aggregated from adapters of that type).
     */
    public double getSuccessRate(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return 0.0;
        return m.getSuccessRate();
    }

    /**
     * Get average latency in milliseconds for provider type.
     */
    public long getAverageLatency(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return Long.MAX_VALUE;
        return m.getAverageLatency();
    }

    /**
     * Get average cost per transaction for provider type.
     */
    public BigDecimal getAverageCost(PaymentProviderType provider) {
        return BigDecimal.ZERO;
    }

    /**
     * Get current active connection count for provider type.
     */
    public int getActiveConnections(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return 0;
        return m.getActiveConnections();
    }

    /**
     * Get total transaction count for provider type.
     */
    public int getTotalTransactions(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return 0;
        return m.getTotalTransactions();
    }

    /**
     * Get all metrics for a provider type.
     */
    public ProviderStats getStats(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) {
            return new ProviderStats(provider, 0.0, 0L, BigDecimal.ZERO, 0, 0);
        }
        return new ProviderStats(
                provider,
                m.getSuccessRate(),
                m.getAverageLatency(),
                BigDecimal.ZERO,
                m.getActiveConnections(),
                m.getTotalTransactions()
        );
    }

    /**
     * Get metrics for a specific adapter (per-PSP).
     */
    public AdapterStats getStatsByAdapter(String adapterName) {
        ProviderMetrics m = adapterMetrics.get(adapterName);
        PaymentProviderType type = adapterToType.getOrDefault(adapterName, PaymentProviderType.MOCK);
        if (m == null) {
            return new AdapterStats(adapterName, type, 0.0, 0L, BigDecimal.ZERO, 0, 0);
        }
        return new AdapterStats(
                adapterName,
                type,
                m.getSuccessRate(),
                m.getAverageLatency(),
                BigDecimal.ZERO,
                m.getActiveConnections(),
                m.getTotalTransactions()
        );
    }

    /**
     * Thread-safe metrics storage for a single provider.
     */
    private static class ProviderMetrics {
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong totalLatencyMs = new AtomicLong(0);
        private final AtomicInteger activeConnections = new AtomicInteger(0);
        private final AtomicInteger totalTransactions = new AtomicInteger(0);

        void recordSuccess(long latencyMs) {
            successCount.incrementAndGet();
            totalLatencyMs.addAndGet(latencyMs);
            int total = totalTransactions.incrementAndGet();
            if (total > WINDOW_SIZE) {
                resetMetrics();
            }
        }

        void recordFailure(long latencyMs) {
            failureCount.incrementAndGet();
            totalLatencyMs.addAndGet(latencyMs);
            int total = totalTransactions.incrementAndGet();
            if (total > WINDOW_SIZE) {
                resetMetrics();
            }
        }

        void incrementActiveConnections() {
            activeConnections.incrementAndGet();
        }

        void decrementActiveConnections() {
            activeConnections.decrementAndGet();
        }

        double getSuccessRate() {
            int total = totalTransactions.get();
            if (total == 0) return 0.0;
            return (double) successCount.get() / total;
        }

        long getAverageLatency() {
            int total = totalTransactions.get();
            if (total == 0) return 0L;
            return totalLatencyMs.get() / total;
        }

        int getActiveConnections() {
            return activeConnections.get();
        }

        int getTotalTransactions() {
            return totalTransactions.get();
        }

        private void resetMetrics() {
            // Reset to sliding window (keep last 50% of data)
            int keepSuccess = successCount.get() / 2;
            int keepFailure = failureCount.get() / 2;
            successCount.set(keepSuccess);
            failureCount.set(keepFailure);
            totalTransactions.set(keepSuccess + keepFailure);
        }
    }

    /**
     * Immutable stats snapshot for a provider type.
     */
    @Value
    public static class ProviderStats {
        PaymentProviderType provider;
        double successRate; // 0.0 to 1.0
        long averageLatencyMs;
        BigDecimal averageCost;
        int activeConnections;
        int totalTransactions;
    }

    /**
     * Immutable stats snapshot for an adapter (per-PSP).
     */
    @Value
    public static class AdapterStats {
        String adapterName;
        PaymentProviderType providerType;
        double successRate;
        long averageLatencyMs;
        BigDecimal averageCost;
        int activeConnections;
        int totalTransactions;
    }
}
