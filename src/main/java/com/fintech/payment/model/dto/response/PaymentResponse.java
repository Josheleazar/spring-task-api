package com.fintech.payment.model.dto.response;

import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound representation of a {@code Payment}. Mirrors the JPA entity but
 * immutably, so lazy proxies and JPA-managed state never escape the service
 * layer.
 */
public record PaymentResponse(
        UUID id,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant processedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getSourceAccountId(),
                payment.getTargetAccountId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getProcessedAt());
    }
}
