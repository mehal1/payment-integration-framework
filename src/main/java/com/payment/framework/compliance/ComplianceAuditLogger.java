package com.payment.framework.compliance;

import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Logs payment attempts and outcomes for compliance and audit. In production,
 * this can be extended to write to a dedicated audit store (e.g. S3, audit DB)
 * or emit to a compliance-specific Kafka topic for retention and regulatory reporting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceAuditLogger {

    public void logRequest(PaymentRequest request) {
        log.info("[AUDIT] PAYMENT_REQUEST idempotencyKey={} provider={} amount={} currency={} correlationId={}",
                request.getIdempotencyKey(),
                request.getProviderType(),
                request.getAmount(),
                request.getCurrencyCode(),
                request.getCorrelationId());
    }

    public void logResult(PaymentRequest request, PaymentResult result) {
        log.info("[AUDIT] PAYMENT_RESULT idempotencyKey={} providerTxId={} status={} failureCode={}",
                result.getIdempotencyKey(),
                result.getProviderTransactionId(),
                result.getStatus(),
                result.getFailureCode());
    }
}
