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
 * Checks if a payment looks suspicious. Uses simple rules by default, but can use ML if available.
 */
@Slf4j
@Service
public class RiskEngine {

    private final TransactionWindowAggregator aggregator;
    private final MlRiskScorer mlScorer;

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
     * Look at a payment and decide if it's suspicious. Evaluates across merchant, card, email, and IP
     * so fraud is detected for card, wallet, and BNPL (and any type that shares email/IP).
     */
    public Optional<RiskAlert> evaluate(PaymentEvent event) {
        aggregator.record(event);

        Set<RiskSignalType> allSignals = new HashSet<>();
        double maxScore = 0.0;
        String primaryEntityId = null;
        String primaryEntityType = null;
        TransactionWindowFeatures primaryFeatures = null;
        boolean mlUsed = false;

        // 1) Merchant dimension (always)
        Optional<TransactionWindowFeatures> merchantOpt = aggregator.getFeaturesFromEvent(event);
        if (merchantOpt.isPresent()) {
            TransactionWindowFeatures f = merchantOpt.get();
            Set<RiskSignalType> sigs = new HashSet<>();
            double s = computeRuleScoreForDimension(f, event, sigs, null);
            if (mlScorer != null) {
                Optional<Double> mlOpt = mlScorer.score(f);
                if (mlOpt.isPresent()) {
                    s = mlOpt.get();
                    mlUsed = true;
                    computeRuleSignals(f, event, sigs);
                }
            }
            if (s > maxScore) {
                maxScore = s;
                primaryEntityId = f.getEntityId();
                primaryEntityType = f.getEntityType();
                primaryFeatures = f;
            }
            allSignals.addAll(sigs);
        }

        // 2) Card dimension (when we have card identity: card, wallet)
        Optional<TransactionWindowFeatures> cardOpt = aggregator.getCardFeaturesFromEvent(event);
        if (cardOpt.isPresent()) {
            TransactionWindowFeatures f = cardOpt.get();
            Set<RiskSignalType> sigs = new HashSet<>();
            double s = computeRuleScoreForDimension(f, event, sigs, null);
            if (s > maxScore) {
                maxScore = s;
                primaryEntityId = f.getEntityId();
                primaryEntityType = f.getEntityType();
                primaryFeatures = f;
            }
            allSignals.addAll(sigs);
        }

        // 3) Email dimension (cross-type: same email for card, wallet, BNPL)
        Optional<TransactionWindowFeatures> emailOpt = aggregator.getEmailFeaturesFromEvent(event);
        if (emailOpt.isPresent()) {
            TransactionWindowFeatures f = emailOpt.get();
            Set<RiskSignalType> sigs = new HashSet<>();
            double s = computeRuleScoreForDimension(f, event, sigs, "EMAIL");
            if (s > maxScore) {
                maxScore = s;
                primaryEntityId = f.getEntityId();
                primaryEntityType = f.getEntityType();
                primaryFeatures = f;
            }
            allSignals.addAll(sigs);
        }

        // 4) IP dimension (cross-type: same IP for all payment types)
        Optional<TransactionWindowFeatures> ipOpt = aggregator.getIpFeaturesFromEvent(event);
        if (ipOpt.isPresent()) {
            TransactionWindowFeatures f = ipOpt.get();
            Set<RiskSignalType> sigs = new HashSet<>();
            double s = computeRuleScoreForDimension(f, event, sigs, "IP");
            if (s > maxScore) {
                maxScore = s;
                primaryEntityId = f.getEntityId();
                primaryEntityType = f.getEntityType();
                primaryFeatures = f;
            }
            allSignals.addAll(sigs);
        }

        if (primaryEntityId == null) primaryEntityId = "unknown";
        if (primaryEntityType == null) primaryEntityType = "MERCHANT";
        if (primaryFeatures == null) primaryFeatures = merchantOpt.orElse(null);

        log.debug("Risk evaluation: eventId={}, primaryEntity={}/{}, score={}, signals={}, threshold={}",
                event.getEventId(), primaryEntityId, primaryEntityType, maxScore, allSignals, alertScoreThreshold);
        if (allSignals.isEmpty() || maxScore < alertScoreThreshold) {
            return Optional.empty();
        }

        RiskLevel level = maxScore >= 0.8 ? RiskLevel.CRITICAL : maxScore >= 0.6 ? RiskLevel.HIGH : maxScore >= 0.4 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        List<String> relatedIds = List.of(event.getEventId());
        String summary = buildCrossTypeSummary(allSignals, maxScore, primaryFeatures, primaryEntityType, mlUsed);

        RiskAlert alert = RiskAlert.builder()
                .alertId(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .level(level)
                .signalTypes(allSignals)
                .riskScore(maxScore)
                .entityId(primaryEntityId)
                .entityType(primaryEntityType)
                .relatedEventIds(relatedIds)
                .amount(event.getAmount())
                .currencyCode(event.getCurrencyCode())
                .summary(summary)
                .detailedExplanation(null)
                .build();
        return Optional.of(alert);
    }

    /**
     * Rule score for one dimension (merchant, card, email, or IP). When dimensionKind is EMAIL or IP,
     * adds HIGH_EMAIL_* / HIGH_IP_* signals instead of generic HIGH_VELOCITY / HIGH_FAILURE_RATE.
     */
    private double computeRuleScoreForDimension(TransactionWindowFeatures features, PaymentEvent event,
                                                 Set<RiskSignalType> signals, String dimensionKind) {
        double score = 0.0;
        boolean isEmail = "EMAIL".equals(dimensionKind);
        boolean isIp = "IP".equals(dimensionKind);

        if (features.getFailureRate() >= highFailureRateThreshold) {
            if (isEmail) signals.add(RiskSignalType.HIGH_EMAIL_FAILURE_RATE);
            else if (isIp) signals.add(RiskSignalType.HIGH_IP_FAILURE_RATE);
            else signals.add(RiskSignalType.HIGH_FAILURE_RATE);
            score = Math.max(score, 0.4 + features.getFailureRate() * 0.4);
        }
        if (features.getFailureCount() >= 3 && features.getTotalCount() <= 10) {
            signals.add(RiskSignalType.REPEATED_FAILURES);
            score = Math.max(score, 0.5);
        }
        if (features.getCountLast1Min() >= velocity1MinThreshold) {
            if (isEmail) signals.add(RiskSignalType.HIGH_EMAIL_VELOCITY);
            else if (isIp) signals.add(RiskSignalType.HIGH_IP_VELOCITY);
            else signals.add(RiskSignalType.HIGH_VELOCITY);
            score = Math.max(score, 0.3 + Math.min(0.4, features.getCountLast1Min() / 50.0));
        }
        if (features.getTotalCount() >= 3 && event.getAmount() != null && features.getAvgAmount().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = event.getAmount().doubleValue() / features.getAvgAmount().doubleValue();
            if (ratio >= 2.0) {
                signals.add(RiskSignalType.UNUSUAL_AMOUNT);
                score = Math.max(score, 0.35);
            }
        }
        if (features.getTotalCount() >= 3 && features.getIncreasingAmountCount() >= 2 && features.getAmountTrend() > 0) {
            signals.add(RiskSignalType.COMPLIANCE_ANOMALY);
            score = Math.max(score, 0.5 + Math.min(0.2, features.getIncreasingAmountCount() * 0.05));
        }
        if (features.getTotalCount() >= 3 && features.getSecondsSinceLastTransaction() > 0
                && features.getSecondsSinceLastTransaction() < 5 && features.getAvgTimeGapSeconds() < 3) {
            if (isEmail) signals.add(RiskSignalType.HIGH_EMAIL_VELOCITY);
            else if (isIp) signals.add(RiskSignalType.HIGH_IP_VELOCITY);
            else signals.add(RiskSignalType.HIGH_VELOCITY);
            score = Math.max(score, 0.35 + Math.min(0.15, (5 - features.getAvgTimeGapSeconds()) / 10.0));
        }
        return score;
    }

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

        if (features.getTotalCount() >= 3 && features.getIncreasingAmountCount() >= 2 && features.getAmountTrend() > 0) {
            signals.add(RiskSignalType.COMPLIANCE_ANOMALY);
        }

        if (features.getTotalCount() >= 3 && features.getSecondsSinceLastTransaction() > 0 
                && features.getSecondsSinceLastTransaction() < 5 && features.getAvgTimeGapSeconds() < 3) {
            signals.add(RiskSignalType.HIGH_VELOCITY);
        }
    }

    private String buildCrossTypeSummary(Set<RiskSignalType> signals, double score,
                                          TransactionWindowFeatures primaryFeatures, String primaryType, boolean mlUsed) {
        String method = mlUsed ? "ML" : "rules";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Risk score %.2f (%s). Primary: %s. Signals: %s",
                score, method, primaryType,
                signals.stream().map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("")));
        if (primaryFeatures != null) {
            sb.append(String.format(" (failure rate %.0f%%, velocity 1m=%d)", primaryFeatures.getFailureRate() * 100, primaryFeatures.getCountLast1Min()));
        }
        if (signals.contains(RiskSignalType.HIGH_EMAIL_VELOCITY) || signals.contains(RiskSignalType.HIGH_EMAIL_FAILURE_RATE)) {
            sb.append(" [email cross-type]");
        }
        if (signals.contains(RiskSignalType.HIGH_IP_VELOCITY) || signals.contains(RiskSignalType.HIGH_IP_FAILURE_RATE)) {
            sb.append(" [IP cross-type]");
        }
        return sb.toString();
    }
}
