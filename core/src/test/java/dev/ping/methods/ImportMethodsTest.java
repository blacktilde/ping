package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @TempDir
    Path workspace;

    private JsonNode call(Map<String, Object> params) throws Exception {
        return call("import.curl", params);
    }

    private JsonNode call(String method, Map<String, Object> params) throws Exception {
        String line = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", method, "params", params));
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

    private static final String POSTMAN = """
            {"info": {"name": "Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "item": [{"name": "Login", "event": [{"listen": "test", "script": {"exec": ["pm.test('x')"]}}],
                       "request": {"method": "POST", "url": "https://x.io/login",
                         "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "s3cr3t-token"}]},
                         "body": {"mode": "file", "file": {"src": "/tmp/x"}}}}]}""";

    @Test
    void importsACollectionIntoTheWorkspaceAndReturnsSecretsWarningsAndCounts() throws Exception {
        JsonNode result = call("import.collection",
                Map.of("root", workspace.toString(), "content", POSTMAN)).path("result");

        JsonNode collection = result.path("collections").get(0);
        assertEquals("Demo", collection.path("path").asText());
        assertEquals("Demo", collection.path("name").asText());
        assertEquals(1, collection.path("requests").asInt());

        assertEquals(1, result.path("warnings").size());
        JsonNode secret = result.path("secrets").get(0);
        assertEquals("import-demo-login-token", secret.path("name").asText());
        assertEquals("s3cr3t-token", secret.path("value").asText());

        String yaml = Files.readString(workspace.resolve("Demo/login.yaml"));
        assertTrue(yaml.contains("{{import-demo-login-token}}"), yaml);
        assertFalse(yaml.contains("s3cr3t-token"), yaml);
        assertTrue(yaml.contains("docs:"), yaml);
    }

    @Test
    void rejectsAnUnusableCollectionImportAsInvalidParams() throws Exception {
        String root = workspace.toString();
        assertEquals(-32602, call("import.collection", Map.of("root", root, "content", "nope"))
                .path("error").path("code").asInt());
        assertEquals(-32602, call("import.collection", Map.of("root", root, "content", ""))
                .path("error").path("code").asInt());
        assertEquals(-32602, call("import.collection", Map.of("content", POSTMAN))
                .path("error").path("code").asInt());
        try (var files = Files.list(workspace)) {
            assertEquals(0, files.count(), "a rejected import writes nothing");
        }
    }

    @Test
    void importsAnOpenApiYamlDocumentSentAsJson() throws Exception {
        String spec = String.join("\n",
                "openapi: 3.0.3",
                "info: {title: Pets}",
                "servers: [{url: 'https://pets.io'}]",
                "security: [{bearerAuth: []}]",
                "paths:",
                "  /pets/{id}:",
                "    get:",
                "      tags: [pets]",
                "      summary: Get pet",
                "components:",
                "  securitySchemes:",
                "    bearerAuth: {type: http, scheme: bearer}",
                "");
        JsonNode result = call("import.collection",
                Map.of("root", workspace.toString(), "content", spec)).path("result");

        assertEquals("Pets", result.path("collections").get(0).path("path").asText());
        assertEquals(1, result.path("collections").get(0).path("requests").asInt());
        assertFalse(result.has("secrets"), "a spec holds no credentials to lift");
        assertTrue(result.path("warnings").get(0).asText().contains("bearerAuth"));

        String yaml = Files.readString(workspace.resolve("Pets/pets/get-pet.yaml"));
        assertTrue(yaml.contains("{{baseUrl}}/pets/{{id}}"), yaml);
        assertTrue(yaml.contains("{{bearerAuth}}"), yaml);
    }
}
