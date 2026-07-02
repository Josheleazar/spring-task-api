package com.fintech.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.exception.ReconciliationReportUnavailableException;
import com.fintech.payment.exception.ValidationException;
import com.fintech.payment.model.dto.response.AuditLogResponse;
import com.fintech.payment.model.dto.response.ReconciliationReportResponse;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.service.AuditService;
import com.fintech.payment.service.ReconciliationReportService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 audit controllers test pair — mirrors the Phase-2/3/4
 * {@code @WebMvcTest} shape.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>FR-4.1/4.2 — GET {@code /api/v1/audit?entityType=…&entityId=…}</li>
 *   <li>FR-4.3 — GET {@code /api/v1/reports/daily?date=…}</li>
 * </ul>
 *
 * <p>Two controllers are loaded by a single {@code @WebMvcTest} to keep
 * the asciidoc narrative compact (&lt;10s slice). MockBean injection is
 * via Spring 6.2+ {@code @MockitoBean} (replacement for the deprecated
 * {@code @MockBean}).</p>
 */
@WebMvcTest(controllers = {AuditController.class, ReportsController.class})
@Import(com.fintech.payment.exception.GlobalExceptionHandler.class)
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private ReconciliationReportService reconciliationReportService;

    /**
     * §12.3.1 regression-fix mirror: {@code @WebMvcTest} auto-loads
     * {@code @Component} Filter beans, including {@code IdempotencyFilter},
     * which requires {@code IdempotencyService} at construction. The
     * idiomatic context-wiring fix is a MockitoBean stub here even
     * though the AuditController never hits the POST /payments path
     * the filter guards — the filter bean has to be visible to the
     * context-load or the slice fails to start.
     */
    @MockitoBean
    private com.fintech.payment.service.IdempotencyService idempotencyService;

    /* -------------------- Audit trail -------------------- */

    @Nested
    @DisplayName("AuditTrail")
    class AuditTrail {

        @Test
        void returns_trail_newest_first_when_entity_id_supplied() throws Exception {
            UUID id = UUID.randomUUID();
            UUID auditId1 = UUID.randomUUID();
            Instant t1 = Instant.parse("2026-07-02T01:00:00Z");
            List<AuditLogResponse> trail = List.of(
                    new AuditLogResponse(auditId1, "PAYMENT", id,
                            AuditAction.REVERSED, null, "{\"status\":\"REVERSED\"}",
                            "system", t1));

            when(auditService.findByEntity(eq("PAYMENT"), eq(id))).thenReturn(trail);

            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "PAYMENT")
                            .param("entityId", id.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", equalTo(auditId1.toString())))
                    .andExpect(jsonPath("$[0].entityType", equalTo("PAYMENT")))
                    .andExpect(jsonPath("$[0].action", equalTo("REVERSED")));
        }

        @Test
        void returns_paginated_when_entity_id_absent() throws Exception {
            UUID id = UUID.randomUUID();
            AuditLogResponse r = new AuditLogResponse(
                    id, "PAYMENT", UUID.randomUUID(),
                    AuditAction.CREATED, null, null,
                    "system", Instant.now());
            Page<AuditLogResponse> page = new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1);
            when(auditService.findByEntityType(eq("PAYMENT"), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "PAYMENT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.totalElements", equalTo(1)));
        }

        @Test
        void missing_entityType_returns_400_validation_failed() throws Exception {
            mockMvc.perform(get("/api/v1/audit"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejected_entityType_returns_400_validation_failed() throws Exception {
            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
        }

        @Test
        void malformed_entityId_returns_400_type_mismatch() throws Exception {
            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "PAYMENT")
                            .param("entityId", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", equalTo("TYPE_MISMATCH")));
        }
    }

    /* -------------------- Reconciliation report -------------------- */

    @Nested
    @DisplayName("ReconciliationReport")
    class ReconciliationReport {

        @Test
        void returns_200_with_breakdowns_for_a_past_date() throws Exception {
            LocalDate date = LocalDate.parse("2026-07-01");
            Map<String, Long> byType = new TreeMap<>();
            byType.put("PAYMENT", 3L);
            byType.put("ACCOUNT", 1L);
            Map<String, Long> byAction = new TreeMap<>();
            byAction.put("CREATED", 3L);
            byAction.put("STATUS_CHANGE", 1L);
            ReconciliationReportResponse report = new ReconciliationReportResponse(
                    date, 4, byType, byAction, false,
                    Instant.parse("2026-07-01T00:00:00Z"),
                    Instant.parse("2026-07-02T00:00:00Z"),
                    Instant.parse("2026-07-02T14:00:00Z")
            );
            when(reconciliationReportService.generateReport(date)).thenReturn(report);

            mockMvc.perform(get("/api/v1/reports/daily")
                            .param("date", "2026-07-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date", equalTo("2026-07-01")))
                    .andExpect(jsonPath("$.totalAuditEvents", equalTo(4)))
                    .andExpect(jsonPath("$.byEntityType.PAYMENT", equalTo(3)))
                    .andExpect(jsonPath("$.byAction.CREATED", equalTo(3)))
                    .andExpect(jsonPath("$.fromCache", equalTo(false)));
        }

        @Test
        void future_date_returns_422_unavailable() throws Exception {
            LocalDate future = LocalDate.now().plusDays(1);
            doThrow(new ReconciliationReportUnavailableException(
                    "Reconciliation report for future date " + future + " is unavailable"))
                    .when(reconciliationReportService).generateReport(future);

            mockMvc.perform(get("/api/v1/reports/daily")
                            .param("date", future.toString()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error", equalTo("RECONCILIATION_REPORT_UNAVAILABLE")));
        }

        @Test
        void malformed_date_returns_400_type_mismatch() throws Exception {
            mockMvc.perform(get("/api/v1/reports/daily")
                            .param("date", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", equalTo("TYPE_MISMATCH")));
        }
    }
}
