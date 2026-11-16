package com.payments.gateway.service;

import org.springframework.stereotype.Service;

@Service
public class CardSimulator {

    public record CardResult(boolean success, String failureCode, String cardLast4) {}

    public CardResult evaluateCard(String rawCardNumber) {
        if (rawCardNumber == null || rawCardNumber.isBlank()) {
            // Default to mock test card
            return new CardResult(true, null, "4242");
        }

        String cleaned = rawCardNumber.replaceAll("[^0-9]", "");
        String last4 = cleaned.length() >= 4 ? cleaned.substring(cleaned.length() - 4) : "0000";

        // Deterministic test card rules (Mirroring Stripe test card suite)
        if (cleaned.endsWith("0002")) {
            return new CardResult(false, "insufficient_funds", last4);
        }
        if (cleaned.endsWith("0005")) {
            return new CardResult(false, "card_declined_do_not_honor", last4);
        }
        if (cleaned.endsWith("0001")) {
            return new CardResult(false, "expired_card", last4);
        }
        if (cleaned.length() < 13 || cleaned.length() > 19) {
            return new CardResult(false, "invalid_card_number", last4);
        }

        // Standard success cards (e.g. 4242 4242 4242 4242)
        return new CardResult(true, null, last4);
    }
}
