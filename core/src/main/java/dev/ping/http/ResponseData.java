package dev.ping.http;

import java.util.List;

/**
 * Everything the UI needs to render one exchange.
 *
 * <p>Sizes are reported separately from the body text because the body may be truncated,
 * and "how big was it" stays a useful answer even when the bytes were discarded.
 */
public record ResponseData(
        int status,
        String httpVersion,
        List<Header> headers,
        BodyData body,
        Timing timing,
        List<Redirect> redirects,
        List<AssertionResult> assertions,
        List<CaptureResult> captured) {

    /** Assertions run after the exchange is assembled, so they see exactly what the UI will. */
    public ResponseData withAssertions(List<AssertionResult> results) {
        return new ResponseData(status, httpVersion, headers, body, timing, redirects, results, captured);
    }

    /**
     * Values pulled out by the request's {@code capture} list. They hold live data (often a
     * token), so they travel only from the core to the shell, which stores them and removes
     * the values before anything reaches the renderer.
     */
    public ResponseData withCaptured(List<CaptureResult> results) {
        return new ResponseData(status, httpVersion, headers, body, timing, redirects, assertions, results);
    }

    public record Header(String name, String value) {
    }

    /**
     * @param content   decoded text, or null when the payload is not textual or was truncated away
     * @param base64    the kept bytes as base64 when the payload is not textual; null otherwise
     * @param truncated true when {@code bytes} exceeded the configured cap
     * @param bytes     total bytes received, whether or not they were kept
     */
    public record BodyData(
            String content,
            String base64,
            boolean truncated,
            long bytes,
            boolean textual,
            String contentType,
            String charset) {
    }

    /**
     * What {@code java.net.http} can honestly report.
     *
     * <p>{@code dnsMs} is measured by resolving the host immediately before dispatch.
     * TCP-connect and TLS-handshake timings are deliberately absent: the JDK client does not
     * expose them, and inventing a split would be worse than omitting it. See docs/PLAN.md.
     *
     * @param ttfbMs     dispatch until response headers are available
     * @param downloadMs headers available until the body is fully read
     */
    public record Timing(Long dnsMs, long ttfbMs, long downloadMs, long totalMs) {
    }

    /** One hop of a followed redirect chain, oldest first. */
    public record Redirect(int status, String url, String location) {
    }
}
