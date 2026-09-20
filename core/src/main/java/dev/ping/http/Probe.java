package dev.ping.http;

import dev.ping.rpc.RpcException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures how a connection to a host comes together, on a socket of its own.
 *
 * <p>{@code java.net.http} does not report when the TCP connection or the TLS handshake finished,
 * and an invented split was refused in phase 5. This is the honest alternative: connect a second
 * socket the way the client would (through the proxy and its {@code CONNECT} tunnel when one is
 * configured, offering the same client certificate), time each stage, and say it is a second
 * connection. A different connection can take a different path or find a warm cache, which is why the
 * result is shown apart from the exchange's own timing and never added to it.
 */
public final class Probe {

    private static final int MAX_ADDRESSES = 8;
    private static final int MAX_ALT_NAMES = 10;
    private static final int MAX_HEAD_BYTES = 16 * 1024;

    private Probe() {
    }

    /**
     * @param uri       only the scheme, host and port are used: the path and query may carry secrets
     * @param verifyTls false accepts any certificate (it is still reported)
     * @throws RpcException {@code -32602} for a URL that is not http(s) with a host, or an unusable
     *                      proxy or certificate setting; every network failure is a result instead
     */
    public static ProbeResult run(URI uri, boolean verifyTls, int timeoutMs, NetworkConfig network) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            throw RpcException.invalidParams("A probe needs an http:// or https:// address with a host");
        }
        boolean tls = scheme.equals("https");
        int port = uri.getPort() >= 0 ? uri.getPort() : tls ? 443 : 80;
        String host = uri.getHost();
        String origin = scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");

        NetworkConfig net = network == null ? NetworkConfig.NONE : network;
        ProxyRouter router = net.router();
        ProxyRouter.Endpoint proxy = router == null ? null : router.proxyFor(uri);
        X509ExtendedKeyManager keys = tls ? ClientCerts.keyManager(net.clientCerts()) : null;

        String targetHost = proxy != null ? proxy.host() : host;
        int targetPort = proxy != null ? proxy.port() : port;
        Builder result = new Builder(origin, targetHost + ":" + targetPort, proxy != null);

        InetAddress[] resolved;
        long started = System.nanoTime();
        try {
            resolved = InetAddress.getAllByName(targetHost);
        } catch (UnknownHostException e) {
            result.dnsMs = since(started);
            return result.failed("dns", "Unknown host: " + targetHost);
        }
        result.dnsMs = since(started);
        for (int i = 0; i < Math.min(resolved.length, MAX_ADDRESSES); i++) {
            result.addresses.add(resolved[i].getHostAddress());
        }

        Socket socket = new Socket();
        try {
            started = System.nanoTime();
            IOException last = null;
            boolean connected = false;
            for (InetAddress address : resolved) {
                try {
                    socket.connect(new InetSocketAddress(address, targetPort), timeoutMs);
                    connected = true;
                    break;
                } catch (IOException e) {
                    last = e;
                    socket = new Socket();
                }
            }
            result.connectMs = since(started);
            if (!connected) {
                return result.failed("connect", describeConnect(last, timeoutMs));
            }
            socket.setSoTimeout(timeoutMs);

            if (proxy != null && tls) {
                started = System.nanoTime();
                String refusal = tunnel(socket, host, port, router.authorizationFor(uri));
                result.tunnelMs = since(started);
                if (refusal != null) {
                    return result.failed("tunnel", refusal);
                }
            }
            if (!tls) {
                return result.build();
            }
            return handshake(result, socket, host, port, verifyTls, keys);
        } catch (IOException e) {
            return result.failed(result.connectMs == null ? "connect" : "tunnel", messageOf(e));
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // The probe is over; a close failure is not a finding.
            }
        }
    }

    // --- stages -------------------------------------------------------------------------------

    /** Sends {@code CONNECT} and reads the proxy's answer; returns null on success, else why not. */
    private static String tunnel(Socket socket, String host, int port, String authorization) throws IOException {
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n");
        if (authorization != null) {
            request.append("Proxy-Authorization: ").append(authorization).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();

        // Read the head a byte at a time: anything past it belongs to the TLS handshake.
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int window = 0; // the last four bytes; the head ends at CR LF CR LF
        while (window != 0x0D0A0D0A && head.size() < MAX_HEAD_BYTES) {
            int next = in.read();
            if (next < 0) {
                return "The proxy closed the connection without answering CONNECT";
            }
            head.write(next);
            window = (window << 8) | next;
        }
        String statusLine = head.toString(StandardCharsets.ISO_8859_1).split("\r\n", 2)[0];
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[1].startsWith("2")) {
            return "The proxy refused CONNECT: " + (statusLine.isBlank() ? "no status" : statusLine);
        }
        return null;
    }

    private static ProbeResult handshake(
            Builder result, Socket socket, String host, int port, boolean verifyTls,
            X509ExtendedKeyManager keys) throws IOException {
        CapturingTrust trust = new CapturingTrust(verifyTls ? defaultTrust() : null);
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLS");
            context.init(keys == null ? null : new KeyManager[] {keys}, new TrustManager[] {trust}, null);
        } catch (GeneralSecurityException e) {
            throw new RpcException(RpcException.INTERNAL_ERROR, "Could not set up TLS", e);
        }

        long started = System.nanoTime();
        try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true)) {
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {"h2", "http/1.1"});
            parameters.setEndpointIdentificationAlgorithm(verifyTls ? "HTTPS" : null);
            tls.setSSLParameters(parameters);
            try {
                tls.startHandshake();
            } catch (SSLException e) {
                result.tlsMs = since(started);
                result.certificate = trust.certificate();
                result.verified = false;
                return result.failed("tls", "TLS handshake failed: " + messageOf(e));
            }
            result.tlsMs = since(started);
            result.protocol = tls.getSession().getProtocol();
            result.cipherSuite = tls.getSession().getCipherSuite();
            String alpn = tls.getApplicationProtocol();
            result.alpn = alpn == null || alpn.isEmpty() ? null : alpn;
            result.verified = verifyTls;
            result.certificate = trust.certificate();
            return result.build();
        }
    }

    /** The platform's trust, or null when none can be built (which then fails every check). */
    private static X509TrustManager defaultTrust() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
        } catch (GeneralSecurityException e) {
            // Fall through: without trust roots every certificate is refused, which is the safe answer.
        }
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No trust roots are available");
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No trust roots are available");
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * Records the server's chain before deciding, so an expired or untrusted certificate is still
     * shown: that is the case a probe is most often run for. With no delegate everything is accepted.
     */
    private static final class CapturingTrust implements X509TrustManager {

        private final X509TrustManager delegate;
        private volatile X509Certificate[] chain;

        CapturingTrust(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
            throw new CertificateException("Not a server");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
            chain = certs == null ? null : certs.clone();
            if (delegate != null) {
                delegate.checkServerTrusted(certs, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate == null ? new X509Certificate[0] : delegate.getAcceptedIssuers();
        }

        ProbeResult.Certificate certificate() {
            X509Certificate[] seen = chain;
            if (seen == null || seen.length == 0) {
                return null;
            }
            X509Certificate leaf = seen[0];
            List<String> names = new ArrayList<>();
            try {
                var alternatives = leaf.getSubjectAlternativeNames();
                if (alternatives != null) {
                    for (List<?> entry : alternatives) {
                        // 2 is a DNS name, 7 an IP address; the rest are not something a user reads.
                        if (names.size() < MAX_ALT_NAMES && entry.size() >= 2
                                && (Integer.valueOf(2).equals(entry.get(0)) || Integer.valueOf(7).equals(entry.get(0)))) {
                            names.add(String.valueOf(entry.get(1)));
                        }
                    }
                }
            } catch (CertificateException e) {
                // Unreadable names: report the certificate without them.
            }
            return new ProbeResult.Certificate(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getNotBefore().toInstant().toString(),
                    leaf.getNotAfter().toInstant().toString(),
                    names,
                    seen.length);
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Milliseconds to a tenth: loopback stages are well under one, and "0 ms" would look broken. */
    private static double since(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 100_000.0) / 10.0;
    }

    private static String describeConnect(IOException e, int timeoutMs) {
        if (e instanceof SocketTimeoutException) {
            return "Connect timed out after " + timeoutMs + " ms";
        }
        if (e instanceof ConnectException) {
            return "Connection refused";
        }
        if (e instanceof NoRouteToHostException) {
            return "No route to host";
        }
        return messageOf(e);
    }

    private static String messageOf(Throwable e) {
        if (e instanceof SocketTimeoutException) {
            return "Timed out waiting for the server";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Collects the fields as the stages report them. */
    private static final class Builder {
        final String origin;
        final String connectedTo;
        final boolean viaProxy;
        final List<String> addresses = new ArrayList<>();
        Double dnsMs;
        Double connectMs;
        Double tunnelMs;
        Double tlsMs;
        String protocol;
        String cipherSuite;
        String alpn;
        Boolean verified;
        ProbeResult.Certificate certificate;

        Builder(String origin, String connectedTo, boolean viaProxy) {
            this.origin = origin;
            this.connectedTo = connectedTo;
            this.viaProxy = viaProxy;
        }

        ProbeResult build() {
            return result(null, null);
        }

        ProbeResult failed(String stage, String error) {
            return result(stage, error);
        }

        private ProbeResult result(String stage, String error) {
            return new ProbeResult(origin, connectedTo, viaProxy, addresses.isEmpty() ? null : addresses,
                    dnsMs, connectMs, tunnelMs, tlsMs, protocol, cipherSuite, alpn, verified, certificate,
                    stage, error);
        }
    }
}
