package com.payment.framework.core;

import com.payment.framework.domain.PaymentResult;
import com.payment.framework.persistence.entity.PaymentTransactionEntity;
import com.payment.framework.persistence.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Prevents duplicate charges using Redis (fast) with PostgreSQL fallback (persistent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "payment:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, PaymentResult> redisTemplate;
    private final PaymentTransactionRepository transactionRepository;

    public Optional<PaymentResult> getCachedResult(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        try {
            PaymentResult cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Idempotency hit in Redis for key={}", idempotencyKey);
                return Optional.of(cached);
            }
        } catch (Exception e) {
            if (e.getClass().getSimpleName().contains("Serialization")) {
                log.error("Idempotency cache deserialization failed for key={}. " +
                        "Data exists but cannot be read - may indicate schema mismatch. " +
                        "Falling back to database check. Consider clearing Redis before deployment.", idempotencyKey, e);
            } else {
                log.warn("Idempotency cache read failed for key={} (Redis unavailable), falling back to database: {}",
                        idempotencyKey, e.getMessage());
            }
        }

        try {
            Optional<PaymentTransactionEntity> entityOpt = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (entityOpt.isPresent()) {
                PaymentTransactionEntity entity = entityOpt.get();
                PaymentResult result = convertEntityToResult(entity);
                log.debug("Idempotency hit in database for key={}, status={}", idempotencyKey, result.getStatus());
                
                try {
                    storeResult(idempotencyKey, result);
                } catch (Exception e) {
                    log.warn("Failed to cache database result in Redis for key={}: {}", idempotencyKey, e.getMessage());
                }
                
                return Optional.of(result);
            }
        } catch (Exception e) {
            log.error("Database idempotency check failed for key={}: {}", idempotencyKey, e.getMessage());
        }

        return Optional.empty();
    }

    private PaymentResult convertEntityToResult(PaymentTransactionEntity entity) {
        Map<String, Object> metadata = new HashMap<>();
        if (entity.getAdapterName() != null) {
            metadata.put("adapterName", entity.getAdapterName());
        }
        if (entity.getProviderType() != null) {
            metadata.put("providerType", entity.getProviderType().name());
        }

        return PaymentResult.builder()
                .idempotencyKey(entity.getIdempotencyKey())
                .providerTransactionId(entity.getProviderTransactionId())
                .status(entity.getStatus())
                .amount(entity.getAmount())
                .currencyCode(entity.getCurrencyCode())
                .failureCode(entity.getFailureCode())
                .message(entity.getFailureMessage())
                .timestamp(entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now())
                .metadata(metadata)
                .build();
    }

    public void storeResult(String idempotencyKey, PaymentResult result, Duration ttl) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.opsForValue().set(key, result, ttl != null ? ttl : DEFAULT_TTL);
        log.debug("Stored idempotency result for key={}", idempotencyKey);
    }

    public void storeResult(String idempotencyKey, PaymentResult result) {
        storeResult(idempotencyKey, result, DEFAULT_TTL);
    }
}
