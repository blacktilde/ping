package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.ping.auth.TokenCache;
import dev.ping.rpc.RpcServer;
import dev.ping.run.RunFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code run.collection} as JSON, so the result and progress records cross Jackson the
 * way they do in the shipped binary. That is also what the native-image agent records.
 */
class RunMethodsTest {

    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = RunFixture.startServer();
        RunFixture.write(root, RunFixture.baseUrl(server), true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private List<JsonNode> exchange(String request) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer rpc = new RpcServer(
                new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)), out);
        RunMethods.registerOn(rpc, new TokenCache());
        rpc.serve();
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError("Non-JSON output: " + line, e);
                    }
                })
                .toList();
    }

    private String request(Map<String, Object> extra) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("root", root.toString());
        params.put("collection", "demo");
        params.putAll(extra);
        return json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "run.collection", "params", params));
    }

    private static JsonNode response(List<JsonNode> out) {
        return out.stream().filter(n -> n.has("id")).findFirst().orElseThrow();
    }

    @Test
    void runsACollectionAndStreamsProgress() throws Exception {
        List<JsonNode> out = exchange(request(Map.of(
                "runId", "r1", "environment", "staging", "variables", Map.of("extra", "x"))));

        JsonNode response = response(out);
        JsonNode result = response.path("result");
        assertEquals("Demo", result.path("collection").asText());
        assertEquals("Staging", result.path("environment").asText());
        assertEquals(4, result.path("total").asInt());
        assertEquals(2, result.path("passed").asInt());
        assertEquals(1, result.path("failed").asInt());
        assertEquals(1, result.path("errored").asInt());
        JsonNode wrong = result.path("requests").get(3);
        assertFalse(wrong.path("passed").asBoolean());
        assertEquals("Expected status 404, got 200",
                wrong.path("assertions").get(0).path("message").asText());

        List<JsonNode> progress = out.stream()
                .filter(n -> !n.has("id") && "run.progress".equals(n.path("method").asText()))
                .toList();
        assertEquals(4, progress.size());
        for (int i = 0; i < 4; i++) {
            JsonNode params = progress.get(i).path("params");
            assertEquals("r1", params.path("runId").asText());
            assertEquals(i, params.path("index").asInt());
            assertEquals(4, params.path("total").asInt());
            assertEquals(result.path("requests").get(i).path("name").asText(),
                    params.path("request").path("name").asText());
        }

        // Progress is emitted while the run is going, so it precedes the final response.
        assertTrue(out.indexOf(progress.get(3)) < out.indexOf(response));
    }

    @Test
    void rejectsAnUnknownEnvironmentAndMissingParams() throws Exception {
        JsonNode unknown = response(exchange(request(Map.of("environment", "prod"))));
        assertEquals(-32602, unknown.path("error").path("code").asInt());
        assertTrue(unknown.path("error").path("message").asText().contains("Available: Staging"));

        JsonNode missing = response(exchange("""
                {"jsonrpc":"2.0","id":1,"method":"run.collection","params":{}}"""));
        assertEquals(-32602, missing.path("error").path("code").asInt());
    }
}
