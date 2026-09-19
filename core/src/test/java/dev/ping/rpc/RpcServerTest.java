package dev.ping.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.methods.CoreMethods;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcServerTest {

    private final ObjectMapper json = new ObjectMapper();

    /** Runs the server over a fixed input and returns one parsed JSON object per output line. */
    private List<JsonNode> exchange(String... requestLines) throws Exception {
        String input = String.join("\n", requestLines) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        CoreMethods.registerOn(server);
        server.serve();

        return out.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError("Server emitted a non-JSON line: " + line, e);
                    }
                })
                .toList();
    }

    @Test
    void announcesReadinessBeforeAnyRequest() throws Exception {
        List<JsonNode> out = exchange();

        assertEquals(1, out.size());
        assertEquals("core.ready", out.get(0).path("method").asText());
        assertTrue(out.get(0).path("params").path("methods").isArray());
    }

    @Test
    void echoesPingMessageWithMatchingId() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"core.ping\",\"params\":{\"message\":\"hello\"}}");

        JsonNode response = out.get(1);
        assertEquals(7, response.path("id").asInt());
        assertEquals("hello", response.path("result").path("message").asText());
    }

    @Test
    void defaultsToPongWhenNoParamsGiven() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"core.ping\"}");

        assertEquals("pong", out.get(1).path("result").path("message").asText());
    }

    @Test
    void reportsCoreInfo() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"core.info\"}");

        JsonNode result = out.get(1).path("result");
        assertEquals(CoreMethods.VERSION, result.path("coreVersion").asText());
        assertTrue(result.path("javaVersion").asText().startsWith("25"));

        // This same suite runs twice: on the JVM, and compiled by native-image via nativeTest.
        // Cross-check the reported flag against an independent signal rather than hardcoding
        // either answer — GraalVM names its runtime "Substrate VM".
        boolean runningNatively = System.getProperty("java.vm.name", "").contains("Substrate");
        assertEquals(runningNatively, result.path("nativeImage").asBoolean());
    }

    @Test
    void returnsMethodNotFoundForUnknownMethod() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}");

        assertEquals(RpcException.METHOD_NOT_FOUND, out.get(1).path("error").path("code").asInt());
    }

    @Test
    void returnsParseErrorWithoutDroppingTheConnection() throws Exception {
        List<JsonNode> out = exchange(
                "{ not json",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"core.ping\"}");

        assertEquals(RpcException.PARSE_ERROR, out.get(1).path("error").path("code").asInt());
        // The second, well-formed request must still be served.
        assertEquals(3, out.get(2).path("id").asInt());
    }

    @Test
    void answersNothingForNotifications() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"method\":\"core.ping\"}");

        assertEquals(1, out.size(), "only core.ready should have been written");
    }

    @Test
    void keepsNewlinesInsidePayloadsFromBreakingFraming() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"core.ping\",\"params\":{\"message\":\"a\\nb\"}}");

        assertEquals(2, out.size(), "a multi-line payload must still serialize to one line");
        assertEquals("a\nb", out.get(1).path("result").path("message").asText());
    }
}
