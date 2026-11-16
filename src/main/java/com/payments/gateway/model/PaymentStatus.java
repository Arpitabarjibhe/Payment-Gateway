package com.payments.gateway.model;

public enum PaymentStatus {
    REQUIRES_CONFIRMATION,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
