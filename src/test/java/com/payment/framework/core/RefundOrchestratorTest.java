package com.payment.framework.core;

import com.payment.framework.domain.*;
import com.payment.framework.persistence.entity.RefundEntity;
import com.payment.framework.persistence.repository.RefundRepository;
import com.payment.framework.persistence.service.PaymentPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundOrchestratorTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private PaymentOrchestrator paymentOrchestrator;
    @Mock private PaymentPersistenceService persistenceService;
    @Mock private RefundRepository refundRepository;
    @Mock private PSPAdapter mockAdapter;

    private RefundOrchestrator refundOrchestrator;

    private static final String PAYMENT_KEY = "pay-123";
    private static final String REFUND_KEY = "refund-456";

    @BeforeEach
    void setUp() {
        refundOrchestrator = new RefundOrchestrator(
                idempotencyService, paymentOrchestrator, persistenceService, refundRepository);
    }

    private PaymentResult successfulPayment(BigDecimal amount) {
        return PaymentResult.builder()
                .idempotencyKey(PAYMENT_KEY)
                .providerTransactionId("txn-abc")
                .status(TransactionStatus.SUCCESS)
                .amount(amount)
                .currencyCode("USD")
                .timestamp(Instant.now())
                .metadata(Map.of("adapterName", "MockStripeAdapter", "providerType", "CARD"))
                .build();
    }

    private RefundRequest refundRequest(BigDecimal amount) {
        return RefundRequest.builder()
                .idempotencyKey(REFUND_KEY)
                .paymentIdempotencyKey(PAYMENT_KEY)
                .amount(amount)
                .currencyCode("USD")
                .reason("test")
                .build();
    }

    private RefundResult successfulRefundResult(BigDecimal amount) {
        return RefundResult.builder()
                .idempotencyKey(REFUND_KEY)
                .paymentIdempotencyKey(PAYMENT_KEY)
                .providerRefundId("refund-txn-xyz")
                .status(RefundStatus.SUCCESS)
                .amount(amount)
                .currencyCode("USD")
                .message("Refund processed")
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void fullRefundSucceeds() {
        PaymentResult payment = successfulPayment(new BigDecimal("100.00"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS)).thenReturn(BigDecimal.ZERO);
        when(paymentOrchestrator.getAdapterByName("MockStripeAdapter")).thenReturn(Optional.of(mockAdapter));
        when(mockAdapter.refund(any(RefundRequest.class)))
                .thenReturn(Optional.of(successfulRefundResult(new BigDecimal("100.00"))));

        RefundRequest request = refundRequest(null); // null = full refund
        RefundResult result = refundOrchestrator.execute(request);

        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(result.getAmount()).isEqualByComparingTo("100.00");
        verify(persistenceService).persistRefund(eq(REFUND_KEY), any(RefundResult.class));
    }

    @Test
    void partialRefundSucceeds() {
        PaymentResult payment = successfulPayment(new BigDecimal("100.00"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS)).thenReturn(BigDecimal.ZERO);
        when(paymentOrchestrator.getAdapterByName("MockStripeAdapter")).thenReturn(Optional.of(mockAdapter));
        when(mockAdapter.refund(any(RefundRequest.class)))
                .thenReturn(Optional.of(successfulRefundResult(new BigDecimal("30.00"))));

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("30.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(result.getAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    void refundExceedingPaymentAmountFails() {
        PaymentResult payment = successfulPayment(new BigDecimal("100.00"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("150.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("REFUND_AMOUNT_EXCEEDED");
        verify(mockAdapter, never()).refund(any());
    }

    @Test
    void cumulativeRefundLimitExceeded() {
        PaymentResult payment = successfulPayment(new BigDecimal("100.00"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS))
                .thenReturn(new BigDecimal("80.00"));

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("30.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("REFUND_LIMIT_EXCEEDED");
        assertThat(result.getMessage()).contains("Already refunded: 80.00");
        assertThat(result.getMessage()).contains("Remaining: 20.00");
        verify(mockAdapter, never()).refund(any());
    }

    @Test
    void idempotentRefundReturnsCachedResult() {
        RefundEntity existing = RefundEntity.builder()
                .refundIdempotencyKey(REFUND_KEY)
                .paymentIdempotencyKey(PAYMENT_KEY)
                .providerRefundId("refund-txn-cached")
                .status(RefundStatus.SUCCESS)
                .amount(new BigDecimal("50.00"))
                .currencyCode("USD")
                .createdAt(Instant.now())
                .build();
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.of(existing));

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("50.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(result.getProviderRefundId()).isEqualTo("refund-txn-cached");
        verify(mockAdapter, never()).refund(any());
        verify(idempotencyService, never()).getCachedResult(any());
    }

    @Test
    void paymentNotFoundThrows() {
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.empty());
        when(persistenceService.getTransaction(PAYMENT_KEY)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> refundOrchestrator.execute(refundRequest(new BigDecimal("50.00"))));
    }

    @Test
    void failedPaymentCannotBeRefunded() {
        PaymentResult failedPayment = PaymentResult.builder()
                .idempotencyKey(PAYMENT_KEY)
                .status(TransactionStatus.FAILED)
                .amount(new BigDecimal("100.00"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .build();
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(failedPayment));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> refundOrchestrator.execute(refundRequest(new BigDecimal("50.00"))));
    }

    @Test
    void adapterNotFoundByNameFails() {
        PaymentResult payment = successfulPayment(new BigDecimal("100.00"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS)).thenReturn(BigDecimal.ZERO);
        when(paymentOrchestrator.getAdapterByName("MockStripeAdapter")).thenReturn(Optional.empty());

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("50.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("ADAPTER_NOT_FOUND");
    }

    @Test
    void adapterNotFoundByProviderTypeFallback() {
        PaymentResult paymentNoAdapterName = PaymentResult.builder()
                .idempotencyKey(PAYMENT_KEY)
                .providerTransactionId("txn-abc")
                .status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("100.00"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .metadata(Map.of("providerType", "CARD"))
                .build();
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(paymentNoAdapterName));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS)).thenReturn(BigDecimal.ZERO);
        when(paymentOrchestrator.getAdapter(PaymentProviderType.CARD)).thenReturn(Optional.empty());

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("50.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("ADAPTER_NOT_FOUND");
    }

    @Test
    void adapterDoesNotSupportRefundsFails() {
        PaymentResult payment = successfulPayment(new BigDecimal("100.00"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS)).thenReturn(BigDecimal.ZERO);
        when(paymentOrchestrator.getAdapterByName("MockStripeAdapter")).thenReturn(Optional.of(mockAdapter));
        when(mockAdapter.refund(any(RefundRequest.class))).thenReturn(Optional.empty());
        when(mockAdapter.getPSPAdapterName()).thenReturn("MockStripeAdapter");

        RefundResult result = refundOrchestrator.execute(refundRequest(new BigDecimal("50.00")));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo("REFUND_NOT_SUPPORTED");
    }

    @Test
    void fullRefundResolvesAmountFromPayment() {
        PaymentResult payment = successfulPayment(new BigDecimal("75.50"));
        when(persistenceService.getRefund(REFUND_KEY)).thenReturn(Optional.empty());
        when(idempotencyService.getCachedResult(PAYMENT_KEY)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundedAmountByPayment(PAYMENT_KEY, RefundStatus.SUCCESS)).thenReturn(BigDecimal.ZERO);
        when(paymentOrchestrator.getAdapterByName("MockStripeAdapter")).thenReturn(Optional.of(mockAdapter));
        when(mockAdapter.refund(any(RefundRequest.class)))
                .thenReturn(Optional.of(successfulRefundResult(new BigDecimal("75.50"))));

        RefundResult result = refundOrchestrator.execute(refundRequest(null));

        assertThat(result.getStatus()).isEqualTo(RefundStatus.SUCCESS);
        // Verify adapter was called with the resolved amount, not null
        verify(mockAdapter, times(2)).refund(argThat(req -> req.getAmount().compareTo(new BigDecimal("75.50")) == 0));
    }
}
