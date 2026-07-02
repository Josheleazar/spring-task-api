package com.fintech.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.exception.AccountNotClosableException;
import com.fintech.payment.exception.DuplicateAccountNumberException;
import com.fintech.payment.exception.GlobalExceptionHandler;
import com.fintech.payment.exception.InvalidAccountStatusTransitionException;
import com.fintech.payment.exception.ResourceNotFoundException;
import com.fintech.payment.idempotency.IdempotencyFilter;
import com.fintech.payment.model.dto.response.AccountResponse;
import com.fintech.payment.model.enums.AccountStatus;
import com.fintech.payment.service.AccountService;
import com.fintech.payment.service.IdempotencyService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for {@link AccountController} covering SRS §5.1 endpoint
 * contracts and the §8 error-envelope shape across FR-1.1 .. FR-1.5.
 *
 * <p>Strategy:</p>
 * <ul>
 *   <li>{@code @WebMvcTest(AccountController.class)} brings up only the web slice
 *       and the auto-discovered {@code @RestControllerAdvice} family.</li>
 *   <li>{@code @Import(GlobalExceptionHandler.class)} is explicit defence: even if a
 *       future change to scan semantics hid the advice, this keeps the contract
 *       test honest.</li>
 *   <li>{@code @MockitoBean} (Spring 6.2+ replacement for {@code @MockBean})
 *       injects a Mockito-stubbed {@link AccountService}; service-level
 *       exceptions are simulated by stubbing {@code thenThrow(...)}.</li>
 * </ul>
 *
 * <p>Assertions read the JSON body as a tree (no typed deserialisation) so a
 * regression in the {@code ApiResponse}/{@code ApiErrorResponse} envelope shape
 * fails loudly rather than deserialising-around the bug.</p>
 */
