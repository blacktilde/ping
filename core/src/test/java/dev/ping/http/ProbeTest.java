package dev.ping.http;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import dev.ping.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The connection probe against real sockets: each stage, and each way it can stop. */
class ProbeTest {

    @TempDir
    Path files;

    private HttpServer plain;
    private HttpsServer secure;

    @BeforeEach
    void start() throws Exception {
        plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plain.start();
        secure = https();
    }

    @AfterEach
    void stop() {
        plain.stop(0);
        secure.stop(0);
    }

    private static HttpsServer https() throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(TlsFixtures.serverConfigurator(false));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static URI uri(String scheme, String host, int port) {
        return URI.create(scheme + "://" + host + ":" + port + "/some/path?token=hunter2");
    }

    private ProbeResult probe(URI uri, boolean verify) {
        return Probe.run(uri, verify, 5000, NetworkConfig.NONE);
    }

    @Test
    void aPlainConnectionHasResolveAndConnectStagesAndNothingElse() {
        ProbeResult result = probe(uri("http", "127.0.0.1", plain.getAddress().getPort()), true);

        assertNull(result.failedStage(), String.valueOf(result.error()));
        assertNotNull(result.dnsMs());
        assertNotNull(result.connectMs());
        assertTrue(result.connectMs() >= 0);
        assertNull(result.tunnelMs());
        assertNull(result.tlsMs());
        assertNull(result.protocol());
        assertNull(result.certificate());
        assertEquals("127.0.0.1:" + plain.getAddress().getPort(), result.connectedTo());
        assertFalse(result.viaProxy());
        assertEquals(List.of("127.0.0.1"), result.addresses());
    }

    @Test
    void theOriginNeverCarriesThePathOrTheQuery() {
        ProbeResult result = probe(uri("http", "127.0.0.1", plain.getAddress().getPort()), true);
        assertEquals("http://127.0.0.1:" + plain.getAddress().getPort(), result.origin());
        assertFalse(result.toString().contains("hunter2"));
    }

    @Test
    void aSendReportsTheOriginOfItsFinalRequestWithoutThePathQueryOrUserinfo() {
        plain.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        int port = plain.getAddress().getPort();
        ResponseData response = new HttpEngine().send(
                new RequestSpec.Builder("http://user:pw@127.0.0.1:" + port + "/deep/path?api_key=hunter2").build());
        assertEquals("http://127.0.0.1:" + port, response.origin());
    }

    @Test
    void aTlsHandshakeIsTimedAndTheCertificateReported() {
        ProbeResult result = probe(uri("https", "127.0.0.1", secure.getAddress().getPort()), false);

        assertNull(result.failedStage(), String.valueOf(result.error()));
        assertNotNull(result.tlsMs());
        assertTrue(result.protocol().startsWith("TLSv1."), result.protocol());
        assertNotNull(result.cipherSuite());
        assertFalse(result.verified(), "verification was off, so nothing was verified");
        ProbeResult.Certificate certificate = result.certificate();
        assertEquals("CN=localhost", certificate.subject());
        assertEquals("CN=Ping Test CA", certificate.issuer());
        assertTrue(Instant.parse(certificate.notAfter()).isAfter(Instant.now()));
        assertTrue(certificate.altNames().contains("localhost"), certificate.altNames().toString());
        assertTrue(certificate.altNames().contains("127.0.0.1"), certificate.altNames().toString());
        assertEquals(2, certificate.chainLength(), "the leaf and the CA the server sent");
    }

    @Test
    void anUntrustedCertificateFailsTheHandshakeButIsStillShown() {
        ProbeResult result = probe(uri("https", "127.0.0.1", secure.getAddress().getPort()), true);

        assertEquals("tls", result.failedStage());
        assertTrue(result.error().startsWith("TLS handshake failed"), result.error());
        assertNotNull(result.dnsMs());
        assertNotNull(result.connectMs());
        assertEquals("CN=localhost", result.certificate().subject(), "the case a probe is run for");
        assertFalse(result.verified());
    }

    @Test
    void aClosedPortIsAConnectFailureWithTheResolveStillReported() throws IOException {
        int free;
        try (ServerSocket socket = new ServerSocket(0)) {
            free = socket.getLocalPort();
        }
        ProbeResult result = probe(uri("http", "127.0.0.1", free), true);

        assertEquals("connect", result.failedStage());
        assertEquals("Connection refused", result.error());
        assertNotNull(result.dnsMs());
    }

    @Test
    void anUnknownNameIsAResolveFailure() {
        ProbeResult result = probe(uri("http", "no-such-host.invalid", 80), true);

        assertEquals("dns", result.failedStage());
        assertTrue(result.error().contains("no-such-host.invalid"), result.error());
        assertNull(result.connectMs());
    }

