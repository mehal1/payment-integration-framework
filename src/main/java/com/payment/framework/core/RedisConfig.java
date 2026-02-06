package com.payment.framework.core;

import com.payment.framework.domain.PaymentResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for idempotency. Uses a dedicated serializer for
 * {@link PaymentResult} (JSON with Java 8 date/time support, no @class)
 * so read/write is consistent and compatible with existing keys.
 */
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public RedisTemplate<String, PaymentResult> paymentResultRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, PaymentResult> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new PaymentResultRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new PaymentResultRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
