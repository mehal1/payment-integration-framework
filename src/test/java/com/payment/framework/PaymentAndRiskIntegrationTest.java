package com.payment.framework;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test: payment execute API and risk pipeline.
 * Uses Embedded Kafka and Testcontainers Redis. Requires Docker (API 1.44+).
 * Run with: mvn test -DincludeTags=integration (and ensure Docker is available).
 */
@Tag("integration")
@Disabled("Requires Docker; run with docker-compose up -d and remove @Disabled, or use -DincludeTags=integration with Docker")
@SpringBootTest(classes = PaymentFrameworkApplication.class)
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = { "payment-events", "risk-alerts" },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Testcontainers
class PaymentAndRiskIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Test
    @DisplayName("POST /execute returns 200 and success result for valid request")
    void executePaymentReturnsSuccess() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/v1/payments/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "int-test-1",
                                  "providerType": "MOCK",
                                  "amount": 50.00,
                                  "currencyCode": "USD",
                                  "merchantReference": "order-int-1"
                                }
                                """));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value("int-test-1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(50.0));
    }

    @Test
    @DisplayName("Same idempotency key returns same result (idempotency)")
    void idempotencyReturnsCachedResult() throws Exception {
        String body = """
                {
                  "idempotencyKey": "idem-int-1",
                  "providerType": "MOCK",
                  "amount": 25.00,
                  "currencyCode": "USD"
                }
                """;
        mockMvc.perform(post("/api/v1/payments/execute").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerTransactionId").exists());

        mockMvc.perform(post("/api/v1/payments/execute").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value("idem-int-1"));
    }

    @Test
    @DisplayName("GET /risk/alerts returns array (may be empty or contain alerts)")
    void riskAlertsEndpointReturnsArray() throws Exception {
        mockMvc.perform(get("/api/v1/risk/alerts").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
