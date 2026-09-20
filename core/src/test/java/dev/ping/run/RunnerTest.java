package dev.ping.run;

import com.sun.net.httpserver.HttpServer;
import dev.ping.http.HttpEngine;
import dev.ping.rpc.RpcException;
import dev.ping.store.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerTest {

    @TempDir
    Path root;

    private HttpServer server;
    private final Runner runner = new Runner(new YamlStore(), new HttpEngine());

    @BeforeEach
    void start() throws IOException {
        server = RunFixture.startServer();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RunResult run(boolean broken, RunOptions options) throws IOException {
        RunFixture.write(root, RunFixture.baseUrl(server), broken);
        return runner.run(root, "demo", options, null);
    }

    @Test
    void runsInSidebarOrderFoldersFirst() throws IOException {
        RunResult result = run(true, new RunOptions("staging", Map.of()));
        assertEquals(List.of("Login", "Dead", "Health", "Wrong"),
                result.requests().stream().map(RequestResult::name).toList());
        assertEquals("Demo", result.collection());
        assertEquals("Staging", result.environment());
    }

    @Test
    void separatesPassedFailedAndErrored() throws IOException {
        RunResult result = run(true, new RunOptions("staging", Map.of()));

        assertEquals(4, result.total());
        assertEquals(2, result.passed());
        assertEquals(1, result.failed());
        assertEquals(1, result.errored());
        assertFalse(result.succeeded());

        RequestResult dead = result.requests().get(1);
        assertTrue(dead.errored());
        assertNull(dead.status());
        assertFalse(dead.passed());

        RequestResult wrong = result.requests().get(3);
        assertFalse(wrong.errored());
        assertEquals(200, wrong.status());
        assertFalse(wrong.passed());
        assertEquals("Expected status 404, got 200", wrong.assertions().get(0).message());
    }

    @Test
    void aDeadRequestDoesNotStopTheRun() throws IOException {
        RunResult result = run(true, new RunOptions("staging", Map.of()));
        // Health and Wrong come after Dead and still ran.
        assertEquals(200, result.requests().get(2).status());
        assertEquals(200, result.requests().get(3).status());
    }

    @Test
    void aRequestWithNoAssertionsPassesWhenItGetsAResponse() throws IOException {
        RunFixture.write(root, RunFixture.baseUrl(server), false);
        java.nio.file.Files.writeString(root.resolve("demo/plain.yaml"),
                "name: Plain\nmethod: GET\nurl: \"{{baseUrl}}/ok\"\n");
        RunResult result = runner.run(root, "demo", RunOptions.none(), null);
        RequestResult plain = result.requests().stream()
                .filter(r -> r.name().equals("Plain")).findFirst().orElseThrow();
        assertTrue(plain.passed());
    }

    @Test
    void theEnvironmentDecidesWhatHealthSends() throws IOException {
        assertTrue(run(false, new RunOptions("staging", Map.of())).succeeded());

        // Without it {{who}} goes out literally, so the jsonpath assertion fails.
        RunResult without = runner.run(root, "demo", RunOptions.none(), null);
        assertFalse(without.succeeded());
        assertEquals(1, without.failed());
    }

    @Test
    void anEnvironmentIsFoundByNameFileNameOrPath() throws IOException {
        RunFixture.write(root, RunFixture.baseUrl(server), false);
        for (String key : List.of("Staging", "staging", "STAGING", "demo/environments/staging.yaml")) {
            assertEquals("Staging",
                    runner.run(root, "demo", new RunOptions(key, Map.of()), null).environment(), key);
        }
    }

    @Test
    void anUnknownEnvironmentListsWhatExists() throws IOException {
        RunFixture.write(root, RunFixture.baseUrl(server), false);
        RpcException error = assertThrows(RpcException.class, () ->
                runner.run(root, "demo", new RunOptions("prod", Map.of()), null));
        assertEquals("Unknown environment \"prod\". Available: Staging", error.getMessage());
    }

    @Test
    void explicitVariablesOutrankEveryOtherScope() throws IOException {
        // The environment says staging; the override says something else, so Health fails.
        RunResult result = run(false, new RunOptions("staging", Map.of("who", "override")));
        assertFalse(result.succeeded());
        assertEquals(1, result.failed());
    }

    @Test
    void explicitVariablesAreMaskedWhereverTheyResurface() throws IOException {
        // The server echoes X-Who back, and the assertion's expected value is interpolated,
        // so the value would otherwise appear in both actual and message.
        RunResult result = run(false, new RunOptions("staging", Map.of("who", "hunter2-secret")));
        String everything = result.requests().toString();
        assertFalse(everything.contains("hunter2-secret"), everything);
        assertTrue(everything.contains("***"), everything);
    }

    @Test
    void valuesTooShortToMaskSafelyAreLeftAlone() throws IOException {
        RunResult result = run(false, new RunOptions("staging", Map.of("who", "abc")));
        assertEquals("abc", result.requests().get(1).assertions().get(1).actual());
    }

    @Test
    void reportsProgressOncePerRequestInOrder() throws IOException {
        RunFixture.write(root, RunFixture.baseUrl(server), true);
        List<String> seen = new ArrayList<>();
        runner.run(root, "demo", new RunOptions("staging", Map.of()),
                (index, total, result) -> seen.add(index + "/" + total + " " + result.name()));
        assertEquals(List.of("0/4 Login", "1/4 Dead", "2/4 Health", "3/4 Wrong"), seen);
    }

    @Test
    void resultsCarryTheUrlAsWrittenNeverTheResolvedOne() throws IOException {
        RunResult result = run(false, new RunOptions("staging", Map.of("baseUrl", "http://secret.invalid")));
        assertEquals("{{baseUrl}}/ok", result.requests().get(0).url());
    }

    @Test
    void aMissingCollectionIsAStoreError() {
        assertThrows(RpcException.class,
                () -> runner.run(root, "nope", RunOptions.none(), null));
    }

    /** A three-step chain: log in and capture, use the capture, then use a capture that missed. */
    private void writeChain() throws IOException {
        java.nio.file.Path chain = root.resolve("chain");
        java.nio.file.Files.createDirectories(chain);
        java.nio.file.Files.writeString(chain.resolve("collection.yaml"), """
                name: Chain
                variables:
                - name: baseUrl
                  value: "%s"
                  enabled: true
                """.formatted(RunFixture.baseUrl(server)));
        java.nio.file.Files.writeString(chain.resolve("a-login.yaml"), """
                name: A login
                method: GET
                url: "{{baseUrl}}/login"
                asserts:
                - type: jsonpath
                  target: $.token
                  op: exists
                capture:
                - name: token
                  source: jsonpath
                  target: $.token
                - name: missing
                  source: jsonpath
                  target: $.nope
                - name: code
                  source: status
                """);
        java.nio.file.Files.writeString(chain.resolve("b-use.yaml"), """
                name: B use
                method: GET
                url: "{{baseUrl}}/who"
                headers:
                - name: X-Who
                  value: "{{token}}"
                  enabled: true
                asserts:
                - type: jsonpath
                  target: $.who
                  op: equals
                  expected: "{{token}}"
                """);
        java.nio.file.Files.writeString(chain.resolve("c-miss.yaml"), """
                name: C miss
                method: GET
                url: "{{baseUrl}}/who"
                headers:
                - name: X-Who
                  value: "{{missing}}"
                  enabled: true
                asserts:
                - type: body
                  op: contains
                  expected: "{{missing}}"
                """);
    }

    @Test
    void aCapturedValueIsOnTheWireInTheNextRequest() throws IOException {
        writeChain();
        RunResult result = runner.run(root, "chain", RunOptions.none(), null);

        RequestResult use = result.requests().get(1);
        assertEquals("B use", use.name());
        // The server echoed X-Who back, so this only passes if {{token}} became the captured value.
        assertTrue(use.passed(), use.assertions().toString());
        assertEquals(200, use.status());
    }

    @Test
    void aCaptureThatMissesLeavesTheVariableAbsentNotEmpty() throws IOException {
        writeChain();
        RunResult result = runner.run(root, "chain", RunOptions.none(), null);

        RequestResult login = result.requests().get(0);
        CaptureResultView missing = view(login, "missing");
        assertFalse(missing.found());
        assertTrue(missing.message().contains("matched nothing"), missing.message());

        // C's server saw the placeholder itself. An empty string would have sent "who":"".
        RequestResult miss = result.requests().get(2);
        assertTrue(miss.passed(), "the body must hold the literal {{missing}}: " + miss.assertions());
    }

    @Test
    void capturedValuesNeverAppearInAResult() throws Exception {
        writeChain();
        RunResult result = runner.run(root, "chain", RunOptions.none(), null);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("tok-secret-99"), "the captured token leaked into a result: " + json);
        // The login request's own assertion echoes the token it just captured; it is masked too.
        assertEquals("***", result.requests().get(0).assertions().get(0).actual());
        assertEquals("***", result.requests().get(1).assertions().get(0).expected());

        CaptureResultView token = view(result.requests().get(0), "token");
        assertTrue(token.found());
        assertEquals(null, token.value(), "results carry names, not values");
        // Short values are not masked (the same floor as explicit variables) and still carry no value.
        assertTrue(view(result.requests().get(0), "code").found());
    }

    private record CaptureResultView(boolean found, String message, String value) {
    }

    private static CaptureResultView view(RequestResult request, String name) {
        return request.captures().stream().filter(c -> c.name().equals(name)).findFirst()
                .map(c -> new CaptureResultView(c.found(), c.message(), c.value()))
                .orElseThrow(() -> new AssertionError("no capture named " + name));
    }

    // --- files ---------------------------------------------------------------------------------

    private static final byte[] BLOB = {0, 1, 2, (byte) 0xFF, '\r', '\n', 42, 7};

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** A collection whose requests upload {@code fixtures/blob.bin}, by the given stored path. */
    private void writeUploads(String storedPath) throws Exception {
        java.nio.file.Path upload = root.resolve("upload");
        java.nio.file.Files.createDirectories(upload.resolve("fixtures"));
        java.nio.file.Files.write(upload.resolve("fixtures/blob.bin"), BLOB);
        java.nio.file.Files.writeString(upload.resolve("collection.yaml"), """
                name: Upload
                variables:
                - name: baseUrl
                  value: "%s"
                  enabled: true
                """.formatted(RunFixture.baseUrl(server)));
        java.nio.file.Files.writeString(upload.resolve("a-binary.yaml"), """
                name: A binary
                method: PUT
                url: "{{baseUrl}}/upload"
                body:
                  type: file
                  file: "%s"
                asserts:
                - type: jsonpath
                  target: $.sha256
                  op: equals
                  expected: "%s"
                """.formatted(storedPath.replace("\\", "\\\\"), sha256(BLOB)));
        java.nio.file.Files.writeString(upload.resolve("b-multipart.yaml"), """
                name: B multipart
                method: POST
                url: "{{baseUrl}}/upload"
                body:
                  type: multipart
                  fields:
                  - name: logo
                    file: "%s"
                    filename: logo.bin
                asserts:
                - type: body
                  op: contains
                  expected: filename=\\"logo.bin\\"
                """.formatted(storedPath.replace("\\", "\\\\")));
    }

    @Test
    void aRelativeFileResolvesAgainstTheCollectionFolder() throws Exception {
        writeUploads("fixtures/blob.bin");
        RunResult result = runner.run(root, "upload", RunOptions.none(), null);
        assertTrue(result.succeeded(), result.requests().toString());
        assertEquals(2, result.total());
    }

    @Test
    void aCollectionCannotReachOutsideItselfWithARelativePath() throws Exception {
        writeUploads("../outside.bin");
        RunResult result = runner.run(root, "upload", RunOptions.none(), null);
        assertEquals(2, result.errored());
        assertTrue(result.requests().get(0).error().contains("outside the collection"),
                result.requests().get(0).error());
    }

    @Test
    void anAbsolutePathNeedsPermissionAndIsRefusedByDefault() throws Exception {
        java.nio.file.Path absolute = root.resolve("elsewhere.bin");
        java.nio.file.Files.write(absolute, BLOB);
        writeUploads(absolute.toString());

        RunResult refused = runner.run(root, "upload", new RunOptions(null, Map.of(), false), null);
        assertEquals(2, refused.errored(), "the desktop app never allows absolute files in a run");
        assertTrue(refused.requests().get(0).error().contains("absolute path"), refused.requests().get(0).error());

        RunResult allowed = runner.run(root, "upload", new RunOptions(null, Map.of(), true), null);
        assertTrue(allowed.succeeded(), allowed.requests().toString());
    }
}
