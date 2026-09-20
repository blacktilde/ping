package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the store the way the desktop does: as JSON crossing the RPC boundary.
 *
 * <p>Besides covering the behaviour, these tests are what the native-image tracing agent
 * observes. The YAML round trip uses Jackson reflection on {@code StoredRequest} and the
 * nested request records, so without a test that actually binds them from JSON the shipped
 * binary would fail on the first collection it opened.
 */
class StoreMethodsTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    /** Builds a request line structurally so Windows paths never need hand-escaping. */
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
        server.serve();

        List<JsonNode> responses = out.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .map(text -> {
                    try {
                        return json.readTree(text);
                    } catch (Exception e) {
                        throw new AssertionError("Non-JSON output: " + text, e);
                    }
                })
                .filter(node -> node.has("id"))
                .toList();

        return responses.get(0);
    }

    @Test
    void scansCollectionsFoldersAndRequests() throws Exception {
        Files.createDirectories(workspace.resolve("my-api/users"));
        Files.writeString(workspace.resolve("my-api/login.yaml"), """
                name: Login
                method: POST
                url: https://example.com/login
                """);
        Files.writeString(workspace.resolve("my-api/users/list.yaml"), """
                name: List users
                method: GET
                url: https://example.com/users
                """);
        // Hidden files and non-YAML files are not part of the tree.
        Files.writeString(workspace.resolve("my-api/.notes.yaml"), "name: nope");
        Files.writeString(workspace.resolve("my-api/readme.md"), "not a request");

        JsonNode collections = call("store.scan", Map.of("root", workspace.toString()))
                .path("result").path("collections");

        assertEquals(1, collections.size());
        JsonNode collection = collections.get(0);
        assertEquals("my-api", collection.path("name").asText());
        assertEquals("collection", collection.path("type").asText());
        assertEquals("my-api", collection.path("path").asText());

        // Folders sort before requests.
        assertEquals("users", collection.path("children").get(0).path("name").asText());
        assertEquals("folder", collection.path("children").get(0).path("type").asText());
        JsonNode login = collection.path("children").get(1);
        assertEquals("Login", login.path("name").asText(), "the display name comes from the file");
        assertEquals("POST", login.path("method").asText());
        assertEquals("my-api/login.yaml", login.path("path").asText());

        JsonNode listed = collection.path("children").get(0).path("children").get(0);
        assertEquals("List users", listed.path("name").asText());
        assertEquals("my-api/users/list.yaml", listed.path("path").asText());
    }

    @Test
    void sortsRequestsByDisplayNameNotFileName() throws Exception {
        // File names are slugs that do not follow the display names, so a path sort would
        // put them out of order: z-first.yaml is "Alpha" and a-first.yaml is "Zulu".
        Files.createDirectories(workspace.resolve("api"));
        Files.writeString(workspace.resolve("api/z-first.yaml"), """
                name: Alpha
                method: GET
                url: https://example.com/a
                """);
        Files.writeString(workspace.resolve("api/a-first.yaml"), """
                name: Zulu
                method: GET
                url: https://example.com/z
                """);
        Files.writeString(workspace.resolve("api/m-middle.yaml"), """
                name: Mike
                method: GET
                url: https://example.com/m
                """);

        JsonNode children = call("store.scan", Map.of("root", workspace.toString()))
                .path("result").path("collections").get(0).path("children");

        assertEquals("Alpha", children.get(0).path("name").asText());
        assertEquals("Mike", children.get(1).path("name").asText());
        assertEquals("Zulu", children.get(2).path("name").asText());
    }

    @Test
    void readsARequestFile() throws Exception {
        Files.createDirectories(workspace.resolve("api"));
        Files.writeString(workspace.resolve("api/create.yaml"), """
                name: Create item
                method: POST
                url: https://example.com/items
                query:
                  - name: dry
                    value: "true"
                headers:
                  - name: X-Token
                    value: keep-out
                body:
                  type: json
                  content: '{"a":1}'
                auth:
                  type: basic
                  username: alice
                  password: hunter2
                timeoutMs: 1234
                """);

        JsonNode result = call("store.read",
                Map.of("root", workspace.toString(), "path", "api/create.yaml")).path("result");

        assertEquals("Create item", result.path("name").asText());
        assertEquals("POST", result.path("method").asText());
        assertEquals("true", result.path("query").get(0).path("value").asText());
        assertEquals("X-Token", result.path("headers").get(0).path("name").asText());
        assertEquals("json", result.path("body").path("type").asText());
        assertEquals("{\"a\":1}", result.path("body").path("content").asText());
        assertEquals("basic", result.path("auth").path("type").asText());
        assertEquals("alice", result.path("auth").path("username").asText());
        assertEquals(1234, result.path("timeoutMs").asInt());
    }

    @Test
    void assertsSurviveAYamlRoundTripAndEmptyOnesAreOmitted() throws Exception {
        call("store.write", Map.of("root", workspace.toString(), "path", "a.yaml",
                "request", Map.of("name", "A", "method", "GET", "url", "https://example.com",
                        "asserts", List.of(
                                Map.of("type", "status", "expected", "200"),
                                Map.of("type", "jsonpath", "target", "$.id", "op", "equals",
                                        "expected", "7", "enabled", false)))));

        String yaml = Files.readString(workspace.resolve("a.yaml"));
        assertTrue(yaml.contains("asserts:"), yaml);
        assertTrue(yaml.indexOf("url:") < yaml.indexOf("asserts:"), "asserts key order: " + yaml);

        JsonNode asserts = call("store.read",
                Map.of("root", workspace.toString(), "path", "a.yaml"))
                .path("result").path("asserts");
        assertEquals(2, asserts.size());
        assertEquals("status", asserts.get(0).path("type").asText());
        assertEquals("$.id", asserts.get(1).path("target").asText());
        assertFalse(asserts.get(1).path("enabled").asBoolean(true));

        call("store.write", Map.of("root", workspace.toString(), "path", "b.yaml",
                "request", Map.of("name", "B", "method", "GET", "url", "https://example.com",
                        "asserts", List.of())));
        assertFalse(Files.readString(workspace.resolve("b.yaml")).contains("asserts"));
    }

    @Test
    void captureSurvivesAYamlRoundTripAndAnEmptyListIsOmitted() throws Exception {
        call("store.write", Map.of("root", workspace.toString(), "path", "a.yaml",
                "request", Map.of("name", "A", "method", "GET", "url", "https://example.com",
                        "capture", List.of(
                                Map.of("name", "token", "source", "jsonpath", "target", "$.access"),
                                Map.of("name", "code", "source", "status", "enabled", false)))));

        String yaml = Files.readString(workspace.resolve("a.yaml"));
        assertTrue(yaml.contains("capture:"), yaml);
        assertTrue(yaml.indexOf("url:") < yaml.indexOf("capture:"), "capture key order: " + yaml);

        JsonNode capture = call("store.read",
                Map.of("root", workspace.toString(), "path", "a.yaml")).path("result").path("capture");
        assertEquals(2, capture.size());
        assertEquals("$.access", capture.get(0).path("target").asText());
        assertFalse(capture.get(1).path("enabled").asBoolean(true));

        call("store.write", Map.of("root", workspace.toString(), "path", "b.yaml",
                "request", Map.of("name", "B", "method", "GET", "url", "https://example.com",
                        "capture", List.of())));
        assertFalse(Files.readString(workspace.resolve("b.yaml")).contains("capture"));
    }

    @Test
    void thePinnedHttpVersionSurvivesAYamlRoundTripAndTheDefaultIsOmitted() throws Exception {
        call("store.write", Map.of("root", workspace.toString(), "path", "a.yaml",
                "request", Map.of("name", "A", "method", "GET", "url", "https://example.com", "httpVersion", "1.1")));
        String yaml = Files.readString(workspace.resolve("a.yaml"));
        assertTrue(yaml.contains("httpVersion: \"1.1\"") || yaml.contains("httpVersion: '1.1'")
                || yaml.contains("httpVersion: 1.1"), yaml);
        assertEquals("1.1", call("store.read", Map.of("root", workspace.toString(), "path", "a.yaml"))
                .path("result").path("httpVersion").asText());

        call("store.write", Map.of("root", workspace.toString(), "path", "b.yaml",
                "request", Map.of("name", "B", "method", "GET", "url", "https://example.com")));
        assertFalse(Files.readString(workspace.resolve("b.yaml")).contains("httpVersion"));
    }

    @Test
    void theCookieOptOutSurvivesAYamlRoundTripAndTheDefaultIsOmitted() throws Exception {
        call("store.write", Map.of("root", workspace.toString(), "path", "a.yaml",
                "request", Map.of("name", "A", "method", "GET", "url", "https://example.com", "cookies", false)));
        String yaml = Files.readString(workspace.resolve("a.yaml"));
        assertTrue(yaml.contains("cookies: false"), yaml);
        assertTrue(yaml.indexOf("url:") < yaml.indexOf("cookies:"), yaml);
        assertFalse(call("store.read", Map.of("root", workspace.toString(), "path", "a.yaml"))
                .path("result").path("cookies").asBoolean(true));

        call("store.write", Map.of("root", workspace.toString(), "path", "b.yaml",
                "request", Map.of("name", "B", "method", "GET", "url", "https://example.com")));
        assertFalse(Files.readString(workspace.resolve("b.yaml")).contains("cookies"));
    }

    @Test
    void writesYamlThatReadsBack() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "New request",
                "method", "PUT",
                "url", "https://example.com/x",
                "query", List.of(Map.of("name", "a", "value", "b", "enabled", true)),
                "body", Map.of("type", "raw", "content", "hello", "contentType", "text/plain"),
                "auth", Map.of("type", "bearer", "token", "s3cret"));

        JsonNode response = call("store.write", Map.of(
                "root", workspace.toString(),
                "path", "api/new.yaml",
                "request", request));

        assertEquals("api/new.yaml", response.path("result").path("path").asText());

        Path file = workspace.resolve("api/new.yaml");
        assertTrue(Files.isRegularFile(file), "the write must create parent folders");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("method: PUT"), yaml);
        assertFalse(yaml.contains("null"), "empty fields must be omitted: " + yaml);
        assertFalse(yaml.startsWith("---"), "no document marker for a single document");

        JsonNode readBack = call("store.read",
                Map.of("root", workspace.toString(), "path", "api/new.yaml")).path("result");
        assertEquals("PUT", readBack.path("method").asText());
        assertEquals("hello", readBack.path("body").path("content").asText());
        assertEquals("a", readBack.path("query").get(0).path("name").asText());
        assertEquals("bearer", readBack.path("auth").path("type").asText());
        assertEquals("s3cret", readBack.path("auth").path("token").asText());
    }

    @Test
    void createsUniqueFilesForRepeatedNames() throws Exception {
        Files.createDirectories(workspace.resolve("api"));

        String first = call("store.create",
                Map.of("root", workspace.toString(), "collection", "api", "name", "Get thing"))
                .path("result").path("path").asText();
        String second = call("store.create",
                Map.of("root", workspace.toString(), "collection", "api", "name", "Get thing"))
                .path("result").path("path").asText();

        assertEquals("api/get-thing.yaml", first);
        assertEquals("api/get-thing-2.yaml", second);
        assertTrue(Files.isRegularFile(workspace.resolve(second)));

        JsonNode created = call("store.read",
                Map.of("root", workspace.toString(), "path", second)).path("result");
        assertEquals("Get thing", created.path("name").asText());
        assertEquals("none", created.path("body").path("type").asText());
    }

    @Test
    void refusesToEscapeTheWorkspace() throws Exception {
        JsonNode read = call("store.read",
                Map.of("root", workspace.toString(), "path", "../escape.yaml"));
        assertEquals(RpcException.INVALID_PARAMS, read.path("error").path("code").asInt());

        JsonNode write = call("store.write", Map.of(
                "root", workspace.toString(),
                "path", "../escape.yaml",
                "request", Map.of("name", "nope")));
        assertEquals(RpcException.INVALID_PARAMS, write.path("error").path("code").asInt());
    }

    @Test
    void reportsUnparseableFilesAsStoreFailures() throws Exception {
        Files.createDirectories(workspace.resolve("api"));
        Files.writeString(workspace.resolve("api/broken.yaml"), "name: [unclosed\n");

        JsonNode response = call("store.read",
                Map.of("root", workspace.toString(), "path", "api/broken.yaml"));

        assertEquals(RpcException.STORE_FAILED, response.path("error").path("code").asInt());
    }

    @Test
    void scaffoldsAStarterCollectionOnce() throws Exception {
        JsonNode first = call("store.scaffold",
                Map.of("root", workspace.toString(), "collection", "My Collection"));

        assertEquals("My Collection", first.path("result").path("collection").asText());
        Path collection = workspace.resolve("My Collection");
        assertTrue(Files.isRegularFile(collection.resolve("collection.yaml")));
        Path starter = collection.resolve("get-started.yaml");
        assertTrue(Files.isRegularFile(starter));

        // A second run must leave whatever the user has since written alone.
        Files.writeString(starter, "name: Mine\nmethod: POST\nurl: https://example.com\n");
        call("store.scaffold", Map.of("root", workspace.toString(), "collection", "My Collection"));
        assertTrue(Files.readString(starter).contains("name: Mine"),
                "scaffolding must not overwrite an existing collection");
    }

    @Test
    void deletesACollectionRecursively() throws Exception {
        Files.createDirectories(workspace.resolve("col/users"));
        Files.writeString(workspace.resolve("col/a.yaml"), "name: a\n");
        Files.writeString(workspace.resolve("col/users/b.yaml"), "name: b\n");

        JsonNode response = call("store.delete",
                Map.of("root", workspace.toString(), "path", "col"));

        assertFalse(response.has("error"), response.toString());
        assertFalse(Files.exists(workspace.resolve("col")), "the whole collection should be gone");
    }

    @Test
    void refusesToDeleteTheRootOrEscape() throws Exception {
        JsonNode root = call("store.delete", Map.of("root", workspace.toString(), "path", "."));
        assertEquals(RpcException.INVALID_PARAMS, root.path("error").path("code").asInt());

        JsonNode escape = call("store.delete",
                Map.of("root", workspace.toString(), "path", "../x"));
        assertEquals(RpcException.INVALID_PARAMS, escape.path("error").path("code").asInt());
    }

    @Test
    void refusesSymlinksThatLeaveTheWorkspace() throws Exception {
        Path outside = Files.createTempDirectory("ping-outside");
        try {
            Files.createDirectories(workspace.resolve("col"));
            Files.writeString(outside.resolve("secret.yaml"), "name: secret\n");
            try {
                Files.createSymbolicLink(
                        workspace.resolve("col/linked.yaml"), outside.resolve("secret.yaml"));
                Files.createSymbolicLink(workspace.resolve("col/outdir"), outside);
                Files.createSymbolicLink(workspace.resolve("col/loop"), Path.of(".."));
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "symlinks are unavailable here: " + e.getMessage());
            }
            Files.writeString(workspace.resolve("col/real.yaml"), "name: real\n");

            JsonNode read = call("store.read",
                    Map.of("root", workspace.toString(), "path", "col/linked.yaml"));
            assertEquals(RpcException.INVALID_PARAMS, read.path("error").path("code").asInt());

            JsonNode write = call("store.write", Map.of(
                    "root", workspace.toString(),
                    "path", "col/outdir/planted.yaml",
                    "request", Map.of("name", "planted")));
            assertEquals(RpcException.INVALID_PARAMS, write.path("error").path("code").asInt());
            assertFalse(Files.exists(outside.resolve("planted.yaml")),
                    "nothing may be written outside the workspace");

            JsonNode children = call("store.scan", Map.of("root", workspace.toString()))
                    .path("result").path("collections").get(0).path("children");
            List<String> names = new ArrayList<>();
            children.forEach(node -> names.add(node.path("name").asText()));
            assertTrue(names.contains("real"), names.toString());
            assertFalse(names.contains("linked") || names.contains("outdir") || names.contains("loop"),
                    "a symlink must not appear in the tree, let alone be followed: " + names);
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    void writtenFilesAreReadableByOthers() throws Exception {
        assumeTrue(
                Files.getFileStore(workspace)
                        .supportsFileAttributeView(PosixFileAttributeView.class),
                "this filesystem has no POSIX file modes");
        Files.createDirectories(workspace.resolve("col"));

        call("store.write", Map.of(
                "root", workspace.toString(),
                "path", "col/x.yaml",
                "request", Map.of("name", "x")));

        Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(workspace.resolve("col/x.yaml"));
        assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ),
                "a written file should be as shareable as a hand-written one: " + permissions);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
