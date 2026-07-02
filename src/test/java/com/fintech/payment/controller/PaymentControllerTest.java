package com.fintech.payment.controller;

import com.fintech.payment.exception.AccountNotActiveException;
import com.fintech.payment.exception.CurrencyMismatchException;
import com.fintech.payment.exception.GlobalExceptionHandler;
import com.fintech.payment.exception.InsufficientFundsException;
import com.fintech.payment.exception.InvalidPaymentStateException;
import com.fintech.payment.exception.ResourceNotFoundException;
import com.fintech.payment.exception.SelfTransferException;
import com.fintech.payment.idempotency.IdempotencyFilter;
import com.fintech.payment.model.dto.response.ApiResponse;
import com.fintech.payment.model.dto.response.PaymentResponse;
import com.fintech.payment.model.enums.AccountStatus;
import com.fintech.payment.model.enums.PaymentStatus;
import com.fintech.payment.service.IdempotencyService;
import com.fintech.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for {@link PaymentController} covering FR-2.1..2.6 envelope
 * contract, the {@code Idempotency-Key} header (missing / malformed) and the
 * reverse endpoint.
 *
 * <p>Strategy mirrors the Phase-2 {@code AccountControllerTest}:</p>
 * <ul>
 *   <li>{@code @WebMvcTest(PaymentController.class)} scopes to the web layer.</li>
 *   <li>{@code @Import} explicitly brings in the
 *       {@link GlobalExceptionHandler} advice and the
 *       {@link IdempotencyFilter} (so missing-header / cache-miss paths are
 *       exercised against the real components).</li>
 *   <li>{@code @MockitoBean} stubs {@link PaymentService} for happy paths and
 *       stubs {@link IdempotencyService} to always return envelope Miss on
 *       lookup (Mockito default for {@code Optional<T>}) so the test focuses
 *       on the controller contract rather than cache replay logic — the
 *       latter is exercised end-to-end by the live curl smoke matrix.</li>
 * </ul>
 */
