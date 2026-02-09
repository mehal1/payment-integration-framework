# Local Testing Guide

This guide covers how to set up and test the Payment Integration Framework locally.

## Prerequisites

- Java 17+
- Docker Desktop (for Kafka and Redis)
- Maven 3.6+

## Quick Start

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- Kafka (port 9092)
- Zookeeper (port 2181)
- Redis (port 6379)

### 2. Build and Run

```bash
./mvnw clean install
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`

### 3. Verify Health

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{
  "status": "UP",
  "components": {
    "kafka": {"status": "UP"},
    "redis": {"status": "UP"},
    "circuitBreakers": {"status": "UP"}
  }
}
```

### 4. Test Payment Execution

```bash
curl -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "test-2",
    "providerType": "MOCK",
    "amount": 100.50,
    "currencyCode": "USD",
    "merchantReference": "order-123"
  }'
```

### 5. View Risk Alerts

**Option A: Trigger alerts** (via payment events - uses ML scoring if ML service is enabled, otherwise falls back to rule-based scoring):
```bash
# Trigger multiple failing payments quickly to trigger velocity alerts
for i in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"risk-test-$i\",\"providerType\":\"MOCK\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"merchant-1\"}" > /dev/null
done

# Wait a few seconds for risk engine to process
sleep 3

# View alerts (check alert summary to see if ML or rules were used)
curl http://localhost:8080/api/v1/risk/alerts?limit=10
```

**Option B: View via Swagger UI**:
Open http://localhost:8080/swagger-ui/index.html and use the `/api/v1/risk/alerts` endpoint

### 6. View API Documentation

Open Swagger UI: http://localhost:8080/swagger-ui/index.html

## Testing

### Project 1: Payment Integration Framework Tests

#### Unit Tests (No Docker Required)

```bash
# Run all unit tests
./mvnw test

# Run specific Project 1 tests
./mvnw test -Dtest=PaymentControllerTest
```

**Coverage:**
- `PaymentController` - REST API endpoints, validation, response format
- `PaymentOrchestrator` - PSP adapter routing, idempotency, circuit breakers
- `IdempotencyService` - Redis operations (mocked)

### Project 2: Risk & Fraud Detection Tests

#### Unit Tests (No Docker Required)

```bash
# Run all unit tests
./mvnw test

# Run specific tests
./mvnw test -Dtest=RiskEngineTest
```

#### Manual Testing: Risk Scenarios

**Test 1: High Velocity Alert** (Multiple transactions in short time)
```bash
# Trigger 10+ payments in quick succession
for i in {1..12}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"velocity-test-$i\",\"providerType\":\"MOCK\",\"amount\":100,\"currencyCode\":\"USD\",\"merchantReference\":\"merchant-1\"}" > /dev/null
done

# Wait for risk engine to process
sleep 3

# Check for alerts
curl http://localhost:8080/api/v1/risk/alerts?limit=10
```

**Test 2: High Failure Rate Alert** (Multiple failures)
```bash
# Trigger multiple failing payments (amount >= 999999 triggers failure in MockPSPAdapter)
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"failure-test-$i\",\"providerType\":\"MOCK\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"merchant-2\"}" > /dev/null
done

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=10
```

**Test 3: Unusual Amount Alert** (Amount significantly higher than average)
```bash
# First, establish a baseline with normal amounts
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"baseline-$i\",\"providerType\":\"MOCK\",\"amount\":100,\"currencyCode\":\"USD\",\"merchantReference\":\"merchant-3\"}" > /dev/null
done

# Then trigger an unusually high amount (2x+ average)
curl -s -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"unusual-amount","providerType":"MOCK","amount":500,"currencyCode":"USD","merchantReference":"merchant-3"}'

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=10
```

**Test 4: Cross-PSP Fraud Detection** (Distributed failure pattern)
```bash
# Simulate fraudster trying multiple PSPs (in real scenario, these would be different PSPs)
# All events are aggregated by merchantReference, detecting cross-PSP patterns
merchant="cross-psp-fraud-123"

# Simulate failures across "different PSPs" (using same MOCK provider for demo)
for i in {1..3}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"psp1-fail-$i\",\"providerType\":\"MOCK\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
done

for i in {1..2}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"psp2-fail-$i\",\"providerType\":\"MOCK\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
done

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {entityId, failureRate, riskScore, signalTypes}'
# Should show aggregated failure rate across all "PSPs" for the merchant
```

**Test 5: ML Integration** (If ML service is running)
```bash
# Ensure ML service is running on port 5001
# Check if ML scoring is enabled in application.yaml:
# payment.risk.ml.enabled: true

# Trigger payments and check if alerts show "ML" in summary
curl -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"ml-test","providerType":"MOCK","amount":999999,"currencyCode":"USD","merchantReference":"merchant-1"}'

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0].summary'
# Should show "ML" in summary if ML service responded
```

#### Webhook Testing

Test webhook delivery for risk alerts:

```bash
# Run automated webhook test script
./test-webhook.sh
```

The script will:
1. Prompt for your webhook.site URL (get one from https://webhook.site)
2. Register the webhook
3. Trigger a demo alert
4. Verify webhook delivery

**Manual testing:**
```bash
# 1. Enable webhooks in application.yaml: payment.risk.webhook.enabled=true
# 2. Get webhook URL from https://webhook.site
# 3. Register webhook
curl -X POST http://localhost:8080/api/v1/risk/webhooks \
  -H "Content-Type: application/json" \
  -d '{"entityId": "test-merchant", "webhookUrl": "https://webhook.site/YOUR-UNIQUE-ID"}'

# 4. Trigger alert with matching merchantRef
curl -X POST "http://localhost:8080/api/v1/risk/demo/trigger?merchantRef=test-merchant"

# 5. Check webhook.site for the POST request
```

### Integration Tests (Docker Required)

```bash
# Ensure Docker is running
./mvnw test -DincludeTags=integration
```

**What it tests:**
- End-to-end payment execution flow
- Redis idempotency with Testcontainers Redis instance
- Uses Embedded Kafka for event infrastructure (full Kafka → risk evaluation flow not verified in this test)

## Common Local Testing Commands

### Clear Redis Cache

```bash
# Using Docker
docker exec -it payment-integration-framework-redis-1 redis-cli FLUSHALL
```

### View Kafka Topics

```bash
# List topics
docker exec -it payment-integration-framework-kafka-1 kafka-topics --bootstrap-server localhost:9092 --list

# Consume messages from payment-events topic
docker exec -it payment-integration-framework-kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic payment-events --from-beginning
```

### Stop Infrastructure

```bash
docker-compose down
```

### View Logs

```bash
# Application logs
tail -f logs/application.log

# Docker logs
docker-compose logs -f kafka redis
```
