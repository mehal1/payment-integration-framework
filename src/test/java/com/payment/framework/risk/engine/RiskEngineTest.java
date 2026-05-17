package com.payment.framework.risk.engine;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.domain.RiskLevel;
import com.payment.framework.risk.domain.RiskSignalType;
import com.payment.framework.risk.features.TransactionWindowAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RiskEngine: rule-based scoring and alert generation.
 */
class RiskEngineTest {

    private TransactionWindowAggregator aggregator;
    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        aggregator = new TransactionWindowAggregator();
        riskEngine = new RiskEngine(aggregator, null); // ML scorer is null for unit tests (uses rules only)
        ReflectionTestUtils.setField(riskEngine, "highFailureRateThreshold", 0.5);
        ReflectionTestUtils.setField(riskEngine, "velocity1MinThreshold", 10);
        ReflectionTestUtils.setField(riskEngine, "alertScoreThreshold", 0.3);
    }

    private static PaymentEvent event(String id, String merchantRef, BigDecimal amount, boolean failure) {
        return PaymentEvent.builder()
                .eventId(id)
                .merchantReference(merchantRef)
                .amount(amount)
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType(failure ? "PAYMENT_FAILED" : "PAYMENT_COMPLETED")
                .build();
    }

    private static PaymentEvent eventWithEmail(String id, String merchantRef, String email, BigDecimal amount, boolean failure) {
        return PaymentEvent.builder()
                .eventId(id)
                .merchantReference(merchantRef)
                .email(email)
                .amount(amount)
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType(failure ? "PAYMENT_FAILED" : "PAYMENT_COMPLETED")
                .build();
    }

    private static PaymentEvent eventWithIp(String id, String merchantRef, String clientIp, BigDecimal amount, boolean failure) {
        return PaymentEvent.builder()
                .eventId(id)
                .merchantReference(merchantRef)
                .clientIp(clientIp)
                .amount(amount)
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType(failure ? "PAYMENT_FAILED" : "PAYMENT_COMPLETED")
                .build();
    }

    @Test
    void evaluateReturnsEmptyWhenSingleSuccessEvent() {
        Optional<RiskAlert> alert = riskEngine.evaluate(event("e1", "m1", new BigDecimal("100"), false));
        assertThat(alert).isEmpty();
    }

    @Test
    void evaluateProducesAlertWhenFailureRateAboveThreshold() {
        riskEngine.evaluate(event("e1", "m1", new BigDecimal("100"), true));
        riskEngine.evaluate(event("e2", "m1", new BigDecimal("100"), true));
        riskEngine.evaluate(event("e3", "m1", new BigDecimal("100"), false));
        riskEngine.evaluate(event("e4", "m1", new BigDecimal("100"), true));

        Optional<RiskAlert> alert = riskEngine.evaluate(event("e5", "m1", new BigDecimal("100"), true));

        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.HIGH_FAILURE_RATE);
        assertThat(alert.get().getRiskScore()).isGreaterThanOrEqualTo(0.5);
        assertThat(alert.get().getLevel()).isIn(RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.CRITICAL);
        assertThat(alert.get().getEntityId()).isEqualTo("m1");
    }

    @Test
    void evaluateProducesRepeatedFailuresSignalWhenMultipleFailuresInSmallBatch() {
        riskEngine.evaluate(event("e1", "m2", new BigDecimal("50"), true));
        riskEngine.evaluate(event("e2", "m2", new BigDecimal("50"), true));
        riskEngine.evaluate(event("e3", "m2", new BigDecimal("50"), true));

        Optional<RiskAlert> alert = riskEngine.evaluate(event("e4", "m2", new BigDecimal("50"), false));

        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.REPEATED_FAILURES);
    }

    @Test
    void evaluateProducesUnusualAmountSignalWhenCurrentAmountMuchHigherThanAvg() {
        riskEngine.evaluate(event("e1", "m3", new BigDecimal("10"), false));
        riskEngine.evaluate(event("e2", "m3", new BigDecimal("10"), false));
        riskEngine.evaluate(event("e3", "m3", new BigDecimal("10"), false));

        Optional<RiskAlert> alert = riskEngine.evaluate(event("e4", "m3", new BigDecimal("100"), false));

        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.UNUSUAL_AMOUNT);
    }

    @Test
    void evaluateSummaryContainsFailureRateAndVelocity() {
        riskEngine.evaluate(event("e1", "m4", new BigDecimal("100"), true));
        riskEngine.evaluate(event("e2", "m4", new BigDecimal("100"), true));
        Optional<RiskAlert> alert = riskEngine.evaluate(event("e3", "m4", new BigDecimal("100"), true));

        assertThat(alert).isPresent();
        assertThat(alert.get().getSummary()).contains("Risk score");
        assertThat(alert.get().getSummary()).contains("failure rate");
        assertThat(alert.get().getSummary()).contains("velocity");
    }

    @Test
    void evaluateProducesEmailFailureRateAlertAcrossPaymentTypes() {
        // Same email used for multiple payments (e.g. card + BNPL + wallet); high failure rate on email dimension
        riskEngine.evaluate(eventWithEmail("e1", "m1", "user@test.com", new BigDecimal("50"), true));
        riskEngine.evaluate(eventWithEmail("e2", "m2", "user@test.com", new BigDecimal("50"), true));
        riskEngine.evaluate(eventWithEmail("e3", "m3", "user@test.com", new BigDecimal("50"), false));
        riskEngine.evaluate(eventWithEmail("e4", "m4", "user@test.com", new BigDecimal("50"), true));

        Optional<RiskAlert> alert = riskEngine.evaluate(eventWithEmail("e5", "m5", "user@test.com", new BigDecimal("50"), true));

        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.HIGH_EMAIL_FAILURE_RATE);
        assertThat(alert.get().getSummary()).contains("email cross-type");
    }

    @Test
    void evaluateProducesIpVelocityAlertWhenSameIpHighVolume() {
        ReflectionTestUtils.setField(riskEngine, "velocity1MinThreshold", 5);
        for (int i = 0; i < 6; i++) {
            riskEngine.evaluate(eventWithIp("e" + i, "m" + i, "192.168.1.1", new BigDecimal("10"), false));
        }
        Optional<RiskAlert> alert = riskEngine.evaluate(eventWithIp("e6", "m6", "192.168.1.1", new BigDecimal("10"), false));

        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.HIGH_IP_VELOCITY);
        assertThat(alert.get().getSummary()).contains("IP cross-type");
    }

    @Test
    void evaluateFlagsDisposableEmailDomain() {
        PaymentEvent e = PaymentEvent.builder()
                .eventId("e-disposable")
                .merchantReference("m-disposable")
                .email("user@mailinator.com")
                .amount(new BigDecimal("100"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType("PAYMENT_COMPLETED")
                .build();
        Optional<RiskAlert> alert = riskEngine.evaluate(e);
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.DISPOSABLE_EMAIL);
    }

    @Test
    void evaluateFlagsWeakAvsOnSingleEvent() {
        PaymentEvent e = PaymentEvent.builder()
                .eventId("e-avs")
                .merchantReference("m-avs")
                .amount(new BigDecimal("100"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType("PAYMENT_COMPLETED")
                .avsResult("NO_MATCH")
                .build();
        Optional<RiskAlert> alert = riskEngine.evaluate(e);
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.WEAK_CARD_VERIFICATION);
    }

    @Test
    void evaluateFlagsMultipleCardsSameEmail() {
        ReflectionTestUtils.setField(riskEngine, "distinctInstrumentsEmailIpThreshold", 3);
        riskEngine.evaluate(emailWithCard("e1", "m1", "buyer@example.com", "411111", "1111"));
        riskEngine.evaluate(emailWithCard("e2", "m2", "buyer@example.com", "422222", "2222"));
        Optional<RiskAlert> alert = riskEngine.evaluate(emailWithCard("e3", "m3", "buyer@example.com", "433333", "3333"));
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.MULTIPLE_INSTRUMENTS_SAME_CUSTOMER);
    }

    @Test
    void evaluateFlagsCrossProviderVelocityOnSameEmail() {
        ReflectionTestUtils.setField(riskEngine, "crossProviderVelocity1MinThreshold", 5);
        for (int i = 0; i < 5; i++) {
            PaymentProviderType pt = i % 2 == 0 ? PaymentProviderType.CARD : PaymentProviderType.WALLET;
            riskEngine.evaluate(PaymentEvent.builder()
                    .eventId("cp" + i)
                    .merchantReference("merch-" + i)
                    .email("hopper@test.com")
                    .providerType(pt)
                    .amount(new BigDecimal("5"))
                    .currencyCode("USD")
                    .timestamp(Instant.now())
                    .eventType(i % 2 == 0 ? "PAYMENT_COMPLETED" : "PAYMENT_FAILED")
                    .paymentMethodId("pm-" + i)
                    .build());
        }
        PaymentEvent last = PaymentEvent.builder()
                .eventId("cp-last")
                .merchantReference("merch-last")
                .email("hopper@test.com")
                .providerType(PaymentProviderType.CARD)
                .amount(new BigDecimal("5"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType("PAYMENT_COMPLETED")
                .paymentMethodId("pm-last")
                .build();
        Optional<RiskAlert> alert = riskEngine.evaluate(last);
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.CROSS_PROVIDER_ABUSE);
    }

    @Test
    void evaluateFlagsScaFailedAtProductionAlertThreshold() {
        ReflectionTestUtils.setField(riskEngine, "alertScoreThreshold", 0.5);
        PaymentEvent e = PaymentEvent.builder()
                .eventId("e-sca")
                .merchantReference("m-sca")
                .amount(new BigDecimal("10"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType("PAYMENT_COMPLETED")
                .threeDsResult("FAILED")
                .build();
        Optional<RiskAlert> alert = riskEngine.evaluate(e);
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.SCA_FAILED);
        assertThat(alert.get().getRiskScore()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void evaluateFlagsMultipleCardsAtProductionAlertThreshold() {
        ReflectionTestUtils.setField(riskEngine, "alertScoreThreshold", 0.5);
        ReflectionTestUtils.setField(riskEngine, "distinctInstrumentsEmailIpThreshold", 3);
        riskEngine.evaluate(emailWithCard("e1", "m1", "buyer@example.com", "411111", "1111"));
        riskEngine.evaluate(emailWithCard("e2", "m2", "buyer@example.com", "422222", "2222"));
        Optional<RiskAlert> alert = riskEngine.evaluate(emailWithCard("e3", "m3", "buyer@example.com", "433333", "3333"));
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.MULTIPLE_INSTRUMENTS_SAME_CUSTOMER);
        assertThat(alert.get().getRiskScore()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void evaluateFlagsDisposableWithScaAtProductionAlertThreshold() {
        ReflectionTestUtils.setField(riskEngine, "alertScoreThreshold", 0.5);
        PaymentEvent e = PaymentEvent.builder()
                .eventId("e-disp-sca")
                .merchantReference("m-disp")
                .email("user@mailinator.com")
                .amount(new BigDecimal("10"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType("PAYMENT_COMPLETED")
                .threeDsResult("FAILED")
                .build();
        Optional<RiskAlert> alert = riskEngine.evaluate(e);
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes())
                .contains(RiskSignalType.DISPOSABLE_EMAIL, RiskSignalType.SCA_FAILED);
    }

    @Test
    void evaluateFlagsSmallAmountCardTestingOnMerchant() {
        ReflectionTestUtils.setField(riskEngine, "smallAmountMinTx", 5);
        ReflectionTestUtils.setField(riskEngine, "smallAmountMinFailures", 3);
        for (int i = 0; i < 4; i++) {
            riskEngine.evaluate(event("small" + i, "msmall", new BigDecimal("1.00"), true));
        }
        Optional<RiskAlert> alert = riskEngine.evaluate(event("small4", "msmall", new BigDecimal("1.00"), true));
        assertThat(alert).isPresent();
        assertThat(alert.get().getSignalTypes()).contains(RiskSignalType.SMALL_AMOUNT_CARD_TESTING);
    }

    private static PaymentEvent emailWithCard(String id, String merchant, String email, String bin, String last4) {
        return PaymentEvent.builder()
                .eventId(id)
                .merchantReference(merchant)
                .email(email)
                .providerType(PaymentProviderType.CARD)
                .cardBin(bin)
                .cardLast4(last4)
                .amount(new BigDecimal("10"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .eventType("PAYMENT_COMPLETED")
                .build();
    }
}
