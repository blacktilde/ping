package dev.ping.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ping.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the engine against a real server on the loopback interface.
 *
 * <p>The JDK's own {@code HttpServer} is used rather than a mocking library: it costs no
 * dependency and it puts actual sockets, headers and status lines in the path.
 */
class HttpEngineTest {

    private HttpServer server;
    private HttpEngine engine;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        engine = new HttpEngine();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Registers a handler and returns nothing; the path is fixed per test for clarity. */
    private void handle(String path, Consumer<HttpExchange> handler) {
        server.createContext(path, exchange -> {
            try (exchange) {
                handler.accept(exchange);
            }
        });
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) {
        try {
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private RequestSpec.Builder get(String path) {
        return new RequestSpec.Builder(baseUrl + path);
    }

    @Test
    void returnsStatusHeadersAndDecodedBody() {
        handle("/hello", exchange -> respond(exchange, 201, "application/json", "{\"ok\":true}"));

        ResponseData response = engine.send(get("/hello").build());

        assertEquals(201, response.status());
        assertEquals("{\"ok\":true}", response.body().content());
        assertTrue(response.body().textual());
        assertEquals(11, response.body().bytes());
        assertFalse(response.body().truncated());
        assertTrue(response.headers().stream()
                .anyMatch(header -> header.name().equalsIgnoreCase("content-type")));
    }

    @Test
    void appendsAndEncodesQueryParameters() {
        AtomicReference<String> seen = new AtomicReference<>();
        handle("/search", exchange -> {
            seen.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "text/plain", "ok");
        });

        engine.send(get("/search")
                .query("q", "a b&c")
                .query("skipped", "no", false)
                .build());

        assertEquals("q=a+b%26c", seen.get());
    }

    @Test
    void preservesAnExistingQueryStringOnTheUrl() {
        AtomicReference<String> seen = new AtomicReference<>();
        handle("/search", exchange -> {
            seen.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "text/plain", "ok");
        });

        engine.send(new RequestSpec.Builder(baseUrl + "/search?existing=1").query("added", "2").build());

        assertEquals("existing=1&added=2", seen.get());
    }

    @Test
    void sendsHeadersAndSkipsDisabledOnes() {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> disabled = new AtomicReference<>();
        handle("/headers", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Token"));
            disabled.set(exchange.getRequestHeaders().getFirst("X-Disabled"));
            respond(exchange, 200, "text/plain", "ok");
        });

        engine.send(get("/headers")
                .header("X-Token", "abc123")
                .header("X-Disabled", "nope", false)
                .build());

