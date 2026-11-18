package com.payments.gateway;

import com.payments.gateway.dto.AccountBalanceResponse;
import com.payments.gateway.dto.CreatePaymentRequest;
import com.payments.gateway.dto.RefundRequest;
import com.payments.gateway.model.PaymentIntent;
import com.payments.gateway.model.PaymentStatus;
import com.payments.gateway.model.Refund;
import com.payments.gateway.repository.IdempotencyRepository;
import com.payments.gateway.repository.LedgerEntryRepository;
import com.payments.gateway.repository.PaymentIntentRepository;
import com.payments.gateway.service.CardSimulator;
import com.payments.gateway.service.LedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Integration Tests - Payments, Idempotency & Ledger Lifecycle")
public class PaymentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @SpyBean
    private CardSimulator cardSimulator;

    @Test
    @DisplayName("Positive: End-to-end charge card and settle balanced ledger entries")
    void testSuccessfulPaymentSettlement() throws Exception {
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(5000L) // $50.00
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();

        MvcResult result = mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amount").value(5000))
                .andReturn();

        PaymentIntent payment = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentIntent.class);
        assertThat(payment.getId()).startsWith("pi_");

        // Verify ledger invariant: Assets == Liabilities + Revenue
        AccountBalanceResponse cash = ledgerService.getAccountBalance(ledgerService.getOrCreateCustomerCashAccount("usd"));
        long totalMerchantPending = ledgerService.getTotalMerchantPendingBalance("usd");
        AccountBalanceResponse fees = ledgerService.getAccountBalance(ledgerService.getOrCreatePlatformFeesAccount("usd"));

        assertThat(cash.getSettledBalance()).isGreaterThanOrEqualTo(5000L);
        assertThat(cash.getSettledBalance()).isEqualTo(totalMerchantPending + fees.getSettledBalance());
    }

    @Test
    @DisplayName("Positive: Repeated request with identical Idempotency-Key replays cached response")
    void testIdempotentReplay() throws Exception {
        String idempotencyKey = "key_replay_" + System.currentTimeMillis();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(2500L) // $25.00
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();

        String payload = objectMapper.writeValueAsString(request);

        // First call: Processed
        MvcResult firstResult = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentIntent firstPayment = objectMapper.readValue(firstResult.getResponse().getContentAsString(), PaymentIntent.class);

        // Second call: Replayed
        MvcResult secondResult = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        PaymentIntent secondPayment = objectMapper.readValue(secondResult.getResponse().getContentAsString(), PaymentIntent.class);
        assertThat(firstPayment.getId()).isEqualTo(secondPayment.getId());
    }

    @Test
    @DisplayName("Negative: Reusing Idempotency-Key with altered payload returns 400 Bad Request")
    void testIdempotencyPayloadConflict() throws Exception {
        String idempotencyKey = "key_conflict_" + System.currentTimeMillis();

        CreatePaymentRequest initialRequest = CreatePaymentRequest.builder()
                .amount(1000L)
                .currency("usd")
                .confirm(false)
                .build();

        mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialRequest)))
                .andExpect(status().isCreated());

        CreatePaymentRequest alteredRequest = CreatePaymentRequest.builder()
                .amount(9999L) // Altered amount!
                .currency("usd")
                .confirm(false)
                .build();

        mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(alteredRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    }

    @Test
    @DisplayName("Negative: Insufficient funds test card (0002) marks payment FAILED without ledger postings")
    void testDeclinedCard() throws Exception {
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(3000L)
                .currency("usd")
                .cardNumber("4000000000000002")
                .confirm(true)
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_code").value("insufficient_funds"));
    }

    
