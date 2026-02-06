package com.payment.framework.risk.llm;

import com.payment.framework.risk.domain.RiskAlert;

import java.util.Optional;

/**
 * No-op implementation: no LLM call. Registered as default via RiskKafkaConfig.
 * Use a real implementation (e.g. OpenAI) when you want human-readable alert explanations.
 */
public class NoOpAlertSummaryService implements AlertSummaryService {

    @Override
    public Optional<String> generateSummary(RiskAlert alert) {
        return Optional.empty();
    }
}
