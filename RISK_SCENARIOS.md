# Risk Scenarios – Manual Testing

Manual curl-based scenarios for risk and fraud detection. Use these when you want to run individual tests instead of the full script. Ensure the app is running on `http://localhost:8080` and Kafka is up.

See [LOCAL_TESTING.md](LOCAL_TESTING.md) for the comprehensive script and setup.

---

## Test 1: High Velocity Alert

Multiple transactions in short time.

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

---

## Test 2: High Failure Rate Alert

Multiple failures (MockPSP fails when amount >= 999999).

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

---

## Test 3: Unusual Amount Alert

Amount significantly higher than average (2x+ baseline).

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

---

## Test 4: Cross-PSP Fraud Detection

Distributed failure pattern across mock Stripe and Adyen (same merchant).

```bash
# Use multiple mock PSP adapters (MockStripeAdapter and MockAdyenAdapter) for same payment type (CARD).
# Both Stripe and Adyen support card payments; events are aggregated by merchantReference.
#
# Note: In this framework we decide which PSP to use at runtime (routing/failover). So "failures
# across Stripe and Adyen" reflects our routing, not necessarily a fraudster trying multiple
# gateways. The signal is "high failure rate for this merchant"; the PSP mix can be a false-
# positive driver if we overweight "spread across PSPs." True per-card cross-PSP attack detection
# would require card identity (e.g. token/fingerprint) and aggregation by card.
merchant="cross-psp-fraud-123"

# Failures via "Stripe" mock adapter (3) - specify adapter via providerPayload
for i in {1..3}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"psp-stripe-fail-$i\",\"providerType\":\"CARD\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\",\"providerPayload\":{\"testAdapterName\":\"MockStripeAdapter\"}}" > /dev/null
done

# Failures via "Adyen" mock adapter (2) - specify adapter via providerPayload
for i in {1..2}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"psp-adyen-fail-$i\",\"providerType\":\"CARD\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\",\"providerPayload\":{\"testAdapterName\":\"MockAdyenAdapter\"}}" > /dev/null
done

sleep 5
curl http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {entityId, riskScore, signalTypes}'
# Should show one alert for the merchant with aggregated failure rate across both PSP adapters
```

---

## Test 4b: BNPL, Wallet & Email Cross-Type Fraud

The script `./test-all-risk-fraud-alerts-scenarios.sh` runs **Section 4** for these; you can run the same flows manually.

### BNPL (Afterpay mock) – high failure rate

```bash
# MockAfterpayAdapter fails when amount >= 888888; no card data in response
merchant="bnpl-manual-test"
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"bnpl-$i\",\"providerType\":\"BNPL\",\"amount\":888888,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
done
sleep 5
curl http://localhost:8080/api/v1/risk/alerts?limit=10 | jq '.[] | select(.entityId == "'"$merchant"'") | {entityId, signalTypes}'
```

### Wallet mock – high failure rate

```bash
# MockWalletAdapter fails when amount >= 777777; returns DPAN-style card data
merchant="wallet-manual-test"
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"wallet-$i\",\"providerType\":\"WALLET\",\"amount\":777777,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
done
sleep 5
curl http://localhost:8080/api/v1/risk/alerts?limit=10 | jq '.[] | select(.entityId == "'"$merchant"'") | {entityId, signalTypes}'
```

### Email cross-type – same email across CARD, BNPL, WALLET

```bash
# Same email across payment types to trigger HIGH_EMAIL_FAILURE_RATE / HIGH_EMAIL_VELOCITY
email="cross-type@example.com"
merchant="email-cross-type-test"
for i in {1..2}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"email-card-$i\",\"providerType\":\"CARD\",\"amount\":999999,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\",\"email\":\"$email\"}" > /dev/null
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"email-bnpl-$i\",\"providerType\":\"BNPL\",\"amount\":888888,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\",\"email\":\"$email\"}" > /dev/null
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"email-wallet-$i\",\"providerType\":\"WALLET\",\"amount\":777777,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\",\"email\":\"$email\"}" > /dev/null
done
sleep 5
curl http://localhost:8080/api/v1/risk/alerts?limit=20 | jq '.[] | select(.signalTypes | index("HIGH_EMAIL_FAILURE_RATE") or index("HIGH_EMAIL_VELOCITY")) | {entityId, entityType, signalTypes}'
```

---

## Test 5: ML Integration

Only when ML service is running on port 5001 and `payment.risk.ml.enabled: true` in `application.yaml`.

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

---

## Test 6: Card Testing Pattern Detection

Increasing amounts (10 → 20 → 30 → 40). Exercises: `increasingAmountCount`, `amountTrend`, `avgTimeGapSeconds`.

```bash
merchant="card-test-merchant"

for i in {1..4}; do
  amount=$((10 * i))
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"card-test-$i\",\"providerType\":\"MOCK\",\"amount\":$amount,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
  sleep 0.5  # Small gap between transactions
done

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {summary, riskScore, signalTypes}'
# Summary should mention "card testing pattern detected" if pattern is detected
```

---

## Test 7: Rapid-Fire Transaction Detection

Transactions &lt; 5 seconds apart. Exercises: `secondsSinceLastTransaction`, `avgTimeGapSeconds`.

```bash
merchant="rapid-fire-merchant"

for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"rapid-$i\",\"providerType\":\"MOCK\",\"amount\":100,\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
  sleep 1  # 1 second gap (rapid-fire)
done

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0].summary'
# Summary should mention "rapid-fire transactions" if detected
```

---

## Test 8: Amount Variance Detection

Inconsistent amounts (high variance). Exercises: `amountVariance`, `minAmount`, `maxAmount`.

```bash
merchant="variance-test-merchant"

amounts=(10 500 20 1000 15 800)
for i in "${!amounts[@]}"; do
  curl -s -X POST http://localhost:8080/api/v1/payments/execute \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"variance-$i\",\"providerType\":\"MOCK\",\"amount\":${amounts[$i]},\"currencyCode\":\"USD\",\"merchantReference\":\"$merchant\"}" > /dev/null
  sleep 0.3
done

sleep 3
curl http://localhost:8080/api/v1/risk/alerts?limit=1 | jq '.[0] | {summary, riskScore}'
# High variance may contribute to risk score
```
