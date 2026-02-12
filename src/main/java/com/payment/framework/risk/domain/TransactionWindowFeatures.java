package com.payment.framework.risk.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Aggregated features over a time window (e.g. last 5 min per merchant).
 * Input to the risk engine for scoring. Enhanced with time-based, amount pattern, and behavioral features.
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
    /** Min single amount in window. */
    BigDecimal minAmount;
    /** Events in last 1 min (velocity). */
    int countLast1Min;
    /** Events in last 5 min. */
    int countLast5Min;
    
    // Time-based features
    /** Hour of day (0-23) for current transaction. */
    int hourOfDay;
    /** Day of week (0=Monday, 6=Sunday) for current transaction. */
    int dayOfWeek;
    /** Seconds since last transaction (0 if first transaction). */
    long secondsSinceLastTransaction;
    
    // Amount pattern features
    /** Variance of amounts in window (measures amount consistency). */
    BigDecimal amountVariance;
    /** Trend: positive = increasing amounts, negative = decreasing amounts, 0 = no trend. */
    double amountTrend;
    
    // Behavioral features
    /** Number of transactions with increasing amounts (card testing pattern). */
    int increasingAmountCount;
    /** Number of transactions with decreasing amounts. */
    int decreasingAmountCount;
    /** Average time gap between transactions in seconds. */
    double avgTimeGapSeconds;
}
