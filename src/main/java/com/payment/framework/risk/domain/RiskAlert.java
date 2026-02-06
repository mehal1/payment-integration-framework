package com.payment.framework.risk.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Alert emitted when the risk engine detects fraud, compliance, or systemic risk.
 * Consumed by ops dashboards, compliance tools, or optional LLM for human-readable summaries.
 */
@Value
@Builder
public class RiskAlert {

    String alertId;
    Instant timestamp;
    RiskLevel level;
    Set<RiskSignalType> signalTypes;
    /** 0.0–1.0; higher = higher risk. */
    double riskScore;
    /** Context: e.g. merchantReference or correlationId. */
    String entityId;
    String entityType;
    /** Related payment event ids for traceability. */
    List<String> relatedEventIds;
    BigDecimal amount;
    String currencyCode;
    String summary;
    /** Optional: LLM-generated human-readable explanation when enabled. */
    String detailedExplanation;
}
