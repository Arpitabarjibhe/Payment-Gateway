package com.chronos.gateway.controller;

import com.chronos.gateway.dto.ConfirmPaymentRequest;
import com.chronos.gateway.dto.CreatePaymentRequest;
import com.chronos.gateway.dto.RefundRequest;
import com.chronos.gateway.model.PaymentIntent;
import com.chronos.gateway.service.IdempotencyService;
import com.chronos.gateway.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/payments")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public static final String DEFAULT_MERCHANT = "merch_default";

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Merchant-Id", required = false, defaultValue = DEFAULT_MERCHANT) String merchantId) {

        String payloadHash = computeRequestHash(request);

        // Single atomic transaction: payment settlement + idempotency resolution commit together
        PaymentService.PaymentResult result = paymentService.processIdempotentPayment(request, merchantId, idempotencyKey, payloadHash);

        if (result.isReplayed()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("Idempotent-Replayed", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.cachedJson());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.payment());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentIntent> getPayment(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Merchant-Id", required = false, defaultValue = DEFAULT_MERCHANT) String merchantId) {
        return ResponseEntity.ok(paymentService.getPayment(id, merchantId));
    }

    @GetMapping
    public ResponseEntity<List<PaymentIntent>> listPayments(
            @RequestHeader(value = "X-Merchant-Id", required = false, defaultValue = DEFAULT_MERCHANT) String merchantId) {
        return ResponseEntity.ok(paymentService.listPayments(merchantId));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentIntent> confirmPayment(
            @PathVariable("id") String id,
            @RequestBody(required = false) ConfirmPaymentRequest request,
            @RequestHeader(value = "X-Merchant-Id", required = false, defaultValue = DEFAULT_MERCHANT) String merchantId) {
        return ResponseEntity.ok(paymentService.confirmPayment(id, request, merchantId));
    }

    @PostMapping("/{id}/refunds")
    public ResponseEntity<?> refundPayment(
            @PathVariable("id") String id,
            @RequestBody(required = false) RefundRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Merchant-Id", required = false, defaultValue = DEFAULT_MERCHANT) String merchantId) {

        String payloadHash = computeRequestHash(request != null ? request : "{}");

        PaymentService.RefundResult result = paymentService.processIdempotentRefund(id, request, merchantId, idempotencyKey, payloadHash);

        if (result.isReplayed()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("Idempotent-Replayed", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.cachedJson());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.refund());
    }

    private String computeRequestHash(Object request) {
        try {
            return idempotencyService.computePayloadHash(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request for idempotency hashing", e);
        }
    }
}
