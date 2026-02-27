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

### 5. Test Refund

**Full refund** (omit `amount` to refund the entire payment):
```bash
curl -X POST http://localhost:8080/api/v1/payments/refund \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "refund-1",
    "paymentIdempotencyKey": "test-2",
    "currencyCode": "USD",
    "reason": "Customer requested"
  }'
```

**Partial refunds** (make a fresh payment, then refund in parts):
```bash
# Payment of $100
curl -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"pay-partial-1","providerType":"CARD","amount":100.00,"currencyCode":"USD","merchantReference":"order-partial"}'

# Partial refund: $30
curl -X POST http://localhost:8080/api/v1/payments/refund \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"refund-p1","paymentIdempotencyKey":"pay-partial-1","amount":30.00,"currencyCode":"USD","reason":"Partial return"}'

# Partial refund: $50
curl -X POST http://localhost:8080/api/v1/payments/refund \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"refund-p2","paymentIdempotencyKey":"pay-partial-1","amount":50.00,"currencyCode":"USD","reason":"Second return"}'

# This should FAIL — only $20 remaining
curl -X POST http://localhost:8080/api/v1/payments/refund \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"refund-p3","paymentIdempotencyKey":"pay-partial-1","amount":30.00,"currencyCode":"USD","reason":"Should fail"}'
```

Expected: First two succeed (status `SUCCESS`), third returns status `FAILED` with `failureCode: REFUND_LIMIT_EXCEEDED` and message showing "Already refunded: 80.00, Remaining: 20.00".

### 6. View Risk Alerts

**Option A: Trigger alerts** (via payment events - uses ML scoring if ML service is enabled, otherwise falls back to rule-based scoring):
```bash
for i in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"risk-test-$i\",\"providerType\":\"MOCK\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"merchant-1\"}" > /dev/null
done

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=10
```

**Option B: View via Swagger UI**:
Open http://localhost:8080/swagger-ui/index.html and use the `/api/v1/risk/alerts` endpoint

### 7. View API Documentation

Open Swagger UI: http://localhost:8080/swagger-ui/index.html

## Testing

### Project 1: Payment Integration Framework Tests

#### Unit Tests (No Docker Required)

```bash
./mvnw test
./mvnw test -Dtest=PaymentControllerTest
```

**Coverage:**
- `PaymentController` - REST API endpoints, validation, response format
- `PaymentOrchestrator` - PSP adapter routing, idempotency, circuit breakers
- `IdempotencyService` - Redis operations (mocked)

### Project 2: Risk & Fraud Detection Tests

#### Unit Tests (No Docker Required)

```bash
./mvnw test
./mvnw test -Dtest=RiskEngineTest
```

#### Comprehensive Test Script

**Quick Start: Run All Risk/Fraud Tests**
```bash
./test-all-risk-fraud-alerts-scenarios.sh
```

This script tests:
- High velocity alerts
- High failure rate alerts
- Unusual amount detection
- Cross-PSP fraud detection
- Card testing patterns (enhanced features)
- Rapid-fire transactions (enhanced features)
- Amount variance detection (enhanced features)
- ML integration verification
- Demo endpoint with all features
- **BNPL (Afterpay mock)** – failures via `providerType: BNPL` (amount ≥ 888888)
- **Wallet mock** – failures via `providerType: WALLET` (amount ≥ 777777)
- **Email cross-type fraud** – same email across card + BNPL + wallet; HIGH_EMAIL_FAILURE_RATE / HIGH_EMAIL_VELOCITY

#### Manual Testing: Risk Scenarios

Manual curl-based risk scenarios (high velocity, failure rate, unusual amount, cross-PSP, BNPL, wallet, email cross-type, ML, card testing, rapid-fire, variance) are in a separate file:

**[Risk Scenarios – Manual Testing](RISK_SCENARIOS.md)** — Tests 1–8 and 4b with copy-paste curl examples.

#### Testing Enhanced Feature Engineering

The enhanced feature engineering adds 9 new features for better fraud detection. Here's how to test them:

**1. Verify Unit Tests Pass**
```bash
./mvnw test -Dtest=TransactionWindowAggregatorTest#getFeaturesComputesEnhancedFeatures
./mvnw test -Dtest=TransactionWindowAggregatorTest
```

**2. Enable Debug Logging to See Feature Values**
Add to `application.yaml`:
```yaml
logging:
  level:
    com.payment.framework.risk: DEBUG
```

Then check logs when processing payments:
```bash
tail -f logs/application.log | grep -E "(Risk evaluation|Using ML score|features)"
```

