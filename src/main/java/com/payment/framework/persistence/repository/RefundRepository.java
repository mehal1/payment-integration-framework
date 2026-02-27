package com.payment.framework.persistence.repository;

import com.payment.framework.domain.RefundStatus;
import com.payment.framework.persistence.entity.RefundEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data repository for refund transactions. */
@Repository
public interface RefundRepository extends JpaRepository<RefundEntity, String> {

    Optional<RefundEntity> findByRefundIdempotencyKey(String refundIdempotencyKey);

    List<RefundEntity> findByPaymentIdempotencyKey(String paymentIdempotencyKey);

    List<RefundEntity> findByMerchantReference(String merchantReference);

    Page<RefundEntity> findByMerchantReference(String merchantReference, Pageable pageable);

    Page<RefundEntity> findByStatus(RefundStatus status, Pageable pageable);

    @Query("SELECT r FROM RefundEntity r WHERE r.createdAt BETWEEN :startDate AND :endDate")
    List<RefundEntity> findByDateRange(@Param("startDate") Instant startDate, @Param("endDate") Instant endDate);

    @Query("SELECT SUM(r.amount) FROM RefundEntity r WHERE r.paymentIdempotencyKey = :paymentIdempotencyKey AND r.status = :status")
    BigDecimal sumRefundedAmountByPayment(@Param("paymentIdempotencyKey") String paymentIdempotencyKey,
                                         @Param("status") RefundStatus status);
}
