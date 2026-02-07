package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * REST API for managing provider routing, viewing metrics, and A/B testing.
 */
@RestController
@RequestMapping("/api/v1/routing")
@RequiredArgsConstructor
public class ProviderRoutingController {

    private final ProviderPerformanceMetrics metrics;
    private final ABTestingFramework abTestingFramework;

    /**
     * Get performance metrics for all providers.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<PaymentProviderType, ProviderPerformanceMetrics.ProviderStats>> getMetrics() {
        Map<PaymentProviderType, ProviderPerformanceMetrics.ProviderStats> allMetrics = Map.of(
                PaymentProviderType.MOCK, metrics.getStats(PaymentProviderType.MOCK),
                PaymentProviderType.CARD, metrics.getStats(PaymentProviderType.CARD),
                PaymentProviderType.WALLET, metrics.getStats(PaymentProviderType.WALLET)
        );
        return ResponseEntity.ok(allMetrics);
    }

    /**
     * Get metrics for a specific provider.
     */
    @GetMapping("/metrics/{providerType}")
    public ResponseEntity<ProviderPerformanceMetrics.ProviderStats> getProviderMetrics(
            @PathVariable PaymentProviderType providerType) {
        return ResponseEntity.ok(metrics.getStats(providerType));
    }

    /**
     * Create an A/B test.
     */
    @PostMapping("/ab-test")
    public ResponseEntity<ABTestingFramework.ABTest> createABTest(
            @RequestBody ABTestRequest request) {
        ABTestingFramework.ABTest test = abTestingFramework.createTest(
                request.getTestId(),
                request.getProviders(),
                request.getTrafficSplit()
        );
        return ResponseEntity.ok(test);
    }

    /**
     * Get A/B test results.
     */
    @GetMapping("/ab-test/{testId}/results")
    public ResponseEntity<ABTestingFramework.ABTestResults> getABTestResults(
            @PathVariable String testId) {
        return ResponseEntity.ok(abTestingFramework.getTestResults(testId));
    }

    /**
     * Stop an A/B test.
     */
    @DeleteMapping("/ab-test/{testId}")
    public ResponseEntity<Void> stopABTest(@PathVariable String testId) {
        abTestingFramework.stopTest(testId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all active A/B tests.
     */
    @GetMapping("/ab-test")
    public ResponseEntity<Set<String>> getActiveTests() {
        return ResponseEntity.ok(abTestingFramework.getActiveTests());
    }

    @lombok.Value
    public static class ABTestRequest {
        String testId;
        java.util.List<PaymentProviderType> providers;
        Map<PaymentProviderType, Integer> trafficSplit;
    }
}
