package com.fintech.payment.controller;

import com.fintech.payment.model.dto.response.ApiResponse;
import com.fintech.payment.model.dto.response.SettlementResponse;
import com.fintech.payment.model.enums.SettlementStatus;
import com.fintech.payment.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 settlement controller — covers FR-3.1 (read-side listing +
 * via daily @Scheduled), FR-3.2 (manual trigger via POST /process),
 * FR-3.5 (view batch status + summary).
 *
 * <p>Endpoints per SRS §5.3:</p>
 * <ul>
 *   <li>{@code GET /api/v1/settlements} — list batches, optionally
 *       filtered by status (FR-3.5).</li>
 *   <li>{@code GET /api/v1/settlements/{id}} — single batch by id (FR-3.5).</li>
 *   <li>{@code POST /api/v1/settlements/{id}/process} — manual trigger
 *       (FR-3.2 — "process pending payments into an OPEN batch").</li>
 * </ul>
 *
 * <p>Envelope family matches the rest of the API: {@link ApiResponse} on
 * body, status code on the HTTP envelope itself. The POST /process endpoint
 * returns 202 ACCEPTED rather than 200 because the actual settlement work
 * runs on the {@code settlementWorkerExecutor} async pool — the response
 * only acknowledges that the worker has been dispatched.</p>
 *
 * <p>Test wiring note: SettlementService is injected via {@code @Lazy} so
 * the @MockitoBean stub in {@code @WebMvcTest} can substitute without
 * triggering SettlementWorker's @Async + @Retryable AOP-proxy chain at
 * context-load time (which would otherwise fail because Spring's @WebMvcTest
 * slice does not initialise the @EnableAsync / @EnableRetry proxy machinery).</p>
 */
@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    /** FR-3.5 — list. */
    @GetMapping
    public ApiResponse<Page<SettlementResponse>> list(
            @RequestParam(required = false) SettlementStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.of(settlementService.listBatches(status, pageable));
    }

    /** FR-3.5 — single batch. */
    @GetMapping("/{id}")
    public ApiResponse<SettlementResponse> get(@PathVariable UUID id) {
        return ApiResponse.of(settlementService.getBatch(id));
    }

    /**
     * FR-3.2 — manual trigger. Returns 202 ACCEPTED with a structured body
     * indicating the worker has been dispatched. The actual SETTLED state
     * arrives later on the polling {@code GET /api/v1/settlements/{id}}.
     */
    @PostMapping("/{id}/process")
    public ResponseEntity<ApiResponse<Map<String, Object>>> process(@PathVariable UUID id) {
        settlementService.triggerProcessing(id);
        return ResponseEntity.accepted().body(ApiResponse.of(Map.of(
                "batchId", id,
                "status", "PROCESSING_DISPATCHED",
                "note", "Poll GET /api/v1/settlements/{id} for terminal state")));
    }
}
