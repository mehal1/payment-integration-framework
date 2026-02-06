package com.payment.framework;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test: verifies that the Payment Framework application context loads.
 * Requires Kafka and Redis (e.g. docker-compose up -d). Run with:
 *   mvn test -DincludeTags=integration
 * Or remove @Disabled and start infrastructure first.
 */
@Tag("integration")
@Disabled("Requires Kafka and Redis; run with docker-compose up -d then remove @Disabled or use -DincludeTags=integration")
@SpringBootTest(classes = PaymentFrameworkApplication.class)
@TestPropertySource(properties = {
		"spring.kafka.bootstrap-servers=localhost:9092",
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=6379"
})
class PaymentFrameworkApplicationTests {

	@Test
	void contextLoads() {
	}
}
