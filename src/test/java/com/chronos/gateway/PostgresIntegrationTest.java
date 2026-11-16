package com.chronos.gateway;

import com.chronos.gateway.dto.CreatePaymentRequest;
import com.chronos.gateway.dto.RefundRequest;
import com.chronos.gateway.model.PaymentIntent;
import com.chronos.gateway.repository.IdempotencyRepository;
import com.chronos.gateway.repository.LedgerEntryRepository;
import com.chronos.gateway.repository.PaymentIntentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"
})
@DisplayName("PostgreSQL Integration Tests (Testcontainers)")
public class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    // (a) 10 concurrent POSTs with SAME idempotency key → exactly 1 payment row + 3 ledger entries
    @Test
    @DisplayName("(a) 10 concurrent requests with same Idempotency-Key create exactly 1 payment and 3 ledger entries")
    void testConcurrentIdempotencyStorm() throws Exception {
        String idempotencyKey = "pg_storm_key_" + System.currentTimeMillis();
        String merchantId = "pg_merch_storm";
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(5000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        String payload = objectMapper.writeValueAsString(request);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    MvcResult res = mockMvc.perform(post("/v1/payments")
                                    .header("Idempotency-Key", idempotencyKey)
                                    .header("X-Merchant-Id", merchantId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) created.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        latch.countDown();
        done.await();
        executor.shutdown();

        // Exactly 1 payment row for this merchant
        long paymentCount = paymentIntentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId).size();
        assertThat(paymentCount).isEqualTo(1);

        // Exactly 3 ledger entries for that payment
        String paymentId = paymentIntentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId).get(0).getId();
        long entryCount = ledgerEntryRepository.findByTransactionId(paymentId).size();
        assertThat(entryCount).isEqualTo(3);
    }

    // (b) 2 concurrent full refunds on one payment → exactly 1 succeeds, 1 rejected
    @Test
    @DisplayName("(b) 2 concurrent full refunds: exactly one succeeds, the other is rejected")
    void testConcurrentRefunds() throws Exception {
        // Create payment
        String merchantId = "pg_merch_refund";
        CreatePaymentRequest req = CreatePaymentRequest.builder()
                .amount(5000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        MvcResult cr = mockMvc.perform(post("/v1/payments")
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        PaymentIntent payment = objectMapper.readValue(cr.getResponse().getContentAsString(), PaymentIntent.class);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    MvcResult res = mockMvc.perform(post("/v1/payments/" + payment.getId() + "/refunds")
                                    .header("X-Merchant-Id", merchantId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new RefundRequest(5000L))))
                            .andReturn();
                    int status = res.getResponse().getStatus();
                    if (status == 201) successCount.incrementAndGet();
                    else if (status == 422) rejectedCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        latch.countDown();
        done.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(1);
    }

    // (c) Idempotent replay on PostgreSQL (exercises TEXT column)
    @Test
    @DisplayName("(c) Idempotent replay on PostgreSQL: second call returns cached body with Idempotent-Replayed: true")
    void testIdempotentReplayOnPostgres() throws Exception {
        String merchantId = "pg_merch_replay";
        String idempotencyKey = "pg_replay_key_" + System.currentTimeMillis();
        CreatePaymentRequest req = CreatePaymentRequest.builder()
                .amount(3000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        String payload = objectMapper.writeValueAsString(req);

        // First call
        MvcResult first = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // Second call (replay)
        MvcResult second = mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        String secondBody = second.getResponse().getContentAsString();
        assertThat(secondBody).isEqualTo(firstBody);
    }

    // (d) Reconciliation reports is_balanced = true
    @Test
    @DisplayName("(d) Reconciliation reports is_balanced=true after payment settlement")
    void testReconciliationBalancedOnPostgres() throws Exception {
        String merchantId = "pg_merch_reconcile";
        CreatePaymentRequest req = CreatePaymentRequest.builder()
                .amount(4000L)
                .currency("usd")
                .cardNumber("4242424242424242")
                .confirm(true)
                .build();
        mockMvc.perform(post("/v1/payments")
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/ledger/reconciliation")
                        .param("merchant_id", merchantId)
                        .param("currency", "usd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_balanced").value(true))
                .andExpect(jsonPath("$.discrepancy").value(0));
    }

    @Test
    @DisplayName("Task 6 - 10 simultaneous first payments for new merchant all succeed and reconcile")
    void testConcurrentNewMerchantOnPostgres() throws Exception {
        String newMerchantId = "pg_new_merch_" + System.currentTimeMillis();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amount(1000L)
                .currency("usd")
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

        assertThat(successCount.get()).isEqualTo(10);

        mockMvc.perform(get("/v1/ledger/reconciliation")
                        .param("merchant_id", newMerchantId)
                        .param("currency", "usd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_balanced").value(true));
    }
}
