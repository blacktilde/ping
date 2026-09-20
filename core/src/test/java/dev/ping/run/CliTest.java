package dev.ping.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path root;

    private HttpServer server;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        server = RunFixture.startServer();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int cli(Map<String, String> env, String... args) {
        return Cli.run(args, env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int cli(String... args) {
        return cli(Map.of(), args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path collection(boolean broken) throws IOException {
        return RunFixture.write(root, RunFixture.baseUrl(server), broken);
    }

    @Test
    void exitsZeroWhenEverythingPasses() throws IOException {
        Path demo = collection(false);
        assertEquals(0, cli("run", demo.toString(), "-e", "staging"));
        assertTrue(stdout().contains("2 requests: 2 passed, 0 failed, 0 errored"), stdout());
        assertEquals("", stderr());
    }

    @Test
    void exitsOneWhenAnAssertionFailsOrARequestErrors() throws IOException {
        Path demo = collection(true);
        assertEquals(1, cli("run", demo.toString(), "-e", "staging"));
        assertTrue(stdout().contains("FAIL  GET Wrong"), stdout());
        assertTrue(stdout().contains("ERR   GET Dead"), stdout());
    }

    @Test
    void theCommandLineMayUploadAFileByAbsolutePath() throws IOException {
        Path blob = root.resolve("blob.bin");
        Files.write(blob, new byte[] {0, 1, 2, (byte) 0xFF});
        Path demo = collection(false);
        Files.writeString(demo.resolve("upload.yaml"), """
                name: Upload
                method: PUT
                url: "{{baseUrl}}/upload"
                body:
                  type: file
                  file: "%s"
                """.formatted(blob.toString().replace("\\", "\\\\")));
        assertEquals(0, cli("run", demo.toString(), "-e", "staging"), stdout());
        assertTrue(stdout().contains("PASS  PUT Upload"), stdout());
    }

    @Test
    void exitsOneWhenTheEnvironmentIsWhatMakesItPass() throws IOException {
        Path demo = collection(false);
        assertEquals(1, cli("run", demo.toString()), "no environment: {{who}} is never resolved");
    }

    @Test
    void exitsTwoWhenTheRunCannotStart() throws IOException {
        Path demo = collection(false);
        assertEquals(2, cli("run", demo.toString(), "-e", "prod"));
        assertTrue(stderr().contains("Unknown environment \"prod\". Available: Staging"), stderr());
        assertEquals("", stdout(), "no report when nothing ran");

        assertEquals(2, cli("run", root.resolve("missing").toString()));
        assertEquals(2, cli("run"));
        assertEquals(2, cli("run", demo.toString(), "-r", "xml"));
        assertEquals(2, cli("run", demo.toString(), "--bogus"));
        assertEquals(2, cli("run", demo.toString(), "-e"));
        assertEquals(2, cli("run", demo.toString(), "--var", "novalue"));
        assertEquals(2, cli("run", demo.toString(), "extra"));
        assertEquals(2, cli("frobnicate"));
    }

    @Test
    void reportsGoToStdoutAndDiagnosticsToStderr() throws IOException {
        Path demo = collection(false);
        cli("run", demo.toString(), "-e", "staging", "-r", "json");
        // The whole of stdout must be one JSON document: nothing else may share it.
        assertEquals(2, new ObjectMapper().readTree(stdout()).path("total").asInt());
    }

    @Test
    void writesTheReportToAFileWhenAsked() throws IOException {
        Path demo = collection(false);
        Path report = root.resolve("report.xml");
        assertEquals(0, cli("run", demo.toString(), "-e", "staging", "-r", "junit",
                "-o", report.toString()));
        assertEquals("", stdout());
        assertTrue(Files.readString(report).contains("<testsuites name=\"Demo\""));
    }

    @Test
    void variablesFromTheFlagAndTheEnvironmentOutrankTheFiles() throws IOException {
        Path demo = collection(false);
        assertEquals(1, cli("run", demo.toString(), "-e", "staging", "--var", "who=other"));

        // A secret in the environment behaves the same way, and --var beats it.
        assertEquals(1, cli(Map.of("PING_SECRET_who", "other"),
                "run", demo.toString(), "-e", "staging"));
        assertEquals(0, cli(Map.of("PING_SECRET_who", "other"),
                "run", demo.toString(), "-e", "staging", "--var", "who=staging"));
    }

    @Test
    void aSecretNeverAppearsInAReport() throws IOException {
        Path demo = collection(true);
        Map<String, String> secret = Map.of("PING_SECRET_who", "s3cr3t-value");
        cli(secret, "run", demo.toString(), "-e", "staging", "-r", "json");
        assertFalse(stdout().contains("s3cr3t-value"), stdout());
        out.reset();
        cli(secret, "run", demo.toString(), "-r", "junit");
        assertFalse(stdout().contains("s3cr3t-value"), stdout());
    }

    @Test
    void helpAndVersionExitZeroAndBareInvocationIsAUsageError() {
        assertEquals(0, cli("--help"));
        assertTrue(stdout().contains("Usage: ping-core run"), stdout());
        out.reset();
        assertEquals(0, cli("--version"));
        assertTrue(stdout().startsWith("ping-core "), stdout());
        assertEquals(2, cli(), "Main routes no-arg to RPC; reaching Cli with none is a usage error");
    }

    // --- proxy ------------------------------------------------------------------------------

    /** A working HTTP proxy: records each request, then forwards it to the address it names. */
    private HttpServer startProxy(java.util.List<String> authorizations) throws IOException {
        HttpServer proxy = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        java.net.http.HttpClient forward = java.net.http.HttpClient.newHttpClient();
        proxy.createContext("/", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Proxy-Authorization")));
            try (exchange) {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                var builder = java.net.http.HttpRequest.newBuilder(exchange.getRequestURI())
                        .method(exchange.getRequestMethod(),
                                java.net.http.HttpRequest.BodyPublishers.ofByteArray(requestBody));
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!name.toLowerCase().startsWith("proxy-") && !name.equalsIgnoreCase("content-length")
                            && !name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("connection")
                            && !name.equalsIgnoreCase("upgrade") && !name.equalsIgnoreCase("http2-settings")) {
                        values.forEach(value -> builder.header(name, value));
                    }
                });
                try {
                    var response = forward.send(builder.build(),
                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    response.headers().map().forEach((name, values) -> {
                        if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("transfer-encoding")) {
                            values.forEach(value -> exchange.getResponseHeaders().add(name, value));
                        }
                    });
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    try (var body = exchange.getResponseBody()) {
                        body.write(response.body());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        proxy.start();
        return proxy;
    }

    private static String address(HttpServer proxy) {
        return "127.0.0.1:" + proxy.getAddress().getPort();
    }

    @Test
    void theProxyFlagRoutesEveryRequestThroughIt() throws IOException {
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer proxy = startProxy(seen);
        try {
            Path demo = collection(false);
            assertEquals(0, cli("run", demo.toString(), "-e", "staging", "--proxy", address(proxy)), stdout());
            assertEquals(2, seen.size(), "both requests, and nothing went around the proxy: " + seen);
            assertEquals("null", seen.get(0), "no credentials were configured");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void withoutFlagsTheEnvironmentsProxyApplies() throws IOException {
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer proxy = startProxy(seen);
        try {
            Path demo = collection(false);
            assertEquals(0, cli(Map.of("HTTP_PROXY", "http://" + address(proxy)),
                    "run", demo.toString(), "-e", "staging"), stdout());
            assertEquals(2, seen.size());

            // NO_PROXY exempts the loopback address the collection talks to.
            seen.clear();
            assertEquals(0, cli(Map.of("http_proxy", address(proxy), "no_proxy", "127.0.0.1"),
                    "run", demo.toString(), "-e", "staging"), stdout());
            assertTrue(seen.isEmpty(), "the proxy must not see an exempted host: " + seen);
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void noProxyIgnoresTheEnvironment() throws IOException {
        Path demo = collection(false);
        // A dead proxy in the environment would fail every request if it were honoured.
        assertEquals(0, cli(Map.of("HTTP_PROXY", "127.0.0.1:1"),
                "run", demo.toString(), "-e", "staging", "--no-proxy"), stdout());
    }

    @Test
    void proxyCredentialsComeFromTheFlagOrThePasswordVariable() throws IOException {
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer proxy = startProxy(seen);
        String bobPw = "Basic " + java.util.Base64.getEncoder().encodeToString("bob:pw".getBytes(StandardCharsets.UTF_8));
        try {
            Path demo = collection(false);
            cli("run", demo.toString(), "-e", "staging", "--proxy", address(proxy), "--proxy-user", "bob:pw");
            assertEquals(bobPw, seen.get(0));

            seen.clear();
            cli(Map.of("PING_PROXY_PASSWORD", "pw"),
                    "run", demo.toString(), "-e", "staging", "--proxy", address(proxy), "--proxy-user", "bob");
            assertEquals(bobPw, seen.get(0));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void aProxyPasswordNeverAppearsInAReport() throws IOException {
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer proxy = startProxy(seen);
        try {
            Path demo = collection(true);
            cli("run", demo.toString(), "-e", "staging", "-r", "json", "--proxy", address(proxy),
                    "--proxy-user", "bob:hunter2-proxy");
            assertFalse(stdout().contains("hunter2-proxy"), stdout());
            assertFalse(stderr().contains("hunter2-proxy"), stderr());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void proxyMisuseIsAUsageError() throws IOException {
        Path demo = collection(false);
        assertEquals(2, cli("run", demo.toString(), "--no-proxy", "--proxy", "p:1"));
        assertEquals(2, cli("run", demo.toString(), "--proxy-user", "bob"));
        assertEquals(2, cli("run", demo.toString(), "--proxy"));
        assertEquals(2, cli("run", demo.toString(), "--proxy", "socks5://p:1080"));
        assertTrue(stderr().contains("SOCKS"), stderr());
    }

    // --- client certificate -----------------------------------------------------------------

    private Path mtlsCollection(int port) throws Exception {
        Path demo = root.resolve("secure");
        Files.createDirectories(demo);
        Files.writeString(demo.resolve("collection.yaml"), "name: Secure\n");
        Files.writeString(demo.resolve("whoami.yaml"), """
                name: Who am I
                method: GET
                url: "https://127.0.0.1:%d/whoami"
                verifyTls: false
                asserts:
                - type: body
                  op: contains
                  expected: ping-client
                """.formatted(port));
        return demo;
    }

    @Test
    void aClientCertificateLetsARunReachAServerThatRequiresOne() throws Exception {
        com.sun.net.httpserver.HttpsServer secure =
                com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        secure.setHttpsConfigurator(dev.ping.http.TlsFixtures.serverConfigurator(true));
        secure.createContext("/whoami", exchange -> {
            byte[] payload = ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession()
                    .getPeerPrincipal().getName().getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        secure.start();
        try {
            Path demo = mtlsCollection(secure.getAddress().getPort());
            Path p12 = dev.ping.http.TlsFixtures.writeClientP12(root, "client.p12");
            Path pem = dev.ping.http.TlsFixtures.write(root, "client.pem", dev.ping.http.TlsFixtures.CLIENT_CERT_PEM);
            Path key = dev.ping.http.TlsFixtures.write(root, "client.key", dev.ping.http.TlsFixtures.CLIENT_KEY_ENCRYPTED);
            Map<String, String> passphrase = Map.of("PING_CERT_PASSWORD", dev.ping.http.TlsFixtures.CLIENT_PASSPHRASE);

            assertEquals(1, cli("run", demo.toString()), "without a certificate the server refuses");
            assertEquals(0, cli(passphrase, "run", demo.toString(), "--cert", p12.toString()), stdout());
            assertEquals(0, cli(passphrase, "run", demo.toString(), "--cert", pem.toString(), "--key", key.toString()),
                    stdout());
        } finally {
            secure.stop(0);
        }
    }

    @Test
    void aCertificateThatCannotBeOpenedIsAUsageErrorAndNeverEchoesThePassphrase() throws Exception {
        Path demo = collection(false);
        Path p12 = dev.ping.http.TlsFixtures.writeClientP12(root, "client.p12");
        assertEquals(2, cli(Map.of("PING_CERT_PASSWORD", "wrong-pass-99"), "run", demo.toString(), "--cert", p12.toString()));
        assertTrue(stderr().contains("passphrase"), stderr());
        assertFalse(stderr().contains("wrong-pass-99"), stderr());
        assertEquals(2, cli("run", demo.toString(), "--cert", root.resolve("missing.p12").toString()));
        assertEquals(2, cli("run", demo.toString(), "--key", "k.pem"));
        assertEquals(2, cli("run", demo.toString(), "--cert"));
    }
}
