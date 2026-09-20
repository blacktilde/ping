package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends {@code import.curl} as JSON so the result records cross Jackson the way they do in
 * the shipped binary, which is also what the native-image agent records.
 */
class ImportMethodsTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode call(Map<String, Object> params) throws Exception {
        String line = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "import.curl", "params", params));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(
                new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8)), out);
        ImportMethods.registerOn(server);
        server.serve();
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank())
                .map(l -> {
                    try {
                        return json.readTree(l);
                    } catch (Exception e) {
                        throw new AssertionError("Non-JSON output: " + l, e);
                    }
                })
                .filter(node -> node.has("id"))
                .findFirst().orElseThrow();
    }

    @Test
    void importsACurlCommandIntoARequest() throws Exception {
        JsonNode result = call(Map.of("command", "curl -u alice:pw -X POST 'https://x.io/a?b=1' "
                + "-H 'Content-Type: application/json' -d '{\"n\":1}' --cert c.pem")).path("result");

        JsonNode request = result.path("request");
        assertEquals("POST", request.path("method").asText());
        assertEquals("https://x.io/a", request.path("url").asText());
        assertEquals("b", request.path("query").get(0).path("name").asText());
        assertEquals("json", request.path("body").path("type").asText());
        assertEquals("{\"n\":1}", request.path("body").path("content").asText());
        assertEquals("basic", request.path("auth").path("type").asText());
        assertEquals("alice", request.path("auth").path("username").asText());

        assertEquals(1, result.path("warnings").size());
        assertTrue(result.path("warnings").get(0).asText().contains("--cert"));
    }

    @Test
    void aCleanImportCarriesNoWarningsField() throws Exception {
        JsonNode result = call(Map.of("command", "curl https://x.io")).path("result");
        assertFalse(result.has("warnings"));
    }

    @Test
    void reportsAnUnusableCommandAsInvalidParams() throws Exception {
        assertEquals(-32602, call(Map.of("command", "wget https://x.io")).path("error").path("code").asInt());
        assertEquals(-32602, call(Map.of("command", "")).path("error").path("code").asInt());
        assertEquals(-32602, call(Map.of("other", "x")).path("error").path("code").asInt());
    }
}
