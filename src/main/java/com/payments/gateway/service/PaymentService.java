
package com.payments.gateway.service;

import com.payments.gateway.dto.ConfirmPaymentRequest;
import com.payments.gateway.dto.CreatePaymentRequest;
import com.payments.gateway.dto.RefundRequest;
import com.payments.gateway.exception.InvalidPaymentStateException;
import com.payments.gateway.exception.ResourceNotFoundException;
import com.payments.gateway.model.IdempotencyRecord;
import com.payments.gateway.model.PaymentIntent;
import com.payments.gateway.model.PaymentStatus;
import com.payments.gateway.model.Refund;
import com.payments.gateway.repository.PaymentIntentRepository;
import com.payments.gateway.repository.RefundRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final CardSimulator cardSimulator;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public record PaymentResult(PaymentIntent payment, boolean isReplayed, String cachedJson) {}
    public record RefundResult(Refund refund, boolean isReplayed, String cachedJson) {}

    /**
     * Atomically executes payment settlement and idempotency key resolution
     * within a single database transaction.
     */
    @Transactional
    public PaymentResult processIdempotentPayment(
            CreatePaymentRequest request,
            String merchantId,
            String idempotencyKey,
            String payloadHash) {

        // 1. Check or acquire idempotency lock
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> cached =
                    idempotencyService.checkOrLock(idempotencyKey, merchantId, payloadHash);

            if (cached.isPresent()) {
                return new PaymentResult(null, true, cached.get().getResponseBody());
            }
        }

        // 2. Create PaymentIntent and settle ledger
        String paymentId = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        PaymentIntent payment = PaymentIntent.builder()
                .id(paymentId)
                .merchantId(merchantId)
                .amount(request.getAmount())
                .currency(request.getCurrency().toLowerCase())
                .status(PaymentStatus.REQUIRES_CONFIRMATION)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        if (Boolean.TRUE.equals(request.getConfirm())) {
            executeConfirmation(payment, request.getCardNumber(), merchantId);
        }

        PaymentIntent savedPayment = paymentIntentRepository.save(payment);

        // 3. Atomically resolve idempotency record
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String responseJson = objectMapper.writeValueAsString(savedPayment);
                idempotencyService.resolve(
                        idempotencyKey,
                        merchantId,
                        HttpStatus.CREATED.value(),
                        responseJson
                );
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize payment response", e);
            }
        }

        return new PaymentResult(savedPayment, false, null);
    }

    /**
     * Executes exactly one full refund per payment.
     * Partial refunds and subsequent refund requests are rejected.
     * Refund settlement and idempotency resolution are atomic.
     */
    @Transactional
    public RefundResult processIdempotentRefund(
            String paymentIntentId,
            RefundRequest request,
            String merchantId,
            String idempotencyKey,
            String payloadHash) {

        // 1. Check or acquire idempotency lock
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> cached =
                    idempotencyService.checkOrLock(idempotencyKey, merchantId, payloadHash);

            if (cached.isPresent()) {
                return new RefundResult(null, true, cached.get().getResponseBody());
            }
        }

        // 2. Lock payment to prevent concurrent refund requests
        PaymentIntent payment = paymentIntentRepository
                .findByIdAndMerchantIdForUpdate(paymentIntentId, merchantId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("No such payment_intent: " + paymentIntentId));

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new InvalidPaymentStateException(
                    "Only succeeded payments can be refunded."
            );
        }

        // 3. Reject if this payment already has a refund
        List<Refund> existingRefunds =
                refundRepository.findByPaymentIntentId(paymentIntentId);

        if (!existingRefunds.isEmpty()) {
            throw new InvalidPaymentStateException(
                    "This payment has already been refunded."
            );
        }

        // 4. Only full refunds are supported
        long requestedAmount = (request != null && request.getAmount() != null)
                ? request.getAmount()
                : payment.getAmount();

        if (requestedAmount != payment.getAmount()) {
            throw new InvalidPaymentStateException(
                    "Only full refunds are supported. Refund amount must equal the original payment amount."
            );
        }

        // 5. Create refund
        String refundId = "re_" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16);

        Refund refund = Refund.builder()
                .id(refundId)
                .paymentIntentId(paymentIntentId)
                .amount(requestedAmount)
                .currency(payment.getCurrency())
                .status("succeeded")
                .createdAt(Instant.now())
                .build();

        Refund savedRefund = refundRepository.save(refund);

        // 6. Record reverse double-entry ledger entries
        ledgerService.recordRefundSettlement(
                refundId,
                requestedAmount,
                payment.getCurrency(),
                merchantId
        );

        // 7. Atomically resolve idempotency record
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String responseJson = objectMapper.writeValueAsString(savedRefund);
                idempotencyService.resolve(
                        idempotencyKey,
                        merchantId,
                        HttpStatus.CREATED.value(),
                        responseJson
                );
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize refund response", e);
            }
        }

        return new RefundResult(savedRefund, false, null);
    }

    @Transactional
    public PaymentIntent confirmPayment(
            String paymentIntentId,
            ConfirmPaymentRequest request,
            String merchantId) {

        PaymentIntent payment = paymentIntentRepository
                .findByIdAndMerchantIdForUpdate(paymentIntentId, merchantId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("No such payment_intent: " + paymentIntentId));

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            throw new InvalidPaymentStateException(
                    "PaymentIntent has already succeeded and cannot be confirmed again."
            );
        }

        if (payment.getStatus() == PaymentStatus.CANCELED
                || payment.getStatus() == PaymentStatus.FAILED) {
            throw new InvalidPaymentStateException(
                    "PaymentIntent in state " + payment.getStatus() + " cannot be confirmed."
            );
        }

        String cardNumber = request != null ? request.getCardNumber() : null;
        executeConfirmation(payment, cardNumber, merchantId);

        return paymentIntentRepository.save(payment);
    }

    private void executeConfirmation(
            PaymentIntent payment,
            String cardNumber,
            String merchantId) {

        payment.setStatus(PaymentStatus.PROCESSING);

        CardSimulator.CardResult result = cardSimulator.evaluateCard(cardNumber);
        payment.setCardLast4(result.cardLast4());

        if (result.success()) {
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setFailureCode(null);

            ledgerService.recordPaymentSettlement(
                    payment.getId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    merchantId
            );
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(result.failureCode());

            log.warn(
                    "Payment {} failed with code: {}",
                    payment.getId(),
                    result.failureCode()
            );
        }
    }

    @Transactional(readOnly = true)
    public PaymentIntent getPayment(String paymentIntentId, String merchantId) {
        return paymentIntentRepository.findById(paymentIntentId)
                .filter(p -> p.getMerchantId().equals(merchantId))
                .orElseThrow(() ->
                        new ResourceNotFoundException("No such payment_intent: " + paymentIntentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentIntent> listPayments(String merchantId) {
        return paymentIntentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }
}
