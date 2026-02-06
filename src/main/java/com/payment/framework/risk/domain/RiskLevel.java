package com.payment.framework.risk.domain;

/**
 * Severity of a risk alert. Drives prioritization and routing (e.g. high → immediate review).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
