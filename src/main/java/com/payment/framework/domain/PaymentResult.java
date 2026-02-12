package com.payment.framework.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * What happened when we tried to charge someone. Standardized so all PSPs look the same.
 */
@Value
@Builder
@JsonDeserialize(builder = PaymentResult.PaymentResultBuilder.class)
@JsonPOJOBuilder(withPrefix = "")
public class PaymentResult {

    String idempotencyKey;
    String providerTransactionId;
    TransactionStatus status;
    BigDecimal amount;
    String currencyCode;
    String failureCode;
    String message;
    Instant timestamp;
    Map<String, Object> metadata;

    /** Card BIN (first 6–8 digits). Populated by adapters from PSP response; use with cardLast4 or networkToken for card identity. */
    String cardBin;
    /** Card last 4 digits. Populated by adapters from PSP response; use with cardBin or networkToken for card identity. */
    String cardLast4;
    /** Network token (e.g. DPAN from Visa VTS / Mastercard MDES) when PSP returns it. Best for cross-PSP card identity. */
    String networkToken;
    /** Payment Account Reference (Visa PAR / network). Stable across tokens/cards for same account; link email→PAR for fraud. */
    String par;
    /** Universal fingerprint: hash of card (e.g. hash(BIN+last4)). Generated at vault/routing when BIN+last4 available. */
    String cardFingerprint;

    public boolean isSuccess() {
        return status == TransactionStatus.SUCCESS || status == TransactionStatus.CAPTURED || status == TransactionStatus.PENDING;
    }

    public boolean isTerminalFailure() {
        return status == TransactionStatus.FAILED || status == TransactionStatus.CANCELLED;
    }
}
