package com.fintech.payment.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7 §12.7.1 — dev-profile {@link NotificationClient} mock.
 *
 * <p>Logs each notification to the application console at {@code INFO}
 * (FR-6.6: "Mock notification client logs to console — verifiable
 * in tests.") and stores the rendered message in an in-memory ring
 * buffer so the integration-test pair can assert on the call
 * history.</p>
 *
 * <p>The implementation is intentionally minimal — it does NOT
 * persist the message-id anywhere; Phase-8 production adapters
 * (SendGrid / Twilio) will return provider-assigned ids via the
 * same {@link #send} signature.</p>
 */
@Component
@Profile("dev")
@Slf4j
public class MockNotificationClient implements NotificationClient {

    /** Ring buffer for the most-recent N sent notifications (test-assertable). */
    private static final int HISTORY_LIMIT = 100;

    private final List<SentRecord> history = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String send(NotificationType type, String message) {
        String id = UUID.randomUUID().toString();
        log.info("[NOTIFY-{}] type={} message={}", id, type, message);
        SentRecord r = new SentRecord(id, type, message);
        synchronized (history) {
            history.add(r);
            if (history.size() > HISTORY_LIMIT) {
                history.remove(0);
            }
        }
        return id;
    }

    /** Immutable view of the most-recent sent notifications (for tests). */
    public List<SentRecord> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /** Test helper — clear the history buffer between tests. */
    public void clearHistory() {
        synchronized (history) {
            history.clear();
        }
    }

    /** Single notification record exposed for test assertions. */
    public record SentRecord(String messageId, NotificationType type, String message) {}
}
