package com.fintech.payment.model.dto.response;

import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbound representation of a {@code SettlementBatch}. Mirrors the JPA
 * entity but immutably, so lazy proxies and JPA-managed state never escape
 * the service layer.
 *
 * <p>Same shape pattern as {@link PaymentResponse} — record components
 * + a static {@code from(entity)} mapper. {@code updatedAt} is included
 * (mirrors {@code PaymentResponse}) so daily reconciliation reports can
 * show when a batch was last touched.</p>
 */
public record SettlementResponse(
        UUID id,
        LocalDate batchDate,
        SettlementStatus status,
        int totalPayments,
        BigDecimal totalAmount,
        String currency,
        Instant processedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static SettlementResponse from(SettlementBatch batch) {
        return new SettlementResponse(
                batch.getId(),
                batch.getBatchDate(),
                batch.getStatus(),
                batch.getTotalPayments(),
                batch.getTotalAmount(),
                batch.getCurrency(),
                batch.getProcessedAt(),
                batch.getCreatedAt(),
                batch.getUpdatedAt());
    }
}
