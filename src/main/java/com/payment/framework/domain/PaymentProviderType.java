package com.payment.framework.domain;

/**
 * Types of payments we support (cards, wallets, BNPL, etc).
 */
public enum PaymentProviderType {
    CARD,
    BANK_TRANSFER,
    WALLET,
    BNPL,
    CRYPTO,
    MOCK
}
