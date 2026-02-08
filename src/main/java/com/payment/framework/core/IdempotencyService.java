package com.payment.framework.core;

import com.payment.framework.domain.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Prevents duplicate charges by caching payment results.
 * If someone retries the same payment, we return the cached result instead of charging again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "payment:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, PaymentResult> redisTemplate;

    /**
     * Check if we've seen this payment before. If Redis is down, we just proceed anyway.
     */
    public Optional<PaymentResult> getCachedResult(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        try {
            PaymentResult cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Idempotency hit for key={}", idempotencyKey);
                return Optional.of(cached);
            }
        } catch (Exception e) {
            if (e.getClass().getSimpleName().contains("Serialization")) {
                log.error("Idempotency cache deserialization failed for key={}. " +
                        "Data exists but cannot be read - may indicate schema mismatch. " +
                        "Treating as cache miss. Consider clearing Redis before deployment.", idempotencyKey, e);
            } else {
                log.warn("Idempotency cache read failed for key={} (Redis unavailable), treating as miss: {}",
                        idempotencyKey, e.getMessage());
            }
        }
        return Optional.empty();
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
