# PostgreSQL Setup Guide

This guide explains how to set up and use PostgreSQL persistence in the Payment Integration Framework.

## Overview

PostgreSQL is now integrated to persist:
- **Payment Transactions**: All payment attempts and their results
- **Payment Events**: Complete audit trail of payment lifecycle events
- **Risk Alerts**: Fraud and risk alerts with lifecycle management

## Prerequisites

- Docker and Docker Compose installed
- Java 17+
- Maven 3.6+

## Step 1: Start PostgreSQL with Docker Compose

The framework includes PostgreSQL in `docker-compose.yml`. Start all services:

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL** on port `5432`
- **Kafka** on port `9092`
- **Redis** on port `6379`

Verify PostgreSQL is running:
```bash
docker-compose ps
```

You should see `postgres` with status `healthy`.

## Step 2: Database Configuration

The database is configured in `src/main/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/payment_framework
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### Environment Variables (Optional)

You can override database settings using environment variables:

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=payment_framework
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
```

## Step 3: Database Migration

**Flyway** automatically runs migrations on application startup. The initial migration (`V1__create_payment_tables.sql`) creates:

- `payment_transactions` - Payment transaction records
- `payment_events` - Audit trail of all payment events
- `risk_alerts` - Risk and fraud alerts
- `risk_alert_signals` - Alert signal types (many-to-many)
- `risk_alert_related_events` - Related event IDs (many-to-many)

No manual SQL execution needed - Flyway handles it automatically.

## Step 4: Build and Run the Application

```bash
mvn clean install
mvn spring-boot:run
```

On startup, you should see:
- Flyway migration logs showing tables being created
- Database connection established
- Application ready on port `8080`

## Step 5: Verify Database Setup

### Option 1: Using psql (PostgreSQL CLI)

```bash
docker exec -it paymentintegrationframework-postgres-1 psql -U postgres -d payment_framework
```

Then run:
```sql
\dt                    -- List all tables
SELECT COUNT(*) FROM payment_transactions;
SELECT COUNT(*) FROM payment_events;
SELECT COUNT(*) FROM risk_alerts;
```

### Option 2: Using Docker Compose

```bash
docker-compose exec postgres psql -U postgres -d payment_framework -c "\dt"
```

## Step 6: Test Persistence

### Submit a Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/execute \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "test-123",
    "providerType": "CARD",
    "amount": 100.00,
    "currencyCode": "USD",
    "merchantReference": "merchant-001",
    "customerId": "customer-001"
  }'
```

### Check Database

```bash
docker-compose exec postgres psql -U postgres -d payment_framework \
  -c "SELECT * FROM payment_transactions WHERE idempotency_key = 'test-123';"

docker-compose exec postgres psql -U postgres -d payment_framework \
  -c "SELECT event_type, amount, status FROM payment_events WHERE idempotency_key = 'test-123' ORDER BY timestamp;"
```

## Database Schema

### payment_transactions
Stores payment transaction records:
- `idempotency_key` (PK) - Unique identifier for idempotency
- `transaction_id` - Provider transaction ID
- `merchant_reference` - Merchant reference
- `customer_id` - Customer identifier
- `amount`, `currency_code` - Payment amount
- `provider_type` - Payment provider (CARD, WALLET, etc.)
- `status` - Transaction status (SUCCESS, FAILED, etc.)
- `created_at`, `updated_at` - Timestamps

### payment_events
Complete audit trail:
- `event_id` (PK) - Unique event identifier
- `idempotency_key` - Links to payment transaction
- `event_type` - PAYMENT_REQUESTED, PAYMENT_COMPLETED, PAYMENT_FAILED
- `timestamp` - Event timestamp
- All payment details (amount, status, provider info)

### risk_alerts
Risk and fraud alerts:
- `alert_id` (PK) - Unique alert identifier
- `entity_id` - Entity being flagged (merchant, customer, etc.)
- `risk_level` - HIGH, MEDIUM, LOW
- `risk_score` - 0.0-1.0 risk score
- `status` - NEW, ACKNOWLEDGED, INVESTIGATING, RESOLVED, etc.
- `summary`, `detailed_explanation` - Alert descriptions

## How Persistence Works

### Payment Transactions
- **When**: After payment execution completes
- **Where**: `PaymentController.execute()` calls `PaymentPersistenceService.persistTransaction()`
- **What**: Creates or updates transaction record

### Payment Events
- **When**: Every payment event published to Kafka
- **Where**: `PaymentEventConsumer.onPaymentEvent()` calls `PaymentPersistenceService.persistEvent()`
- **What**: Stores complete audit trail

### Risk Alerts
- **When**: When risk engine generates an alert
- **Where**: `PaymentEventConsumer` calls `RiskAlertPersistenceService.persistAlert()`
- **What**: Stores alert with all details

## Querying Data

### Find Transactions by Merchant

```sql
SELECT * FROM payment_transactions 
WHERE merchant_reference = 'merchant-001' 
ORDER BY created_at DESC;
```

### Find Failed Payments

```sql
SELECT * FROM payment_transactions 
WHERE status = 'FAILED' 
AND created_at >= NOW() - INTERVAL '24 hours';
```

### Find High-Risk Alerts

```sql
SELECT * FROM risk_alerts 
WHERE risk_level = 'HIGH' 
AND status != 'RESOLVED'
ORDER BY created_at DESC;
```

### Payment Event Timeline

```sql
SELECT event_type, timestamp, amount, status 
FROM payment_events 
WHERE idempotency_key = 'test-123'
ORDER BY timestamp;
```

## Troubleshooting

### Database Connection Failed

**Error**: `Connection refused` or `Connection timeout`

**Solution**:
1. Verify PostgreSQL is running: `docker-compose ps`
2. Check port 5432 is not in use: `lsof -i :5432`
3. Verify credentials in `application.yaml`

### Migration Failed

**Error**: `Migration failed` or `Table already exists`

**Solution**:
1. Check Flyway logs in application startup
2. If tables exist, set `spring.flyway.baseline-on-migrate=true` (already set)
3. Drop database and recreate: `docker-compose down -v` then `docker-compose up -d`

### JPA Validation Errors

**Error**: `Schema-validation: missing table` or `Schema-validation: missing column`

**Solution**:
1. Ensure Flyway migrations ran successfully
2. Check `src/main/resources/db/migration/V1__create_payment_tables.sql` matches entities
3. Verify `spring.jpa.hibernate.ddl-auto=validate` (not `create` or `update`)

## Next Steps

- **Add Query APIs**: Create REST endpoints to query transactions, events, and alerts
- **Add Reporting**: Build analytics queries for success rates, fraud patterns
- **Add Retention Policies**: Implement data archival for old transactions
- **Add Indexes**: Optimize queries based on access patterns
- **Add Backup**: Set up automated database backups

## Cleanup

To stop all services:
```bash
docker-compose down
```

To remove all data (including database):
```bash
docker-compose down -v
```
