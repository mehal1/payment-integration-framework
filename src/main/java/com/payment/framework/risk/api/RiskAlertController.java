package com.payment.framework.risk.api;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.TransactionStatus;
import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.domain.TransactionWindowFeatures;
import com.payment.framework.risk.engine.RiskEngine;
import com.payment.framework.risk.features.TransactionWindowAggregator;
import com.payment.framework.risk.messaging.WebhookService;
import com.payment.framework.risk.store.RecentAlertsStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * REST API for recent risk alerts (Project 2). Useful for dashboards and testing.
 * Alerts are also published to the risk-alerts Kafka topic.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/risk")
@RequiredArgsConstructor
@Tag(name = "Risk", description = "Risk and fraud alerts from ML/rule engine")
public class RiskAlertController {

    private static final double LABEL_FRAUD_FAILURE_RATE_THRESHOLD = 0.5;

    private final RecentAlertsStore recentAlertsStore;
    private final RiskEngine riskEngine;
    private final TransactionWindowAggregator aggregator;
    private final WebhookService webhookService;

    @GetMapping("/alerts")
    @Operation(summary = "List recent alerts", description = "Returns recently emitted risk alerts (in-memory; last 100)")
    public ResponseEntity<List<RiskAlert>> listAlerts(
            @RequestParam(defaultValue = "50") int limit) {
        List<RiskAlert> alerts = recentAlertsStore.getRecent(limit);
        return ResponseEntity.ok(alerts);
    }

    @PostMapping("/demo/trigger")
    @Operation(summary = "Generate demo alerts", description = "Injects synthetic failed payment events into the risk engine so you can see alerts without Kafka. Call this then GET /alerts. Optional: ?merchantRef=your-merchant-id to use specific merchant.")
    public ResponseEntity<Map<String, Object>> triggerDemoAlerts(
            @RequestParam(required = false) String merchantRef) {
        if (merchantRef == null || merchantRef.isEmpty()) {
            merchantRef = "demo-merchant-" + UUID.randomUUID().toString().substring(0, 8);
        }
        int alertsCreated = 0;
        log.info("Demo trigger: injecting 4 failed payment events for merchant {}", merchantRef);
        for (int i = 0; i < 4; i++) {
            PaymentEvent event = PaymentEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .idempotencyKey("demo-" + i)
                    .correlationId("demo-correlation")
                    .providerType(PaymentProviderType.MOCK)
                    .providerTransactionId(null)
                    .status(TransactionStatus.FAILED)
                    .amount(BigDecimal.valueOf(50 + i * 10))
                    .currencyCode("USD")
                    .failureCode("DEMO")
                    .message("Demo failure")
                    .merchantReference(merchantRef)
                    .timestamp(Instant.now())
                    .eventType("PAYMENT_FAILED")
                    .build();
            Optional<RiskAlert> alertOpt = riskEngine.evaluate(event);
            if (alertOpt.isPresent()) {
                RiskAlert alert = alertOpt.get();
                recentAlertsStore.add(alert);
                webhookService.sendAlert(alert);
                alertsCreated++;
            }
        }
        int total = recentAlertsStore.getRecent(100).size();
        log.info("Demo trigger complete: {} alerts created, {} total alerts in store", alertsCreated, total);
        return ResponseEntity.ok(Map.of(
                "message", "Demo events injected. Risk engine produced " + alertsCreated + " alert(s).",
                "alertsCreated", alertsCreated,
                "alertsNow", total,
                "merchantReference", merchantRef
        ));
    }