@WebMvcTest(PaymentController.class)
@Import({GlobalExceptionHandler.class, IdempotencyFilter.class})
@DisplayName("PaymentController — FR-2.1 .. FR-2.6 + reverse envelope contract")
class PaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private PaymentService paymentService;
    @MockitoBean private IdempotencyService idempotencyService;

    /* -------------------- IdempotencyFilter precondition -------------------- */

    /**
     * The {@link IdempotencyFilter} uses {@link IdempotencyService#lookup(String)}
     * to gate replays. We stub lookup to return {@link Optional#empty()} across
     * every test so the controller path executes normally. Save is a no-op since
     * the upstream {@code @MockitoBean} returns {@code void} by default.
     */
    private void stubIdempotencyMissForAnyKey() {
        when(idempotencyService.lookup(any())).thenAnswer((InvocationOnMock inv) -> Optional.empty());
    }

    /* -------------------- helpers -------------------- */

    private static final Instant SAMPLE_TIMESTAMP = Instant.parse("2026-07-02T10:30:00Z");

    private static PaymentResponse sample(UUID id, PaymentStatus status, String idemKey) {
        return new PaymentResponse(
                id, idemKey,
                UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50.00"), "USD", status,
                null,                              // failureReason
                SAMPLE_TIMESTAMP, SAMPLE_TIMESTAMP,   // createdAt, updatedAt
                SAMPLE_TIMESTAMP);                 // processedAt
    }

    /* -------------------- FR-2.1 + FR-2.6: POST submit -------------------- */

    @Nested
    @DisplayName("POST /api/v1/payments (FR-2.1, FR-2.4..2.6)")
    class SubmitPayment {

        @Test
        void post_valid_returns_201_with_Location_and_ApiResponse_envelope() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID sourceId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            when(paymentService.submitPayment(eq("IDEM-OK-1"), any())).thenAnswer(inv ->
                    new PaymentResponse(paymentId, "IDEM-OK-1",
                            sourceId, targetId,
                            new BigDecimal("100.00"), "USD", PaymentStatus.COMPLETED,
                            null,
                            SAMPLE_TIMESTAMP, SAMPLE_TIMESTAMP, SAMPLE_TIMESTAMP));

            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-OK-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s",
                                     "targetAccountId":"%s",
                                     "amount":100.00,
                                     "currency":"USD"}"""
                                    .formatted(sourceId, targetId)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/" + paymentId)))
                    .andExpect(jsonPath("$.data.id").value(paymentId.toString()))
                    .andExpect(jsonPath("$.data.idempotencyKey").value("IDEM-OK-1"))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.amount").value(100.00))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void post_missing_Idempotency_Key_header_returns_400_MISSING_HEADER() throws Exception {
            stubIdempotencyMissForAnyKey();
            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":1.00,"currency":"USD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("MISSING_HEADER"))
                    .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
        }

        @Test
        void post_Idempotency_Key_too_short_returns_400_VALIDATION_FAILED() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID sourceId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "short")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":1.00,"currency":"USD"}"""
                                    .formatted(sourceId, targetId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }

        @Test
        void post_missing_amount_returns_400_VALIDATION_FAILED() throws Exception {
            stubIdempotencyMissForAnyKey();
            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-MISSING-AMT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s","currency":"USD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')]").exists());
        }

        @Test
        void post_zero_or_negative_amount_returns_400_VALIDATION_FAILED() throws Exception {
            stubIdempotencyMissForAnyKey();
            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-NEG-AMT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":0,"currency":"USD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')]").exists());
        }

        @Test
        void post_invalid_currency_returns_400_VALIDATION_FAILED() throws Exception {
            stubIdempotencyMissForAnyKey();
            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-BAD-CUR")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":1.00,"currency":"USDD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='currency')]").exists());
        }

        @Test
        void post_insufficient_funds_returns_422_INSUFFICIENT_FUNDS() throws Exception {
            stubIdempotencyMissForAnyKey();
            when(paymentService.submitPayment(any(), any()))
                    .thenThrow(new InsufficientFundsException(
                            "ACC-1001", new BigDecimal("100.00"), new BigDecimal("12.50")));

            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-NSF")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":100.00,"currency":"USD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                    .andExpect(jsonPath("$.message", containsString("ACC-1001")));
        }

        @Test
        void post_self_transfer_returns_400_SELF_TRANSFER() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID sameId = UUID.randomUUID();
            when(paymentService.submitPayment(any(), any()))
                    .thenThrow(new SelfTransferException(sameId));

            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-SELF")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":1.00,"currency":"USD"}"""
                                    .formatted(sameId, sameId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SELF_TRANSFER"));
        }

        @Test
        void post_source_FROZEN_returns_409_ACCOUNT_NOT_ACTIVE() throws Exception {
            stubIdempotencyMissForAnyKey();
            when(paymentService.submitPayment(any(), any()))
                    .thenThrow(new AccountNotActiveException("ACC-SRC", AccountStatus.FROZEN));

            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-FROZEN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":1.00,"currency":"USD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_ACTIVE"));
        }

        @Test
        void post_currency_mismatch_returns_422_CURRENCY_MISMATCH() throws Exception {
            stubIdempotencyMissForAnyKey();
            when(paymentService.submitPayment(any(), any()))
                    .thenThrow(new CurrencyMismatchException("USD", "USD", "EUR"));

            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "IDEM-CCY")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":"%s","targetAccountId":"%s",
                                     "amount":1.00,"currency":"USD"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"));
        }
    }

    /* -------------------- GET endpoints -------------------- */

    @Nested
    @DisplayName("GET /api/v1/payments/{id}")
    class GetPayment {

        @Test
        void get_existing_returns_200_with_envelope_and_data() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            when(paymentService.getPayment(id)).thenReturn(sample(id, PaymentStatus.COMPLETED, "IDEM-G1"));

            mockMvc.perform(get("/api/v1/payments/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.amount").value(50.00));
        }

        @Test
        void get_unknown_id_returns_404_NOT_FOUND() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            when(paymentService.getPayment(id)).thenThrow(new ResourceNotFoundException("Payment", id));

            mockMvc.perform(get("/api/v1/payments/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }

        @Test
        void get_malformed_uuid_returns_400_TYPE_MISMATCH() throws Exception {
            stubIdempotencyMissForAnyKey();
            mockMvc.perform(get("/api/v1/payments/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TYPE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/payments")
    class ListPayments {

        @Test
        void list_with_status_returns_200_paginated_envelope() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            Page<PaymentResponse> page = new PageImpl<>(
                    List.of(sample(id, PaymentStatus.COMPLETED, "IDEM-L1")),
                    PageRequest.of(0, 20), 1L);
            when(paymentService.listPayments(eq(PaymentStatus.COMPLETED), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/v1/payments?status=COMPLETED&page=0&size=20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        void list_without_status_returns_200_paginated_envelope() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            Page<PaymentResponse> page = new PageImpl<>(
                    List.of(sample(id, PaymentStatus.COMPLETED, "IDEM-L2")),
                    PageRequest.of(0, 20), 1L);
            when(paymentService.listPayments(eq(null), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/payments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    /* -------------------- POST /reverse -------------------- */

    @Nested
    @DisplayName("POST /api/v1/payments/{id}/reverse")
    class ReversePayment {

        @Test
        void reverse_completed_returns_200_status_REVERSED() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            when(paymentService.reversePayment(eq(id), any()))
                    .thenReturn(sample(id, PaymentStatus.REVERSED, "IDEM-R1"));

            mockMvc.perform(post("/api/v1/payments/{id}/reverse", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"chargeback\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.status").value("REVERSED"));
        }

        @Test
        void reverse_already_reversed_returns_400_INVALID_PAYMENT_STATE() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            when(paymentService.reversePayment(eq(id), any()))
                    .thenThrow(new InvalidPaymentStateException(PaymentStatus.REVERSED));

            mockMvc.perform(post("/api/v1/payments/{id}/reverse", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PAYMENT_STATE"));
        }

        @Test
        void reverse_unknown_id_returns_404_NOT_FOUND() throws Exception {
            stubIdempotencyMissForAnyKey();
            UUID id = UUID.randomUUID();
            when(paymentService.reversePayment(eq(id), any()))
                    .thenThrow(new ResourceNotFoundException("Payment", id));

            mockMvc.perform(post("/api/v1/payments/{id}/reverse", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }
    }
}
