package com.payment.framework.persistence.repository;

import com.payment.framework.domain.TransactionStatus;
import com.payment.framework.persistence.entity.PaymentTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for payment transactions.
 */
@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, String> {

    Optional<PaymentTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransactionEntity> findByMerchantReference(String merchantReference);

    List<PaymentTransactionEntity> findByCustomerId(String customerId);

    Page<PaymentTransactionEntity> findByMerchantReference(String merchantReference, Pageable pageable);

    Page<PaymentTransactionEntity> findByStatus(TransactionStatus status, Pageable pageable);

    @Query("SELECT p FROM PaymentTransactionEntity p WHERE p.createdAt BETWEEN :startDate AND :endDate")
    List<PaymentTransactionEntity> findByDateRange(@Param("startDate") Instant startDate, @Param("endDate") Instant endDate);

    @Query("SELECT COUNT(p) FROM PaymentTransactionEntity p WHERE p.merchantReference = :merchantRef AND p.status = :status")
    long countByMerchantAndStatus(@Param("merchantRef") String merchantReference, @Param("status") TransactionStatus status);
}
