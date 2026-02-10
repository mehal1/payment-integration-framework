-- Payment Integration Framework - Database Schema
-- Flyway migration script

-- Payment Transactions Table
CREATE TABLE payment_transactions (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    transaction_id VARCHAR(255) UNIQUE,
    merchant_reference VARCHAR(255),
    customer_id VARCHAR(255),
    amount NUMERIC(19, 2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    provider_type VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    failure_code VARCHAR(50),
    failure_message VARCHAR(1000),
    correlation_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE INDEX idx_payment_merchant_ref ON payment_transactions(merchant_reference);
CREATE INDEX idx_payment_customer_id ON payment_transactions(customer_id);
CREATE INDEX idx_payment_created_at ON payment_transactions(created_at);
CREATE INDEX idx_payment_status ON payment_transactions(status);

-- Payment Events Table (Audit Trail)
CREATE TABLE payment_events (
    event_id VARCHAR(255) PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),
    event_type VARCHAR(50) NOT NULL,
    provider_type VARCHAR(100),
    provider_transaction_id VARCHAR(255),
    status VARCHAR(50),
    amount NUMERIC(19, 2),
    currency_code VARCHAR(3),
    failure_code VARCHAR(50),
    message VARCHAR(1000),
    merchant_reference VARCHAR(255),
    customer_id VARCHAR(255),
    timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_event_idempotency_key ON payment_events(idempotency_key);
CREATE INDEX idx_event_merchant_ref ON payment_events(merchant_reference);
CREATE INDEX idx_event_timestamp ON payment_events(timestamp);
CREATE INDEX idx_event_type ON payment_events(event_type);

-- Risk Alerts Table
CREATE TABLE risk_alerts (
    alert_id VARCHAR(255) PRIMARY KEY,
    entity_id VARCHAR(255) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    risk_score DOUBLE PRECISION NOT NULL,
    amount NUMERIC(19, 2),
    currency_code VARCHAR(3),
    summary VARCHAR(500),
    detailed_explanation TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'NEW',
    assigned_to VARCHAR(255),
    resolution_notes TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    resolved_at TIMESTAMP
);

CREATE INDEX idx_alert_entity_id ON risk_alerts(entity_id);
CREATE INDEX idx_alert_status ON risk_alerts(status);
CREATE INDEX idx_alert_created_at ON risk_alerts(created_at);
CREATE INDEX idx_alert_risk_level ON risk_alerts(risk_level);

-- Risk Alert Signals (Many-to-Many relationship)
CREATE TABLE risk_alert_signals (
    alert_id VARCHAR(255) NOT NULL,
    signal_type VARCHAR(50) NOT NULL,
    PRIMARY KEY (alert_id, signal_type),
    FOREIGN KEY (alert_id) REFERENCES risk_alerts(alert_id) ON DELETE CASCADE
);

-- Risk Alert Related Events (Many-to-Many relationship)
CREATE TABLE risk_alert_related_events (
    alert_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (alert_id, event_id),
    FOREIGN KEY (alert_id) REFERENCES risk_alerts(alert_id) ON DELETE CASCADE
);
