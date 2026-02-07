package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks performance metrics for each payment provider to enable intelligent routing.
 * Metrics include success rate, average latency, cost per transaction, and active connections.
 * Thread-safe implementation using atomic operations.
 */
@Slf4j
@Component
public class ProviderPerformanceMetrics {

    private static final int WINDOW_SIZE = 1000; // Track last 1000 transactions per provider
    private static final long METRICS_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private final Map<PaymentProviderType, ProviderMetrics> metrics = new ConcurrentHashMap<>();

    /**
     * Record a successful transaction.
     */
    public void recordSuccess(PaymentProviderType provider, long latencyMs, BigDecimal cost) {
        ProviderMetrics m = metrics.computeIfAbsent(provider, k -> new ProviderMetrics());
        m.recordSuccess(latencyMs, cost);
        log.debug("Recorded success for provider={}, latency={}ms, cost={}", provider, latencyMs, cost);
    }

    /**
     * Record a failed transaction.
     */
    public void recordFailure(PaymentProviderType provider, long latencyMs) {
        ProviderMetrics m = metrics.computeIfAbsent(provider, k -> new ProviderMetrics());
        m.recordFailure(latencyMs);
        log.debug("Recorded failure for provider={}, latency={}ms", provider, latencyMs);
    }

    /**
     * Increment active connection count (when starting a request).
     */
    public void incrementActiveConnections(PaymentProviderType provider) {
        ProviderMetrics m = metrics.computeIfAbsent(provider, k -> new ProviderMetrics());
        m.incrementActiveConnections();
    }

    /**
     * Decrement active connection count (when request completes).
     */
    public void decrementActiveConnections(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m != null) {
            m.decrementActiveConnections();
        }
    }

    /**
     * Get success rate (0.0 to 1.0) for the provider.
     */
    public double getSuccessRate(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return 0.0;
        return m.getSuccessRate();
    }

    /**
     * Get average latency in milliseconds.
     */
    public long getAverageLatency(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return Long.MAX_VALUE;
        return m.getAverageLatency();
    }

    /**
     * Get average cost per transaction.
     */
    public BigDecimal getAverageCost(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return BigDecimal.ZERO;
        return m.getAverageCost();
    }

    /**
     * Get current active connection count.
     */
    public int getActiveConnections(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return 0;
        return m.getActiveConnections();
    }

    /**
     * Get total transaction count.
     */
    public int getTotalTransactions(PaymentProviderType provider) {
        ProviderMetrics m = metrics.get(provider);
        if (m == null) return 0;
        return m.getTotalTransactions();
    }

    /**
     * Get all metrics for a provider.
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
                m.getAverageCost(),
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
        private final LongAdder totalCost = new LongAdder(); // Store as cents (long)
        private final AtomicInteger activeConnections = new AtomicInteger(0);
        private final AtomicInteger totalTransactions = new AtomicInteger(0);

        void recordSuccess(long latencyMs, BigDecimal cost) {
            successCount.incrementAndGet();
            totalLatencyMs.addAndGet(latencyMs);
            totalCost.add(cost.multiply(BigDecimal.valueOf(100)).longValue()); // Convert to cents
            int total = totalTransactions.incrementAndGet();
            if (total > WINDOW_SIZE) {
                // Reset metrics periodically to use sliding window
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

        BigDecimal getAverageCost() {
            int total = totalTransactions.get();
            if (total == 0) return BigDecimal.ZERO;
            long totalCostCents = totalCost.sum();
            return BigDecimal.valueOf(totalCostCents)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
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
     * Immutable stats snapshot for a provider.
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
}
