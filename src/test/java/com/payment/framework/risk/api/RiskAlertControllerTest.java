package com.payment.framework.risk.api;

import com.payment.framework.risk.domain.RiskAlert;
import com.payment.framework.risk.domain.RiskLevel;
import com.payment.framework.risk.engine.RiskEngine;
import com.payment.framework.risk.features.TransactionWindowAggregator;
import com.payment.framework.risk.messaging.WebhookService;
import com.payment.framework.risk.store.RecentAlertsStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for RiskAlertController.
 */
@WebMvcTest(controllers = RiskAlertController.class)
class RiskAlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecentAlertsStore recentAlertsStore;

    @MockitoBean
    private RiskEngine riskEngine;

    @MockitoBean
    private TransactionWindowAggregator transactionWindowAggregator;

    @MockitoBean
    private WebhookService webhookService;

    @Test
    void listAlertsReturnsOkAndArray() throws Exception {
        RiskAlert alert = RiskAlert.builder()
                .alertId("alert-1")
                .timestamp(Instant.now())
                .level(RiskLevel.MEDIUM)
                .signalTypes(Set.of(com.payment.framework.risk.domain.RiskSignalType.HIGH_FAILURE_RATE))
                .riskScore(0.6)
                .entityId("merchant-1")
                .entityType("MERCHANT")
                .relatedEventIds(List.of("e1"))
                .amount(new BigDecimal("100"))
                .currencyCode("USD")
                .summary("Risk score 0.60: HIGH_FAILURE_RATE")
                .build();
        when(recentAlertsStore.getRecent(50)).thenReturn(List.of(alert));

        mockMvc.perform(get("/api/v1/risk/alerts").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].alertId").value("alert-1"))
                .andExpect(jsonPath("$[0].level").value("MEDIUM"))
                .andExpect(jsonPath("$[0].riskScore").value(0.6));
    }

    @Test
    void triggerDemoAlertsReturnsOkWithBody() throws Exception {
        when(riskEngine.evaluate(any())).thenReturn(Optional.empty());
        when(recentAlertsStore.getRecent(100)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/risk/demo/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.alertsNow").isNumber())
                .andExpect(jsonPath("$.merchantReference").value(org.hamcrest.Matchers.startsWith("demo-merchant-")));
    }
}