        assertEquals("abc123", token.get());
        assertNull(disabled.get());
    }

    @Test
    void postsJsonWithAnImpliedContentType() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        handle("/create", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(readAll(exchange), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json", "{}");
        });

        engine.send(get("/create").method("POST").jsonBody("{\"name\":\"ping\"}").build());

        assertEquals("application/json", contentType.get());
        assertEquals("{\"name\":\"ping\"}", body.get());
    }

    @Test
    void letsAnExplicitContentTypeHeaderWinOverTheBodyMode() {
        AtomicReference<String> contentType = new AtomicReference<>();
        handle("/create", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 200, "text/plain", "ok");
        });

        engine.send(get("/create")
                .method("POST")
                .jsonBody("{}")
                .header("Content-Type", "application/vnd.api+json")
                .build());

        assertEquals("application/vnd.api+json", contentType.get());
    }

    @Test
    void encodesFormBodies() {
        AtomicReference<String> body = new AtomicReference<>();
        handle("/form", exchange -> {
            body.set(new String(readAll(exchange), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/plain", "ok");
        });

        engine.send(get("/form").method("POST").formBody("user", "a b").build());

        assertEquals("user=a+b", body.get());
    }

    @Test
    void followsRedirectsAndRecordsTheChain() {
        handle("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/end");
            respond(exchange, 302, null, "");
        });
        handle("/end", exchange -> respond(exchange, 200, "text/plain", "arrived"));

        ResponseData response = engine.send(get("/start").build());

        assertEquals(200, response.status());
        assertEquals("arrived", response.body().content());
        assertEquals(1, response.redirects().size());
        assertEquals(302, response.redirects().get(0).status());
        assertTrue(response.redirects().get(0).location().endsWith("/end"));
    }

    @Test
    void returnsTheRedirectItselfWhenFollowingIsOff() {
        handle("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/end");
            respond(exchange, 302, null, "");
        });

        ResponseData response = engine.send(get("/start").redirects("never").build());

        assertEquals(302, response.status());
        assertTrue(response.redirects().isEmpty());
    }

    @Test
    void countsOversizedBodiesWithoutBufferingThemWhole() {
        byte[] payload = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
        handle("/big", exchange -> respond(exchange, 200, "text/plain", payload));

        ResponseData response = engine.send(get("/big").maxBodyBytes(1000).build());

        assertTrue(response.body().truncated());
        assertEquals(5000, response.body().bytes(), "the full size must still be reported");
        assertEquals(1000, response.body().content().length(), "only the cap is kept");
    }

    @Test
    void omitsContentForNonTextualPayloads() {
        handle("/blob", exchange ->
                respond(exchange, 200, "application/octet-stream", new byte[]{1, 2, 3, 4}));

        ResponseData response = engine.send(get("/blob").build());

        assertFalse(response.body().textual());
        assertNull(response.body().content());
        assertEquals(4, response.body().bytes());
    }

    @Test
    void decodesUsingTheCharsetFromTheContentType() {
        byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
        handle("/latin", exchange -> respond(exchange, 200, "text/plain; charset=ISO-8859-1", latin1));

        ResponseData response = engine.send(get("/latin").build());

        assertEquals("café", response.body().content());
        assertEquals("ISO-8859-1", response.body().charset());
    }

    @Test
    void reportsTimingsForEveryObservableStage() {
        handle("/slow", exchange -> {
            sleep(60);
            respond(exchange, 200, "text/plain", "ok");
        });

        ResponseData.Timing timing = engine.send(get("/slow").build()).timing();

        assertNotNull(timing.dnsMs(), "a resolvable host must produce a dns measurement");
        assertTrue(timing.ttfbMs() >= 50, "ttfb should cover the server delay, was " + timing.ttfbMs());
        assertTrue(timing.totalMs() >= timing.ttfbMs(), "total must include ttfb");
    }

    @Test
    void failsWithRequestFailedWhenTheServerIsTooSlow() {
        handle("/stall", exchange -> {
            sleep(2000);
            respond(exchange, 200, "text/plain", "late");
        });

        RpcException thrown = assertThrows(RpcException.class,
                () -> engine.send(get("/stall").timeoutMs(200).build()));

        assertEquals(RpcException.REQUEST_FAILED, thrown.code());
        assertEquals("Request timed out", thrown.getMessage());
    }

    @Test
    void substitutesVariablesAcrossTheRequest() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        server.createContext("/v", exchange -> {
            try (exchange) {
                path.set(exchange.getRequestURI().getPath());
                query.set(exchange.getRequestURI().getRawQuery());
                header.set(exchange.getRequestHeaders().getFirst("X-Token"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
            }
        });

        RequestSpec spec = new RequestSpec.Builder("{{base}}/v")
                .query("q", "{{value}}")
                .header("X-Token", "{{token}}")
                .jsonBody("{\"who\":\"{{who}}\"}")
                .build();
        Map<String, String> variables = Map.of(
                "base", baseUrl,
                "value", "a b",
                "token", "secret",
                "who", "ping");

        engine.send(spec, variables);

        assertEquals("/v", path.get());
        assertEquals("q=a+b", query.get(), "the value is substituted before it is url-encoded");
        assertEquals("secret", header.get());
        assertEquals("{\"who\":\"ping\"}", body.get());
    }

    @Test
    void leavesUnknownPlaceholdersIntact() {
        assertEquals("{{missing}}", HttpEngine.interpolate("{{missing}}", Map.of("present", "x")));
        assertEquals("x/{{missing}}",
                HttpEngine.interpolate("{{present}}/{{missing}}", Map.of("present", "x")));
    }

    @Test
    void appliesBasicAuth() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        handleHeader("/basic", "Authorization", authorization);

        engine.send(new RequestSpec.Builder(baseUrl + "/basic")
                .auth(RequestSpec.Auth.basic("user", "pass"))
                .build());

        String expected = Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + expected, authorization.get());
    }

    @Test
    void appliesBearerAuthWithAnInterpolatedToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        handleHeader("/bearer", "Authorization", authorization);

        engine.send(new RequestSpec.Builder(baseUrl + "/bearer")
                .auth(RequestSpec.Auth.bearer("{{token}}"))
                .build(), Map.of("token", "abc123"));

        assertEquals("Bearer abc123", authorization.get());
    }

    @Test
    void appliesApiKeyAsHeaderOrQuery() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/key", exchange -> {
            try (exchange) {
                header.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                query.set(exchange.getRequestURI().getRawQuery());
                exchange.sendResponseHeaders(204, -1);
            }
        });

        engine.send(new RequestSpec.Builder(baseUrl + "/key")
                .auth(RequestSpec.Auth.apiKey("X-Api-Key", "k1", "header"))
                .build());
        assertEquals("k1", header.get());
        assertNull(query.get());

        engine.send(new RequestSpec.Builder(baseUrl + "/key")
                .auth(RequestSpec.Auth.apiKey("api_key", "k1", "query"))
                .build());
        assertEquals("api_key=k1", query.get());
    }

    @Test
    void fetchesAClientCredentialsTokenAndCachesIt() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicReference<String> tokenForm = new AtomicReference<>();
        AtomicReference<String> resourceAuth = new AtomicReference<>();

        server.createContext("/token", exchange -> {
            try (exchange) {
                tokenCalls.incrementAndGet();
                tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        handleHeader("/resource", "Authorization", resourceAuth);

        RequestSpec spec = new RequestSpec.Builder(baseUrl + "/resource")
                .auth(RequestSpec.Auth.clientCredentials(baseUrl + "/token", "id", "secret", "read write"))
                .build();
        engine.send(spec);
        engine.send(spec);

        assertEquals("Bearer tok-123", resourceAuth.get());
        assertEquals(1, tokenCalls.get(), "the second send must reuse the cached token");
        assertTrue(tokenForm.get().contains("grant_type=client_credentials"), tokenForm.get());
        assertTrue(tokenForm.get().contains("scope=read+write"), tokenForm.get());
        assertTrue(tokenForm.get().contains("client_secret=secret"), tokenForm.get());
    }

    @Test
    void reportsAFailedTokenExchangeAsAuthFailed() throws Exception {
        server.createContext("/bad-token", exchange -> {
            try (exchange) {
                byte[] payload = "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });

        RpcException thrown = assertThrows(RpcException.class,
                () -> engine.send(new RequestSpec.Builder(baseUrl + "/resource")
                        .auth(RequestSpec.Auth.clientCredentials(baseUrl + "/bad-token", "id", "nope", null))
                        .build()));

        assertEquals(RpcException.AUTH_FAILED, thrown.code());
        assertTrue(thrown.getMessage().contains("invalid_client"), thrown.getMessage());
    }

    /** A context that records one request header and answers 204. */
    private void handleHeader(String path, String name, AtomicReference<String> target) {
        server.createContext(path, exchange -> {
            try (exchange) {
                target.set(exchange.getRequestHeaders().getFirst(name));
                exchange.sendResponseHeaders(204, -1);
            }
        });
    }

    @Test
    void framesMultipartFieldsWithABoundary() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> contentLength = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();

        server.createContext("/upload", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                received.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(204, -1);
            }
        });

        engine.send(new RequestSpec.Builder(baseUrl + "/upload")
                .method("POST")
                .multipartBody(List.of(
                        new RequestSpec.Param("a", "1", true),
                        new RequestSpec.Param("skip", "x", false),
                        new RequestSpec.Param("b", "two words", true)))
                .build());

        String header = contentType.get();
        assertTrue(header != null && header.startsWith("multipart/form-data; boundary="),
                "a boundary is declared: " + header);
        String boundary = header.substring("multipart/form-data; boundary=".length());

        String payload = new String(received.get(), StandardCharsets.UTF_8);
        assertTrue(payload.startsWith("--" + boundary + "\r\n"), payload);
        assertTrue(payload.contains("Content-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n"), payload);
        assertTrue(payload.contains("name=\"b\"\r\n\r\ntwo words\r\n"), payload);
        assertFalse(payload.contains("skip"), "disabled fields must not be sent: " + payload);
        assertTrue(payload.endsWith("--" + boundary + "--\r\n"), "closing delimiter: " + payload);

        // The framing has to be fully known up front for the length to be fixed rather than chunked.
        assertEquals(String.valueOf(received.get().length), contentLength.get());
    }

    @Test
    void rejectsHeaderLineBreaksAsBadInput() {
        RpcException thrown = assertThrows(RpcException.class, () -> engine.send(
                new RequestSpec.Builder(baseUrl + "/x")
                        .header("X-Injected", "ok\r\nX-Evil: yes")
                        .build()));

        assertEquals(RpcException.INVALID_PARAMS, thrown.code());
        assertFalse(thrown.getMessage().contains("Exception"),
                "raw Java must not reach the user: " + thrown.getMessage());
    }

    @Test
    void rejectsInvalidHeaderNames() {
        RpcException thrown = assertThrows(RpcException.class, () -> engine.send(
                new RequestSpec.Builder(baseUrl + "/x").header("Bad Name", "v").build()));

        assertEquals(RpcException.INVALID_PARAMS, thrown.code());
        assertFalse(thrown.getMessage().contains("Exception"), thrown.getMessage());
    }

    @Test
    void rejectsMultipartNamesThatBreakFraming() {
        RpcException thrown = assertThrows(RpcException.class, () -> engine.send(
                new RequestSpec.Builder(baseUrl + "/upload")
                        .method("POST")
                        .multipartBody(List.of(
                                new RequestSpec.Param("a\"\r\nContent-Type: text/html", "v", true)))
                        .build()));

        assertEquals(RpcException.INVALID_PARAMS, thrown.code());
    }

    @Test
    void rewritesTransportFailuresForPeople() {
        assertEquals("Unknown host: nope.invalid",
                HttpEngine.describe(new UnknownHostException("nope.invalid")));
        assertEquals("Request timed out",
                HttpEngine.describe(new HttpTimeoutException("timed out")));
        assertEquals("Request timed out",
                HttpEngine.describe(new SocketTimeoutException("read timed out")));
        assertTrue(HttpEngine.describe(new SSLHandshakeException("no cipher suites"))
                        .startsWith("TLS handshake failed"),
                "a TLS failure should lead with the plain meaning");
        assertEquals("Connection refused", HttpEngine.describe(new ConnectException()));
        assertEquals("Connection refused",
                HttpEngine.describe(new ConnectException("Connection refused")));
    }

    @Test
    void cancelsAnInFlightRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        handle("/hang", exchange -> {
            started.countDown();
            sleep(5000);
            respond(exchange, 200, "text/plain", "never read");
        });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<RpcException> failure = caller.submit(() -> assertThrows(RpcException.class,
                    () -> engine.send(get("/hang").requestId("req-1").timeoutMs(10_000).build())));

            assertTrue(started.await(5, TimeUnit.SECONDS), "server never received the request");
            // The engine registers the request as it dispatches, a moment before the server sees it.
            assertTrue(waitUntil(() -> engine.cancel("req-1")), "cancel never took effect");

            assertEquals(RpcException.REQUEST_CANCELLED, failure.get(5, TimeUnit.SECONDS).code());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void reportsUnknownIdsAsNotCancelled() {
        assertFalse(engine.cancel("nothing-in-flight"));
    }

    @Test
    void rejectsRelativeUrls() {
        RpcException thrown = assertThrows(RpcException.class,
                () -> engine.send(new RequestSpec.Builder("/no-host").build()));

        assertEquals(RpcException.INVALID_PARAMS, thrown.code());
    }

    @Test
    void rejectsAnUnknownBodyType() {
        RpcException thrown = assertThrows(RpcException.class,
                () -> engine.send(get("/x").rawBodyOfType("telepathy").build()));

        assertEquals(RpcException.INVALID_PARAMS, thrown.code());
    }

    @Test
    void surfacesConnectionFailuresAsRequestFailed() {
        // Port 1 on loopback: reserved, and nothing is listening.
        RpcException thrown = assertThrows(RpcException.class,
                () -> engine.send(new RequestSpec.Builder("http://127.0.0.1:1/nope")
                        .timeoutMs(2000).build()));

        assertEquals(RpcException.REQUEST_FAILED, thrown.code());
        assertFalse(thrown.getMessage().contains("Exception"),
                "raw exception class names must not reach the user: " + thrown.getMessage());
    }

    // --- helpers ---------------------------------------------------------------------------

    private static byte[] readAll(HttpExchange exchange) {
        try {
            return exchange.getRequestBody().readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Retries a condition briefly, for the gap between dispatch and registration. */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition) {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(20);
        }
        return false;
    }
}
