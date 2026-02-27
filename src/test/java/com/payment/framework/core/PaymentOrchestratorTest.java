package com.payment.framework.core;

import com.payment.framework.core.routing.ProviderPerformanceMetrics;
import com.payment.framework.core.routing.ProviderRouter;
import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PaymentOrchestrator (idempotency and PSP routing).
 * Does not require Kafka or Redis.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorTest {

	@Mock
	private PSPAdapter mockAdapter;
	@Mock
	private IdempotencyService idempotencyService;
	@Mock
	private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;
	@Mock
	private io.github.resilience4j.retry.RetryRegistry retryRegistry;
	@Mock
	private ProviderRouter providerRouter;
	@Mock
	private ProviderPerformanceMetrics metrics;
	@Mock
	private com.payment.framework.persistence.service.PaymentPersistenceService persistenceService;

	private PaymentOrchestrator orchestrator;

	@BeforeEach
	void setUp() {
		when(mockAdapter.getProviderType()).thenReturn(PaymentProviderType.MOCK);
		lenient().when(mockAdapter.getPSPAdapterName()).thenReturn("MockPSPAdapter");
		io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
				io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("MockPSPAdapter");
		io.github.resilience4j.retry.Retry retry =
				io.github.resilience4j.retry.Retry.ofDefaults("payment");
		lenient().when(circuitBreakerRegistry.circuitBreaker(any())).thenReturn(cb);
		lenient().when(retryRegistry.retry(any())).thenReturn(retry);
		// Mock ProviderRouter to return the mock adapter
		lenient().when(providerRouter.selectProvider(any(PaymentRequest.class)))
				.thenReturn(Optional.of(mockAdapter));
		// Mock metrics methods (no-op for unit tests)
		lenient().doNothing().when(metrics).incrementActiveConnections(any(PaymentProviderType.class));
		lenient().doNothing().when(metrics).decrementActiveConnections(any(PaymentProviderType.class));
		lenient().doNothing().when(metrics).recordSuccess(any(PaymentProviderType.class), anyLong(), any(java.math.BigDecimal.class));
		lenient().doNothing().when(metrics).recordFailure(any(PaymentProviderType.class), anyLong());
		orchestrator = new PaymentOrchestrator(
				List.of(mockAdapter),
				idempotencyService,
				circuitBreakerRegistry,
				retryRegistry,
				providerRouter,
				metrics,
				persistenceService
		);
		// Set @Value fields using reflection (they're not injected in unit tests)
		try {
			Field failoverEnabledField = PaymentOrchestrator.class.getDeclaredField("failoverEnabled");
			failoverEnabledField.setAccessible(true);
			failoverEnabledField.setBoolean(orchestrator, true);
			
			Field maxFailoverAttemptsField = PaymentOrchestrator.class.getDeclaredField("maxFailoverAttempts");
			maxFailoverAttemptsField.setAccessible(true);
			maxFailoverAttemptsField.setInt(orchestrator, 3);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set @Value fields", e);
		}
		orchestrator.init();
	}

	@Test
	void getAdapterReturnsAdapterForRegisteredType() {
		// This test doesn't need getPSPAdapterName() stubbing
		Optional<PSPAdapter> adapter = orchestrator.getAdapter(PaymentProviderType.MOCK);
		assertThat(adapter).isPresent().get().isSameAs(mockAdapter);
	}

	@Test
	void getAdapterReturnsEmptyForUnregisteredType() {
		// This test doesn't need getPSPAdapterName() stubbing
		assertThat(orchestrator.getAdapter(PaymentProviderType.CARD)).isEmpty();
	}

	@Test
	void executeDelegatesToAdapterAndReturnsResult() {
		PaymentRequest request = PaymentRequest.builder()
				.idempotencyKey("key-1")
				.providerType(PaymentProviderType.MOCK)
				.amount(new BigDecimal("10.00"))
				.currencyCode("USD")
				.build();
		PaymentResult expected = PaymentResult.builder()
				.idempotencyKey("key-1")
				.providerTransactionId("tx-1")
				.status(TransactionStatus.SUCCESS)
				.amount(request.getAmount())
				.currencyCode("USD")
				.timestamp(Instant.now())
				.build();
		when(idempotencyService.getCachedResult("key-1")).thenReturn(Optional.empty());
		// Ensure ProviderRouter returns the adapter (override lenient setup if needed)
		when(providerRouter.selectProvider(any(PaymentRequest.class))).thenReturn(Optional.of(mockAdapter));
		when(mockAdapter.execute(request)).thenReturn(expected);

		PaymentResult result = orchestrator.execute(request);

		assertThat(result).isEqualTo(expected);
		assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
	}

	/**
	 * Tests the decorator pattern: Retry wraps the original call.
	 * Verifies that retries happen on transient failures.
	 */
	@Test
	void decoratorPattern_RetryWrapsOriginalCall() {
		// Setup: Create real retry with 3 max attempts
		RetryConfig retryConfig = RetryConfig.custom()
				.maxAttempts(3)
				.waitDuration(Duration.ofMillis(10))
				.build();
		RetryRegistry realRetryRegistry = RetryRegistry.of(retryConfig);
		when(retryRegistry.retry(any())).thenReturn(realRetryRegistry.retry("payment"));

		// Setup: Create real circuit breaker (always closed for this test)
		CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
				.failureRateThreshold(50)
				.slidingWindowSize(10)
				.build();
		CircuitBreakerRegistry realCbRegistry = CircuitBreakerRegistry.of(cbConfig);
		when(circuitBreakerRegistry.circuitBreaker(any())).thenReturn(realCbRegistry.circuitBreaker("MockPSPAdapter"));

		PaymentRequest request = PaymentRequest.builder()
				.idempotencyKey("retry-test")
				.providerType(PaymentProviderType.MOCK)
				.amount(new BigDecimal("10.00"))
				.currencyCode("USD")
				.build();

		PaymentResult successResult = PaymentResult.builder()
				.idempotencyKey("retry-test")
				.providerTransactionId("tx-success")
				.status(TransactionStatus.SUCCESS)
				.amount(request.getAmount())
				.currencyCode("USD")
				.timestamp(Instant.now())
				.build();

		when(idempotencyService.getCachedResult("retry-test")).thenReturn(Optional.empty());
		when(providerRouter.selectProvider(any(PaymentRequest.class))).thenReturn(Optional.of(mockAdapter));

		// First 2 calls fail, 3rd succeeds (retry should retry 3 times total)
		when(mockAdapter.execute(request))
				.thenThrow(new RuntimeException("Transient failure"))
				.thenThrow(new RuntimeException("Transient failure"))
				.thenReturn(successResult);

		PaymentResult result = orchestrator.execute(request);

		// Verify retry happened: adapter.execute should be called 3 times (initial + 2 retries)
		verify(mockAdapter, times(3)).execute(request);
		assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
	}

	/**
	 * Tests the decorator pattern: Circuit breaker wraps retry.
	 * Verifies that circuit breaker opens after failures and blocks calls.
	 */
	@Test
	void decoratorPattern_CircuitBreakerWrapsRetry() {
		// Setup: Create real circuit breaker that opens after 2 failures (low threshold for testing)
		CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
				.failureRateThreshold(50) // 50% failure rate
				.slidingWindowSize(2) // Small window: 2 calls
				.minimumNumberOfCalls(2) // Need at least 2 calls to evaluate
				.waitDurationInOpenState(Duration.ofMillis(100))
				.build();
		CircuitBreakerRegistry realCbRegistry = CircuitBreakerRegistry.of(cbConfig);
		CircuitBreaker cb = realCbRegistry.circuitBreaker("MockPSPAdapter");
		when(circuitBreakerRegistry.circuitBreaker(any())).thenReturn(cb);

		// Setup: Create real retry
		RetryConfig retryConfig = RetryConfig.custom()
				.maxAttempts(1) // No retries for this test
				.waitDuration(Duration.ofMillis(10))
				.build();
		RetryRegistry realRetryRegistry = RetryRegistry.of(retryConfig);
		when(retryRegistry.retry(any())).thenReturn(realRetryRegistry.retry("payment"));

		PaymentRequest request1 = PaymentRequest.builder()
				.idempotencyKey("cb-test-1")
				.providerType(PaymentProviderType.MOCK)
				.amount(new BigDecimal("10.00"))
				.currencyCode("USD")
				.build();

		PaymentRequest request2 = PaymentRequest.builder()
				.idempotencyKey("cb-test-2")
				.providerType(PaymentProviderType.MOCK)
				.amount(new BigDecimal("10.00"))
				.currencyCode("USD")
				.build();

		PaymentRequest request3 = PaymentRequest.builder()
				.idempotencyKey("cb-test-3")
				.providerType(PaymentProviderType.MOCK)
				.amount(new BigDecimal("10.00"))
				.currencyCode("USD")
				.build();

		when(idempotencyService.getCachedResult(any())).thenReturn(Optional.empty());
		when(providerRouter.selectProvider(any(PaymentRequest.class))).thenReturn(Optional.of(mockAdapter));

		// First 2 calls fail (will open circuit breaker)
		when(mockAdapter.execute(any(PaymentRequest.class)))
				.thenThrow(new RuntimeException("Failure 1"))
				.thenThrow(new RuntimeException("Failure 2"));

		// Execute first call (fails, returns failure result)
		PaymentResult result1 = orchestrator.execute(request1);
		assertThat(result1.getStatus()).isEqualTo(TransactionStatus.FAILED);
		assertThat(result1.getFailureCode()).isEqualTo("ALL_PROVIDERS_FAILED");

		// Execute second call (fails, should open circuit breaker)
		PaymentResult result2 = orchestrator.execute(request2);
		assertThat(result2.getStatus()).isEqualTo(TransactionStatus.FAILED);
		assertThat(result2.getFailureCode()).isEqualTo("ALL_PROVIDERS_FAILED");

		// Verify circuit breaker is OPEN
		assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		// Third call should be blocked by circuit breaker (returns failure result)
		PaymentResult result3 = orchestrator.execute(request3);
		assertThat(result3.getStatus()).isEqualTo(TransactionStatus.FAILED);
		assertThat(result3.getFailureCode()).isEqualTo("ALL_PROVIDERS_FAILED");

		// Verify adapter.execute was only called 2 times (circuit breaker blocked the 3rd)
		verify(mockAdapter, times(2)).execute(any(PaymentRequest.class));
	}

	/**
	 * Tests the decorator pattern wrapping order: CircuitBreaker wraps Retry wraps Original.
	 * Verifies that circuit breaker can block retries when open.
	 */
	@Test
	void decoratorPattern_WrappingOrder_CircuitBreakerWrapsRetryWrapsOriginal() {
		// Setup: Circuit breaker opens immediately (for testing)
		CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
				.failureRateThreshold(1) // 100% failure rate (opens immediately)
				.slidingWindowSize(1)
				.minimumNumberOfCalls(1)
				.waitDurationInOpenState(Duration.ofSeconds(1))
				.build();
		CircuitBreakerRegistry realCbRegistry = CircuitBreakerRegistry.of(cbConfig);
		CircuitBreaker cb = realCbRegistry.circuitBreaker("MockPSPAdapter");
		// Manually open the circuit breaker to test wrapping order
		cb.transitionToOpenState();
		when(circuitBreakerRegistry.circuitBreaker(any())).thenReturn(cb);

		// Setup: Retry with multiple attempts
		RetryConfig retryConfig = RetryConfig.custom()
				.maxAttempts(3)
				.waitDuration(Duration.ofMillis(10))
				.build();
		RetryRegistry realRetryRegistry = RetryRegistry.of(retryConfig);
		when(retryRegistry.retry(any())).thenReturn(realRetryRegistry.retry("payment"));

		PaymentRequest request = PaymentRequest.builder()
				.idempotencyKey("wrapping-order-test")
				.providerType(PaymentProviderType.MOCK)
				.amount(new BigDecimal("10.00"))
				.currencyCode("USD")
				.build();

		when(idempotencyService.getCachedResult("wrapping-order-test")).thenReturn(Optional.empty());
		when(providerRouter.selectProvider(any(PaymentRequest.class))).thenReturn(Optional.of(mockAdapter));

		// Circuit breaker is OPEN, so it should block immediately (before retry or adapter call)
		// Returns failure result instead of throwing exception
		PaymentResult result = orchestrator.execute(request);
		assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
		assertThat(result.getFailureCode()).isEqualTo("ALL_PROVIDERS_FAILED");

		// Verify adapter.execute was NEVER called (circuit breaker blocked before retry could execute)
		verify(mockAdapter, times(0)).execute(request);

		// This proves: CircuitBreaker wraps Retry wraps Original
		// When circuit breaker is OPEN, it blocks before retry logic runs
	}
}
