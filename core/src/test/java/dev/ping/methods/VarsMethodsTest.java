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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Drives variables and environments the way the desktop does: JSON over the RPC boundary.
 *
 * <p>These also feed the native-image tracing agent the collection and environment records,
 * so the shipped binary can parse a real {@code collection.yaml} and environment file.
 */
class VarsMethodsTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    private String line(int id, String method, Object params) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return json.writeValueAsString(request);
    }

    private JsonNode call(String method, Object params) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(
                new ByteArrayInputStream((line(1, method, params) + "\n").getBytes(StandardCharsets.UTF_8)),
                out);
        StoreMethods.registerOn(server);
        VarsMethods.registerOn(server);
        server.serve();

        return out.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(text -> !text.isBlank())
                .map(text -> {
                    try {
                        return json.readTree(text);
                    } catch (Exception e) {
                        throw new AssertionError("Non-JSON output: " + text, e);
                    }
                })
                .filter(node -> node.has("id"))
                .findFirst()
                .orElseThrow();
    }

    private void seedCollection() throws Exception {
        Files.createDirectories(workspace.resolve("demo/environments"));
        Files.writeString(workspace.resolve("demo/collection.yaml"), """
                name: Demo API
                variables:
                  - name: base
                    value: https://collection
                  - name: token
                    value: collection-token
                """);
        Files.writeString(workspace.resolve("demo/environments/dev.yaml"), """
                name: Dev
                variables:
                  - name: base
                    value: https://environment
                """);
    }

    @Test
    void catalogReportsNameVariablesAndEnvironments() throws Exception {
        seedCollection();

        JsonNode result = call("vars.catalog",
                Map.of("root", workspace.toString(), "collection", "demo")).path("result");

        assertEquals("Demo API", result.path("name").asText());
        assertEquals(2, result.path("variables").size());
        assertEquals("base", result.path("variables").get(0).path("name").asText());

        assertEquals(1, result.path("environments").size());
        assertEquals("Dev", result.path("environments").get(0).path("name").asText());
        assertEquals("demo/environments/dev.yaml",
                result.path("environments").get(0).path("path").asText());
    }

    @Test
    void scanHidesCollectionMetadataAndEnvironments() throws Exception {
        seedCollection();
        Files.writeString(workspace.resolve("demo/get.yaml"), """
                name: Get thing
                method: GET
                url: https://example.com/thing
                """);
        Files.writeString(workspace.resolve("demo/environments/ignored-request.yaml"), """
                name: Not a request
                method: GET
                url: https://example.com/nope
                """);

        JsonNode collection = call("store.scan", Map.of("root", workspace.toString()))
                .path("result").path("collections").get(0);

        assertEquals(1, collection.path("children").size(),
                "collection.yaml and environments/ must not appear as requests");
        assertEquals("Get thing", collection.path("children").get(0).path("name").asText());
    }

    @Test
    void savingAnEnvironmentThenResolvingAppliesPrecedence() throws Exception {
        Files.createDirectories(workspace.resolve("demo"));

        call("vars.saveCollection", Map.of(
                "root", workspace.toString(),
                "collection", "demo",
                "name", "Demo",
                "variables", List.of(
                        Map.of("name", "base", "value", "https://collection"),
                        Map.of("name", "token", "value", "collection-token"))));

        JsonNode saved = call("vars.saveEnvironment", Map.of(
                "root", workspace.toString(),
                "collection", "demo",
                "name", "Dev",
                "variables", List.of(Map.of("name", "base", "value", "https://environment"))));

        String environmentPath = saved.path("result").path("path").asText();
        assertEquals("demo/environments/dev.yaml", environmentPath);

        JsonNode resolved = call("vars.resolve", Map.of(
                "root", workspace.toString(),
                "collection", "demo",
                "environment", environmentPath)).path("result").path("variables");

        assertEquals("https://environment", resolved.path("base").asText());
        assertEquals("collection-token", resolved.path("token").asText());

        // With no environment selected the collection value stands on its own.
        JsonNode collectionOnly = call("vars.resolve", Map.of(
                "root", workspace.toString(),
                "collection", "demo")).path("result").path("variables");
        assertEquals("https://collection", collectionOnly.path("base").asText());
    }

    // --- collection notes ------------------------------------------------------------------

    private JsonNode catalog() throws Exception {
        return call("vars.catalog", Map.of("root", workspace.toString(), "collection", "demo")).path("result");
    }

    private void save(Map<String, Object> extra) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>(extra);
        params.put("root", workspace.toString());
        params.put("collection", "demo");
        assertFalse(call("vars.saveCollection", params).has("error"));
    }

    @Test
    void collectionNotesRoundTripAndAreOmittedWhenThereAreNone() throws Exception {
        seedCollection();
        assertFalse(catalog().has("docs"), "no notes, no field");

        save(Map.of("docs", "# Demo\n\nHow this API works."));
        assertEquals("# Demo\n\nHow this API works.", catalog().path("docs").asText());
        String yaml = Files.readString(workspace.resolve("demo/collection.yaml"));
        assertTrue(yaml.contains("docs:"), yaml);
        assertTrue(yaml.indexOf("variables:") < yaml.indexOf("docs:"), "key order: " + yaml);
    }

    @Test
    void savingVariablesWithoutDocsNeverWipesTheNotes() throws Exception {
        seedCollection();
        save(Map.of("docs", "keep me"));

        save(Map.of("name", "Renamed", "variables",
                java.util.List.of(Map.of("name", "base", "value", "https://x", "enabled", true))));

        JsonNode after = catalog();
        assertEquals("keep me", after.path("docs").asText(), "a caller that sends no docs must not clear them");
        assertEquals("Renamed", after.path("name").asText());
        assertEquals(1, after.path("variables").size());
    }

    @Test
    void anEmptyDocsStringClearsTheNotes() throws Exception {
        seedCollection();
        save(Map.of("docs", "temporary"));
        save(Map.of("docs", ""));
        assertFalse(catalog().has("docs"));
        assertFalse(Files.readString(workspace.resolve("demo/collection.yaml")).contains("docs"));
    }
}
