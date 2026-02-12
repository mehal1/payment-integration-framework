package com.payment.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payment.framework.messaging.PaymentEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer configuration for payment events. Uses JSON serialization
 * for {@link PaymentEvent} so consumers (and future ML pipelines) can read
 * events without Java-specific serialization.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /** ObjectMapper for PaymentEvent serialization - matches consumer configuration */
    @Bean(name = "paymentEventProducerObjectMapper")
    public ObjectMapper paymentEventProducerObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Ensure compatibility with consumer deserialization
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    @Bean
    public ProducerFactory<String, PaymentEvent> paymentEventProducerFactory(
            @org.springframework.beans.factory.annotation.Qualifier("paymentEventProducerObjectMapper") ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        // Custom serializer using ObjectMapper directly (replaces deprecated JsonSerializer)
        Serializer<PaymentEvent> serializer = new Serializer<PaymentEvent>() {
            @Override
            public byte[] serialize(String topic, PaymentEvent data) {
                if (data == null) {
                    return null;
                }
                try {
                    byte[] result = objectMapper.writeValueAsBytes(data);
                    if (result != null) {
                        // Do not log message body: it may contain card data (paymentMethodId, cardBin, cardLast4)
                        log.debug("Serialized PaymentEvent (topic={}, length={}, eventId={})", topic, result.length, data.getEventId());
                    }
                    return result;
                } catch (Exception e) {
                    log.error("Serialization failed for topic={}", topic, e);
                    throw new RuntimeException("Failed to serialize PaymentEvent", e);
                }
            }
        };
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate(
            ProducerFactory<String, PaymentEvent> paymentEventProducerFactory) {
        return new KafkaTemplate<>(paymentEventProducerFactory);
    }
}
