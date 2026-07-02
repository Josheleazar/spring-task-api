package com.fintech.payment.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised mapping of domain and framework exceptions to HTTP responses
 * following the {@link ApiErrorResponse} contract defined in SRS §8.
 *
 * <p>Domain exceptions declare their own HTTP status via
 * {@link DomainException#getHttpStatus()}, so this handler is single-pass
 * for the entire {@code DomainException} family.</p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /* -------------------- Domain exceptions -------------------- */

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomain(DomainException ex, HttpServletRequest request) {
        log.debug("Domain exception: code={} status={} message={}",
                ex.getErrorCode(), ex.getHttpStatus(), ex.getMessage());
        return build(ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage(), request, null, null);
    }

    /* -------------------- Routing (404 / 405 / 406 / 415) -------------------- */

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex,
                                                            HttpServletRequest request) {
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "No handler for %s %s".formatted(request.getMethod(), request.getRequestURI()),
                request, null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                             HttpServletRequest request) {
        log.debug("No resource for {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "No resource for %s %s".formatted(request.getMethod(), request.getRequestURI()),
                request, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                    HttpServletRequest request) {
        String supported = ex.getSupportedHttpMethods() == null
                ? "(none advertised)"
                : ex.getSupportedHttpMethods().stream().map(Object::toString).collect(Collectors.joining(", "));
        Map<String, Object> details = Map.of("supportedMethods", supported);
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Method %s is not supported for this resource. Supported methods: %s"
                        .formatted(request.getMethod(), supported),
                request, details, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request) {
        String supported = ex.getSupportedMediaTypes().isEmpty()
                ? "(none advertised)"
                : ex.getSupportedMediaTypes().stream().map(Object::toString).collect(Collectors.joining(", "));
        String requested = ex.getContentType() == null ? "(none)" : ex.getContentType().toString();
        Map<String, Object> details = Map.of(
                "requestedMediaType", requested,
                "supportedMediaTypes", supported);
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Media type '%s' is not supported for this resource. Supported: %s"
                        .formatted(requested, supported),
                request, details, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                                HttpServletRequest request) {
        String producible = ex.getSupportedMediaTypes().isEmpty()
                ? "(none advertised)"
                : ex.getSupportedMediaTypes().stream().map(Object::toString).collect(Collectors.joining(", "));
        Map<String, Object> details = Map.of("supportedMediaTypes", producible);
        return build(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE",
                "None of the requested media types can be produced. Producible: %s".formatted(producible),
                request, details, null);
    }

    /* -------------------- Concurrency -------------------- */

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                 HttpServletRequest request) {
        // FR-5.2: concurrency conflict -> 409 with retry guidance.
        log.debug("Optimistic lock failure at {}", request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                "The resource was modified by another request. Retry the operation.",
                request, Map.of("retryable", Boolean.TRUE), null);
    }

    /* -------------------- Data integrity --------------------
     *
     * Backstop for unique-constraint violations that slip past the service-level
     * {@code existsBy<UniqueField>(...)} check (TOCTOU window) and pre-emptive
     * handling for FK-error envelopes before Phase 3 introduces payment FKs.
     * Splits the envelope by ANSI SQL state:
     *
     *   23505 unique_violation          -> 409 DUPLICATE_RESOURCE
     *   23503 foreign_key_violation     -> 404 REFERENCED_RESOURCE_NOT_FOUND
     *   23502 not_null_violation        -> 422 DATA_INTEGRITY_VIOLATION
     *   23514 check_violation           -> 422 DATA_INTEGRITY_VIOLATION
     *   23000 generic integrity         -> 422 DATA_INTEGRITY_VIOLATION
     *
     * 404 is preferred over 422 for FK because the failure mode is uniformly
     * "this referenced id does not exist", which clients can act on by
     * re-fetching the referenced resource. 422 would conflate FK misses with
     * semantically-malformed bodies (regex / range failures).
     *
     * Every envelope carries {@code details.sqlState} so client logs / dashboards
     * never depend on parsing the message string to tell uniqueness from FK misses.
     * See <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">PostgreSQL errcodes</a>
     * — H2 in PG-compat mode uses the same codes.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.debug("Data integrity violation at {}", request.getRequestURI(), ex);
        String sqlState = extractSqlState(ex);
        Map<String, Object> details = sqlState == null ? null : Map.of("sqlState", sqlState);
        if ("23505".equals(sqlState)) {
            return build(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE",
                    "A resource with these unique properties already exists",
                    request, details, null);
        }
        if ("23503".equals(sqlState)) {
            return build(HttpStatus.NOT_FOUND, "REFERENCED_RESOURCE_NOT_FOUND",
                    "The request references a resource that does not exist",
                    request, details, null);
        }
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_INTEGRITY_VIOLATION",
                "The request would violate a database constraint",
                request, details, null);
    }

    /**
     * Returns the SQLState of the outermost {@link SQLException} in the
     * cause chain, or {@code null} if none is present. Spring's
     * {@code DataIntegrityViolationException} wraps the underlying JDBC
     * exception, so the relevant SQLException is typically the first frame
     * embedded inside Spring's wrapper. Both cycle and depth are guarded so
     * a hypothetical pathological cause-chain cannot spin the handler.
     */
    private static String extractSqlState(Throwable t) {
        Throwable current = t;
        int depth = 0;
        final int maxDepth = 32;  // cycle guard + paranoia cap
        while (current != null && depth++ < maxDepth) {
            if (current instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                return sqlEx.getSQLState();
            }
            Throwable next = current.getCause();
            if (next == current || next == null) break;
            current = next;
        }
        return null;
    }

    /* -------------------- Validation -------------------- */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex,
                                                                 HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed for request body", request, null, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                     HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> ApiErrorResponse.FieldError.of(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed for request parameters", request, null, errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '%s' is missing".formatted(ex.getParameterName()), request, null, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
                "Parameter '%s' has invalid value '%s'".formatted(ex.getName(), ex.getValue()), request, null, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body could not be parsed", request, null, null);
    }

    /* -------------------- Fallback -------------------- */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Log with stack trace but never expose internals to the client.
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request, null, null);
    }

    /* -------------------- Helpers -------------------- */

    private ApiErrorResponse.FieldError toFieldError(FieldError fe) {
        return ApiErrorResponse.FieldError.of(fe.getField(), fe.getDefaultMessage());
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status,
                                                   String error,
                                                   String message,
                                                   HttpServletRequest request,
                                                   Map<String, Object> details,
                                                   List<ApiErrorResponse.FieldError> fieldErrors) {
        ApiErrorResponse body = new ApiErrorResponse(
                status.value(),
                error,
                message,
                Instant.now(),
                request.getRequestURI(),
                details,
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }
}
