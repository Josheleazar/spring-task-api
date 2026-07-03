package com.fintech.payment.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.exception.ApiErrorResponse;
import com.fintech.payment.service.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.Writer;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8 §12.6.3.4 — coverage-push test for {@link IdempotencyFilter}'s
 * cache-miss path branches through the HandlerExceptionResolver routing
 * (Phase 7.x fix, SRS §12.7.2 forward-flag).
 *
 * <p>The existing {@code PaymentControllerTest} exercises the filter
 * end-to-end at the MockMvc slice but stubs {@code IdempotencyService} to
 * always return {@code Optional.empty()} so the cache-miss path is the only
 * branch exercised, and the test doesn't trigger the
 * {@code routeToAdviceOrFallback} defensive path that lands after a
 * {@code lookupStrict} throw or resolver-throw. This class targets exactly
 * those 7 uncovered branches (D, I, J, K, L, M).</p>
 *
 * <h2>Test design</h2>
 *
 * <p>Hermetic hybrid per the Phase-8-Item-3 lessons + Item-4 design pass:</p>
 * <ul>
 *   <li>collaborators (filters + Spring MVC + persistence services) are
 *       pure {@code @Mock} — fastest possible test path, no Spring context.</li>
 *   <li>Servlet API request/response are real
 *       {@link MockHttpServletRequest} / {@link MockHttpServletResponse}
 *       from {@code org.springframework.mock.web} — those classes exist
 *       specifically to be used in unit-test scope, no servlet container
 *       needed. Pure-Mockito stubs for {@code HttpServletResponse#getWriter()}
 *       would return null and break the
 *       {@link ContentCachingResponseWrapper} body-capture semantics.</li>
 *   <li>log verification is intentionally skipped — per the Phase-8-Item-3
 *       "side-effects only" rule, behavioral invariants are pinned via
 *       {@link ArgumentCaptor} on {@code objectMapper.writeValue} and
 *       {@code verify(times(N)).save/.lookupStrict} call counts. Log
 *       capture would require a Logback {@code ListAppender} or
 *       slf4j-test dependency not on the classpath today.</li>
 * </ul>
 *
 * <h2>OncePerRequestFilter re-entry check</h2>
 *
 * <p>Spring's {@code OncePerRequestFilter.doFilter} checks
 * {@code request.getAttribute(alreadyFilteredAttributeName)} for re-entry
 * guard. With {@link MockHttpServletRequest}, {@code getAttribute} returns
 * {@code null} by default → the re-entry guard is absent → the filter
 * proceeds into {@code doFilterInternal} as expected. No stubbing of
 * {@code setAttribute}/{@code removeAttribute} needed.</p>
 *
 * <h2>Why class-level LENIENT</h2>
 *
 * <p>7 tests exercise overlapping but not identical portions of the
 * {@code doFilterInternal} flow (request header read, body cache, lookup,
 * chain dispatch, status check, save call). Mockito's per-stub strict
 * mode would flag the unused stubs as unnecessary; LENIENT eliminates that
 * boilerplate without losing the behavioural-regression net (see
 * {@code verify(times(N))} assertions on each test).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IdempotencyFilter — cache-miss paths through HandlerExceptionResolver routing")
class IdempotencyFilterCoverageTest {

    private static final String IDEMPOTENCY_KEY = "IDEM-FILTER-COVERAGE-TEST";
    private static final String POST_URI = "/api/v1/payments";

    @Mock private IdempotencyService idempotencyService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApplicationContext applicationContext;
    @Mock private HandlerExceptionResolver exceptionResolver;
    @Mock private FilterChain filterChain;

    private IdempotencyFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        // IdempotencyFilter constructor resolves the resolver bean by-name.
        // Stub ApplicationContext.getBean("handlerExceptionResolver", HandlerExceptionResolver.class)
        // → returns our mock resolver so the filter wires its exceptionResolver field with the mock.
        when(applicationContext.getBean("handlerExceptionResolver", HandlerExceptionResolver.class))
                .thenReturn(exceptionResolver);
        filter = new IdempotencyFilter(idempotencyService, objectMapper, applicationContext);

        // Real Spring Test mocks for Servlet API — keep hermeticity, eliminate
        // the brittle "HttpServletResponse mock returns null for getWriter()"
        // gotcha when ContentCachingResponseWrapper tries to capture bytes.
        request = new MockHttpServletRequest("POST", POST_URI);
        request.addHeader("Idempotency-Key", IDEMPOTENCY_KEY);
        response = new MockHttpServletResponse();
    }

    /* ====================================================================
     * RouteToAdviceOrFallback — branches D, I, J, K, L, M
     * ==================================================================== */

    @Nested
    @DisplayName("RouteToAdviceOrFallback — cache-miss exception-routing coverage")
    class RouteToAdviceOrFallback {

        @Test
        @DisplayName("Branch D — lookupStrict throws RuntimeException → routed to HandlerExceptionResolver; chain NOT invoked")
        void lookupStrict_throwsRuntimeException_routedToHandlerExceptionResolver() throws Exception {
            RuntimeException simulated = new RuntimeException("simulated cache-lookup failure");
            when(idempotencyService.lookupStrict(eq(IDEMPOTENCY_KEY), any())).thenThrow(simulated);

            filter.doFilter(request, response, filterChain);

            verify(exceptionResolver, times(1)).resolveException(
                    eq(request), eq(response), eq(filter), any());
            // CRITICAL: filterChain must NOT have been invoked — the exception
            // short-circuited the dispatch BEFORE the chain ran. If the chain
            // were invoked, the controller would commit a response before the
            // resolver could write its advice-driven envelope.
            verify(filterChain, times(0)).doFilter(any(), any());
        }

        @Test
        @DisplayName("Branch I — idempotencyService.save(...) throws post-commit → caught + logged; save WAS called; filter returns normally")
        void save_throwsRuntimeException_caughtAndLogged_doesNotPropagate() throws Exception {
            // Cache-miss → empty lookup result
            when(idempotencyService.lookupStrict(any(), any())).thenReturn(Optional.empty());

            // Mock filterChain to commit 201 + JSON body via the wrapped response —
            // ContentCachingResponseWrapper.getStatus() returns 201 after this
            // writes the wrapper.status field (set by setStatus propagation).
            String responseBody = "{\"paymentId\":\"PAY-001\"}";
            doAnswer(inv -> {
                HttpServletResponse resp = inv.getArgument(1);
                resp.setStatus(201);
                resp.setContentType("application/json");
                resp.getWriter().write(responseBody);
                resp.getWriter().flush();
                return null;
            }).when(filterChain).doFilter(any(), any());

            // The save() call itself throws — Branch I catch-arm fires + log.warn
            // (log assertion intentionally skipped — side-effects only).
            // save(...) is a void method, so we must use doThrow().when() instead of
            // when().thenThrow() — the latter produces a compile error because
            // a void return value cannot be passed to Mockito.when().
            doThrow(new RuntimeException("simulated cache-write failure"))
                    .when(idempotencyService).save(eq(IDEMPOTENCY_KEY), eq(201), anyString(), any());

            // INVOKE: filter.doFilter must NOT throw — writeAudit's catch IS on the path.
            filter.doFilter(request, response, filterChain);

            // Invariants:
            // (a) save() WAS called with the commit status (proves the catch is on the path;
            //     if Branch I's try/catch were missing, this verify wouldn't be reached because
            //     the test would already have failed with a propagated RuntimeException).
            verify(idempotencyService, times(1)).save(eq(IDEMPOTENCY_KEY), eq(201), anyString(), any());
            // (b) Filter returned normally + response body was flushed to client —
            //     the response code stays 201 (not 500), which is correct per
            //     writeAudit's fail-silent contract (Phase 5 audit-aspect analog).
            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(response.getContentAsString()).isEqualTo(responseBody);
        }

        @Test
        @DisplayName("Branch J — resolver returns non-null ModelAndView → advice wrote the response; filter skips its own fallback write")
        void resolverReturnsNonNull_adviceWroteResponse_filterSkipsFallback() throws Exception {
            when(idempotencyService.lookupStrict(eq(IDEMPOTENCY_KEY), any()))
                    .thenThrow(new RuntimeException("simulated cache-lookup failure"));
            // Non-null ModelAndView signals that an @ControllerAdvice wrote a response —
            // the filter early-returns without writing its fallback envelope.
            when(exceptionResolver.resolveException(any(), any(), any(), any()))
                    .thenReturn(new ModelAndView());

            filter.doFilter(request, response, filterChain);

            // Resolver was invoked (the gateway).
            verify(exceptionResolver, times(1)).resolveException(any(), any(), any(), any());
            // CRITICAL: the filter must NOT have written its own fallback envelope.
            // If Branch J's early-return were missing, the filter would overwrite the
            // advice-written response with an INTERNAL_ERROR envelope, contradicting the
            // §12.7.2 design (advice wins).
            verify(objectMapper, times(0)).writeValue(any(Writer.class), any());
        }

        @Test
        @DisplayName("Branch K — resolver returns null → fallback 500 envelope written via objectMapper.writeValue(...) with INTERNAL_ERROR")
        void resolverReturnsNull_fallbackEnvelopeWritten_withINTERNAL_ERROR() throws Exception {
            when(idempotencyService.lookupStrict(eq(IDEMPOTENCY_KEY), any()))
                    .thenThrow(new RuntimeException("simulated cache-lookup failure"));
            // null signals "no @ControllerAdvice matched the exception" —
            // fall through to the ObjectMapper-based fallback envelope write.
            when(exceptionResolver.resolveException(any(), any(), any(), any())).thenReturn(null);

            filter.doFilter(request, response, filterChain);

            // Resolver was invoked (the gateway).
            verify(exceptionResolver, times(1)).resolveException(any(), any(), any(), any());

            // CRITICAL: the fallback envelope WAS written via objectMapper — pin its
            // shape via ArgumentCaptor so a future refactor cannot accidentally drop
            // the status=500 / error=INTERNAL_ERROR semantics.
            ArgumentCaptor<ApiErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ApiErrorResponse.class);
            verify(objectMapper, times(1)).writeValue(any(Writer.class), bodyCaptor.capture());

            ApiErrorResponse written = bodyCaptor.getValue();
            assertThat(written.status()).as("fallback envelope sets HTTP status 500").isEqualTo(500);
            assertThat(written.error()).as("fallback envelope error tag").isEqualTo("INTERNAL_ERROR");
            assertThat(written.message())
                    .as("fallback envelope message identifies the filter path")
                    .isEqualTo("Idempotency filter path failure");
            assertThat(written.path())
                    .as("fallback envelope captures the request URI for client diagnostics")
                    .isEqualTo(POST_URI);
            assertThat(written.timestamp())
                    .as("fallback envelope carries an Instant.now() timestamp")
                    .isNotNull();
            // The Spring MockHttpServletResponse.verify() flow reflects the
            // writeValue result via status 500 + content-type JSON.
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("Branch L — resolver itself throws RuntimeException → caught; fallback envelope STILL written (resilient)")
        void resolverThrows_fallbackEnvelopeStillWritten() throws Exception {
            when(idempotencyService.lookupStrict(eq(IDEMPOTENCY_KEY), any()))
                    .thenThrow(new RuntimeException("simulated cache-lookup failure"));
            // The resolver composite itself blows up — Branch L's outer catch
            // logs the resolver failure and FALLS THROUGH to the fallback write
            // path (resilient design — clients always see a coherent contract).
            when(exceptionResolver.resolveException(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("simulated resolver chain failure"));

            filter.doFilter(request, response, filterChain);

            // CRITICAL: the resolver chain exception was swallowed, and the fallback
            // envelope was still written. If Branch L's catch were missing, the
            // resolver exception would propagate up to OncePerRequestFilter and
            // become a Tomcat 500 error page, breaking the §12.7.2 contract.
            ArgumentCaptor<ApiErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ApiErrorResponse.class);
            verify(objectMapper, times(1)).writeValue(any(Writer.class), bodyCaptor.capture());
            assertThat(bodyCaptor.getValue().error())
                    .as("Branch L resilience — fallback envelope still present after resolver throws")
                    .isEqualTo("INTERNAL_ERROR");
        }

        @Test
        @DisplayName("Branch M — response already committed BEFORE filter → fallback envelope write gated; objectMapper.writeValue NOT invoked")
        void responseAlreadyCommitted_fallbackEnvelopeSkipped_noWriteAttempted() throws Exception {
            when(idempotencyService.lookupStrict(eq(IDEMPOTENCY_KEY), any()))
                    .thenThrow(new RuntimeException("simulated cache-lookup failure"));
            when(exceptionResolver.resolveException(any(), any(), any(), any())).thenReturn(null);

            // PRE-COMMIT the response BEFORE filter.doFilter — simulates a controller
            // upstream that wrote a partial body before the filter's exception routing.
            response.setStatus(500);
            response.getWriter().write("already committed body upstream");
            response.getWriter().flush();
            assertThat(response.isCommitted()).as("test fixture check — response committed pre-call").isTrue();

            filter.doFilter(request, response, filterChain);

            // Resolver is still called (it's the gateway before the fallback path).
            verify(exceptionResolver, times(1)).resolveException(any(), any(), any(), any());
            // CRITICAL: objectMapper.writeValue is NOT invoked because response.isCommitted()
            // gates the fallback write. Calling write would throw IllegalStateException
            // ("Response already committed") — defensive branch M is essential.
            verify(objectMapper, times(0)).writeValue(any(Writer.class), any());
        }

    }
}
