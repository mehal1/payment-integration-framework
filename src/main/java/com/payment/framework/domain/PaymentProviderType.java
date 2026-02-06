package com.payment.framework.domain;

/**
 * Supported payment provider types. The framework routes requests to the
 * appropriate adapter based on configuration and request context.
 * New providers are added by implementing {@link com.payment.framework.core.PaymentGateway}
 * and registering under one of these types.
 */
public enum PaymentProviderType {
    /** Card networks (Visa, MC) via a single gateway (e.g. Stripe, Adyen). */
    CARD,
    /** Bank transfers / ACH / SEPA. */
    BANK_TRANSFER,
    /** Digital wallets (Apple Pay, Google Pay, etc.). */
    WALLET,
    /** BNPL (Buy Now Pay Later) providers. */
    BNPL,
    /** Crypto or stablecoin payments. */
    CRYPTO,
    /** Fallback / mock for testing. */
    MOCK
}
