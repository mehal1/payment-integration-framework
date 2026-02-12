package com.payment.framework.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

/**
 * Everything we need to know to process a payment.
 */
@Value
@Builder
public class PaymentRequest {

    @NotBlank
    String idempotencyKey;

    @NotNull
    PaymentProviderType providerType;

    @NotNull
    @DecimalMin("0.01")
    BigDecimal amount;

    @NotBlank
    String currencyCode;

    String merchantReference;
    String customerId;
    /** Optional: Customer email. Used for ingestion velocity and Email→PAR linking for fraud. */
    String email;
    /** Optional: Client IP. Used for velocity check and fraud. */
    String clientIp;
    /** Optional: PSP payment method id or token (e.g. Stripe pm_xxx, Adyen storedPaymentMethodId). Merchants send what their PSP gave them; used for card-level risk aggregation. */
    String paymentMethodId;
    Map<String, Object> providerPayload;
    String correlationId;

    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }
}
