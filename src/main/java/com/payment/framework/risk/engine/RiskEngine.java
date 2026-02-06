package com.payment.framework.risk.engine;

import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.domain.RiskLevel;
import com.payment.framework.risk.domain.RiskSignalType;
import com.payment.framework.risk.domain.TransactionWindowFeatures;
import com.payment.framework.risk.features.TransactionWindowAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;


/**
 * Rule-based risk engine with optional ML extension point. Evaluates aggregated
 * features and current event to produce a risk score and signal types. No LLM
 * here; use {@link com.payment.framework.risk.llm.AlertSummaryService} for optional
 * human-readable summaries.
 */
@Slf4j
@Service
public class RiskEngine {

    private final TransactionWindowAggregator aggregator;
    private final MlRiskScorer mlScorer; // Optional: null if ML is disabled

    public RiskEngine(TransactionWindowAggregator aggregator,
                      @Autowired(required = false) MlRiskScorer mlScorer) {
        this.aggregator = aggregator;
        this.mlScorer = mlScorer;
    }

    @Value("${payment.risk.engine.threshold.high-failure-rate:0.5}")
    private double highFailureRateThreshold;
    @Value("${payment.risk.engine.threshold.velocity-1min:10}")
    private int velocity1MinThreshold;
    @Value("${payment.risk.engine.threshold.alert-score:0.5}")
    private double alertScoreThreshold;

    /**
     * Process an event: update aggregates, compute features, score, and return alert if above threshold.
     */
    public Optional<RiskAlert> evaluate(PaymentEvent event) {
        aggregator.record(event);
        Optional<TransactionWindowFeatures> featuresOpt = aggregator.getFeaturesFromEvent(event);
        if (featuresOpt.isEmpty()) return Optional.empty();

        TransactionWindowFeatures features = featuresOpt.get();
        Set<RiskSignalType> signals = new HashSet<>();
        double score = 0.0;

        // Try ML scoring first (if enabled and available)
        boolean mlUsed = false;
        if (mlScorer != null) {
            Optional<Double> mlScoreOpt = mlScorer.score(features);
            if (mlScoreOpt.isPresent()) {
                double mlScore = mlScoreOpt.get();
                log.debug("Using ML score: {} for entity {}", mlScore, features.getEntityId());
                score = mlScore;
                mlUsed = true;
                // Still compute signals from rules for context
                computeRuleSignals(features, event, signals);
            } else {
                // ML service unavailable, fallback to rules
                score = computeRuleScore(features, event, signals);
            }
        } else {
            // ML not enabled, use rules
            score = computeRuleScore(features, event, signals);
        }

        log.debug("Risk evaluation: eventId={}, entityId={}, score={}, signals={}, threshold={}",
                event.getEventId(), features.getEntityId(), score, signals, alertScoreThreshold);
        if (signals.isEmpty() || score < alertScoreThreshold) {
            log.debug("No alert generated: signals empty={}, score below threshold={}", signals.isEmpty(), score < alertScoreThreshold);
            return Optional.empty();
        }

        RiskLevel level = score >= 0.8 ? RiskLevel.CRITICAL : score >= 0.6 ? RiskLevel.HIGH : score >= 0.4 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        List<String> relatedIds = List.of(event.getEventId());

        RiskAlert alert = RiskAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .level(level)
                .signalTypes(signals)
                .riskScore(score)
                .entityId(features.getEntityId())
                .entityType(features.getEntityType())
                .relatedEventIds(relatedIds)
                .amount(event.getAmount())
                .currencyCode(event.getCurrencyCode())
                .summary(buildSummary(signals, score, features, mlUsed))
                .detailedExplanation(null)
                .build();
        return Optional.of(alert);
    }

    /**
     * Compute risk score using rules (fallback when ML is unavailable).
     */
    private double computeRuleScore(TransactionWindowFeatures features, PaymentEvent event, Set<RiskSignalType> signals) {
        double score = 0.0;

        // Rule: high failure rate
        if (features.getFailureRate() >= highFailureRateThreshold) {
            signals.add(RiskSignalType.HIGH_FAILURE_RATE);
            score = Math.max(score, 0.4 + features.getFailureRate() * 0.4);
        }
        if (features.getFailureCount() >= 3 && features.getTotalCount() <= 10) {
            signals.add(RiskSignalType.REPEATED_FAILURES);
            score = Math.max(score, 0.5);
        }

        // Rule: velocity
        if (features.getCountLast1Min() >= velocity1MinThreshold) {
            signals.add(RiskSignalType.HIGH_VELOCITY);
            score = Math.max(score, 0.3 + Math.min(0.4, features.getCountLast1Min() / 50.0));
        }

        // Rule: unusual amount (simple: above 2x avg in window)
        if (features.getTotalCount() >= 3 && event.getAmount() != null && features.getAvgAmount().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = event.getAmount().doubleValue() / features.getAvgAmount().doubleValue();
            if (ratio >= 2.0) {
                signals.add(RiskSignalType.UNUSUAL_AMOUNT);
                score = Math.max(score, 0.35);
            }
        }

        return score;
    }

    /**
     * Compute rule-based signals (used for context even when ML is active).
     */
    private void computeRuleSignals(TransactionWindowFeatures features, PaymentEvent event, Set<RiskSignalType> signals) {
        if (features.getFailureRate() >= highFailureRateThreshold) {
            signals.add(RiskSignalType.HIGH_FAILURE_RATE);
        }
        if (features.getFailureCount() >= 3 && features.getTotalCount() <= 10) {
            signals.add(RiskSignalType.REPEATED_FAILURES);
        }
        if (features.getCountLast1Min() >= velocity1MinThreshold) {
            signals.add(RiskSignalType.HIGH_VELOCITY);
        }
        if (features.getTotalCount() >= 3 && event.getAmount() != null && features.getAvgAmount().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = event.getAmount().doubleValue() / features.getAvgAmount().doubleValue();
            if (ratio >= 2.0) {
                signals.add(RiskSignalType.UNUSUAL_AMOUNT);
            }
        }
    }

    private String buildSummary(Set<RiskSignalType> signals, double score, TransactionWindowFeatures features, boolean mlUsed) {
        String method = mlUsed ? "ML" : "rules";
        return String.format("Risk score %.2f (%s): %s (failure rate %.0f%%, velocity 1m=%d)",
                score,
                method,
                signals.stream().map(Enum::name).reduce((a, b) -> a + ", " + b).orElse(""),
                features.getFailureRate() * 100,
                features.getCountLast1Min());
    }
}
