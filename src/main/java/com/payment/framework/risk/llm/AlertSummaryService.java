package com.payment.framework.risk.llm;

import com.payment.framework.risk.domain.RiskAlert;

import java.util.Optional;

/**
 * Optional: generate human-readable alert summaries. Default implementation
 * returns empty (no LLM call). Implement with OpenAI/Anthropic/etc. for
 * natural-language explanations (e.g. "High failure rate for merchant X
 * in the last 5 minutes suggests possible card testing or PSP issues.").
 * <p>
 * LLMs are used here for explanation/UX, not for the core risk score (which
 * uses rules/ML on structured data).
 */
public interface AlertSummaryService {

    /**
     * Optionally generate a short explanation for the alert. Return empty to skip.
     */
    Optional<String> generateSummary(RiskAlert alert);
}
