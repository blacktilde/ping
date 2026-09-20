package dev.ping.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a connection probe found, stage by stage.
 *
 * <p>The probe is a <em>separate</em> connection made on request, so none of this is part of any
 * exchange's timing. A stage that did not run (no proxy, no TLS) is null. A failure is reported
 * here, with the stages that did finish, rather than as an RPC error: explaining failures is what
 * a probe is for.
 *
 * @param origin      {@code scheme://host:port} that was probed
 * @param connectedTo who the TCP connection went to: the origin, or the proxy when there is one
 * @param viaProxy    true when {@code connectedTo} is a proxy
 * @param addresses   what {@code connectedTo}'s name resolved to (at most eight)
 * @param dnsMs       name resolution, in milliseconds to a tenth
 * @param connectMs   TCP connect
 * @param tunnelMs    the proxy's answer to {@code CONNECT}, for HTTPS through a proxy
 * @param tlsMs       the TLS handshake
 * @param protocol    negotiated TLS version
 * @param alpn        negotiated application protocol ({@code h2}, {@code http/1.1}), when the server chose
 * @param verified    true when the server's certificate was checked and accepted; false when
 *                    verification was off or failed
 * @param failedStage {@code dns}, {@code connect}, {@code tunnel} or {@code tls}; null on success
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProbeResult(
        String origin,
        String connectedTo,
        boolean viaProxy,
        List<String> addresses,
        Double dnsMs,
        Double connectMs,
        Double tunnelMs,
        Double tlsMs,
        String protocol,
        String cipherSuite,
        String alpn,
        Boolean verified,
        Certificate certificate,
        String failedStage,
        String error) {

    /** The server's leaf certificate, reported even when it was not trusted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Certificate(
            String subject,
            String issuer,
            String notBefore,
            String notAfter,
            List<String> altNames,
            int chainLength) {
    }
}
