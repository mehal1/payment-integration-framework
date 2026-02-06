package com.payment.framework.core;

import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.redis.serializer.SerializationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for IdempotencyService with mocked Redis.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private RedisTemplate<String, PaymentResult> redisTemplate;

    @Mock
    private ValueOperations<String, PaymentResult> valueOps;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        idempotencyService = new IdempotencyService(redisTemplate);
    }

    @Test
    void getCachedResultReturnsEmptyWhenRedisReturnsNull() {
        when(valueOps.get("payment:idempotency:key-1")).thenReturn(null);

        Optional<PaymentResult> result = idempotencyService.getCachedResult("key-1");

        assertThat(result).isEmpty();
    }

    @Test
    void getCachedResultReturnsStoredResultWhenPresent() {
        PaymentResult cached = PaymentResult.builder()
                .idempotencyKey("key-1")
                .providerTransactionId("tx-1")
                .status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("100"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .build();
        when(valueOps.get("payment:idempotency:key-1")).thenReturn(cached);

        Optional<PaymentResult> result = idempotencyService.getCachedResult("key-1");

        assertThat(result).isPresent();
        assertThat(result.get().getIdempotencyKey()).isEqualTo("key-1");
        assertThat(result.get().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void getCachedResultTreatsDeserializationErrorAsCacheMiss() {
        // Deserialization errors are treated as cache miss (fail-open) with ERROR logging
        SerializationException deserializationError = new SerializationException("Cannot deserialize PaymentResult");
        when(valueOps.get("payment:idempotency:key-3")).thenThrow(deserializationError);

        Optional<PaymentResult> result = idempotencyService.getCachedResult("key-3");

        assertThat(result).isEmpty();
    }

    @Test
    void getCachedResultTreatsNetworkErrorAsCacheMiss() {
        // Network/connection errors are treated as cache miss (fail-open)
        RuntimeException networkError = new RuntimeException("Connection refused");
        when(valueOps.get("payment:idempotency:key-4")).thenThrow(networkError);

        Optional<PaymentResult> result = idempotencyService.getCachedResult("key-4");

        assertThat(result).isEmpty();
    }

    @Test
    void storeResultCallsRedisSetWithKeyPrefix() {
        PaymentResult toStore = PaymentResult.builder()
                .idempotencyKey("key-2")
                .status(TransactionStatus.FAILED)
                .amount(BigDecimal.ZERO)
                .currencyCode("USD")
                .timestamp(Instant.now())
                .build();

        idempotencyService.storeResult("key-2", toStore);

        verify(redisTemplate).opsForValue();
        verify(valueOps).set(eq("payment:idempotency:key-2"), eq(toStore), any());
    }
}
