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
}
