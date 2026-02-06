package com.payment.framework.risk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.llm.AlertSummaryService;
import com.payment.framework.risk.llm.NoOpAlertSummaryService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka config for Project 2 (risk engine): consumer for payment-events (PaymentEvent)
 * and producer for risk-alerts (RiskAlert). Uses JSON (de)serialization.
 */
@Slf4j
@Configuration
public class RiskKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${payment.risk.kafka.consumer-group:payment-risk-engine}")
    private String consumerGroup;

    /** ObjectMapper for PaymentEvent deserialization - handles Lombok @Builder pattern */
    @Bean(name = "paymentEventKafkaObjectMapper")
    public ObjectMapper paymentEventKafkaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // PaymentEvent has @JsonDeserialize(builder = PaymentEvent.PaymentEventBuilder.class)
        // and @JsonPOJOBuilder(withPrefix = "") which tells Jackson to use the Lombok builder
        // Ensure builder-based deserialization is enabled
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        // Disable features that might interfere with builder deserialization
        mapper.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        return mapper;
    }

    @Bean
    public ConsumerFactory<String, PaymentEvent> paymentEventConsumerFactory(
            @Qualifier("paymentEventKafkaObjectMapper") ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Configure deserializer - @Jacksonized on PaymentEvent handles builder deserialization automatically
        JsonDeserializer<PaymentEvent> deserializer = new JsonDeserializer<>(PaymentEvent.class, objectMapper);
        deserializer.setUseTypeHeaders(false);
        deserializer.addTrustedPackages("com.payment.framework");
        // Wrap with ErrorHandlingDeserializer for graceful error handling
        ErrorHandlingDeserializer<PaymentEvent> errorHandlingDeserializer = new ErrorHandlingDeserializer<>(deserializer);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), errorHandlingDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> paymentEventListenerContainerFactory(
            @Qualifier("paymentEventKafkaObjectMapper") ObjectMapper objectMapper) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentEventConsumerFactory(objectMapper));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Add error handler to log deserialization errors explicitly
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)) {
            @Override
            public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer, 
                    MessageListenerContainer container, boolean batchListener) {
                Throwable cause = thrownException.getCause();
                if (cause != null) {
                    String causeName = cause.getClass().getSimpleName();
                    if (causeName.contains("Deserialization") || causeName.contains("JsonMapping") || 
                        causeName.contains("InvalidFormat") || causeName.contains("MismatchedInput")) {
                        log.error("Kafka deserialization error - message format may be incompatible. " +
                                "Exception: {} - {}. " +
                                "This usually means JSON format mismatch between producer and consumer. " +
                                "Check PaymentEvent serialization/deserialization configuration.",
                                causeName, cause.getMessage(), thrownException);
                    } else {
                        log.error("Kafka listener error: {}", cause.getMessage(), thrownException);
                    }
                } else {
                    log.error("Kafka listener error", thrownException);
                }
                super.handleOtherException(thrownException, consumer, container, batchListener);
            }
        });
        return factory;
    }

    /** ObjectMapper with Java 8 date/time support for RiskAlert (Instant) serialization to Kafka. */
    @Bean(name = "riskAlertKafkaObjectMapper")
    public ObjectMapper riskAlertKafkaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean
    public ProducerFactory<String, RiskAlert> riskAlertProducerFactory(
            @Qualifier("riskAlertKafkaObjectMapper") ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        JsonSerializer<RiskAlert> serializer = new JsonSerializer<>(objectMapper);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, RiskAlert> riskAlertKafkaTemplate(
            ProducerFactory<String, RiskAlert> riskAlertProducerFactory) {
        return new KafkaTemplate<>(riskAlertProducerFactory);
    }

    /** Default no-op LLM summary; replace with your own @Bean to enable real LLM summaries. */
    @Bean
    @ConditionalOnMissingBean(AlertSummaryService.class)
    public AlertSummaryService alertSummaryService() {
        return new NoOpAlertSummaryService();
    }
}