@Test
@DisplayName("Concurrency Stress: 10 parallel requests with same Idempotency-Key return one payment and nine replays")
void testConcurrentIdempotencyStorm() throws Exception {
    int threads = 10;
    String sharedKey = "storm_key_" + System.currentTimeMillis();

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threads);

    CreatePaymentRequest request = CreatePaymentRequest.builder()
            .amount(1500L)
            .currency("usd")
            .cardNumber("4242424242424242")
            .confirm(true)
            .build();

    String payload = objectMapper.writeValueAsString(request);

    List<MvcResult> results = Collections.synchronizedList(new ArrayList<>());
    List<Exception> failures = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threads; i++) {
        executor.submit(() -> {
            try {
                latch.await();

                MvcResult result = mockMvc.perform(post("/v1/payments")
                                .header("Idempotency-Key", sharedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();

                results.add(result);

            } catch (Exception e) {
                failures.add(e);
            } finally {
                doneLatch.countDown();
            }
        });
    }

    latch.countDown();
    doneLatch.await();
    executor.shutdown();

    // Every request must complete without an exception.
    assertThat(failures).isEmpty();
    assertThat(results).hasSize(threads);

    int primaryCount = 0;
    int replayCount = 0;
    List<String> paymentIds = new ArrayList<>();

    for (MvcResult result : results) {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        boolean isReplayed = "true".equals(
                result.getResponse().getHeader("Idempotent-Replayed")
        );

        if (isReplayed) {
            replayCount++;
        } else {
            primaryCount++;
        }

        PaymentIntent payment = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                PaymentIntent.class
        );

        paymentIds.add(payment.getId());
    }

    // Exactly one original execution and nine successful replays.
    assertThat(primaryCount).isEqualTo(1);
    assertThat(replayCount).isEqualTo(9);

    // All ten responses must contain the same payment ID.
    assertThat(paymentIds).hasSize(10);
    assertThat(paymentIds.stream().distinct()).hasSize(1);

    // Exactly one payment should exist for this idempotency key.
    assertThat(idempotencyRepository
            .findByKeyAndMerchantId(sharedKey, "merch_default"))
            .isPresent();
}


        @Test
