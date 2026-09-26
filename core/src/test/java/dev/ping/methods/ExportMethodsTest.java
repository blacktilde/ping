package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.rpc.RpcServer;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends {@code export.collection} as JSON, so {@code ExportResult} crosses Jackson the way it
 * does in the shipped binary and the native-image agent records it.
 */
class ExportMethodsTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    private JsonNode call(String method, Map<String, Object> params) throws Exception {
        String line = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(
                new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8)), out);
        ExportMethods.registerOn(server);
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

    private void write(String path, String yaml) throws Exception {
        Path file = workspace.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /** A collection that uses most of what a request can hold. */
    private void sampleCollection() throws Exception {
        write("Shop/collection.yaml", """
                name: Shop API
                docs: The shop.
                variables:
                  - name: baseUrl
                    value: https://shop.test
                """);
        write("Shop/environments/prod.yaml", """
                name: prod
                variables:
                  - name: baseUrl
                    value: https://shop.example
                """);
        write("Shop/login.yaml", """
                name: Login
                method: POST
                url: "{{baseUrl}}/login"
                headers:
                  - name: Accept
                    value: application/json
                  - name: X-Debug
                    value: "1"
                    enabled: false
                body:
                  type: json
                  content: '{"user": "{{user}}", "password": "{{password}}"}'
                asserts:
                  - type: status
                    op: equals
                    expected: "200"
                capture:
                  - name: token
                    source: json
                    target: $.token
                docs: Signs in.
                """);
        write("Shop/Orders/list.yaml", """
                name: List orders
                url: "{{baseUrl}}/orders"
                query:
                  - name: page
                    value: "1"
                  - name: debug
                    value: "true"
                    enabled: false
                auth:
                  type: bearer
                  token: "{{token}}"
                verifyTls: false
                """);
        write("Shop/Orders/upload.yaml", """
                name: Upload receipt
                method: POST
                url: "{{baseUrl}}/receipts"
                body:
                  type: multipart
                  fields:
                    - name: note
                      value: thanks
                    - name: receipt
                      file: /home/alice/private/receipt.pdf
                      contentType: application/pdf
                """);
    }

    private JsonNode export(String path) throws Exception {
        return call("export.collection", Map.of("root", workspace.toString(), "path", path));
    }

    @Test
    void exportsACollectionAsPostmanV21() throws Exception {
        sampleCollection();
        JsonNode result = export("Shop").path("result");

        assertEquals("Shop API", result.path("name").asText());
        assertEquals(3, result.path("requests").asInt());
        JsonNode doc = json.readTree(result.path("content").asText());

        assertTrue(doc.path("info").path("schema").asText().contains("collection/v2.1.0"));
        assertEquals("Shop API", doc.path("info").path("name").asText());
        assertEquals("The shop.", doc.path("info").path("description").asText());
        assertEquals("baseUrl", doc.path("variable").get(0).path("key").asText());

        // Folders before requests, as in the sidebar.
        JsonNode orders = doc.path("item").get(0);
        assertEquals("Orders", orders.path("name").asText());
        JsonNode login = doc.path("item").get(1);
        assertEquals("Login", login.path("name").asText());

        JsonNode request = login.path("request");
        assertEquals("POST", request.path("method").asText());
        assertEquals("{{baseUrl}}/login", request.path("url").path("raw").asText());
        assertEquals("raw", request.path("body").path("mode").asText());
        assertEquals("json", request.path("body").path("options").path("raw").path("language").asText());
        assertTrue(request.path("header").get(1).path("disabled").asBoolean());
        assertEquals("Signs in.", request.path("description").asText());

        JsonNode list = orders.path("item").get(0);
        assertEquals("List orders", list.path("name").asText());
        JsonNode url = list.path("request").path("url");
        // Only the enabled row is in raw; both rows are in query, the disabled one marked.
        assertEquals("{{baseUrl}}/orders?page=1", url.path("raw").asText());
        assertEquals(2, url.path("query").size());
        assertTrue(url.path("query").get(1).path("disabled").asBoolean());
        JsonNode auth = list.path("request").path("auth");
        assertEquals("bearer", auth.path("type").asText());
        assertEquals("{{token}}", auth.path("bearer").get(0).path("value").asText());
        assertFalse(list.path("protocolProfileBehavior").path("strictSSL").asBoolean(true));
    }

    @Test
    void neverResolvesASecretAndNeverLeaksAnAbsolutePath() throws Exception {
        sampleCollection();
        JsonNode result = export("Shop").path("result");
        String content = result.path("content").asText();

        assertTrue(content.contains("{{password}}"));
        assertTrue(content.contains("{{token}}"));
        assertFalse(content.contains("/home/alice"), content);

        JsonNode upload = json.readTree(content).path("item").get(0).path("item").get(1);
        JsonNode file = upload.path("request").path("body").path("formdata").get(1);
        assertEquals("file", file.path("type").asText());
        assertEquals("receipt.pdf", file.path("src").asText());
        assertEquals("application/pdf", file.path("contentType").asText());
    }

    @Test
    void reportsWhatPostmanCannotHold() throws Exception {
        sampleCollection();
        List<String> warnings = new ArrayList<>();
        export("Shop").path("result").path("warnings").forEach(w -> warnings.add(w.asText()));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("1 environment was not exported")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Login") && w.contains("1 assertion and 1 capture")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("receipt.pdf") && w.contains("chosen again")),
                warnings.toString());
    }

    @Test
    void anExportImportsBackAsTheSameRequests() throws Exception {
        sampleCollection();
        String content = export("Shop").path("result").path("content").asText();

        Path other = Files.createDirectory(workspace.resolve("other"));
        JsonNode imported = call("import.collection", Map.of("root", other.toString(), "content", content))
                .path("result");
        String path = imported.path("collections").get(0).path("path").asText();
        assertEquals(3, imported.path("collections").get(0).path("requests").asInt());

        YamlStore store = new YamlStore();
        StoredRequest original = store.read(workspace, "Shop/Orders/list.yaml");
        StoredRequest back = store.read(other, store.requestNodes(other, path).stream()
                .filter(node -> node.name().equals("List orders")).findFirst().orElseThrow().path());
        assertEquals(original.url(), back.url());
        assertEquals(rows(original.query()), rows(back.query()));
        assertEquals(original.auth(), back.auth());

        StoredRequest login = store.read(other, store.requestNodes(other, path).stream()
                .filter(node -> node.name().equals("Login")).findFirst().orElseThrow().path());
        assertEquals("json", login.body().type());
        assertEquals("POST", login.method());
    }

    /** Rows by meaning: an absent {@code enabled} and {@code true} are the same row. */
    private static List<String> rows(List<dev.ping.http.RequestSpec.Param> params) {
        return params.stream().map(p -> p.name() + "=" + p.value() + (p.isEnabled() ? "" : " (off)")).toList();
    }

    @Test
    void anUnreadableRequestIsReportedAndTheRestStillExports() throws Exception {
        sampleCollection();
        write("Shop/broken.yaml", "name: [unclosed\n");
        JsonNode result = export("Shop").path("result");

        assertEquals(3, result.path("requests").asInt());
        assertTrue(result.path("warnings").toString().contains("broken.yaml"));
    }

    @Test
    void aCleanExportCarriesNoWarningsField() throws Exception {
        write("Plain/ping.yaml", "name: Ping\nurl: https://x.test\n");
        JsonNode result = export("Plain").path("result");
        assertEquals(1, result.path("requests").asInt());
        assertFalse(result.has("warnings"));
    }

    @Test
    void anEmptyCollectionStillReportsItsCount() throws Exception {
        Files.createDirectories(workspace.resolve("Empty"));
        JsonNode result = export("Empty").path("result");
        assertTrue(result.has("requests"), result.toString());
        assertEquals(0, result.path("requests").asInt());
        assertEquals(0, json.readTree(result.path("content").asText()).path("item").size());
    }

    @Test
    void rejectsAPathOutsideTheWorkspaceAndBadParams() throws Exception {
        String root = workspace.toString();
        assertEquals(-32602, export("../elsewhere").path("error").path("code").asInt());
        assertEquals(-32602, call("export.collection", Map.of("root", root)).path("error").path("code").asInt());
        assertEquals(-32602, call("export.collection", Map.of("path", "Shop")).path("error").path("code").asInt());
        write("Shop/a.yaml", "name: A\n");
        assertEquals(-32602, call("export.collection", Map.of("root", root, "path", "Shop", "format", "har"))
                .path("error").path("code").asInt());
        assertEquals(-32003, export("Missing").path("error").path("code").asInt());
    }
}
