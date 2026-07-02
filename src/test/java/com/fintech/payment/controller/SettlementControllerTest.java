package com.fintech.payment.controller;

import com.fintech.payment.exception.GlobalExceptionHandler;
import com.fintech.payment.exception.SettlementBatchNotFoundException;
import com.fintech.payment.model.dto.response.SettlementResponse;
import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.SettlementStatus;
import com.fintech.payment.service.IdempotencyService;
import com.fintech.payment.service.SettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 settlement controller test — covers FR-3.5 (GET listing + GET by id)
 * and FR-3.2 (manual POST /process trigger).
 *
 * <p>Wiring mirrors @PaymentControllerTest pattern:</p>
 * <ul>
 *   <li>{@code @WebMvcTest(SettlementController.class)} — the controller
 *       slice. {@code @RestControllerAdvice} loaded via
 *       {@code @Import(GlobalExceptionHandler.class)}.</li>
 *   <li>{@code @MockitoBean SettlementService} — replaces the real service
 *       (avoids the @Async + @Retryable AOP-proxy instantiation that
 *       occurs in {@code @SpringBootTest} but is intentionally absent in
 *       the @WebMvcTest slice).</li>
 *   <li>{@code @MockitoBean IdempotencyService} — required by
 *       {@code @WebMvcTest}'s auto-included {@code IdempotencyFilter}
 *       bean (which depends on {@code IdempotencyService} per the §12.3.1
 *       regression fix). The filter's {@code shouldNotFilter} bypasses
 *       any non-payment path, so its presence is a context-wiring fix
 *       only.</li>
 * </ul>
 */
@WebMvcTest(SettlementController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("SettlementController")
class SettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementService settlementService;

    @MockitoBean
    private IdempotencyService idempotencyService;  // §12.3.1 auto-include fix

    private static SettlementResponse sample(UUID id, SettlementStatus status) {
        SettlementBatch batch = new SettlementBatch();
        batch.setId(id);
        batch.setBatchDate(LocalDate.of(2026, 7, 2));
        batch.setStatus(status);
        batch.setTotalPayments(3);
        batch.setTotalAmount(new BigDecimal("150.00"));
        batch.setCurrency("USD");
        Instant stamp = Instant.parse("2026-07-02T10:30:00Z");
        batch.setProcessedAt(stamp);
        batch.setCreatedAt(stamp);
        batch.setUpdatedAt(stamp);
        return SettlementResponse.from(batch);
    }

    @Nested
    @DisplayName("GET /api/v1/settlements (FR-3.5 list)")
    class ListBatches {

        @Test
        void returns_200_paginated_envelope() throws Exception {
            UUID id = UUID.randomUUID();
            Page<SettlementResponse> page = new PageImpl<>(
                    List.of(sample(id, SettlementStatus.SETTLED)),
                    PageRequest.of(0, 20),
                    1);
            when(settlementService.listBatches(any(), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/settlements?page=0&size=20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].status").value("SETTLED"))
                    .andExpect(jsonPath("$.data.content[0].totalPayments").value(3))
                    .andExpect(jsonPath("$.data.content[0].currency").value("USD"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/settlements/{id} (FR-3.5 single)")
    class GetBatch {

        @Test
        void returns_200_with_envelope() throws Exception {
            UUID id = UUID.randomUUID();
            when(settlementService.getBatch(eq(id))).thenReturn(sample(id, SettlementStatus.SETTLED));

            mockMvc.perform(get("/api/v1/settlements/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.status").value("SETTLED"))
                    .andExpect(jsonPath("$.data.batchDate").value("2026-07-02"));
        }

        @Test
        void unknown_id_returns_404_SETTLEMENT_BATCH_NOT_FOUND() throws Exception {
            UUID id = UUID.randomUUID();
            when(settlementService.getBatch(eq(id)))
                    .thenThrow(new SettlementBatchNotFoundException(id));

            mockMvc.perform(get("/api/v1/settlements/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("SETTLEMENT_BATCH_NOT_FOUND"));
        }

        @Test
        void malformed_uuid_returns_400_TYPE_MISMATCH() throws Exception {
            mockMvc.perform(get("/api/v1/settlements/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TYPE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/settlements/{id}/process (FR-3.2 manual trigger)")
    class ProcessBatch {

        @Test
        void returns_202_accepted_with_dispatched_body() throws Exception {
            UUID id = UUID.randomUUID();
            // triggerProcessing returns void; service is mocked, just verify it was called.
            mockMvc.perform(post("/api/v1/settlements/" + id + "/process"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.batchId").value(id.toString()))
                    .andExpect(jsonPath("$.data.status").value("PROCESSING_DISPATCHED"));
            verify(settlementService).triggerProcessing(eq(id));
        }

        @Test
        void unknown_id_returns_404() throws Exception {
            UUID id = UUID.randomUUID();
            org.mockito.Mockito.doThrow(new SettlementBatchNotFoundException(id))
                    .when(settlementService).triggerProcessing(eq(id));

            mockMvc.perform(post("/api/v1/settlements/" + id + "/process"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("SETTLEMENT_BATCH_NOT_FOUND"));
        }

        @Test
        void malformed_uuid_returns_400() throws Exception {
            mockMvc.perform(post("/api/v1/settlements/not-a-uuid/process"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TYPE_MISMATCH"));
        }
    }
}
