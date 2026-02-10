package com.payment.framework.persistence.repository;

import com.payment.framework.persistence.entity.PaymentEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for payment events (audit trail).
 */
@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEventEntity, String> {

    List<PaymentEventEntity> findByIdempotencyKey(String idempotencyKey);

    List<PaymentEventEntity> findByMerchantReference(String merchantReference);

    Page<PaymentEventEntity> findByMerchantReference(String merchantReference, Pageable pageable);

    @Query("SELECT e FROM PaymentEventEntity e WHERE e.timestamp BETWEEN :startDate AND :endDate ORDER BY e.timestamp DESC")
    List<PaymentEventEntity> findByDateRange(@Param("startDate") Instant startDate, @Param("endDate") Instant endDate);

    @Query("SELECT e FROM PaymentEventEntity e WHERE e.eventType = :eventType AND e.timestamp >= :since")
    List<PaymentEventEntity> findByEventTypeSince(@Param("eventType") String eventType, @Param("since") Instant since);
}
