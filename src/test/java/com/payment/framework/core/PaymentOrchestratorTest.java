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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PaymentOrchestrator (idempotency and gateway routing).
 * Does not require Kafka or Redis.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorTest {

	@Mock
	private PaymentGatewayAdapter mockAdapter;
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

	private PaymentOrchestrator orchestrator;

	@BeforeEach
	void setUp() {
		when(mockAdapter.getProviderType()).thenReturn(PaymentProviderType.MOCK);
		io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
				io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("MOCK");
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
				metrics
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
		Optional<PaymentGatewayAdapter> adapter = orchestrator.getAdapter(PaymentProviderType.MOCK);
		assertThat(adapter).isPresent().get().isSameAs(mockAdapter);
	}

	@Test
	void getAdapterReturnsEmptyForUnregisteredType() {
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
}
