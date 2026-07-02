package com.fintech.payment.model.enums;

/**
 * Lifecycle status of a {@code Payment} (SRS §3.2).
 *
 * <p>State transitions are <em>not</em> codified as a typed state-machine map the
 * way the account lifecycle is — the payment lifecycle is mostly linear
 * (PENDING → COMPLETED) plus the rare REVERSED sink. The enum is therefore a pure
 * value type that JPA persists via {@code EnumType.STRING}.</p>
 *
 * <ul>
 *   <li>{@code PENDING} — accepted by the controller but not yet processed
 *       (reserved for Phase 4 async settlement).</li>
 *   <li>{@code COMPLETED} — funds moved; balances updated.</li>
 *   <li>{@code FAILED} — settlement attempt failed (Phase 4 will set the
 *       {@code failureReason}).</li>
 *   <li>{@code REVERSED} — a completed payment was reversed by
 *       {@code POST /api/v1/payments/{id}/reverse}; balances restored.</li>
 * </ul>
 */
public enum PaymentStatus {

    PENDING,
    COMPLETED,
    FAILED,
    REVERSED
}
