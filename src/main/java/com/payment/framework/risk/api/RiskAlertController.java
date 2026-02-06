package com.payment.framework.risk.api;

import com.payment.framework.domain.TransactionStatus;
import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.domain.TransactionWindowFeatures;
import com.payment.framework.risk.engine.RiskEngine;
import com.payment.framework.risk.features.TransactionWindowAggregator;
import com.payment.framework.risk.store.RecentAlertsStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @GetMapping("/alerts")
    @Operation(summary = "List recent alerts", description = "Returns recently emitted risk alerts (in-memory; last 100)")
    public ResponseEntity<List<RiskAlert>> listAlerts(
            @RequestParam(defaultValue = "50") int limit) {
        List<RiskAlert> alerts = recentAlertsStore.getRecent(limit);
        return ResponseEntity.ok(alerts);
    }

    @PostMapping("/demo/trigger")
    @Operation(summary = "Generate demo alerts", description = "Injects synthetic failed payment events into the risk engine so you can see alerts without Kafka. Call this then GET /alerts.")
    public ResponseEntity<Map<String, Object>> triggerDemoAlerts() {
        String merchantRef = "demo-merchant-" + UUID.randomUUID().toString().substring(0, 8);
        int alertsCreated = 0;
        log.info("Demo trigger: injecting 4 failed payment events for merchant {}", merchantRef);
        for (int i = 0; i < 4; i++) {
            PaymentEvent event = PaymentEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .idempotencyKey("demo-" + i)
                    .correlationId("demo-correlation")
                    .providerType("MOCK")
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
                recentAlertsStore.add(alertOpt.get());
                alertsCreated++;
                log.info("Demo alert created: {} level={} score={}", 
                        alertOpt.get().getAlertId(), alertOpt.get().getLevel(), alertOpt.get().getRiskScore());
            } else {
                log.debug("Demo event {} did not trigger an alert", i);
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
                        .providerType("MOCK")
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
        csv.append("entityId,totalCount,failureCount,failureRate,countLast1Min,avgAmount,maxAmount,label\n");
        int lineCount = 0;
        for (String entityId : entityIds) {
            Optional<TransactionWindowFeatures> opt = aggregator.getFeatures(entityId);
            if (opt.isEmpty()) continue;
            TransactionWindowFeatures f = opt.get();
            int label = f.getFailureRate() >= LABEL_FRAUD_FAILURE_RATE_THRESHOLD ? 1 : 0;
            csv.append(String.format("%s,%d,%d,%.4f,%d,%.2f,%.2f,%d%n",
                    escapeCsv(entityId), f.getTotalCount(), f.getFailureCount(), f.getFailureRate(),
                    f.getCountLast1Min(), f.getAvgAmount().doubleValue(), f.getMaxAmount().doubleValue(), label));
            lineCount++;
        }
        log.info("Training data generated: {} rows (entities)", lineCount);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=risk_training_data.csv");
        return ResponseEntity.ok().headers(headers).body(csv.toString());
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
