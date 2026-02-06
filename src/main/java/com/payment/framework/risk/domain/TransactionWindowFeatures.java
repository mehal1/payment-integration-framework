package com.payment.framework.risk.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Aggregated features over a time window (e.g. last 5 min per merchant).
 * Input to the risk engine for scoring. Can be extended for more ML features.
 */
@Value
@Builder
public class TransactionWindowFeatures {

    /** Window key: e.g. merchantReference or correlationId. */
    String entityId;
    String entityType;
    long windowStartEpochMs;
    long windowEndEpochMs;
    /** Number of completed/failed events in window (excludes only REQUESTED). */
    int totalCount;
    int failureCount;
    /** failureCount / totalCount when totalCount > 0. */
    double failureRate;
    /** Sum of amounts in window. */
    BigDecimal totalAmount;
    /** Average amount. */
    BigDecimal avgAmount;
    /** Max single amount in window. */
    BigDecimal maxAmount;
    /** Events in last 1 min (velocity). */
    int countLast1Min;
    /** Events in last 5 min. */
    int countLast5Min;
}
