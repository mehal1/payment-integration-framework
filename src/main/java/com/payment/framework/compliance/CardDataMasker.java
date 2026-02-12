package com.payment.framework.compliance;

/**
 * Redacts card-related fields so they are safe to include in logs.
 * Do not log BIN or last4 in plain text; use these masks when logging payment events or results.
 */
public final class CardDataMasker {

    private static final String MASKED_BIN = "******";
    private static final String MASKED_LAST4 = "****";
    private static final String MASKED_PM = "pm_***";

    private CardDataMasker() {}

    /** Returns a safe-to-log value for BIN (e.g. "424242" -> "******"). */
    public static String maskBIN(String bin) {
        if (bin == null || bin.isBlank()) return null;
        return MASKED_BIN;
    }

    /** Returns a safe-to-log value for last4 (e.g. "1234" -> "****"). */
    public static String maskLast4(String last4) {
        if (last4 == null || last4.isBlank()) return null;
        return MASKED_LAST4;
    }

    /** Returns a safe-to-log value for payment method id (e.g. "pm_1ABC" -> "pm_***"). */
    public static String maskPaymentMethodId(String paymentMethodId) {
        if (paymentMethodId == null || paymentMethodId.isBlank()) return null;
        return MASKED_PM;
    }

    /** Returns a safe-to-log value for network token (do not log full token). */
    public static String maskNetworkToken(String networkToken) {
        if (networkToken == null || networkToken.isBlank()) return null;
        return "nt_***";
    }
}
