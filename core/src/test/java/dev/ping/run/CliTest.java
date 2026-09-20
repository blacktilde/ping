package dev.ping.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path root;

    private HttpServer server;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        server = RunFixture.startServer();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int cli(Map<String, String> env, String... args) {
        return Cli.run(args, env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int cli(String... args) {
        return cli(Map.of(), args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path collection(boolean broken) throws IOException {
        return RunFixture.write(root, RunFixture.baseUrl(server), broken);
    }

    @Test
    void exitsZeroWhenEverythingPasses() throws IOException {
        Path demo = collection(false);
        assertEquals(0, cli("run", demo.toString(), "-e", "staging"));
        assertTrue(stdout().contains("2 requests: 2 passed, 0 failed, 0 errored"), stdout());
        assertEquals("", stderr());
    }

    @Test
    void exitsOneWhenAnAssertionFailsOrARequestErrors() throws IOException {
        Path demo = collection(true);
        assertEquals(1, cli("run", demo.toString(), "-e", "staging"));
        assertTrue(stdout().contains("FAIL  GET Wrong"), stdout());
        assertTrue(stdout().contains("ERR   GET Dead"), stdout());
    }

    @Test
    void theCommandLineMayUploadAFileByAbsolutePath() throws IOException {
        Path blob = root.resolve("blob.bin");
        Files.write(blob, new byte[] {0, 1, 2, (byte) 0xFF});
        Path demo = collection(false);
        Files.writeString(demo.resolve("upload.yaml"), """
                name: Upload
                method: PUT
                url: "{{baseUrl}}/upload"
                body:
                  type: file
                  file: "%s"
                """.formatted(blob.toString().replace("\\", "\\\\")));
        assertEquals(0, cli("run", demo.toString(), "-e", "staging"), stdout());
        assertTrue(stdout().contains("PASS  PUT Upload"), stdout());
    }

    @Test
    void exitsOneWhenTheEnvironmentIsWhatMakesItPass() throws IOException {
        Path demo = collection(false);
        assertEquals(1, cli("run", demo.toString()), "no environment: {{who}} is never resolved");
    }

    @Test
    void exitsTwoWhenTheRunCannotStart() throws IOException {
        Path demo = collection(false);
        assertEquals(2, cli("run", demo.toString(), "-e", "prod"));
        assertTrue(stderr().contains("Unknown environment \"prod\". Available: Staging"), stderr());
        assertEquals("", stdout(), "no report when nothing ran");

        assertEquals(2, cli("run", root.resolve("missing").toString()));
        assertEquals(2, cli("run"));
        assertEquals(2, cli("run", demo.toString(), "-r", "xml"));
        assertEquals(2, cli("run", demo.toString(), "--bogus"));
        assertEquals(2, cli("run", demo.toString(), "-e"));
        assertEquals(2, cli("run", demo.toString(), "--var", "novalue"));
        assertEquals(2, cli("run", demo.toString(), "extra"));
        assertEquals(2, cli("frobnicate"));
    }

    @Test
    void reportsGoToStdoutAndDiagnosticsToStderr() throws IOException {
        Path demo = collection(false);
        cli("run", demo.toString(), "-e", "staging", "-r", "json");
        // The whole of stdout must be one JSON document: nothing else may share it.
        assertEquals(2, new ObjectMapper().readTree(stdout()).path("total").asInt());
    }

    @Test
    void writesTheReportToAFileWhenAsked() throws IOException {
        Path demo = collection(false);
        Path report = root.resolve("report.xml");
        assertEquals(0, cli("run", demo.toString(), "-e", "staging", "-r", "junit",
                "-o", report.toString()));
        assertEquals("", stdout());
        assertTrue(Files.readString(report).contains("<testsuites name=\"Demo\""));
    }

    @Test
    void variablesFromTheFlagAndTheEnvironmentOutrankTheFiles() throws IOException {
        Path demo = collection(false);
        assertEquals(1, cli("run", demo.toString(), "-e", "staging", "--var", "who=other"));

        // A secret in the environment behaves the same way, and --var beats it.
        assertEquals(1, cli(Map.of("PING_SECRET_who", "other"),
                "run", demo.toString(), "-e", "staging"));
        assertEquals(0, cli(Map.of("PING_SECRET_who", "other"),
                "run", demo.toString(), "-e", "staging", "--var", "who=staging"));
    }

    @Test
    void aSecretNeverAppearsInAReport() throws IOException {
        Path demo = collection(true);
        Map<String, String> secret = Map.of("PING_SECRET_who", "s3cr3t-value");
        cli(secret, "run", demo.toString(), "-e", "staging", "-r", "json");
        assertFalse(stdout().contains("s3cr3t-value"), stdout());
        out.reset();
        cli(secret, "run", demo.toString(), "-r", "junit");
        assertFalse(stdout().contains("s3cr3t-value"), stdout());
    }

    @Test
    void helpAndVersionExitZeroAndBareInvocationIsAUsageError() {
        assertEquals(0, cli("--help"));
        assertTrue(stdout().contains("Usage: ping-core run"), stdout());
        out.reset();
        assertEquals(0, cli("--version"));
        assertTrue(stdout().startsWith("ping-core "), stdout());
        assertEquals(2, cli(), "Main routes no-arg to RPC; reaching Cli with none is a usage error");
    }
}
