package com.fintech.payment.controller;

import com.fintech.payment.model.dto.request.CreateAccountRequest;
import com.fintech.payment.model.dto.request.UpdateAccountStatusRequest;
import com.fintech.payment.model.dto.response.AccountResponse;
import com.fintech.payment.model.dto.response.ApiResponse;
import com.fintech.payment.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST surface for accounts (SRS §5.1) — covers FR-1.1 .. FR-1.5.
 *
 * <p>Every successful response is wrapped in {@link ApiResponse} per the SRS §6 API
 * Consistency rule. Error responses are produced by the
 * {@link com.fintech.payment.exception.GlobalExceptionHandler}
 * — controllers do not handle exceptions locally.</p>
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** FR-1.1: create. */
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(
            @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.createAccount(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(ApiResponse.of(created));
    }

    /** FR-1.2: get by id. */
    @GetMapping("/{id}")
    public ApiResponse<AccountResponse> get(@PathVariable UUID id) {
        return ApiResponse.of(accountService.getAccount(id));
    }

    /** FR-1.3: list (pageable). */
    @GetMapping
    public ApiResponse<Page<AccountResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.of(accountService.listAccounts(pageable));
    }

    /** FR-1.4 / FR-1.5: status transition. */
    @PatchMapping("/{id}/status")
    public ApiResponse<AccountResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        return ApiResponse.of(accountService.updateAccountStatus(id, request.status(), request.reason()));
    }
}
