package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
}
