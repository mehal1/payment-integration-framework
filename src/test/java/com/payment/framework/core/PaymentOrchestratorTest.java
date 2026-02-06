package com.payment.framework.core;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PaymentOrchestrator (idempotency and gateway routing).
 * Does not require Kafka or Redis.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorTest {

	@Mock
	private PaymentGateway mockGateway;
	@Mock
	private IdempotencyService idempotencyService;
	@Mock
	private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;
	@Mock
	private io.github.resilience4j.retry.RetryRegistry retryRegistry;

	private PaymentOrchestrator orchestrator;

	@BeforeEach
	void setUp() {
		when(mockGateway.getProviderType()).thenReturn(PaymentProviderType.MOCK);
		io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
				io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("MOCK");
		io.github.resilience4j.retry.Retry retry =
				io.github.resilience4j.retry.Retry.ofDefaults("payment");
		lenient().when(circuitBreakerRegistry.circuitBreaker(any())).thenReturn(cb);
		lenient().when(retryRegistry.retry(any())).thenReturn(retry);
		orchestrator = new PaymentOrchestrator(
				List.of(mockGateway),
				idempotencyService,
				circuitBreakerRegistry,
				retryRegistry
		);
		orchestrator.init();
	}

	@Test
	void getGatewayReturnsGatewayForRegisteredType() {
		Optional<PaymentGateway> gateway = orchestrator.getGateway(PaymentProviderType.MOCK);
		assertThat(gateway).isPresent().get().isSameAs(mockGateway);
	}

	@Test
	void getGatewayReturnsEmptyForUnregisteredType() {
		assertThat(orchestrator.getGateway(PaymentProviderType.CARD)).isEmpty();
	}

	@Test
	void executeDelegatesToGatewayAndReturnsResult() {
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
		when(mockGateway.execute(request)).thenReturn(expected);

		PaymentResult result = orchestrator.execute(request);

		assertThat(result).isEqualTo(expected);
		assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
	}
}