**3. Test Card Testing Pattern** (Increasing Amounts)
```bash
merchant="card-test-$(date +%s)"

for i in {1..5}; do
  amount=$((10 * i))
  echo "Sending transaction $i: amount=$amount"
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{
      \"idempotencyKey\":\"card-test-$i\",
      \"providerType\":\"MOCK\",
      \"amount\":$amount,
      \"currencyCode\":\"USD\",
      \"merchantReference\":\"$merchant\"
    }" > /dev/null
  sleep 0.5
done

sleep 3
echo "Checking alerts..."
curl -s http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {
  summary,
  riskScore,
  signalTypes,
  entityId
}'
# Look for "card testing pattern detected" in summary
```

**4. Test Time-Based Features** (Hour of Day, Day of Week)
```bash
merchant="time-test-$(date +%s)"

curl -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d "{
    \"idempotencyKey\":\"time-test-1\",
    \"providerType\":\"MOCK\",
    \"amount\":100,
    \"currencyCode\":\"USD\",
    \"merchantReference\":\"$merchant\"
  }"

# Check application logs - should show hourOfDay (0-23) and dayOfWeek (0-6)
# ML service receives these features for time-based pattern detection
```

**5. Test Amount Variance** (Inconsistent Amounts)
```bash
merchant="variance-test-$(date +%s)"

amounts=(10 500 20 1000 15 800 5 2000)
for i in "${!amounts[@]}"; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{
      \"idempotencyKey\":\"var-$i\",
      \"providerType\":\"MOCK\",
      \"amount\":${amounts[$i]},
      \"currencyCode\":\"USD\",
      \"merchantReference\":\"$merchant\"
    }" > /dev/null
  sleep 0.2
done

sleep 3
curl -s http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0].summary'
```

**6. Test Rapid-Fire Transactions** (Small Time Gaps)
```bash
merchant="rapid-$(date +%s)"

for i in {1..8}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{
      \"idempotencyKey\":\"rapid-$i\",
      \"providerType\":\"MOCK\",
      \"amount\":50,
      \"currencyCode\":\"USD\",
      \"merchantReference\":\"$merchant\"
    }" > /dev/null
  sleep 0.5  # 500ms gap (very rapid)
done

sleep 3
curl -s http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {
  summary,
  riskScore
}'
# Summary should mention "rapid-fire transactions" if secondsSinceLastTransaction < 5
```

**7. Verify Features Sent to ML Service** (If ML Enabled)
```bash
curl -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey":"ml-feature-test",
    "providerType":"MOCK",
    "amount":100,
    "currencyCode":"USD",
    "merchantReference":"ml-test-merchant"
  }'

# Check ML service logs - should receive all 15 features:
# Original: totalCount, failureCount, failureRate, countLast1Min, avgAmount, maxAmount
# Enhanced: minAmount, hourOfDay, dayOfWeek, secondsSinceLastTransaction, 
#           amountVariance, amountTrend, increasingAmountCount, 
#           decreasingAmountCount, avgTimeGapSeconds
```

**8. Test Feature Computation with Demo Endpoint**
```bash
curl -X POST "http://localhost:8080/api/v1/risk/demo/trigger?merchantRef=feature-test"

sleep 2
curl -s http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0].summary'
```

**9. View Feature Values in Application Logs**
```bash
tail -f logs/application.log | grep -E "Risk evaluation|features"
```

**10. Integration Test: All Features Together**
```bash
merchant="comprehensive-$(date +%s)"

for i in {1..4}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"comp-$i\",\"providerType\":\"MOCK\",\"amount\":$((i*10)),\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
  sleep 0.3
done

for i in {5..8}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"comp-$i\",\"providerType\":\"MOCK\",\"amount\":100,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
  sleep 0.2
done

sleep 3
echo "Final alert summary:"
curl -s http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {
  summary,
  riskScore,
  signalTypes,
  level
}'
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
curl -X POST http://localhost:8080/api/v1/risk/webhooks \
  -H "Content-Type: application/json" \
  -d '{"entityId": "test-merchant", "webhookUrl": "https://webhook.site/YOUR-UNIQUE-ID"}'

curl -X POST "http://localhost:8080/api/v1/risk/demo/trigger?merchantRef=test-merchant"
```

### Integration Tests (Docker Required)

```bash
./mvnw test -DincludeTags=integration
```

**What it tests:**
- End-to-end payment execution flow
- Redis idempotency with Testcontainers Redis instance
- Uses Embedded Kafka for event infrastructure (full Kafka → risk evaluation flow not verified in this test)

## Common Local Testing Commands

### Clear Redis Cache

```bash
docker exec -it payment-integration-framework-redis-1 redis-cli FLUSHALL
```

### View Kafka Topics

```bash
docker exec -it payment-integration-framework-kafka-1 kafka-topics --bootstrap-server localhost:9092 --list
docker exec -it payment-integration-framework-kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic payment-events --from-beginning
```

### Stop Infrastructure

```bash
docker-compose down
```

### View Logs

```bash
tail -f logs/application.log
docker-compose logs -f kafka redis
```
