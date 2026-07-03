package com.fintech.payment.integration;

/**
 * Phase 7 — notification seam (SRS §2 / FR-6.6).
 *
 * <p>{@code NotificationClient} is the application-side interface that
 * abstracts outbound user notifications (email / SMS / in-app push).
 * The dev profile uses {@link MockNotificationClient} which logs to the
 * application console; production-side wiring (SendGrid / Twilio /
 * AWS SNS) is a Phase 8 production-showcase deliverable.</p>
 *
 * <p>Phase-7 integration point: the {@code AuditAspect} registers an
 * afterCommit hook so a notification only fires when the audited
 * method's transaction has actually committed (mirrors the audit-row
 * invariant that rolls-back-must-not-notify). The pre-commit pattern
 * is documented in §12.7.1 as the architectural verdict.</p>
 *
 * <p>FR-6.6: "Mock notification client logs to console — verifiable in
 * tests." The mock logs each {@link #send} call at {@code INFO} so the
 * Phase-7 test pair asserts on the LOG output via a Logback test
 * appender (or just on the mock's call history).</p>
 */
public interface NotificationClient {

    /**
     * Fire a notification for a given type + message.
     *
     * @param type   the channel; {@link NotificationType#EMAIL} is the
     *               canonical default, {@link NotificationType#SMS} for
     *               payment-alert use cases.
     * @param message the rendered message body. Phase-7 KISS uses a plain
     *                String; a future phase may adopt templated content.
     * @return a synthetic message-id (the mock returns a UUID-string).
     *         Production adapters return the provider-assigned id.
     */
    String send(NotificationType type, String message);
}
