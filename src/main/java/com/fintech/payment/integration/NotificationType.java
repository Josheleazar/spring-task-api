package com.fintech.payment.integration;

/**
 * Phase 7 — outbound notification channel vocabulary.
 *
 * <p>Three orthogonal values cover Phase 7's notification surface;
 * future phases add WEBHOOK (server-to-server integrations) and PUSH
 * (mobile-app deep-link alerts) without breaking the
 * {@code @Enumerated(STRING)} notification log.</p>
 */
public enum NotificationType {
    /** Transactional email (SendGrid / SES / Postmark in production). */
    EMAIL,
    /** SMS alert (Twilio / SNS in production). */
    SMS,
    /** In-app / server-side structured log only; no end-user channel. */
    IN_APP
}
