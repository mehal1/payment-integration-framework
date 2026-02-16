package com.payment.framework.api;

import com.payment.framework.core.PaymentOrchestrator;
import com.payment.framework.core.RequestVelocityService;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.messaging.PaymentEventProducer;
import com.payment.framework.persistence.service.PaymentPersistenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
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
    private final PaymentPersistenceService persistenceService;
    private final RequestVelocityService requestVelocityService;

    @PostMapping("/execute")
    @Operation(
            summary = "Execute payment",
            description = "Submit a payment. Use idempotencyKey for safe retries. On success returns 200 with status SUCCESS/CAPTURED/PENDING; "
                    + "on provider decline returns 200 with status FAILED and failureCode/message. "
                    + "Failure codes (when status=FAILED): MOCK_STRIPE_DECLINED, MOCK_ADYEN_DECLINED, MOCK_WALLET_DECLINED, MOCK_AFTERPAY_DECLINED, MOCK_DECLINED, or provider-specific.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment processed. Check body.status: SUCCESS/CAPTURED/PENDING or FAILED (with failureCode/message).",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaymentResponseDto.class))),
            @ApiResponse(responseCode = "429", description = "Velocity exceeded: too many requests from this email/IP in the last 60s. Body has failureCode=VELOCITY_EXCEEDED.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaymentResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed (invalid request body) or bad request. Body: { \"error\": \"VALIDATION_FAILED\"|\"BAD_REQUEST\", \"details\"|\"message\": ... }"),
            @ApiResponse(responseCode = "503", description = "Recommended PSP unavailable (Method 1). Body: { \"error\": \"RECOMMENDED_PSP_UNAVAILABLE\", \"message\": \"...\" }. Call GET /api/v1/routing/recommend again, re-tokenize, and retry."),
            @ApiResponse(responseCode = "500", description = "Internal error. Body: { \"error\": \"INTERNAL_ERROR\", \"message\": \"...\" }")
    })
    public ResponseEntity<PaymentResponseDto> execute(@Valid @RequestBody PaymentRequestDto dto, HttpServletRequest httpRequest) {
        String correlationId = dto.getCorrelationId() != null ? dto.getCorrelationId() : UUID.randomUUID().toString();
        String clientIp = dto.getClientIp() != null && !dto.getClientIp().isBlank()
                ? dto.getClientIp()
                : (httpRequest != null ? getClientIp(httpRequest) : null);
        PaymentRequest request = PaymentRequest.builder()
                .idempotencyKey(dto.getIdempotencyKey())
                .providerType(dto.getProviderType())
                .amount(dto.getAmount())
                .currencyCode(dto.getCurrencyCode())
                .merchantReference(dto.getMerchantReference())
                .customerId(dto.getCustomerId())
                .email(dto.getEmail())
                .clientIp(clientIp)
                .paymentMethodId(dto.getPaymentMethodId())
                .providerPayload(dto.getProviderPayload())
                .correlationId(correlationId)
                .build();

        // Velocity check at ingestion: how many requests from this email/IP in last 60s
        RequestVelocityService.VelocitySnapshot velocity = requestVelocityService.recordAndCheck(request.getEmail(), clientIp);
        if (velocity.isOverThreshold()) {
            log.warn("Request velocity over threshold: idempotencyKey={} emailCount={} ipCount={}",
                    request.getIdempotencyKey(), velocity.getEmailCountLast60s(), velocity.getIpCountLast60s());
            return ResponseEntity.status(429).body(PaymentResponseDto.velocityExceeded(request.getIdempotencyKey()));
        }

        eventProducer.publishRequested(request);
        PaymentResult result = orchestrator.execute(request);
        
        if (result == null) {
            log.error("PaymentOrchestrator.execute() returned null for idempotencyKey={}", request.getIdempotencyKey());
            throw new IllegalStateException("Payment execution returned null result");
        }
        
        log.debug("Payment execution completed: idempotencyKey={}, status={}, providerTransactionId={}", 
                result.getIdempotencyKey(), result.getStatus(), result.getProviderTransactionId());
        
        // Persist transaction to database
        persistenceService.persistTransaction(request, result);
        
        eventProducer.publishResult(request, result);

        return ResponseEntity.ok(PaymentResponseDto.from(result));
    }

    private static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return request.getRemoteAddr();
    }
}