@DisplayName("Refund: Only one refund is allowed per payment")
void testOnlyOneRefundPerPayment() throws Exception {

    // Create and confirm a $100 payment
    CreatePaymentRequest createReq = CreatePaymentRequest.builder()
            .amount(10000L)
            .currency("usd")
            .cardNumber("4242424242424242")
            .confirm(true)
            .build();

    MvcResult createRes = mockMvc.perform(post("/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createReq)))
            .andExpect(status().isCreated())
            .andReturn();

    PaymentIntent payment = objectMapper.readValue(
            createRes.getResponse().getContentAsString(),
            PaymentIntent.class
    );

    // First refund: Full $100 refund — succeeds
    mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new RefundRequest(10000L))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value(10000))
            .andExpect(jsonPath("$.status").value("succeeded"));

    // Second refund: Rejected because payment was already refunded
    mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new RefundRequest(6000L))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.message")
                    .value("This payment has already been refunded."));

    // Third refund: Also rejected
    mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new RefundRequest(1000L))))
            .andExpect(status().isUnprocessableEntity());
}

    @Test
    @DisplayName("Negative: Refunding a failed payment is rejected with 422")
    void testRefundFailedPaymentRejected() throws Exception {
        CreatePaymentRequest failReq = CreatePaymentRequest.builder()
                .amount(2000L)
                .currency("usd")
                .cardNumber("4000000000000002") // will fail
                .confirm(true)
                .build();

        MvcResult res = mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failReq)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentIntent failedPayment = objectMapper.readValue(res.getResponse().getContentAsString(), PaymentIntent.class);

        mockMvc.perform(post("/v1/payments/" + failedPayment.getId() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefundRequest(1000L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Only succeeded payments can be refunded."));
    }

    @Test
    @DisplayName("Negative: Input validation errors (amount too small, blank currency)")
    void testInputValidationErrors() throws Exception {
        // Amount < 50 cents
        CreatePaymentRequest tinyAmount = CreatePaymentRequest.builder()
                .amount(20L)
                .currency("usd")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tinyAmount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("parameter_invalid"));

        // Missing currency
        CreatePaymentRequest missingCurrency = CreatePaymentRequest.builder()
                .amount(1000L)
                .currency("")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingCurrency)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("parameter_invalid"));
    }

    @Test
    @DisplayName("Negative: Querying non-existent payment returns 404 Not Found")
    void testGetNonExistentPaymentReturns404() throws Exception {
        mockMvc.perform(get("/v1/payments/pi_does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("resource_missing"));
    }

    @Test
    @DisplayName("System Integrity: Reconciliation audit endpoint reports balanced status")
    void testReconciliationAuditEndpoint() throws Exception {
        mockMvc.perform(get("/v1/ledger/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_balanced").value(true))
                .andExpect(jsonPath("$.discrepancy").value(0));
    }

    @Test
@DisplayName("Reconciliation: Global balances include pending accounts from multiple merchants")
void testGlobalReconciliationAcrossMerchants() throws Exception {

    CreatePaymentRequest request = CreatePaymentRequest.builder()
            .amount(5000L)
            .currency("usd")
            .cardNumber("4242424242424242")
            .confirm(true)
            .build();

    String payload = objectMapper.writeValueAsString(request);

    // Create a payment for Merchant A
    mockMvc.perform(post("/v1/payments")
                    .header("X-Merchant-Id", "reconciliation_merchant_a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isCreated());

    // Create a payment for Merchant B
    mockMvc.perform(post("/v1/payments")
                    .header("X-Merchant-Id", "reconciliation_merchant_b")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isCreated());

    // Reconcile all merchants globally
    mockMvc.perform(get("/v1/ledger/reconciliation")
                    .param("currency", "usd"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("global"))
            .andExpect(jsonPath("$.is_balanced").value(true))
            .andExpect(jsonPath("$.discrepancy").value(0))
            .andExpect(jsonPath("$.assets_customer_cash").value(10000L));
}

    @Test
    @DisplayName("Multi-Tenant Isolation: Merchant A and Merchant B using identical key do not collide")
    void testMultiMerchantIdempotencyIsolation() throws Exception {
        String sharedKey = "tenant_iso_key_" + System.currentTimeMillis();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(2000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        String payload = objectMapper.writeValueAsString(request);

        // Merchant A succeeds
        MvcResult resA = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", sharedKey)
                        .header("X-Merchant-Id", "merchant_apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        PaymentIntent paymentA = objectMapper.readValue(resA.getResponse().getContentAsString(), PaymentIntent.class);

        // Merchant B with exact same key succeeds independently (not a 409 collision!)
        MvcResult resB = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", sharedKey)
                        .header("X-Merchant-Id", "merchant_banana")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        PaymentIntent paymentB = objectMapper.readValue(resB.getResponse().getContentAsString(), PaymentIntent.class);

        assertThat(paymentA.getId()).isNotEqualTo(paymentB.getId());
        assertThat(paymentA.getMerchantId()).isEqualTo("merchant_apple");
        assertThat(paymentB.getMerchantId()).isEqualTo("merchant_banana");
    }

    @Test
@DisplayName("Refund idempotency: Same key cannot replay refund across different payments")
void testRefundIdempotencyIncludesPaymentId() throws Exception {

    String merchantId = "merchant_refund_fingerprint";
    String idempotencyKey = "refund_fp_" + System.currentTimeMillis();

    CreatePaymentRequest createRequest = CreatePaymentRequest.builder()
            .amount(5000L)
            .currency("usd")
            .cardNumber("4242424242424242")
            .confirm(true)
            .build();

    String paymentPayload = objectMapper.writeValueAsString(createRequest);

    // Create Payment A
    MvcResult paymentAResult = mockMvc.perform(post("/v1/payments")
                    .header("X-Merchant-Id", merchantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(paymentPayload))
            .andExpect(status().isCreated())
            .andReturn();

    PaymentIntent paymentA = objectMapper.readValue(
            paymentAResult.getResponse().getContentAsString(),
            PaymentIntent.class
    );

    // Create Payment B
    MvcResult paymentBResult = mockMvc.perform(post("/v1/payments")
                    .header("X-Merchant-Id", merchantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(paymentPayload))
            .andExpect(status().isCreated())
            .andReturn();

    PaymentIntent paymentB = objectMapper.readValue(
            paymentBResult.getResponse().getContentAsString(),
            PaymentIntent.class
    );

    assertThat(paymentA.getId()).isNotEqualTo(paymentB.getId());

    // Both payments are $50, so refund the full $50.
    RefundRequest refundRequest = new RefundRequest(5000L);
    String refundPayload = objectMapper.writeValueAsString(refundRequest);

    // First refund: Payment A
    mockMvc.perform(post("/v1/payments/" + paymentA.getId() + "/refunds")
                    .header("X-Merchant-Id", merchantId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refundPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.payment_intent_id").value(paymentA.getId()));

    // Second refund: Payment B, same key.
    // Must return 400 instead of replaying Payment A's refund.
    mockMvc.perform(post("/v1/payments/" + paymentB.getId() + "/refunds")
                    .header("X-Merchant-Id", merchantId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refundPayload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));

    // Retry original request: Payment A's refund should replay successfully.
    mockMvc.perform(post("/v1/payments/" + paymentA.getId() + "/refunds")
                    .header("X-Merchant-Id", merchantId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refundPayload))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"));
}
         
    @Test
    @DisplayName("Atomicity: Idempotency record is RESOLVED (not PROCESSING) after a successful payment commit")
    void testIdempotencyRecordResolvedAtomically() throws Exception {
        String idempotencyKey = "atomic_key_" + System.currentTimeMillis();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(1000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();

        String payload = objectMapper.writeValueAsString(request);

        // First call: should succeed and atomically commit payment + RESOLVED idempotency record
        mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // Replay: if the record is RESOLVED (not PROCESSING), this returns 201 with Idempotent-Replayed: true
        // If the transaction had not committed atomically, the record would still be PROCESSING → 409
        mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    @DisplayName("Concurrency: Simultaneous refund race condition is blocked by pessimistic row locking")
    void testConcurrentRefundRaceProtection() throws Exception {
        // 1. Create a $50.00 payment
        CreatePaymentRequest createReq = CreatePaymentRequest.builder()
                .amount(5000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();

        MvcResult createRes = mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        PaymentIntent payment = objectMapper.readValue(createRes.getResponse().getContentAsString(), PaymentIntent.class);

        // 2. Fire 2 concurrent threads trying to refund $50 each (total $100 on a $50 payment)
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    MvcResult refRes = mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new RefundRequest(5000L))))
                            .andReturn();

                    int code = refRes.getResponse().getStatus();
                    if (code == 201) successCount.incrementAndGet();
                    if (code == 422) rejectedCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        done.await();
        executor.shutdown();

        // Exactly 1 refund succeeded; the competing refund was blocked by pessimistic lock and rejected with 422
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: 10 simultaneous payments for a brand-new merchant all return 201 and reconciliation balances")
    void testConcurrentNewMerchantAccountCreation() throws Exception {
        String newMerchantId = "merch_new_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(1000L)
                .currency("eur")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        String payload = objectMapper.writeValueAsString(request);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    MvcResult res = mockMvc.perform(post("/v1/payments")
                                    .header("X-Merchant-Id", newMerchantId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        latch.countDown();
        done.await();
        executor.shutdown();

        // All 10 payments must succeed (each uses a different request, no idempotency key)
        assertThat(successCount.get()).isEqualTo(10);

        // Reconciliation for this merchant must be balanced
        mockMvc.perform(get("/v1/ledger/reconciliation")
                        .param("merchant_id", newMerchantId)
                        .param("currency", "eur"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_balanced").value(true));
    }

    @Test
    @DisplayName("Rollback: Failure after idempotency insert rolls back idempotency, payment, and ledger rows; retry succeeds")
    void testAtomicRollbackOnProcessingFailure() throws Exception {
        String idempotencyKey = "rollback_key_" + System.currentTimeMillis();
        String merchantId = "merch_rollback";
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(5000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        String payload = objectMapper.writeValueAsString(request);

        // Make cardSimulator throw after idempotency record is inserted in checkOrLock
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated processing crash"))
                .when(cardSimulator).evaluateCard(org.mockito.ArgumentMatchers.any());

        try {
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", idempotencyKey)
                            .header("X-Merchant-Id", merchantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isInternalServerError());
        } finally {
            org.mockito.Mockito.reset(cardSimulator);
        }

        // Assert NO idempotency row exists
        assertThat(idempotencyRepository.findAll().stream().anyMatch(r -> idempotencyKey.equals(r.getKey()))).isFalse();

        // Assert NO payment rows exist for this merchant
        assertThat(paymentIntentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)).isEmpty();

        // Retry with the exact same idempotency key succeeds exactly once
        MvcResult retryResult = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn();

        PaymentIntent payment = objectMapper.readValue(retryResult.getResponse().getContentAsString(), PaymentIntent.class);

        // Assert exactly 1 payment row and 3 ledger entries exist
        assertThat(paymentIntentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)).hasSize(1);
        assertThat(ledgerEntryRepository.findByTransactionId(payment.getId())).hasSize(3);
        assertThat(idempotencyRepository.findAll().stream().anyMatch(r -> idempotencyKey.equals(r.getKey()))).isTrue();
    }
}
