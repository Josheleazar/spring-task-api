package com.fintech.payment.controller;

import com.fintech.payment.model.dto.request.ReversePaymentRequest;
import com.fintech.payment.model.dto.request.SubmitPaymentRequest;
import com.fintech.payment.model.dto.response.ApiResponse;
import com.fintech.payment.model.dto.response.PaymentResponse;
import com.fintech.payment.model.enums.PaymentStatus;
import com.fintech.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST surface for payments (SRS §5.2) — covers FR-2.1 .. FR-2.6 plus the
 * implied {@code POST /reverse} endpoint.
 *
 * <p>The {@code Idempotency-Key} header is read here with regex bounds and
 * forwarded to {@link PaymentService#submitPayment}. It is also intercepted
 * upstream by {@link com.fintech.payment.idempotency.IdempotencyFilter} which
 * is responsible for cache lookup/stash. Splitting the concerns keeps the
 * service unaware of the cache layer.</p>
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {

    /** RFC-friendly header name; {@code @Size} on the controller method enforces bounds. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9_-]{8,128}$";

    private final PaymentService paymentService;

    /** FR-2.1: submit. */
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> submit(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER)
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN,
                    message = "must be 8-128 chars of A-Z a-z 0-9 - _")
            String idempotencyKey,
            @Valid @RequestBody SubmitPaymentRequest request) {
        PaymentResponse payment = paymentService.submitPayment(idempotencyKey, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(payment.id()).toUri();
        return ResponseEntity.created(location).body(ApiResponse.of(payment));
    }

    /** GET single payment. */
    @GetMapping("/{id}")
    public ApiResponse<PaymentResponse> get(@PathVariable UUID id) {
        return ApiResponse.of(paymentService.getPayment(id));
    }

    /** GET listing (pageable, optionally filtered by status). */
    @GetMapping
    public ApiResponse<Page<PaymentResponse>> list(
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.of(paymentService.listPayments(status, pageable));
    }

    /** Postel-style reverse complement to submit. */
    @PostMapping("/{id}/reverse")
    public ApiResponse<PaymentResponse> reverse(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid ReversePaymentRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.of(paymentService.reversePayment(id, reason));
    }
}