    @GetMapping(value = "/demo/training-data", produces = "text/csv")
    @Operation(summary = "Generate synthetic training data (CSV)",
            description = "Generates many synthetic payment events, computes window features per entity, and labels by rule (e.g. fraud=1 if failureRate>=0.5). Use for local/CI ML bootstrapping. No Kafka required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSV file with header: entityId,totalCount,failureCount,failureRate,...,label"),
            @ApiResponse(responseCode = "400", description = "Invalid rows. rows must be between 10 and 10000. Body: plain text error message.")
    })
    public ResponseEntity<String> getTrainingData(
            @RequestParam(defaultValue = "200") int rows) {
        if (rows < 10 || rows > 10_000) {
            return ResponseEntity.badRequest().body("rows must be between 10 and 10000");
        }
        Random rnd = new Random(42);
        List<String> entityIds = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            String entityId = "train-merchant-" + i;
            entityIds.add(entityId);
            int numEvents = 3 + rnd.nextInt(13);
            double failureRateTarget = rnd.nextDouble();
            for (int j = 0; j < numEvents; j++) {
                boolean failed = rnd.nextDouble() < failureRateTarget;
                PaymentEvent event = PaymentEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .idempotencyKey(entityId + "-" + j)
                        .correlationId("training")
                        .providerType(PaymentProviderType.MOCK)
                        .providerTransactionId(null)
                        .status(failed ? TransactionStatus.FAILED : TransactionStatus.SUCCESS)
                        .amount(BigDecimal.valueOf(10 + rnd.nextInt(500)))
                        .currencyCode("USD")
                        .failureCode(failed ? "DEMO" : null)
                        .message(null)
                        .merchantReference(entityId)
                        .timestamp(Instant.now())
                        .eventType(failed ? "PAYMENT_FAILED" : "PAYMENT_COMPLETED")
                        .build();
                aggregator.record(event);
            }
        }
        StringBuilder csv = new StringBuilder();
        csv.append("entityId,totalCount,failureCount,failureRate,countLast1Min,avgAmount,maxAmount,minAmount,hourOfDay,dayOfWeek,secondsSinceLastTransaction,amountVariance,amountTrend,increasingAmountCount,decreasingAmountCount,avgTimeGapSeconds,label\n");
        int lineCount = 0;
        for (String entityId : entityIds) {
            Optional<TransactionWindowFeatures> opt = aggregator.getFeatures(entityId);
            if (opt.isEmpty()) continue;
            TransactionWindowFeatures f = opt.get();
            int label = f.getFailureRate() >= LABEL_FRAUD_FAILURE_RATE_THRESHOLD ? 1 : 0;
            csv.append(String.format("%s,%d,%d,%.4f,%d,%.2f,%.2f,%.2f,%d,%d,%d,%.4f,%.4f,%d,%d,%.4f,%d%n",
                    escapeCsv(entityId), f.getTotalCount(), f.getFailureCount(), f.getFailureRate(),
                    f.getCountLast1Min(), f.getAvgAmount().doubleValue(), f.getMaxAmount().doubleValue(),
                    f.getMinAmount().doubleValue(), f.getHourOfDay(), f.getDayOfWeek(),
                    f.getSecondsSinceLastTransaction(), f.getAmountVariance().doubleValue(), f.getAmountTrend(),
                    f.getIncreasingAmountCount(), f.getDecreasingAmountCount(), f.getAvgTimeGapSeconds(), label));
            lineCount++;
        }
        log.info("Training data generated: {} rows (entities)", lineCount);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=risk_training_data.csv");
        return ResponseEntity.ok().headers(headers).body(csv.toString());
    }

    @PostMapping("/webhooks")
    @Operation(summary = "Register webhook URL", description = "Register a webhook URL to receive risk alerts for an entity (merchant/customer). Request body: { \"entityId\": \"...\", \"webhookUrl\": \"...\" }")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook registered"),
            @ApiResponse(responseCode = "400", description = "Missing required fields. Body: { \"error\": \"Missing required fields: entityId, webhookUrl\" }")
    })
    public ResponseEntity<Map<String, Object>> registerWebhook(
            @RequestBody Map<String, String> request) {
        String entityId = request.get("entityId");
        String webhookUrl = request.get("webhookUrl");
        
        if (entityId == null || webhookUrl == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: entityId, webhookUrl"
            ));
        }
        
        webhookService.registerWebhook(entityId, webhookUrl);
        return ResponseEntity.ok(Map.of(
                "message", "Webhook registered successfully",
                "entityId", entityId,
                "webhookUrl", webhookUrl
        ));
    }

    @DeleteMapping("/webhooks")
    @Operation(summary = "Unregister webhook URL", description = "Remove a webhook URL for an entity")
    public ResponseEntity<Map<String, Object>> unregisterWebhook(
            @RequestParam String entityId,
            @RequestParam String webhookUrl) {
        webhookService.unregisterWebhook(entityId, webhookUrl);
        return ResponseEntity.ok(Map.of(
                "message", "Webhook unregistered successfully",
                "entityId", entityId,
                "webhookUrl", webhookUrl
        ));
    }

    @GetMapping("/webhooks")
    @Operation(summary = "List webhook URLs", description = "Get all registered webhook URLs for an entity")
    public ResponseEntity<Map<String, Object>> getWebhooks(
            @RequestParam String entityId) {
        List<String> webhooks = webhookService.getWebhooks(entityId);
        return ResponseEntity.ok(Map.of(
                "entityId", entityId,
                "webhooks", webhooks
        ));
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