    @Test
    void anHttpsOriginThroughAProxyAddsATunnelStage() throws Exception {
        try (TunnelProxy proxy = new TunnelProxy()) {
            proxy.requiredAuth = "bob:s3cret";
            NetworkConfig network = new NetworkConfig(ProxyConfig.manual(proxy.address(), "bob", "s3cret", null));
            ProbeResult result = Probe.run(uri("https", "localhost", secure.getAddress().getPort()), false, 5000, network);

            assertNull(result.failedStage(), String.valueOf(result.error()));
            assertTrue(result.viaProxy());
            assertEquals(proxy.address(), result.connectedTo(), "DNS and TCP are of the proxy");
            assertNotNull(result.tunnelMs());
            assertNotNull(result.tlsMs());
            assertEquals("CN=localhost", result.certificate().subject());
            assertEquals("CONNECT localhost:" + secure.getAddress().getPort() + " HTTP/1.1", proxy.connects.get(0));
        }
    }

    @Test
    void aProxyThatRefusesTheTunnelIsATunnelFailure() throws Exception {
        try (TunnelProxy proxy = new TunnelProxy()) {
            proxy.requiredAuth = "bob:s3cret";
            NetworkConfig network = new NetworkConfig(ProxyConfig.manual(proxy.address(), "bob", "wrong", null));
            ProbeResult result = Probe.run(uri("https", "localhost", secure.getAddress().getPort()), false, 5000, network);

            assertEquals("tunnel", result.failedStage());
            assertTrue(result.error().contains("407"), result.error());
            assertNull(result.tlsMs());
        }
    }

    @Test
    void aPlainOriginThroughAProxyStopsAfterConnectingToIt() throws Exception {
        try (TunnelProxy proxy = new TunnelProxy()) {
            NetworkConfig network = new NetworkConfig(ProxyConfig.manual(proxy.address(), null, null, null));
            ProbeResult result = Probe.run(uri("http", "origin.invalid", 80), true, 5000, network);

            assertNull(result.failedStage(), String.valueOf(result.error()));
            assertTrue(result.viaProxy());
            assertNull(result.tunnelMs());
        }
    }

    private static javax.net.ssl.SSLServerSocket askingServer(java.util.concurrent.CompletableFuture<String> seen)
            throws Exception {
        // A bare TLS server that asks for a certificate and records whose it saw: the probe closes
        // straight after the handshake, so there is no request to read it from.
        javax.net.ssl.SSLServerSocket server = (javax.net.ssl.SSLServerSocket) TlsFixtures.serverConfigurator(false)
                .getSSLContext().getServerSocketFactory()
                .createServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        server.setWantClientAuth(true);
        Thread.ofVirtual().start(() -> {
            try (javax.net.ssl.SSLSocket client = (javax.net.ssl.SSLSocket) server.accept()) {
                client.startHandshake();
                String subject;
                try {
                    subject = client.getSession().getPeerPrincipal().getName();
                } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
                    subject = "none";
                }
                seen.complete(subject);
            } catch (IOException e) {
                seen.completeExceptionally(e);
            }
        });
        return server;
    }

    private String subjectSeenWith(String certificateHost) throws Exception {
        java.util.concurrent.CompletableFuture<String> seen = new java.util.concurrent.CompletableFuture<>();
        javax.net.ssl.SSLServerSocket server = askingServer(seen);
        try {
            Path p12 = TlsFixtures.writeClientP12(files, "client.p12");
            NetworkConfig network = new NetworkConfig(null, List.of(new ClientCert(
                    certificateHost, "pkcs12", p12.toString(), null, TlsFixtures.CLIENT_PASSPHRASE)));
            ProbeResult result = Probe.run(uri("https", "127.0.0.1", server.getLocalPort()), false, 5000, network);
            assertNull(result.failedStage(), String.valueOf(result.error()));
            return seen.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            server.close();
        }
    }

    @Test
    void theRoutedClientCertificateIsOfferedOnTheProbesHandshake() throws Exception {
        assertEquals("CN=ping-client", subjectSeenWith("127.0.0.1"));
    }

    @Test
    void aClientCertificateForAnotherHostIsNotOfferedOnTheProbe() throws Exception {
        assertEquals("none", subjectSeenWith("elsewhere.example"));
    }

    @Test
    void aBrokenClientCertificateIsBadInputNotAProbeResult() {
        NetworkConfig network = new NetworkConfig(null, List.of(
                new ClientCert("*", "pkcs12", files.resolve("missing.p12").toString(), null, null)));
        assertThrows(RpcException.class,
                () -> Probe.run(uri("https", "127.0.0.1", secure.getAddress().getPort()), false, 5000, network));
    }

    @Test
    void onlyHttpAndHttpsWithAHostCanBeProbed() {
        assertThrows(RpcException.class, () -> probe(URI.create("ftp://example.com/"), true));
        assertThrows(RpcException.class, () -> probe(URI.create("/relative"), true));
    }
}