@WebMvcTest(AccountController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("AccountController — FR-1.1 .. FR-1.5 envelope contract")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    /**
     * The {@link IdempotencyFilter} is a {@code @Component} that extends
     * {@code OncePerRequestFilter}, and {@code @WebMvcTest} auto-includes
     * {@code Filter} beans even when targeting a specific controller class.
     * The filter requires {@link IdempotencyService} at construction time;
     * we provide a Mockito stub so the application context starts successfully
     * for {@code AccountControllerTest} (whose tests never hit the
     * {@code IdempotencyFilter}'s scope — POST {@code /api/v1/payments}).
     */
    @MockitoBean
    private IdempotencyService idempotencyService;

    /* -------------------- constants -------------------- */

    /** Fixed timestamps used by stubbed {@link AccountResponse}s so the JSON
     * envelope is deterministic across runs. The create-account happy-path test
     * inlines a fresh response with the same timestamps — kept in sync via this
     * constant so any future tweak is one-line. */
    private static final Instant SAMPLE_TIMESTAMP = Instant.parse("2026-07-02T10:30:00Z");

    /* -------------------- helpers -------------------- */

    private static AccountResponse sample(UUID id, AccountStatus status, BigDecimal balance) {
        return new AccountResponse(
                id, "ACC-T", "Test Holder", balance, "USD", status,
                SAMPLE_TIMESTAMP, SAMPLE_TIMESTAMP);
    }

    /* -------------------- FR-1.1: POST /api/v1/accounts -------------------- */

    @Nested
    @DisplayName("POST /api/v1/accounts (FR-1.1)")
    class CreateAccount {

        @Test
        void post_valid_request_returns_201_with_Location_header_and_ApiResponse_envelope() throws Exception {
            UUID id = UUID.randomUUID();
            // Inline response build — must mirror the request body verbatim so the
            // assertable envelope fields match. Stubs that ignore input are easier
            // to read than parameterising the sample() helper for one test.
            when(accountService.createAccount(any())).thenReturn(new AccountResponse(
                    id, "ACC-1001", "Alice Anderson",
                    new BigDecimal("100.00"), "USD", AccountStatus.ACTIVE,
                    SAMPLE_TIMESTAMP, SAMPLE_TIMESTAMP));

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountNumber":"ACC-1001",
                                     "accountHolder":"Alice Anderson",
                                     "initialBalance":100.00,
                                     "currency":"USD"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/" + id)))
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.accountNumber").value("ACC-1001"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.balance").value(100.00))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void post_missing_currency_returns_400_VALIDATION_FAILED_with_fieldErrors() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountNumber":"ACC-1002",
                                     "accountHolder":"Bob Brown",
                                     "initialBalance":50.00}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='currency')]").exists());
        }

        @Test
        void post_currency_violates_pattern_returns_400_VALIDATION_FAILED() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountNumber":"ACC-1003",
                                     "accountHolder":"Carl",
                                     "initialBalance":0,
                                     "currency":"USDD"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='currency')]").exists());
        }

        @Test
        void post_blank_accountNumber_returns_400_VALIDATION_FAILED() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountNumber":"",
                                     "accountHolder":"Dave",
                                     "currency":"USD"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='accountNumber')]").exists());
        }

        @Test
        void post_negative_initialBalance_returns_400_VALIDATION_FAILED() throws Exception {
            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountNumber":"ACC-1004",
                                     "accountHolder":"Eve",
                                     "initialBalance":-1.00,
                                     "currency":"USD"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='initialBalance')]").exists());
        }

        @Test
        void post_duplicate_accountNumber_returns_409_DUPLICATE_ACCOUNT_NUMBER() throws Exception {
            when(accountService.createAccount(any()))
                    .thenThrow(new DuplicateAccountNumberException("ACC-DUP"));

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountNumber":"ACC-DUP",
                                     "accountHolder":"x",
                                     "currency":"USD"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("DUPLICATE_ACCOUNT_NUMBER"))
                    .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("ACC-DUP")));
        }
    }

    /* -------------------- FR-1.2: GET /api/v1/accounts/{id} -------------------- */

    @Nested
    @DisplayName("GET /api/v1/accounts/{id} (FR-1.2)")
    class GetAccount {

        @Test
        void get_existing_returns_200_with_envelope_and_data() throws Exception {
            UUID id = UUID.randomUUID();
            when(accountService.getAccount(id))
                    .thenReturn(sample(id, AccountStatus.ACTIVE, new BigDecimal("50.00")));

            mockMvc.perform(get("/api/v1/accounts/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.balance").value(50.00))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void get_unknown_id_returns_404_NOT_FOUND() throws Exception {
            UUID id = UUID.randomUUID();
            when(accountService.getAccount(id))
                    .thenThrow(new ResourceNotFoundException("Account", id));

            mockMvc.perform(get("/api/v1/accounts/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString(id.toString())));
        }

        @Test
        void get_malformed_uuid_returns_400_TYPE_MISMATCH() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TYPE_MISMATCH"));
        }
    }

    /* -------------------- FR-1.3: GET /api/v1/accounts (pageable) -------------------- */

    @Nested
    @DisplayName("GET /api/v1/accounts (FR-1.3)")
    class ListAccounts {

        @Test
        void list_returns_200_with_paged_envelope() throws Exception {
            UUID id = UUID.randomUUID();
            Page<AccountResponse> page = new PageImpl<>(
                    List.of(sample(id, AccountStatus.ACTIVE, new BigDecimal("100.00"))),
                    PageRequest.of(0, 20),
                    1L);
            when(accountService.listAccounts(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/accounts?page=0&size=20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.data.content[0].balance").value(100.00))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1));
        }
    }

    /* -------------------- FR-1.4 / FR-1.5: PATCH /api/v1/accounts/{id}/status -------------------- */

    @Nested
    @DisplayName("PATCH /api/v1/accounts/{id}/status (FR-1.4, FR-1.5)")
    class UpdateStatus {

        @Test
        void patch_active_to_FROZEN_returns_200() throws Exception {
            UUID id = UUID.randomUUID();
            when(accountService.updateAccountStatus(eq(id), eq(AccountStatus.FROZEN), any()))
                    .thenReturn(sample(id, AccountStatus.FROZEN, new BigDecimal("100.00")));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status":"FROZEN","reason":"compliance review"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FROZEN"))
                    .andExpect(jsonPath("$.data.id").value(id.toString()));
        }

        @Test
        void patch_FROZEN_back_to_ACTIVE_returns_200() throws Exception {
            UUID id = UUID.randomUUID();
            when(accountService.updateAccountStatus(eq(id), eq(AccountStatus.ACTIVE), any()))
                    .thenReturn(sample(id, AccountStatus.ACTIVE, new BigDecimal("100.00")));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        void patch_close_with_nonzero_balance_returns_422_ACCOUNT_NOT_CLOSABLE() throws Exception {
            // FR-1.5: zero-balance invariant enforced at service ⇒ 422 at the seam.
            UUID id = UUID.randomUUID();
            when(accountService.updateAccountStatus(eq(id), eq(AccountStatus.CLOSED), any()))
                    .thenThrow(new AccountNotClosableException("ACC-1001", new BigDecimal("99.00")));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CLOSED\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_CLOSABLE"))
                    .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("99.00")));
        }

        @Test
        void patch_close_with_zero_balance_returns_200_status_CLOSED() throws Exception {
            // FR-1.5 happy path: zero balance is the precondition for close.
            UUID id = UUID.randomUUID();
            when(accountService.updateAccountStatus(eq(id), eq(AccountStatus.CLOSED), any()))
                    .thenReturn(sample(id, AccountStatus.CLOSED, BigDecimal.ZERO));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CLOSED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.status").value("CLOSED"))
                    .andExpect(jsonPath("$.data.balance").value(0));
        }

        @Test
        void patch_illegal_transition_returns_400_INVALID_STATUS_TRANSITION() throws Exception {
            // CLOSED -> ACTIVE is unreachable from ALLOWED_TRANSITIONS; service raises.
            UUID id = UUID.randomUUID();
            when(accountService.updateAccountStatus(eq(id), eq(AccountStatus.ACTIVE), any()))
                    .thenThrow(new InvalidAccountStatusTransitionException("CLOSED", "ACTIVE"));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.message",
                            org.hamcrest.Matchers.containsString("CLOSED")))
                    .andExpect(jsonPath("$.message",
                            org.hamcrest.Matchers.containsString("ACTIVE")));
        }

        @Test
        void patch_missing_status_returns_400_VALIDATION_FAILED() throws Exception {
            UUID id = UUID.randomUUID();
            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='status')]").exists());
        }

        @Test
        void patch_unknown_status_enum_returns_400_with_readable_error_envelope() throws Exception {
            // Polymorphic enum deserialization fails before validation; lands as
            // HttpMessageNotReadableException → MALFORMED_REQUEST.
            UUID id = UUID.randomUUID();
            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"FOO\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }

        @Test
        void patch_reason_exceeds_256_chars_returns_400_VALIDATION_FAILED() throws Exception {
            UUID id = UUID.randomUUID();
            String longReason = "x".repeat(257);
            String body = objectMapper.writeValueAsString(
                    Map.of("status", "FROZEN", "reason", longReason));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").exists());
        }

        @Test
        void patch_unknown_account_id_returns_404_NOT_FOUND() throws Exception {
            UUID id = UUID.randomUUID();
            when(accountService.updateAccountStatus(eq(id), eq(AccountStatus.FROZEN), any()))
                    .thenThrow(new ResourceNotFoundException("Account", id));

            mockMvc.perform(patch("/api/v1/accounts/{id}/status", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"FROZEN\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }
    }
}
