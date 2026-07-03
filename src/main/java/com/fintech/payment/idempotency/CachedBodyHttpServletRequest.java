package com.fintech.payment.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Phase 6 — request wrapper that caches the body bytes on first read so
 * they can be read twice. Standard servlet streams are single-shot, which
 * collides with the Phase-6 body-hash flow:
 * <ol>
 *   <li>{@code IdempotencyFilter} reads the bytes to compute SHA-256.</li>
 *   <li>{@code PaymentController.submit} reads the same bytes via
 *       Jackson {@code @RequestBody} deserialization.</li>
 * </ol>
 *
 * <p>This wrapper delegates everything except {@code getInputStream()} and
 * {@code getReader()} to the underlying request; those two methods return
 * view-objects backed by the cached byte[] on first invocation.</p>
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    /** Cached request-body bytes — read once at construction. */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream backing = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return backing.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) { /* no-op */ }
            @Override public int read() { return backing.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset cs = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(cachedBody), cs));
    }
}
