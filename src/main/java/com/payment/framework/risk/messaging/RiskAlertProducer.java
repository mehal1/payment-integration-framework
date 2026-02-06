package com.payment.framework.risk.messaging;

import com.payment.framework.risk.domain.RiskAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes risk alerts to a dedicated topic for dashboards, compliance tools,
 * and downstream automation (e.g. auto-block, case creation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskAlertProducer {

    private final KafkaTemplate<String, RiskAlert> riskAlertKafkaTemplate;

    @Value("${payment.risk.kafka.topic.risk-alerts:risk-alerts}")
    private String topic;

    public void send(RiskAlert alert) {
        String key = alert.getEntityId() != null ? alert.getEntityId() : alert.getAlertId();
        CompletableFuture<SendResult<String, RiskAlert>> future = riskAlertKafkaTemplate.send(topic, key, alert);
        future.whenComplete((result, ex) -> {
            if (ex != null) log.error("Failed to send risk alert {}", alert.getAlertId(), ex);
            else log.debug("Sent risk alert {} partition={}", alert.getAlertId(), result != null ? result.getRecordMetadata().partition() : null);
        });
    }
}
