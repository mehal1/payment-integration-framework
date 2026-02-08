package com.payment.framework.core;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;

/**
 * What every PSP adapter needs to implement.
 * Takes our standard request, talks to Stripe/PayPal/etc (Payment Service Providers), and gives us back a standard result.
 */
public interface PSPAdapter {

    PaymentProviderType getProviderType();

    /**
     * Name of this PSP adapter (like "StripeAdapter" or "PayPalAdapter").
     * Used to track failures separately so one broken PSP doesn't affect others.
     */
    default String getPSPAdapterName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Actually charge the customer.
     * @param request what to charge
     * @return what happened (success or failure)
     */
    PaymentResult execute(PaymentRequest request);

    /**
     * Is this PSP working? Used to skip broken ones.
     */
    default boolean isHealthy() {
        return true;
    }
}
