package com.payment.framework.core;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;

/**
 * Standard interface for all payment provider integrations. Implementing this
 * contract ensures:
 * <ul>
 *   <li>Consistent request/response handling for orchestration and retries</li>
 *   <li>Pluggable providers without changing core flow</li>
 *   <li>Unified compliance and audit event emission</li>
 * </ul>
 * Adapters are responsible for mapping {@link PaymentRequest} to provider APIs
 * and normalizing responses to {@link PaymentResult}. The framework wraps calls
 * with idempotency, circuit breaker, and retry.
 */
public interface PaymentGateway {

    /**
     * Provider type this adapter handles. Used by the orchestrator to route requests.
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
     * Check if this gateway is healthy (e.g. provider API reachable). Used by
     * health indicators and circuit breaker.
     */
    default boolean isHealthy() {
        return true;
    }
}
