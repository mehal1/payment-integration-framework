package com.payment.framework.risk.domain;

/**
 * Type of risk signal detected by the ML/rule engine. Used for filtering and compliance reporting.
 */
public enum RiskSignalType {
    /** Unusually high rate of failed transactions (e.g. possible fraud or systemic issue). */
    HIGH_FAILURE_RATE,
    /** Too many transactions in a short window (velocity). */
    HIGH_VELOCITY,
    /** Amount unusually high vs recent history. */
    UNUSUAL_AMOUNT,
    /** Multiple failures for same merchant/correlation in short time. */
    REPEATED_FAILURES,
    /** Compliance: pattern that may require reporting (e.g. structuring). */
    COMPLIANCE_ANOMALY,
    /** Systemic: provider or PSP degradation. */
    SYSTEMIC_RISK
}
