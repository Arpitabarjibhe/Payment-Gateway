package com.chronos.gateway;

import com.chronos.gateway.dto.AccountBalanceResponse;
import com.chronos.gateway.dto.CreatePaymentRequest;
import com.chronos.gateway.dto.RefundRequest;
import com.chronos.gateway.model.PaymentIntent;
import com.chronos.gateway.model.PaymentStatus;
import com.chronos.gateway.model.Refund;
import com.chronos.gateway.service.LedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
        AccountBalanceResponse merchant = ledgerService.getAccountBalance(ledgerService.getOrCreateMerchantPendingAccount(LedgerService.DEFAULT_MERCHANT, "usd"));
        AccountBalanceResponse fees = ledgerService.getAccountBalance(ledgerService.getOrCreatePlatformFeesAccount("usd"));

        assertThat(cash.getSettledBalance()).isGreaterThanOrEqualTo(5000L);
        assertThat(cash.getSettledBalance()).isEqualTo(merchant.getSettledBalance() + fees.getSettledBalance());
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
    @DisplayName("Concurrency Stress: 10 parallel threads with same Idempotency-Key execute exactly 1 charge")
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

        AtomicInteger successOrReplayedCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<String> createdPaymentIds = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Synchronize all threads to fire simultaneously
                    MvcResult res = mockMvc.perform(post("/v1/payments")
                                    .header("Idempotency-Key", sharedKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();

                    int status = res.getResponse().getStatus();
                    if (status == 201) {
                        successOrReplayedCount.incrementAndGet();
                        PaymentIntent p = objectMapper.readValue(res.getResponse().getContentAsString(), PaymentIntent.class);
                        createdPaymentIds.add(p.getId());
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignored in concurrency test
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // Trigger all threads
        doneLatch.await();
        executor.shutdown();

        // Exactly one unique payment ID must have been created across all threads
        assertThat(createdPaymentIds).isNotEmpty();
        String canonicalId = createdPaymentIds.get(0);
        for (String id : createdPaymentIds) {
            assertThat(id).isEqualTo(canonicalId);
        }

        // Total successful/replayed + 409 in-flight collisions equals total attempts
        assertThat(successOrReplayedCount.get() + conflictCount.get()).isEqualTo(threads);
    }

    @Test
    @DisplayName("Positive & Negative: Partial and full refund lifecycle with balance constraints")
    void testFullAndPartialRefundLifecycle() throws Exception {
        // 1. Create and confirm a $100.00 payment
        CreatePaymentRequest createReq = CreatePaymentRequest.builder()
                .amount(10000L) // $100.00
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

        // 2. Partial Refund #1: Refund $40.00
        RefundRequest refund1 = new RefundRequest(4000L);
        mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refund1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(4000))
                .andExpect(jsonPath("$.status").value("succeeded"));

        // 3. Negative: Over-refund (attempt to refund $70 when only $60 remains) -> 422
        RefundRequest overRefund = new RefundRequest(7000L);
        mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overRefund)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("invalid_state_transition"));

        // 4. Partial Refund #2: Refund remaining $60.00 -> 201
        RefundRequest refund2 = new RefundRequest(6000L);
        mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refund2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(6000));

        // 5. Negative: Attempt refund when completely refunded ($0 remaining) -> 422
        mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefundRequest(100L))))
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
}
