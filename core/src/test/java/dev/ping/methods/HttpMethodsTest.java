package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the engine the way the UI does: as JSON crossing the RPC boundary.
 *
 * <p>{@link dev.ping.http.HttpEngineTest} builds specs in code, which skips Jackson
 * entirely. These tests cover the binding layer — and, because they exercise the
 * reflection Jackson performs on the request records, they are also what the native-image
 * tracing agent observes. Without them the shipped binary would fail on its first real
 * request.
 */
class HttpMethodsTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Runs a full RPC session and returns the responses, skipping the core.ready notification. */
    private List<JsonNode> exchange(String... requestLines) throws Exception {
        String input = String.join("\n", requestLines) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RpcServer rpc = new RpcServer(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        CoreMethods.registerOn(rpc);
        HttpMethods.registerOn(rpc);
        rpc.serve();

        return out.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError("Non-JSON output: " + line, e);
                    }
                })
                .filter(node -> node.has("id"))
                .toList();
    }

    private void handle(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            try (exchange) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
    }

    @TempDir
    java.nio.file.Path files;

    @Test
    void sendsAFilePartAndABinaryBodySentAsJson() throws Exception {
        byte[] payload = {0, 1, 2, (byte) 0xFF, '\r', '\n', 42};
        java.nio.file.Files.createDirectories(files.resolve("fixtures"));
        java.nio.file.Files.write(files.resolve("fixtures/logo.bin"), payload);
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server.createContext("/up", exchange -> {
            try (exchange) {
                received.set(exchange.getRequestBody().readAllBytes());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        String base = files.toString().replace("\\", "\\\\");

        // A relative path resolves against filesBase, which the shell sets from the collection.
        exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{"url":"%s/up","method":"POST",\
                "filesBase":"%s","body":{"type":"multipart","fields":[\
                {"name":"note","value":"hi"},\
                {"name":"logo","file":"fixtures/logo.bin","filename":"logo.png","contentType":"image/png"}]}}}"""
                .formatted(baseUrl, base));
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="), contentType.get());
        String body = new String(received.get(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("filename=\"logo.png\""), body);
        assertTrue(body.contains("Content-Type: image/png"), body);
        assertTrue(body.contains(new String(payload, StandardCharsets.ISO_8859_1)), "the file's bytes are in the body");

        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{"url":"%s/up","method":"PUT",\
                "filesBase":"%s","body":{"type":"file","file":"fixtures/logo.bin","contentType":"image/png"}}}"""
                .formatted(baseUrl, base));
        assertFalse(out.get(0).has("error"), out.get(0).toString());
        assertArrayEquals(payload, received.get());
        assertEquals("image/png", contentType.get());

        // A path that would leave the collection comes back as a normal invalid-params error.
        JsonNode escape = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{"url":"%s/up","method":"PUT",\
                "filesBase":"%s","body":{"type":"file","file":"../outside.bin"}}}"""
                .formatted(baseUrl, base)).get(0);
        assertEquals(-32602, escape.path("error").path("code").asInt());
    }

    @Test
    void evaluatesAssertionsSentAsJson() throws Exception {
        handle("/items", 200, "application/json", "{\"id\":7,\"name\":\"ping\"}");

        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "url":"%s/items","variables":{"want":"ping"},\
                "asserts":[\
                {"type":"status","expected":"200"},\
                {"type":"header","target":"content-type","op":"contains","expected":"json"},\
                {"type":"jsonpath","target":"$.name","op":"equals","expected":"{{want}}"},\
                {"type":"jsonpath","target":"$.id","op":"equals","expected":"8"},\
                {"type":"body","op":"contains","expected":"ping"},\
                {"type":"duration","op":"lt","expected":"60000"},\
                {"type":"status","expected":"500","enabled":false}]}}""".formatted(baseUrl));

        JsonNode results = out.get(0).path("result").path("assertions");
        assertEquals(6, results.size());
        assertTrue(results.get(0).path("passed").asBoolean());
        assertTrue(results.get(1).path("passed").asBoolean());
        assertTrue(results.get(2).path("passed").asBoolean());
        assertEquals("ping", results.get(2).path("expected").asText());
        assertFalse(results.get(3).path("passed").asBoolean());
        assertEquals("7", results.get(3).path("actual").asText());
        assertTrue(results.get(4).path("passed").asBoolean());
        assertTrue(results.get(5).path("passed").asBoolean());
    }

    @Test
    void capturesValuesSentAsJson() throws Exception {
        handle("/login", 200, "application/json", "{\"token\":\"abc\",\"user\":{\"id\":7}}");

        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "url":"%s/login","variables":{"field":"$.user.id"},\
                "capture":[\
                {"name":"token","source":"jsonpath","target":"$.token"},\
                {"name":"uid","source":"jsonpath","target":"{{field}}"},\
                {"name":"code","source":"status"},\
                {"name":"ct","source":"header","target":"content-type"},\
                {"name":"nope","source":"jsonpath","target":"$.nope"},\
                {"name":"off","source":"status","enabled":false}]}}""".formatted(baseUrl));

        JsonNode captured = out.get(0).path("result").path("captured");
        assertEquals(5, captured.size());
        assertEquals("abc", captured.get(0).path("value").asText());
        assertEquals("7", captured.get(1).path("value").asText());
        assertEquals("200", captured.get(2).path("value").asText());
        assertEquals("application/json", captured.get(3).path("value").asText());
        assertFalse(captured.get(4).path("found").asBoolean());
        assertFalse(captured.get(4).has("value"), "a miss carries no value");
    }

    @Test
    void aMisconfiguredAssertionDoesNotFailTheSend() throws Exception {
        handle("/items", 200, "application/json", "{}");

        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "url":"%s/items","asserts":[{"type":"telepathy"}]}}""".formatted(baseUrl));

        assertFalse(out.get(0).has("error"));
        JsonNode result = out.get(0).path("result").path("assertions").get(0);
        assertFalse(result.path("passed").asBoolean());
        assertEquals("Unknown assertion type: telepathy", result.path("message").asText());
    }

    @Test
    void bindsAFullRequestFromJson() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();

        server.createContext("/submit", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                header.set(exchange.getRequestHeaders().getFirst("X-Token"));
                query.set(exchange.getRequestURI().getRawQuery());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = "{\"created\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });

        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "requestId":"r1","method":"POST","url":"%s/submit",\
                "query":[{"name":"dry","value":"false"}],\
                "headers":[{"name":"X-Token","value":"secret"},{"name":"X-Off","value":"x","enabled":false}],\
                "body":{"type":"json","content":"{\\"name\\":\\"ping\\"}"},\
                "timeoutMs":5000,"redirects":"normal","verifyTls":true}}""".formatted(baseUrl));

        JsonNode result = out.get(0).path("result");
        assertEquals(201, result.path("status").asInt());
        assertEquals("{\"created\":true}", result.path("body").path("content").asText());
        assertTrue(result.path("timing").has("totalMs"));

        assertEquals("POST", method.get());
        assertEquals("secret", header.get());
        assertEquals("dry=false", query.get());
        assertEquals("{\"name\":\"ping\"}", body.get());
    }

    @Test
    void bindsFormFieldsFromJson() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/form", exchange -> {
            try (exchange) {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(204, -1);
            }
        });

        exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "method":"POST","url":"%s/form",\
                "body":{"type":"form","fields":[{"name":"a","value":"1"},{"name":"b","value":"2"}]}}}"""
                .formatted(baseUrl));

        assertEquals("a=1&b=2", body.get());
    }

    @Test
    void reportsHttpErrorsAsResultsNotRpcErrors() throws Exception {
        handle("/missing", 404, "application/json", "{\"error\":\"nope\"}");

        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.send\",\"params\":{\"url\":\"%s/missing\"}}"
                        .formatted(baseUrl));

        // A 404 is a successful exchange. Only a failure to exchange at all is an RPC error.
        assertTrue(out.get(0).has("result"));
        assertEquals(404, out.get(0).path("result").path("status").asInt());
    }

    @Test
    void acceptsFieldsItDoesNotRecognise() throws Exception {
        handle("/ok", 200, "text/plain", "fine");

        List<JsonNode> out = exchange(
                ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.send\",\"params\":"
                        + "{\"url\":\"%s/ok\",\"somethingNewerUiSends\":42}}").formatted(baseUrl));

        assertEquals(200, out.get(0).path("result").path("status").asInt(),
                "an unknown field must not fail the request");
    }

    @Test
    void reportsHeaderInjectionAsInvalidParams() throws Exception {
        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "url":"%s/data","headers":[{"name":"X-Injected","value":"ok\\r\\nX-Evil: yes"}]}}"""
                .formatted(baseUrl));

        JsonNode error = out.get(0).path("error");
        assertEquals(RpcException.INVALID_PARAMS, error.path("code").asInt());
        assertFalse(error.path("message").asText().contains("Exception"),
                error.path("message").asText());
    }

    @Test
    void rejectsAMissingUrl() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.send\",\"params\":{\"method\":\"GET\"}}");

        assertEquals(RpcException.INVALID_PARAMS, out.get(0).path("error").path("code").asInt());
    }

    @Test
    void rejectsSendWithoutParams() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.send\"}");

        assertEquals(RpcException.INVALID_PARAMS, out.get(0).path("error").path("code").asInt());
    }

    @Test
    void carriesBinaryBodiesAsBase64AcrossTheBoundary() throws Exception {
        // A 1x1 PNG, chosen because the bytes are not valid text in any charset.
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        server.createContext("/image", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(png);
                }
            }
        });

        List<JsonNode> out = exchange((
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.send\",\"params\":"
                        + "{\"url\":\"%s/image\"}}").formatted(baseUrl));

        JsonNode body = out.get(0).path("result").path("body");
        assertFalse(body.path("textual").asBoolean());
        assertTrue(body.hasNonNull("base64"), "binary body must carry base64");
        assertArrayEquals(png, Base64.getDecoder().decode(body.path("base64").asText()));
    }

    @Test
    void reportsUnreachableHostsAsRequestFailed() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.send\",\"params\":"
                        + "{\"url\":\"http://127.0.0.1:1/nope\",\"timeoutMs\":2000}}");

        assertEquals(RpcException.REQUEST_FAILED, out.get(0).path("error").path("code").asInt());
    }

    @Test
    void substitutesVariablesSentWithTheRequest() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/vars", exchange -> {
            try (exchange) {
                query.set(exchange.getRequestURI().getRawQuery());
                exchange.sendResponseHeaders(200, -1);
            }
        });

        List<JsonNode> out = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"http.send","params":{\
                "url":"%s/vars","query":[{"name":"q","value":"{{token}}"}],\
                "variables":{"token":"resolved"}}}""".formatted(baseUrl));

        assertEquals("q=resolved", query.get());
        assertTrue(out.get(0).has("result"));
    }

    @Test
    void cancelRequiresARequestId() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.cancel\",\"params\":{}}");

        assertEquals(RpcException.INVALID_PARAMS, out.get(0).path("error").path("code").asInt());
    }

    @Test
    void cancelReportsFalseWhenNothingIsInFlight() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"http.cancel\",\"params\":{\"requestId\":\"ghost\"}}");

        assertFalse(out.get(0).path("result").path("cancelled").asBoolean());
    }

    @Test
    void sendsThroughTheProxyNamedInNetworkAndPinsTheVersionSentAsJson() throws Exception {
        // The proxy answers for a host that does not exist, so only a routed request can succeed.
        // Keyed by the absolute URI the proxy was asked for: [Proxy-Authorization, Upgrade].
        java.util.Map<String, List<String>> seen = new java.util.concurrent.ConcurrentHashMap<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(Executors.newCachedThreadPool());
        proxy.createContext("/", exchange -> {
            seen.put(exchange.getRequestURI().toString(), List.of(
                    String.valueOf(exchange.getRequestHeaders().getFirst("Proxy-Authorization")),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade"))));
            try (exchange) {
                byte[] payload = "routed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        proxy.start();
        try {
            String address = "127.0.0.1:" + proxy.getAddress().getPort();
            List<JsonNode> unordered = exchange("""
                    {"jsonrpc":"2.0","id":1,"method":"http.send","params":{"url":"http://origin.invalid/x",\
                    "httpVersion":"1.1","network":{"proxy":{"mode":"manual","url":"%s",\
                    "username":"bob","password":"pw","bypass":["other.example"]}}}}
                    {"jsonrpc":"2.0","id":2,"method":"http.send","params":{"url":"http://origin.invalid/y",\
                    "network":{"proxy":{"mode":"system","env":{"HTTP_PROXY":"http://%s"}}}}}
                    {"jsonrpc":"2.0","id":3,"method":"http.send","params":{"url":"http://origin.invalid/z",\
                    "network":{"proxy":{"mode":"manual","url":"socks5://p:1"}}}}
                    {"jsonrpc":"2.0","id":4,"method":"http.send","params":{"url":"http://origin.invalid/z",\
                    "httpVersion":"9"}}"""
                    .formatted(address, address));

            // Handlers run concurrently, so responses can arrive in any order.
            JsonNode[] responses = new JsonNode[4];
            unordered.forEach(node -> responses[node.path("id").asInt() - 1] = node);
            assertEquals("routed", responses[0].path("result").path("body").path("content").asText());
            assertEquals(List.of("Basic Ym9iOnB3", "null"), seen.get("http://origin.invalid/x"),
                    "credentials reach the proxy; 1.1 was pinned so no h2c upgrade was offered");
            assertEquals(List.of("null", "null"), seen.get("http://origin.invalid/y"));
            assertEquals("routed", responses[1].path("result").path("body").path("content").asText());
            assertEquals(-32602, responses[2].path("error").path("code").asInt());
            assertTrue(responses[2].path("error").path("message").asText().contains("SOCKS"));
            assertEquals(-32602, responses[3].path("error").path("code").asInt());
        } finally {
            proxy.stop(0);
        }
    }
}
