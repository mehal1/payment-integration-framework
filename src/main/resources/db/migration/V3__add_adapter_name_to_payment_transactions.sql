-- Add adapter_name column to track which PSP adapter processed each payment (used for refund routing)
ALTER TABLE payment_transactions ADD COLUMN adapter_name VARCHAR(255);
CREATE INDEX idx_payment_adapter_name ON payment_transactions(adapter_name);
