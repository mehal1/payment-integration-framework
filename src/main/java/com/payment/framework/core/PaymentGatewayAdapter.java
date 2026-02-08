package com.payment.framework.core;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;

/**
 * What every payment gateway adapter needs to implement.
 * Takes our standard request, talks to Stripe/PayPal/etc, and gives us back a standard result.
 */
public interface PaymentGatewayAdapter {

    PaymentProviderType getProviderType();

    /**
     * Name of this gateway (like "StripeGateway" or "PayPalAdapter").
     * Used to track failures separately so one broken gateway doesn't affect others.
     */
    default String getGatewayName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Actually charge the customer.
     * @param request what to charge
     * @return what happened (success or failure)
     */
    PaymentResult execute(PaymentRequest request);

    /**
     * Is this gateway working? Used to skip broken ones.
     */
    default boolean isHealthy() {
        return true;
    }
}
