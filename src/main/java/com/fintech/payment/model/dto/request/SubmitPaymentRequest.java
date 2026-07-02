package com.fintech.payment.model.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/payments} (FR-2.1).
 *
 * <p>The {@code Idempotency-Key} HTTP header is read separately by the
 * controller (with its own pattern/length validation) and forwarded to the
 * service layer. We deliberately keep idempotency metadata out of the body
 * so a body-hash on the cache can be a drop-in addition (Phase 6 hardening)
 * without breaking the request contract today.</p>
 *
 * <ul>
 *   <li>{@code sourceAccountId}, {@code targetAccountId}  — must be non-null UUIDs.
 *       FR-2.5 self-transfer is enforced at the service layer (returns
 *       {@code SELF_TRANSFER} → 400).</li>
 *   <li>{@code amount} — strictly positive {@code >= 0.01}; fractional BigDecimal
 *       accepted, with the entity column {@code NUMERIC(18,2)} rounding on persist.</li>
 *   <li>{@code currency} — 3 uppercase letters, must match both accounts' currencies
 *       or {@link com.fintech.payment.exception.CurrencyMismatchException}
 *       (422 {@code CURRENCY_MISMATCH}) is raised.</li>
 * </ul>
 */
public record SubmitPaymentRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID targetAccountId,
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 currency code (3 uppercase letters)")
        @Size(max = 3) String currency) {
}
