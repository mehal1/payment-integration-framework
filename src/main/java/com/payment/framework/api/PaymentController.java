package com.payment.framework.api;

import com.payment.framework.core.PaymentOrchestrator;
import com.payment.framework.core.RefundOrchestrator;
import com.payment.framework.core.RequestVelocityService;
import com.payment.framework.domain.PaymentRequest;
import com.payment.framework.domain.PaymentResult;
import com.payment.framework.domain.RefundRequest;
import com.payment.framework.domain.RefundResult;
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
 * REST API for payment and refund operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Submit and query payment transactions")
public class PaymentController {

    private final PaymentOrchestrator orchestrator;
    private final RefundOrchestrator refundOrchestrator;
    private final PaymentEventProducer eventProducer;
    private final PaymentPersistenceService persistenceService;
    private final RequestVelocityService requestVelocityService;

    @PostMapping("/execute")
    @Operation(
            summary = "Execute payment",
            description = "Submit a payment with a universal payment token (from the merchant's vault, e.g. VGS). "
                    + "Send the token in paymentMethodId; the framework routes to the best PSP and resolves the token for the chosen provider. "
                    + "Use idempotencyKey for safe retries. On success returns 200 with status SUCCESS/CAPTURED/PENDING; "
                    + "on provider decline returns 200 with status FAILED and failureCode/message. "
                    + "Failure codes (when status=FAILED): MOCK_STRIPE_DECLINED, MOCK_ADYEN_DECLINED, MOCK_WALLET_DECLINED, MOCK_AFTERPAY_DECLINED, MOCK_DECLINED, or provider-specific.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment processed. Check body.status: SUCCESS/CAPTURED/PENDING or FAILED (with failureCode/message).",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaymentResponseDto.class))),
            @ApiResponse(responseCode = "429", description = "Velocity exceeded: too many requests from this email/IP in the last 60s. Body has failureCode=VELOCITY_EXCEEDED.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaymentResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed (invalid request body) or bad request. Body: { \"error\": \"VALIDATION_FAILED\"|\"BAD_REQUEST\", \"details\"|\"message\": ... }"),
            @ApiResponse(responseCode = "503", description = "No PSP available (all down or circuit open). Body: { \"error\": \"NO_PSP_AVAILABLE\", \"message\": \"...\" }. Retry later."),
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
        
        persistenceService.persistTransaction(request, result);
        eventProducer.publishResult(request, result);

        return ResponseEntity.ok(PaymentResponseDto.from(result));
    }

    @PostMapping("/refund")
    @Operation(
            summary = "Refund a payment",
            description = "Refund a previously successful payment. Supports full and partial refunds. " +
                    "Use idempotencyKey for safe retries. On success returns 200 with status SUCCESS or PENDING; " +
                    "on failure returns 200 with status FAILED and failureCode/message.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refund processed. Check body.status: SUCCESS/PENDING or FAILED (with failureCode/message).",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefundResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or payment not found/not refundable. Body: { \"error\": \"VALIDATION_FAILED\"|\"BAD_REQUEST\", \"message\": ... }"),
            @ApiResponse(responseCode = "500", description = "Internal error. Body: { \"error\": \"INTERNAL_ERROR\", \"message\": \"...\" }")
    })
    public ResponseEntity<RefundResponseDto> refund(@Valid @RequestBody RefundRequestDto dto) {
        String correlationId = UUID.randomUUID().toString();
        
        RefundRequest request = RefundRequest.builder()
                .idempotencyKey(dto.getIdempotencyKey())
                .paymentIdempotencyKey(dto.getPaymentIdempotencyKey())
                .amount(dto.getAmount())
                .currencyCode(dto.getCurrencyCode())
                .reason(dto.getReason())
                .merchantReference(dto.getMerchantReference())
                .correlationId(correlationId)
                .build();

        try {
            RefundResult result = refundOrchestrator.execute(request);
            
            if (result == null) {
                log.error("RefundOrchestrator.execute() returned null for refundIdempotencyKey={}", 
                        request.getIdempotencyKey());
                throw new IllegalStateException("Refund execution returned null result");
            }
            
            log.debug("Refund execution completed: refundIdempotencyKey={}, status={}, providerRefundId={}", 
                    result.getIdempotencyKey(), result.getStatus(), result.getProviderRefundId());
            
            return ResponseEntity.ok(RefundResponseDto.from(result));
            
        } catch (IllegalArgumentException e) {
            log.warn("Refund validation failed: refundIdempotencyKey={}, error={}", 
                    request.getIdempotencyKey(), e.getMessage());
            return ResponseEntity.badRequest().body(RefundResponseDto.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .paymentIdempotencyKey(request.getPaymentIdempotencyKey())
                    .status(com.payment.framework.domain.RefundStatus.FAILED)
                    .failureCode("VALIDATION_FAILED")
                    .message(e.getMessage())
                    .timestamp(java.time.Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("Refund execution failed: refundIdempotencyKey={}", request.getIdempotencyKey(), e);
            throw e;
        }
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
