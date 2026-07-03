package com.fintech.payment.integration;

/**
 * Phase 7 — fraud-detection verdict + free-form reason string.
 *
 * <p>Phase-7 KISS is a boolean verdict + a single reason string. The
 * future hardening pass may add a {@code factors} list of risk-axis
 * decompositions (e.g. amount + velocity + IP reputation + device
 * fingerprint), but Phase-7 ships the minimum envelope the audit-trail
 * needs.</p>
 *
 * <p>Reasons must be greppable across H2 dev and Postgres prod so the
 * audit row's {@code newValue} JSON column can carry the same string
 * verbatim — see §12.7.1 architectural verdict.</p>
 */
public record FraudScore(boolean fraudulent, String reason) {

    /** Convenience factory for the canonical "no risk detected" mock default. */
    public static FraudScore clean() {
        return new FraudScore(false, "no risk indicators tripped");
    }

    /** Convenience factory for the amount-threshold rule. */
    public static FraudScore amountThreshold(java.math.BigDecimal amount, java.math.BigDecimal threshold) {
        return new FraudScore(true,
                "amount " + amount + " exceeds configured threshold " + threshold);
    }

    /** Convenience factory for the velocity rule. */
    public static FraudScore velocityWindow(int txCount, int windowSeconds, int limitPerWindow) {
        return new FraudScore(true,
                "velocity " + txCount + " transactions in last " + windowSeconds
                        + "s exceeds limit " + limitPerWindow + "/window");
    }
}
