package com.payment.framework.risk.messaging;

import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.engine.RiskEngine;
import com.payment.framework.risk.llm.AlertSummaryService;
import com.payment.framework.risk.store.RecentAlertsStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Consumes payment lifecycle events from Project 1's topic. For each event,
 * updates feature aggregates and runs the risk engine; when risk is above
 * threshold, produces a risk alert (and optionally enriches with LLM summary).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.risk.engine.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventConsumer {

    private final RiskEngine riskEngine;
    private final RiskAlertProducer alertProducer;
    private final AlertSummaryService alertSummaryService;
    private final RecentAlertsStore recentAlertsStore;

    @KafkaListener(
            topics = "${payment.kafka.topic.payment-events:payment-events}",
            groupId = "${payment.risk.kafka.consumer-group:payment-risk-engine}",
            containerFactory = "paymentEventListenerContainerFactory"
    )
    public void onPaymentEvent(
            @Payload PaymentEvent event,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(value = KafkaHeaders.OFFSET, required = false) Long offset) {
        try {
            if (event == null) {
                log.warn("Received null payment event (deserialization failed). Key={}, partition={}, offset={}", key, partition, offset);
                return;
            }
            if (event.getEventId() == null) {
                log.error("Received payment event with all null fields - deserialization issue! Key={}, partition={}, offset={}, event={}. " +
                        "This suggests JSON format mismatch. Check producer/consumer ObjectMapper configuration.", 
                        key, partition, offset, event);
                return;
            }
            log.info("Processing payment event: eventId={}, idempotencyKey={}, amount={}, eventType={}, merchantRef={}",
                    event.getEventId(), event.getIdempotencyKey(), event.getAmount(), event.getEventType(), event.getMerchantReference());
            Optional<RiskAlert> alertOpt = riskEngine.evaluate(event);
            if (alertOpt.isEmpty()) {
                log.debug("No risk alert generated for eventId={} - risk score below threshold or no signals", event.getEventId());
            }
            if (alertOpt.isPresent()) {
                RiskAlert alert = alertOpt.get();
                Optional<String> explanation = alertSummaryService.generateSummary(alert);
                RiskAlert enriched = explanation
                        .map(ex -> RiskAlert.builder()
                                .alertId(alert.getAlertId())
                                .timestamp(alert.getTimestamp())
                                .level(alert.getLevel())
                                .signalTypes(alert.getSignalTypes())
                                .riskScore(alert.getRiskScore())
                                .entityId(alert.getEntityId())
                                .entityType(alert.getEntityType())
                                .relatedEventIds(alert.getRelatedEventIds())
                                .amount(alert.getAmount())
                                .currencyCode(alert.getCurrencyCode())
                                .summary(alert.getSummary())
                                .detailedExplanation(ex)
                                .build())
                        .orElse(alert);
                recentAlertsStore.add(enriched);
                alertProducer.send(enriched);
                log.info("Risk alert produced: {} level={} score={}", enriched.getAlertId(), enriched.getLevel(), enriched.getRiskScore());
            }
        } catch (Exception e) {
            log.error("Error processing payment event key={} eventId={}", key, event != null ? event.getEventId() : null, e);
        }
    }
}
