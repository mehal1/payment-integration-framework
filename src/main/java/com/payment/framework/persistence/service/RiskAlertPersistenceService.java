package com.payment.framework.persistence.service;

import com.payment.framework.persistence.entity.RiskAlertEntity;
import com.payment.framework.persistence.repository.RiskAlertRepository;
import com.payment.framework.risk.domain.RiskAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service for persisting risk alerts to PostgreSQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAlertPersistenceService {

    private final RiskAlertRepository alertRepository;

    /**
     * Persists a risk alert.
     */
    @Transactional
    public void persistAlert(RiskAlert alert) {
        try {
            RiskAlertEntity entity = RiskAlertEntity.builder()
                    .alertId(alert.getAlertId() != null ? alert.getAlertId() : UUID.randomUUID().toString())
                    .entityId(alert.getEntityId())
                    .entityType(alert.getEntityType())
                    .riskLevel(alert.getLevel())
                    .riskScore(alert.getRiskScore())
                    .signalTypes(alert.getSignalTypes())
                    .amount(alert.getAmount())
                    .currencyCode(alert.getCurrencyCode())
                    .relatedEventIds(alert.getRelatedEventIds())
                    .summary(alert.getSummary())
                    .detailedExplanation(alert.getDetailedExplanation())
                    .status(RiskAlertEntity.AlertStatus.NEW)
                    .build();
            
            alertRepository.save(entity);
            log.debug("Persisted risk alert: alertId={}, entityId={}, riskLevel={}", 
                    entity.getAlertId(), entity.getEntityId(), entity.getRiskLevel());
        } catch (Exception e) {
            log.error("Failed to persist risk alert: alertId={}", alert.getAlertId(), e);
            // Don't throw - persistence failure shouldn't break alert flow
        }
    }
}
