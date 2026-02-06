package com.payment.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payment.framework.domain.PaymentResult;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;

/**
 * Serializes {@link PaymentResult} to/from JSON for Redis. No polymorphic type
 * (@class) so stored values always deserialize to PaymentResult and work with
 * existing entries.
 */
public class PaymentResultRedisSerializer implements RedisSerializer<PaymentResult> {

    private final ObjectMapper mapper;

    public PaymentResultRedisSerializer() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public byte[] serialize(PaymentResult value) throws SerializationException {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SerializationException("Could not serialize PaymentResult", e);
        }
    }

    @Override
    public PaymentResult deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), PaymentResult.class);
        } catch (Exception e) {
            throw new SerializationException("Could not deserialize PaymentResult", e);
        }
    }
}
