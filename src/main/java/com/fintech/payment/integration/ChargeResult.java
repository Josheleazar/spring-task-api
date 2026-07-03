package com.fintech.payment.integration;

import java.time.Instant;

/**
 * Phase 7 — gateway-side charge result envelope.
 *
 * <p>Returned by {@link PaymentGatewayClient#charge} and {@code batchCharge}.
 * Phase-7 KISS shape:</p>
 * <ul>
 *   <li>gateway-assigned confirmation token (string);</li>
 *   <li>timestamp the gateway saw the charge;</li>
 *   <li>canonical status (mirrors {@code Payment.status} enum).</li>
 * </ul>
 *
 * <p>The mock implementation returns a UUID-string token + the system
 * clock timestamp — production adapters will return the provider-assigned
 * ids via the same envelope.</p>
 */
public record ChargeResult(String gatewayChargeId, Instant chargedAt) {

    public static ChargeResult success() {
        return new ChargeResult(java.util.UUID.randomUUID().toString(), java.time.Instant.now());
    }
}
