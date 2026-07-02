package com.fintech.payment.model.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/accounts} (FR-1.1).
 *
 * <p>Field contract enforced by bean validation:</p>
 * <ul>
 *   <li>{@code accountNumber} — required, ≤32 chars. Unique-ness is enforced at
 *       the service layer (returns 409 {@code DUPLICATE_ACCOUNT_NUMBER}).</li>
 *   <li>{@code accountHolder} — required, ≤128 chars.</li>
 *   <li>{@code initialBalance} — optional. When present must be ≥ 0; defaulted
 *       to {@link BigDecimal#ZERO} in the service layer. Decimal scale is
 *       <em>not</em> pre-validated here — the entity column ({@code NUMERIC(18,2)})
 *       rounds on persist.</li>
 *   <li>{@code currency} — required, 3 uppercase letters. Matches the ISO 4217
 *       format. A strict ISO 4217 code-list validator is out of scope for Phase 2.</li>
 * </ul>
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 32) String accountNumber,
        @NotBlank @Size(max = 128) String accountHolder,
        @DecimalMin(value = "0.00", inclusive = true) BigDecimal initialBalance,
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 currency code (3 uppercase letters)")
        String currency) {
}
