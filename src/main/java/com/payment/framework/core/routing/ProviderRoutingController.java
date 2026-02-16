package com.payment.framework.core.routing;

import com.payment.framework.domain.PaymentProviderType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * REST API for provider routing, metrics, and A/B testing.
 */
@RestController
@RequestMapping("/api/v1/routing")
@RequiredArgsConstructor
@Tag(name = "Routing", description = "Provider metrics, A/B tests, and routing configuration")
public class ProviderRoutingController {

    private final ProviderPerformanceMetrics metrics;
    private final ABTestingFramework abTestingFramework;

    @GetMapping("/metrics")
    @Operation(summary = "All provider metrics", description = "Performance metrics for all payment providers (MOCK, CARD, WALLET)")
    public ResponseEntity<Map<PaymentProviderType, ProviderPerformanceMetrics.ProviderStats>> getMetrics() {
        Map<PaymentProviderType, ProviderPerformanceMetrics.ProviderStats> allMetrics = Map.of(
                PaymentProviderType.MOCK, metrics.getStats(PaymentProviderType.MOCK),
                PaymentProviderType.CARD, metrics.getStats(PaymentProviderType.CARD),
                PaymentProviderType.WALLET, metrics.getStats(PaymentProviderType.WALLET)
        );
        return ResponseEntity.ok(allMetrics);
    }

    @GetMapping("/metrics/{providerType}")
    @Operation(summary = "Metrics for one provider", description = "Performance stats for the given provider type (MOCK, CARD, WALLET)")
    public ResponseEntity<ProviderPerformanceMetrics.ProviderStats> getProviderMetrics(
            @PathVariable PaymentProviderType providerType) {
        return ResponseEntity.ok(metrics.getStats(providerType));
    }

    @PostMapping("/ab-test")
    @Operation(summary = "Create A/B test", description = "Create an A/B test with multiple providers and traffic split")
    public ResponseEntity<ABTestingFramework.ABTest> createABTest(
            @RequestBody ABTestRequest request) {
        ABTestingFramework.ABTest test = abTestingFramework.createTest(
                request.getTestId(),
                request.getProviders(),
                request.getTrafficSplit()
        );
        return ResponseEntity.ok(test);
    }

    @GetMapping("/ab-test/{testId}/results")
    @Operation(summary = "A/B test results", description = "Get results for an active or stopped A/B test")
    public ResponseEntity<ABTestingFramework.ABTestResults> getABTestResults(
            @PathVariable String testId) {
        return ResponseEntity.ok(abTestingFramework.getTestResults(testId));
    }

    @DeleteMapping("/ab-test/{testId}")
    @Operation(summary = "Stop A/B test", description = "Stop an A/B test by test ID")
    public ResponseEntity<Void> stopABTest(@PathVariable String testId) {
        abTestingFramework.stopTest(testId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ab-test")
    @Operation(summary = "List active A/B tests", description = "Get IDs of all currently active A/B tests")
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
