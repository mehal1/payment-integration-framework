package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A/B Testing framework for comparing payment provider performance.
 * Splits traffic between providers and tracks metrics for comparison.
 * Useful for evaluating new providers or comparing provider performance.
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ABTestingFramework {

    private final Map<String, ABTest> activeTests = new ConcurrentHashMap<>();
    private final ProviderPerformanceMetrics metrics;

    /**
     * Create an A/B test to compare providers.
     *
     * @param testId unique test identifier
     * @param providers list of providers to test
     * @param trafficSplit traffic split percentages (must sum to 100)
     * @return test configuration
     */
    public ABTest createTest(String testId, List<PaymentProviderType> providers, Map<PaymentProviderType, Integer> trafficSplit) {
        if (providers.size() != trafficSplit.size()) {
            throw new IllegalArgumentException("Providers and traffic split must have same size");
        }

        int totalSplit = trafficSplit.values().stream().mapToInt(Integer::intValue).sum();
        if (totalSplit != 100) {
            throw new IllegalArgumentException("Traffic split must sum to 100, got: " + totalSplit);
        }

        ABTest test = new ABTest(testId, providers, trafficSplit, new HashMap<>());
        activeTests.put(testId, test);
        log.info("Created A/B test: testId={}, providers={}, split={}", testId, providers, trafficSplit);
        return test;
    }

    /**
     * Select provider for A/B test based on traffic split.
     * Uses consistent hashing on idempotency key to ensure same request always goes to same provider.
     */
    public Optional<PaymentProviderType> selectProviderForTest(String testId, PaymentRequest request) {
        ABTest test = activeTests.get(testId);
        if (test == null) {
            return Optional.empty();
        }

        // Use idempotency key for consistent routing (same request always goes to same provider)
        int hash = Math.abs(request.getIdempotencyKey().hashCode()) % 100;
        int cumulative = 0;

        for (PaymentProviderType provider : test.getProviders()) {
            cumulative += test.getTrafficSplit().get(provider);
            if (hash < cumulative) {
                log.debug("A/B test {} selected provider={} for idempotencyKey={} (hash={})",
                        testId, provider, request.getIdempotencyKey(), hash);
                return Optional.of(provider);
            }
        }

        // Fallback to first provider
        return Optional.of(test.getProviders().get(0));
    }

    /**
     * Get test results comparing provider performance.
     */
    public ABTestResults getTestResults(String testId) {
        ABTest test = activeTests.get(testId);
        if (test == null) {
            throw new IllegalArgumentException("Test not found: " + testId);
        }

        Map<PaymentProviderType, ProviderPerformanceMetrics.ProviderStats> results = new HashMap<>();
        for (PaymentProviderType provider : test.getProviders()) {
            results.put(provider, metrics.getStats(provider));
        }

        return new ABTestResults(testId, results);
    }

    /**
     * Stop an A/B test.
     */
    public void stopTest(String testId) {
        ABTest removed = activeTests.remove(testId);
        if (removed != null) {
            log.info("Stopped A/B test: testId={}", testId);
        }
    }

    /**
     * Get all active tests.
     */
    public Set<String> getActiveTests() {
        return new HashSet<>(activeTests.keySet());
    }

    /**
     * A/B test configuration.
     */
    @Value
    public static class ABTest {
        String testId;
        List<PaymentProviderType> providers;
        Map<PaymentProviderType, Integer> trafficSplit; // Percentage (0-100)
        Map<String, Object> metadata;
    }

    /**
     * A/B test results comparing providers.
     */
    @Value
    public static class ABTestResults {
        String testId;
        Map<PaymentProviderType, ProviderPerformanceMetrics.ProviderStats> providerStats;
    }
}
