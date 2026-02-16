package com.payment.framework.api;

import com.payment.framework.domain.PaymentProviderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * REST API request body for initiating a payment.
 * The framework expects a universal payment token from the merchant (e.g. from a vault like VGS).
 */
@Data
public class PaymentRequestDto {

    /** Client-generated idempotency key (e.g. UUID). Required. */
    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    @NotNull
    private PaymentProviderType providerType;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    @NotBlank
    private String currencyCode;

    private String merchantReference;
    private String customerId;
    private String email;
    private String clientIp;
    private String paymentMethodId;
    private Map<String, Object> providerPayload;
    private String correlationId;
}
