-- Refunds Table
CREATE TABLE refunds (
    refund_idempotency_key VARCHAR(255) PRIMARY KEY,
    payment_idempotency_key VARCHAR(255) NOT NULL,
    provider_refund_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    failure_code VARCHAR(50),
    failure_message VARCHAR(1000),
    reason VARCHAR(500),
    merchant_reference VARCHAR(255),
    correlation_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE INDEX idx_refund_payment_key ON refunds(payment_idempotency_key);
CREATE INDEX idx_refund_merchant_ref ON refunds(merchant_reference);
CREATE INDEX idx_refund_created_at ON refunds(created_at);
CREATE INDEX idx_refund_status ON refunds(status);
