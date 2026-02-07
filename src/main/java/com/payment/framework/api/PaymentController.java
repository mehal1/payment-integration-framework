package com.payment.framework.api;

import com.payment.framework.core.PaymentOrchestrator;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.messaging.PaymentEventProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for submitting payments through the framework. All requests are
 * idempotent when the same idempotencyKey is sent; events are published
 * to Kafka for audit and downstream processing.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Submit and query payment transactions")
public class PaymentController {

    private final PaymentOrchestrator orchestrator;
    private final PaymentEventProducer eventProducer;

    @PostMapping("/execute")
    @Operation(summary = "Execute payment", description = "Submit a payment. Use idempotencyKey for safe retries.")
    public ResponseEntity<PaymentResponseDto> execute(@Valid @RequestBody PaymentRequestDto dto) {
        String correlationId = dto.getCorrelationId() != null ? dto.getCorrelationId() : UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey(dto.getIdempotencyKey())
                .providerType(dto.getProviderType())
                .amount(dto.getAmount())
                .currencyCode(dto.getCurrencyCode())
                .merchantReference(dto.getMerchantReference())
                .customerId(dto.getCustomerId())
                .providerPayload(dto.getProviderPayload())
                .correlationId(correlationId)
                .build();

        eventProducer.publishRequested(request);
        PaymentResult result = orchestrator.execute(request);
        
        if (result == null) {
            log.error("PaymentOrchestrator.execute() returned null for idempotencyKey={}", request.getIdempotencyKey());
            throw new IllegalStateException("Payment execution returned null result");
        }
        
        log.debug("Payment execution completed: idempotencyKey={}, status={}, providerTransactionId={}", 
                result.getIdempotencyKey(), result.getStatus(), result.getProviderTransactionId());
        
        eventProducer.publishResult(request, result);

        return ResponseEntity.ok(PaymentResponseDto.from(result));
    }
}
