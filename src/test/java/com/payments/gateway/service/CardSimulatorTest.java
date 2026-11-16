package com.payments.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Unit Tests - Card Simulator")
class CardSimulatorTest {

    private CardSimulator cardSimulator;

    @BeforeEach
    void setUp() {
        cardSimulator = new CardSimulator();
    }

    @Test
    @DisplayName("Should approve standard test card (4242...)")
    void testStandardCardApproval() {
        CardSimulator.CardResult result = cardSimulator.evaluateCard("4242 4242 4242 4242");

        assertThat(result.success()).isTrue();
        assertThat(result.failureCode()).isNull();
        assertThat(result.cardLast4()).isEqualTo("4242");
    }

    @Test
    @DisplayName("Should approve when raw card is null or empty by using default test card")
    void testNullOrEmptyCardFallback() {
        CardSimulator.CardResult nullResult = cardSimulator.evaluateCard(null);
        assertThat(nullResult.success()).isTrue();
        assertThat(nullResult.cardLast4()).isEqualTo("4242");

        CardSimulator.CardResult blankResult = cardSimulator.evaluateCard("   ");
        assertThat(blankResult.success()).isTrue();
        assertThat(blankResult.cardLast4()).isEqualTo("4242");
    }

    @Test
    @DisplayName("Negative: Card ending in 0002 should fail with insufficient_funds")
    void testInsufficientFundsCard() {
        CardSimulator.CardResult result = cardSimulator.evaluateCard("4000-0000-0000-0002");

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("insufficient_funds");
        assertThat(result.cardLast4()).isEqualTo("0002");
    }

    @Test
    @DisplayName("Negative: Card ending in 0005 should fail with card_declined_do_not_honor")
    void testDeclinedCard() {
        CardSimulator.CardResult result = cardSimulator.evaluateCard("4000000000000005");

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("card_declined_do_not_honor");
        assertThat(result.cardLast4()).isEqualTo("0005");
    }

    @Test
    @DisplayName("Negative: Card ending in 0001 should fail with expired_card")
    void testExpiredCard() {
        CardSimulator.CardResult result = cardSimulator.evaluateCard("4000 0000 0000 0001");

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("expired_card");
        assertThat(result.cardLast4()).isEqualTo("0001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "123456789", "123456789012345678901"}) // Too short or too long
    @DisplayName("Negative: Cards with invalid digit lengths (<13 or >19) should fail")
    void testInvalidCardLength(String invalidCard) {
        CardSimulator.CardResult result = cardSimulator.evaluateCard(invalidCard);

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("invalid_card_number");
    }
}
