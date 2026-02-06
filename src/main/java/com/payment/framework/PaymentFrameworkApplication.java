package com.payment.framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Payment Integration Framework. Enables:
 * <ul>
 *   <li>Pluggable payment gateways (card, wallet, BNPL, etc.)</li>
 *   <li>Idempotency (Redis), circuit breaker and retry (Resilience4j)</li>
 *   <li>Kafka events for audit, compliance, and downstream ML/analytics</li>
 *   <li>REST API and OpenAPI docs at /swagger-ui/index.html</li>
 * </ul>
 */
@SpringBootApplication
public class PaymentFrameworkApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentFrameworkApplication.class, args);
    }
}
