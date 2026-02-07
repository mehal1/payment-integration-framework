package com.payment.framework.messaging;

import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes payment lifecycle events to Kafka for audit, compliance, and
 * downstream analytics (e.g. ML risk/fraud systems). Events are keyed by
 * idempotency key for ordered processing per payment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Value("${payment.kafka.topic.payment-events:payment-events}")
    private String topic;

    public void publishRequested(PaymentRequest request) {
        PaymentEvent event = PaymentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .providerType(request.getProviderType().name())
                .providerTransactionId(null)
                .status(null)
                .amount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .failureCode(null)
                .message(null)
                .merchantReference(request.getMerchantReference())
                .timestamp(Instant.now())
                .eventType("PAYMENT_REQUESTED")
                .build();
        send(request.getIdempotencyKey(), event);
    }

    public void publishResult(PaymentRequest request, PaymentResult result) {
        if (result == null) {
            log.error("Cannot publish result: PaymentResult is null for idempotencyKey={}", 
                    request != null ? request.getIdempotencyKey() : "unknown");
            return;
        }
        
        // Check for null fields that would indicate a problem
        if (result.getIdempotencyKey() == null && result.getStatus() == null && result.getAmount() == null) {
            log.error("PaymentResult has all null fields! idempotencyKey={}, status={}, amount={}, timestamp={}. " +
                    "This suggests PaymentResult was not built correctly.",
                    result.getIdempotencyKey(), result.getStatus(), result.getAmount(), result.getTimestamp());
        }
        
        String eventType = result.isSuccess() ? "PAYMENT_COMPLETED" : "PAYMENT_FAILED";
        PaymentEvent event = PaymentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .idempotencyKey(result.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .providerType(request.getProviderType().name())
                .providerTransactionId(result.getProviderTransactionId())
                .status(result.getStatus())
                .amount(result.getAmount())
                .currencyCode(result.getCurrencyCode())
                .failureCode(result.getFailureCode())
                .message(result.getMessage())
                .merchantReference(request.getMerchantReference())
                .timestamp(result.getTimestamp())
                .eventType(eventType)
                .build();
        send(request.getIdempotencyKey(), event);
    }

    private void send(String key, PaymentEvent event) {
        // Log what we're sending for debugging
        log.info("Publishing payment event: key={}, eventId={}, idempotencyKey={}, amount={}, status={}, eventType={}",
                key, event.getEventId(), event.getIdempotencyKey(), event.getAmount(), event.getStatus(), event.getEventType());
        CompletableFuture<SendResult<String, PaymentEvent>> future =
                kafkaTemplate.send(topic, key, event);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish payment event key={} eventId={}", key, event.getEventId(), ex);
            } else {
                log.info("Successfully published payment event: key={}, eventId={}, partition={}, offset={}",
                        key, event.getEventId(),
                        result != null ? result.getRecordMetadata().partition() : null,
                        result != null ? result.getRecordMetadata().offset() : null);
            }
        });
    }
}
