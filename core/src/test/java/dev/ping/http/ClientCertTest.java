package dev.ping.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import dev.ping.cookies.CookieContext;
import dev.ping.methods.CoreMethods;
import dev.ping.methods.HttpMethods;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutual TLS over real sockets. One server insists on a client certificate; another merely asks
 * and reports what arrived, which is how "was it offered to this host?" is observed.
 */
class ClientCertTest {

    @TempDir
    Path files;

    private HttpsServer required;
    private HttpsServer optional;
    private final HttpEngine engine = new HttpEngine();
    private Path p12;
    private Path certPem;
    private Path keyPlain;
    private Path keyEncrypted;

    @BeforeEach
    void start() throws Exception {
        required = server(true);
        optional = server(false);
        p12 = TlsFixtures.writeClientP12(files, "client.p12");
        certPem = TlsFixtures.write(files, "client.pem", TlsFixtures.CLIENT_CERT_PEM);
        keyPlain = TlsFixtures.write(files, "client.key", TlsFixtures.CLIENT_KEY_PLAIN);
        keyEncrypted = TlsFixtures.write(files, "client.enc.key", TlsFixtures.CLIENT_KEY_ENCRYPTED);
    }

    @AfterEach
    void stop() {
        required.stop(0);
        optional.stop(0);
    }

