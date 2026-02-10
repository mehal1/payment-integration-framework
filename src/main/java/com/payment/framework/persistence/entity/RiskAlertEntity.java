package com.payment.framework.persistence.entity;

import com.payment.framework.risk.domain.RiskLevel;
import com.payment.framework.risk.domain.RiskSignalType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent entity for risk alerts.
 * Stores fraud alerts with lifecycle management (status, assignment, resolution).
 */
@Entity
@Table(name = "risk_alerts", indexes = {
    @Index(name = "idx_alert_entity_id", columnList = "entity_id"),
    @Index(name = "idx_alert_status", columnList = "status"),
    @Index(name = "idx_alert_created_at", columnList = "created_at"),
    @Index(name = "idx_alert_risk_level", columnList = "risk_level")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAlertEntity {

    @Id
    @Column(name = "alert_id", nullable = false)
    private String alertId;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "risk_score", nullable = false)
    private double riskScore;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "risk_alert_signals", joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "signal_type", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<RiskSignalType> signalTypes = new HashSet<>();

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "risk_alert_related_events", joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "event_id", nullable = false)
    @Builder.Default
    private List<String> relatedEventIds = new ArrayList<>();

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "detailed_explanation", columnDefinition = "TEXT")
    private String detailedExplanation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private AlertStatus status = AlertStatus.NEW;

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
        if (status == AlertStatus.RESOLVED && resolvedAt == null) {
            resolvedAt = Instant.now();
        }
    }

    public enum AlertStatus {
        NEW, ACKNOWLEDGED, INVESTIGATING, RESOLVED, FALSE_POSITIVE, ESCALATED
    }
}
