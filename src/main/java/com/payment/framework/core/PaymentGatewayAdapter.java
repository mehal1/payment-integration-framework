package com.payment.framework.core;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;

/**
 * Standard interface for all payment gateway integrations. Implementing this
 * contract ensures:
 * <ul>
 *   <li>Consistent request/response handling for orchestration and retries</li>
 *   <li>Pluggable gateways without changing core flow</li>
 *   <li>Unified compliance and audit event emission</li>
 * </ul>
 * Adapters are responsible for mapping {@link PaymentRequest} to payment gateway APIs
 * (Stripe, Adyen, PayPal, etc.) and normalizing responses to {@link PaymentResult}.
 * The framework wraps calls with idempotency, circuit breaker, and retry.
 */
public interface PaymentGatewayAdapter {

    /**
     * Payment gateway type this adapter handles. Used by the orchestrator to route requests.
     */
    PaymentProviderType getProviderType();

    /**
     * Execute a payment (authorize/capture or equivalent). Implementations must
     * be idempotent with respect to {@link PaymentRequest#getIdempotencyKey()}
     * when the framework delegates idempotency; otherwise they should enforce
     * idempotency themselves.
     *
     * @param request canonical payment request
     * @return normalized result (never null)
     */
    PaymentResult execute(PaymentRequest request);

    /**
     * Check if this adapter is healthy (e.g. payment gateway API reachable). Used by
     * health indicators and circuit breaker.
     */
    default boolean isHealthy() {
        return true;
    }
}
