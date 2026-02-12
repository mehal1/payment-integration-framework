package com.payment.framework.api;

import com.payment.framework.domain.PaymentProviderType;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.TransactionStatus;
import com.payment.framework.core.PaymentOrchestrator;
import com.payment.framework.core.RequestVelocityService;
import com.payment.framework.messaging.PaymentEventProducer;
import com.payment.framework.persistence.service.PaymentPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for PaymentController using MockMvc.
 */
@WebMvcTest(controllers = PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentOrchestrator orchestrator;

    @MockitoBean
    private PaymentEventProducer eventProducer;

    @MockitoBean
    private PaymentPersistenceService persistenceService;

    @MockitoBean
    private RequestVelocityService requestVelocityService;

    @Test
    void executeReturnsOkAndResultFromOrchestrator() throws Exception {
        when(requestVelocityService.recordAndCheck(any(), any()))
                .thenReturn(new RequestVelocityService.VelocitySnapshot(0, 0, false));
        PaymentResult result = PaymentResult.builder()
                .idempotencyKey("key-1")
                .providerTransactionId("tx-1")
                .status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("100.50"))
                .currencyCode("USD")
                .timestamp(Instant.now())
                .build();
        when(orchestrator.execute(any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/payments/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "key-1",
                                  "providerType": "MOCK",
                                  "amount": 100.50,
                                  "currencyCode": "USD",
                                  "merchantReference": "order-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value("key-1"))
                .andExpect(jsonPath("$.providerTransactionId").value("tx-1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(100.5))
                .andExpect(jsonPath("$.currencyCode").value("USD"));

        verify(eventProducer).publishRequested(any());
        verify(eventProducer).publishResult(any(), any());
    }

    @Test
    void executeReturnsBadRequestWhenValidationFails() throws Exception {
        when(requestVelocityService.recordAndCheck(any(), any()))
                .thenReturn(new RequestVelocityService.VelocitySnapshot(0, 0, false));
        mockMvc.perform(post("/api/v1/payments/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "",
                                  "providerType": "MOCK",
                                  "amount": 100,
                                  "currencyCode": "USD"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
