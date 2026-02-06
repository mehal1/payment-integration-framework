package com.payment.framework.core;

import com.payment.framework.domain.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Ensures payment operations are idempotent by storing the result of the first
 * execution per idempotency key in Redis. Duplicate requests (e.g. retries,
 * duplicate submissions) return the cached result instead of calling the provider
 * again, reducing transaction failures and duplicate charges.
 * <p>
 * TTL is configurable; 24h is typical for payment idempotency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "payment:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, PaymentResult> redisTemplate;

    /**
     * Return cached result if this idempotency key was already processed.
     * <p>
     * If Redis read fails (network errors, deserialization errors), treats as cache miss
     * to allow requests to proceed (fail-open behavior). Deserialization errors are rare
     * (Jackson is forgiving) and typically indicate deployment issues that should be
     * handled by clearing Redis before schema changes.
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
            // Treat all errors as cache miss (fail-open)
            // This includes network errors and rare deserialization errors.
            // Deserialization errors are logged at ERROR level for alerting.
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

    /**
     * Store result for the given idempotency key so future requests return it.
     */
    public void storeResult(String idempotencyKey, PaymentResult result, Duration ttl) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.opsForValue().set(key, result, ttl != null ? ttl : DEFAULT_TTL);
        log.debug("Stored idempotency result for key={}", idempotencyKey);
    }

    public void storeResult(String idempotencyKey, PaymentResult result) {
        storeResult(idempotencyKey, result, DEFAULT_TTL);
    }
}
