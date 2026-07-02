package com.fintech.payment.service;

import com.fintech.payment.exception.AccountNotActiveException;
import com.fintech.payment.exception.CurrencyMismatchException;
import com.fintech.payment.exception.InsufficientFundsException;
import com.fintech.payment.exception.InvalidPaymentStateException;
import com.fintech.payment.exception.ResourceNotFoundException;
import com.fintech.payment.exception.SelfTransferException;
import com.fintech.payment.model.dto.request.SubmitPaymentRequest;
import com.fintech.payment.model.dto.response.PaymentResponse;
import com.fintech.payment.model.entity.Account;
import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.enums.AccountStatus;
import com.fintech.payment.model.enums.PaymentStatus;
import com.fintech.payment.repository.AccountRepository;
import com.fintech.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 payment service — covers FR-2.1 .. FR-2.6 + reverse.
 *
 * <p>Transaction strategy mirrors Phase 2's
 * {@link AccountService}: class-level {@code @Transactional(readOnly = true)}
 * so default reads run in a read-only transaction context; each mutating
 * method overrides with {@code @Transactional} (read-write).</p>
 *
 * <p>Atomicity (FR-2.6) is satisfied by a single {@code @Transactional}
 * method that performs the load → validate → debit → credit → save sequence
 * with no I/O or async detours. The {@code @Version} columns on
 * {@link Account} and {@link Payment} handle lost-update races (FR-5.2).</p>
 *
 * <p>Self-invocation gotcha (Phase 2 deep-review lesson): mutating helpers
 * below {@link #submitPayment} and {@link #reversePayment} are NOT factored
 * into a shared private {@code @Transactional} method — both methods are
 * self-contained, single-method transactions. {@code reversePayment} calls
 * {@code submitPayment}-style code paths <em>only</em> via the public proxy,
 * so the AOP boundary is preserved.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;

    /* -------------------- FR-2.1: Submit payment -------------------- */

    /**
     * Atomic debit + credit within a single transaction. Validates
     * FR-2.4 (ACTIVE), FR-2.5 (not self), currency match, FR-2.3
     * (sufficient funds), then performs FR-2.6.
     *
     * <p>The idempotency-key uniqueness check is enforced by the
     * {@code @UniqueConstraint} on {@link Payment#getIdempotencyKey()};
     * a TOCTOU race on a brand-new key raises
     * {@code DataIntegrityViolationException} with SQL state 23505, which
     * {@link com.fintech.payment.exception.GlobalExceptionHandler}
     * maps to {@code 409 IDEMPOTENCY_KEY_CONFLICT}.</p>
     */
    @Transactional
    public PaymentResponse submitPayment(String idempotencyKey, SubmitPaymentRequest request) {
        // 1. Source account — must exist and be ACTIVE (FR-2.4)
        Account source = accountRepository.findById(request.sourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.sourceAccountId()));
        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(source.getAccountNumber(), source.getStatus());
        }

        // 2. Target account — must exist and be ACTIVE (FR-2.4)
        Account target = accountRepository.findById(request.targetAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.targetAccountId()));
        if (target.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(target.getAccountNumber(), target.getStatus());
        }

        // 3. Self-transfer prevention (FR-2.5)
        if (source.getId().equals(target.getId())) {
            throw new SelfTransferException(source.getId());
        }

        // 4. Currency match (Phase 3 KISS — exact match required)
        if (!source.getCurrency().equals(request.currency())
                || !target.getCurrency().equals(request.currency())) {
            throw new CurrencyMismatchException(
                    request.currency(), source.getCurrency(), target.getCurrency());
        }

        // 5. Sufficient funds (FR-2.3)
        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    source.getAccountNumber(), request.amount(), source.getBalance());
        }

        // 6. Atomic debit + credit (FR-2.6). The @Version columns on both
        //    accounts and on the Payment raise OptimisticLockingFailureException
        //    on concurrent races — that path surfaces as 409 CONCURRENCY_CONFLICT
        //    per FR-5.2 (retry guidance in the response details).
        source.setBalance(source.getBalance().subtract(request.amount()));
        target.setBalance(target.getBalance().add(request.amount()));
        accountRepository.save(source);
        accountRepository.save(target);

        // 7. Persist Payment record
        Payment payment = new Payment();
        payment.setIdempotencyKey(idempotencyKey);
        payment.setSourceAccountId(source.getId());
        payment.setTargetAccountId(target.getId());
        payment.setAmount(request.amount());
        payment.setCurrency(request.currency());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setProcessedAt(Instant.now());
        Payment saved = paymentRepository.save(payment);

        log.info("Payment {} {}->{} amount={} {} idempotencyKey={}",
                saved.getId(),
                source.getAccountNumber(), target.getAccountNumber(),
                saved.getAmount(), saved.getCurrency(),
                saved.getIdempotencyKey());
        return PaymentResponse.from(saved);
    }

    /* -------------------- GET endpoints -------------------- */

    public PaymentResponse getPayment(UUID id) {
        return paymentRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    public Page<PaymentResponse> listPayments(PaymentStatus status, Pageable pageable) {
        Page<Payment> page = (status == null)
                ? paymentRepository.findAll(pageable)
                : paymentRepository.findByStatus(status, pageable);
        return page.map(PaymentResponse::from);
    }

    /* -------------------- Reverse a completed payment -------------------- */

    /**
     * Reverses a {@code COMPLETED} payment by restoring the source/target
     * balances and marking the payment {@code REVERSED}. Called by
     * {@code POST /api/v1/payments/{id}/reverse}.
     *
     * <p>Pre-condition: payment must exist and be {@code COMPLETED}. Any
     * other state (PENDING / FAILED / REVERSED) raises
     * {@link InvalidPaymentStateException}.</p>
     */
    @Transactional
    public PaymentResponse reversePayment(UUID id, String reason) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStateException(payment.getStatus());
        }

        Account source = accountRepository.findById(payment.getSourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account", payment.getSourceAccountId()));
        Account target = accountRepository.findById(payment.getTargetAccountId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account", payment.getTargetAccountId()));

        source.setBalance(source.getBalance().add(payment.getAmount()));
        target.setBalance(target.getBalance().subtract(payment.getAmount()));
        accountRepository.save(source);
        accountRepository.save(target);

        payment.setStatus(PaymentStatus.REVERSED);
        payment.setFailureReason(reason);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment {} reversed (reason='{}')", saved.getId(), reason);
        return PaymentResponse.from(saved);
    }

    /**
     * Convenience accessor for the idempotency-uniqueness race handler —
     * unused at the moment, kept as a hint that the only DB-level integrity
     * check that distinguishes this domain is the UNIQUE constraint on
     * {@code payments.idempotency_key}. The
     * {@link com.fintech.payment.exception.GlobalExceptionHandler} parses the
     * cause message for this constraint name.
     */
    public static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_payments_idempotency_key";
}
