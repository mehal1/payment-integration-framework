package com.payment.framework.core.routing;

import com.payment.framework.core.PSPAdapter;
import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * REST API for managing provider routing, viewing metrics, A/B testing, and PSP recommendation (Method 1).
 */
@RestController
@RequestMapping("/api/v1/routing")
@RequiredArgsConstructor
@Tag(name = "Routing", description = "Provider metrics, A/B tests, PSP recommendation, and routing configuration")
public class ProviderRoutingController {

    private final ProviderPerformanceMetrics metrics;
    private final ABTestingFramework abTestingFramework;
    private final ProviderRouter providerRouter;

    @GetMapping("/recommend")
    @Operation(
            summary = "Recommend PSP for tokenization (Method 1)",
            description = "Returns the PSP the framework would use for this payment. Client should tokenize with this PSP only, then send that token in paymentMethodId when calling POST /api/v1/payments/execute. If execute returns 503 RECOMMENDED_PSP_UNAVAILABLE, call this again and re-tokenize with the new recommendation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recommended PSP (available=true). Use recommendedPspId to choose which SDK to load for tokenization."),
            @ApiResponse(responseCode = "503", description = "No healthy PSP available (available=false). Retry later or try a different providerType.")
    })
    public ResponseEntity<RecommendationResponse> recommend(
            @RequestParam BigDecimal amount,
            @RequestParam String currencyCode,
            @RequestParam(required = false, defaultValue = "CARD") PaymentProviderType providerType) {
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey("recommend-" + UUID.randomUUID())
                .providerType(providerType)
                .amount(amount)
                .currencyCode(currencyCode)
                .build();
        Optional<PSPAdapter> adapterOpt = providerRouter.selectProvider(request);
        if (adapterOpt.isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(RecommendationResponse.unavailable("No healthy PSP available for providerType=" + providerType));
        }
        PSPAdapter adapter = adapterOpt.get();
        return ResponseEntity.ok(RecommendationResponse.ok(
                adapter.getProviderType(),
                adapter.getPSPAdapterName()
        ));
    }

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

    @lombok.Value
    public static class RecommendationResponse {
        boolean available;
        String recommendedProviderType;
        String recommendedPspId;
        String message;

        public static RecommendationResponse ok(PaymentProviderType providerType, String pspId) {
            return new RecommendationResponse(
                    true,
                    providerType.name(),
                    pspId,
                    "Tokenize with this PSP only; send the resulting token in paymentMethodId when calling POST /api/v1/payments/execute."
            );
        }

        public static RecommendationResponse unavailable(String message) {
            return new RecommendationResponse(false, null, null, message);
        }
    }
}
