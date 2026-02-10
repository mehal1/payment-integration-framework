package com.payment.framework.persistence.repository;

import com.payment.framework.persistence.entity.RiskAlertEntity;
import com.payment.framework.risk.domain.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for risk alerts.
 */
@Repository
public interface RiskAlertRepository extends JpaRepository<RiskAlertEntity, String> {

    List<RiskAlertEntity> findByEntityId(String entityId);

    Page<RiskAlertEntity> findByStatus(RiskAlertEntity.AlertStatus status, Pageable pageable);

    Page<RiskAlertEntity> findByRiskLevel(RiskLevel riskLevel, Pageable pageable);

    Page<RiskAlertEntity> findByEntityId(String entityId, Pageable pageable);

    @Query("SELECT a FROM RiskAlertEntity a WHERE a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<RiskAlertEntity> findRecentAlerts(@Param("since") Instant since);

    @Query("SELECT COUNT(a) FROM RiskAlertEntity a WHERE a.status = :status")
    long countByStatus(@Param("status") RiskAlertEntity.AlertStatus status);

    @Query("SELECT a FROM RiskAlertEntity a WHERE a.entityId = :entityId AND a.status != 'RESOLVED' ORDER BY a.createdAt DESC")
    List<RiskAlertEntity> findActiveAlertsByEntity(@Param("entityId") String entityId);
}
