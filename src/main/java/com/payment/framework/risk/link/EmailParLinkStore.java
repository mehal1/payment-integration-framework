package com.payment.framework.risk.link;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At response: "Email X is now linked to PAR Z."
 * PAR = Payment Account Reference (stable across tokens/cards). Used for cross-method fraud.
 */
@Slf4j
@Component
public class EmailParLinkStore {

    /** email -> set of PARs seen with this email */
    private final Map<String, Set<String>> emailToPars = new ConcurrentHashMap<>();
    /** par -> set of emails seen with this PAR */
    private final Map<String, Set<String>> parToEmails = new ConcurrentHashMap<>();

    public void link(String email, String par) {
        if ((email == null || email.isBlank()) || (par == null || par.isBlank())) return;
        emailToPars.computeIfAbsent(email, k -> ConcurrentHashMap.newKeySet()).add(par);
        parToEmails.computeIfAbsent(par, k -> ConcurrentHashMap.newKeySet()).add(email);
        log.debug("Linked email to PAR (store updated)");
    }

    public Set<String> getParsForEmail(String email) {
        if (email == null || email.isBlank()) return Set.of();
        Set<String> pars = emailToPars.get(email);
        return pars != null ? Collections.unmodifiableSet(pars) : Set.of();
    }

    public Set<String> getEmailsForPar(String par) {
        if (par == null || par.isBlank()) return Set.of();
        Set<String> emails = parToEmails.get(par);
        return emails != null ? Collections.unmodifiableSet(emails) : Set.of();
    }
}