    /** Answers with the subject of the client certificate it received, or "none". */
    private static HttpsServer server(boolean requireClientCert) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(TlsFixtures.serverConfigurator(requireClientCert));
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/whoami", exchange -> {
            String subject;
            try {
                subject = ((HttpsExchange) exchange).getSSLSession().getPeerPrincipal().getName();
            } catch (SSLPeerUnverifiedException e) {
                subject = "none";
            }
            byte[] payload = subject.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpsServer server, String host) {
        return "https://" + host + ":" + server.getAddress().getPort() + "/whoami";
    }

    /** Verification is off because the server's certificate comes from a CA no JVM trusts. */
    private ResponseData send(String url, ClientCert... certs) {
        return engine.send(new RequestSpec.Builder(url).verifyTls(false).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE, new NetworkConfig(null, List.of(certs)));
    }

    private ClientCert pkcs12(String host, String passphrase) {
        return new ClientCert(host, "pkcs12", p12.toString(), null, passphrase);
    }

    private ClientCert pem(String host, Path key, String passphrase) {
        return new ClientCert(host, "pem", certPem.toString(), key.toString(), passphrase);
    }

    @Test
    void aServerRequiringACertificateAcceptsAPkcs12() {
        assertEquals("CN=ping-client",
                send(url(required, "127.0.0.1"), pkcs12("127.0.0.1", TlsFixtures.CLIENT_PASSPHRASE)).body().content());
    }

    @Test
    void aPemCertificateWithAPlainKeyIsAccepted() {
        assertEquals("CN=ping-client",
                send(url(required, "127.0.0.1"), pem("127.0.0.1", keyPlain, null)).body().content());
    }

    @Test
    void aPemCertificateWithAnEncryptedKeyIsAccepted() {
        assertEquals("CN=ping-client",
                send(url(required, "127.0.0.1"), pem("127.0.0.1", keyEncrypted, TlsFixtures.CLIENT_PASSPHRASE))
                        .body().content());
    }

    @Test
    void theSameRequestWithoutACertificateIsRejected() {
        RpcException e = assertThrows(RpcException.class, () -> send(url(required, "127.0.0.1")));
        assertEquals(RpcException.REQUEST_FAILED, e.code());
        assertTrue(e.getMessage().contains("client certificate"), e.getMessage());
    }

    @Test
    void aCertificateIsOnlyOfferedToTheHostsItNames() {
        ClientCert only = pkcs12("127.0.0.1", TlsFixtures.CLIENT_PASSPHRASE);
        assertEquals("CN=ping-client", send(url(optional, "127.0.0.1"), only).body().content());
        // The same server, reached by another name (as a redirect could): no identity is offered.
        assertEquals("none", send(url(optional, "localhost"), only).body().content());
        // And the pattern can name the other one instead.
        assertEquals("CN=ping-client",
                send(url(optional, "localhost"), pkcs12("localhost", TlsFixtures.CLIENT_PASSPHRASE)).body().content());
    }

    @Test
    void aRedirectToAnotherHostIsNotSentTheCertificate() throws Exception {
        String elsewhere = url(optional, "localhost");
        optional.createContext("/hop", exchange -> {
            exchange.getResponseHeaders().add("Location", elsewhere);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        ResponseData response = engine.send(
                new RequestSpec.Builder("https://127.0.0.1:" + optional.getAddress().getPort() + "/hop")
                        .verifyTls(false).redirects("normal").build(),
                Map.of(), FileAccess.LOCAL, CookieContext.NONE,
                new NetworkConfig(null, List.of(pkcs12("127.0.0.1", TlsFixtures.CLIENT_PASSPHRASE))));
        assertEquals("none", response.body().content());
    }

    @Test
    void aCertificateWithNoHostGoesToEveryHost() {
        assertEquals("CN=ping-client",
                send(url(optional, "localhost"), pkcs12(null, TlsFixtures.CLIENT_PASSPHRASE)).body().content());
    }

    @Test
    void verificationStillAppliesWhenACertificateIsConfigured() {
        // verifyTls left on: the test CA is not trusted, so the server's certificate must be refused.
        RpcException e = assertThrows(RpcException.class, () -> engine.send(
                new RequestSpec.Builder(url(optional, "127.0.0.1")).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE,
                new NetworkConfig(null, List.of(pkcs12("127.0.0.1", TlsFixtures.CLIENT_PASSPHRASE)))));
        assertTrue(e.getMessage().startsWith("TLS handshake failed"), e.getMessage());
    }

    @Test
    void aBrokenCertificateDoesNotAffectAPlainHttpRequest() throws Exception {
        var plain = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plain.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        plain.start();
        try {
            assertEquals(204, send("http://127.0.0.1:" + plain.getAddress().getPort() + "/",
                    new ClientCert("*", "pkcs12", files.resolve("missing.p12").toString(), null, null)).status());
        } finally {
            plain.stop(0);
        }
    }

    // --- refusals: a message, never the passphrase ------------------------------------------

    private RpcException refused(ClientCert cert) {
        return assertThrows(RpcException.class, () -> send(url(optional, "127.0.0.1"), cert));
    }

    @Test
    void aWrongPassphraseIsRefusedWithoutEchoingIt() {
        RpcException e = refused(pkcs12("127.0.0.1", "not-the-passphrase-1234"));
        assertEquals(RpcException.INVALID_PARAMS, e.code());
        assertTrue(e.getMessage().contains("passphrase"), e.getMessage());
        assertFalse(e.getMessage().contains("not-the-passphrase-1234"), e.getMessage());

        RpcException key = refused(pem("127.0.0.1", keyEncrypted, "another-wrong-one-5678"));
        assertEquals(RpcException.INVALID_PARAMS, key.code());
        assertFalse(key.getMessage().contains("another-wrong-one-5678"), key.getMessage());
    }

    @Test
    void anEncryptedKeyNeedsAPassphrase() {
        assertTrue(refused(pem("127.0.0.1", keyEncrypted, null)).getMessage().contains("passphrase"));
    }

    @Test
    void theTraditionalKeyFormatIsRefusedWithTheConversion() throws IOException {
        Path traditional = TlsFixtures.write(files, "old.key", TlsFixtures.CLIENT_KEY_PKCS1);
        RpcException e = refused(pem("127.0.0.1", traditional, null));
        assertTrue(e.getMessage().contains("openssl pkcs8 -topk8"), e.getMessage());
    }

    @Test
    void aKeyThatBelongsToAnotherCertificateIsRefused() throws IOException {
        Path other = TlsFixtures.write(files, "other.key", TlsFixtures.OTHER_KEY);
        assertTrue(refused(pem("127.0.0.1", other, null)).getMessage().contains("does not belong"));
    }

    @Test
    void missingAndMalformedFilesAreRefusedByName() throws IOException {
        assertTrue(refused(new ClientCert("*", "pkcs12", files.resolve("nope.p12").toString(), null, "x"))
                .getMessage().contains("not found"));
        Path junk = TlsFixtures.write(files, "junk.p12", "not a keystore");
        assertTrue(refused(new ClientCert("*", "pkcs12", junk.toString(), null, "x")).getMessage().contains("PKCS#12"));
        assertTrue(refused(new ClientCert("*", "pem", junk.toString(), keyPlain.toString(), null))
                .getMessage().contains("not a PEM certificate"));
        assertTrue(refused(new ClientCert("*", "pem", certPem.toString(), null, null)).getMessage().contains("key file"));
        assertTrue(refused(new ClientCert("*", "jks", certPem.toString(), null, null)).getMessage().contains("unknown type"));
    }

    @Test
    void thePassphraseNeverPrintsInTheConfig() {
        assertFalse(pkcs12("h", "hunter2-cert").toString().contains("hunter2-cert"));
    }

    // --- across the RPC boundary ------------------------------------------------------------

    @Test
    void clientCertificatesArriveAsJsonThroughHttpSend() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String request = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "http.send", "params", Map.of(
                        "url", url(required, "127.0.0.1"), "verifyTls", false,
                        "network", Map.of("clientCerts", List.of(Map.of(
                                "host", "127.0.0.1", "type", "pkcs12", "cert", p12.toString(),
                                "passphrase", TlsFixtures.CLIENT_PASSPHRASE))))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer rpc = new RpcServer(new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)), out);
        CoreMethods.registerOn(rpc);
        HttpMethods.registerOn(rpc);
        rpc.serve();

        JsonNode response = out.toString(StandardCharsets.UTF_8).lines()
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError(line, e);
                    }
                })
                .filter(node -> node.has("id")).findFirst().orElseThrow();
        assertEquals("CN=ping-client", response.path("result").path("body").path("content").asText(), response.toString());
    }
}
