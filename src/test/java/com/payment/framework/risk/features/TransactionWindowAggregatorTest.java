package com.payment.framework.risk.features;

import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.TransactionWindowFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TransactionWindowAggregator: feature computation from payment events.
 */
class TransactionWindowAggregatorTest {

    private TransactionWindowAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new TransactionWindowAggregator();
    }

    private static PaymentEvent event(String eventId, String merchantRef, BigDecimal amount, boolean failure) {
        return PaymentEvent.builder()
                .eventId(eventId)
                .merchantReference(merchantRef)
                .amount(amount)
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType(failure ? "PAYMENT_FAILED" : "PAYMENT_COMPLETED")
                .build();
    }

    @Test
    void getFeaturesReturnsEmptyWhenNoEventsRecorded() {
        Optional<TransactionWindowFeatures> features = aggregator.getFeatures("merchant-1");
        assertThat(features).isEmpty();
    }

    @Test
    void getFeaturesAfterRecordingEventsComputesFailureRateAndAmounts() {
        aggregator.record(event("e1", "merchant-1", new BigDecimal("100"), false));
        aggregator.record(event("e2", "merchant-1", new BigDecimal("200"), true));
        aggregator.record(event("e3", "merchant-1", new BigDecimal("50"), true));

        Optional<TransactionWindowFeatures> features = aggregator.getFeatures("merchant-1");

        assertThat(features).isPresent();
        assertThat(features.get().getEntityId()).isEqualTo("merchant-1");
        assertThat(features.get().getTotalCount()).isEqualTo(3);
        assertThat(features.get().getFailureCount()).isEqualTo(2);
        assertThat(features.get().getFailureRate()).isEqualTo(2.0 / 3.0);
        assertThat(features.get().getTotalAmount()).isEqualByComparingTo("350");
        assertThat(features.get().getAvgAmount()).isEqualByComparingTo("116.67");
        assertThat(features.get().getMaxAmount()).isEqualByComparingTo("200");
        assertThat(features.get().getCountLast1Min()).isEqualTo(3);
    }

    @Test
    void getFeaturesFromEventUsesMerchantReferenceAsEntityId() {
        aggregator.record(event("e1", "order-123", new BigDecimal("10"), false));
        aggregator.record(event("e2", "order-123", new BigDecimal("20"), false));
        // getFeaturesFromEvent does not record the event; it looks up by entity id from the event
        Optional<TransactionWindowFeatures> features = aggregator.getFeaturesFromEvent(
                event("e3", "order-123", new BigDecimal("30"), false));
        assertThat(features).isPresent();
        assertThat(features.get().getEntityId()).isEqualTo("order-123");
        assertThat(features.get().getTotalCount()).isEqualTo(2);
    }

    @Test
    void differentMerchantReferencesAreAggregatedSeparately() {
        aggregator.record(event("e1", "merchant-A", new BigDecimal("100"), false));
        aggregator.record(event("e2", "merchant-B", new BigDecimal("200"), false));

        assertThat(aggregator.getFeatures("merchant-A")).isPresent().get().satisfies(f ->
                assertThat(f.getTotalCount()).isEqualTo(1));
        assertThat(aggregator.getFeatures("merchant-B")).isPresent().get().satisfies(f ->
                assertThat(f.getTotalCount()).isEqualTo(1));
    }

    @Test
    void recordHandlesNullTimestampGracefully() {
        // Test defensive null timestamp handling (e.g., from Kafka deserialization)
        PaymentEvent eventWithNullTimestamp = PaymentEvent.builder()
                .eventId("e-null-ts")
                .merchantReference("merchant-1")
                .amount(new BigDecimal("100"))
                .currencyCode("USD")
                .timestamp(null)  // Null timestamp should be handled gracefully
                .eventType("PAYMENT_COMPLETED")
                .build();

        // Should not throw NullPointerException
        aggregator.record(eventWithNullTimestamp);

        Optional<TransactionWindowFeatures> features = aggregator.getFeatures("merchant-1");
        assertThat(features).isPresent();
        assertThat(features.get().getTotalCount()).isEqualTo(1);
    }
}
