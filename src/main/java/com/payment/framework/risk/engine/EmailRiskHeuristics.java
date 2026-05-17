package com.payment.framework.risk.engine;

import java.util.Locale;
import java.util.Set;

public final class EmailRiskHeuristics {

    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "mailinator.com",
            "guerrillamail.com",
            "yopmail.com",
            "tempmail.com",
            "10minutemail.com",
            "throwaway.email",
            "trashmail.com",
            "getnada.com",
            "maildrop.cc"
    );

    private EmailRiskHeuristics() {
    }

    public static boolean isDisposableEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return DISPOSABLE_DOMAINS.contains(domain);
    }
}
