package dev.ping.http;

import com.sun.net.httpserver.HttpServer;
import dev.ping.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bodies that come from disk: what reaches the server must be the file's own bytes, framed
 * with an exact Content-Length, and a path the request may not read must never be read.
 */
class FileBodiesTest {

    /** Bytes that a text-oriented implementation would mangle: NUL, CRLF, high bytes, a fake delimiter. */
    private static final byte[] TRICKY = concatBytes(
            new byte[] {0, 1, 2, (byte) 0xFF, (byte) 0xFE, '\r', '\n', '\r', '\n'},
            "--PingBoundaryDecoy\r\n".getBytes(StandardCharsets.UTF_8),
            new byte[] {'\n', '\r', 0, (byte) 0x80});

    @TempDir
    Path dir;

    private HttpServer server;
    private HttpEngine engine;
    private String baseUrl;

    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private final AtomicReference<String> transferEncoding = new AtomicReference<>();
    private final AtomicReference<byte[]> received = new AtomicReference<>();

    private static byte[] concatBytes(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/upload", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
                received.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        engine = new HttpEngine();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Path file(String name, byte[] content) throws IOException {
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        return path;
    }

    private RequestSpec.Builder upload() {
        return new RequestSpec.Builder(baseUrl + "/upload").method("POST");
    }

    private static RequestSpec.Param filePart(String name, Path file) {
        return new RequestSpec.Param(name, null, true, file.toString(), null, null);
    }

    private String boundary() {
        return contentType.get().substring("multipart/form-data; boundary=".length());
    }

    /** The bytes between a part's blank line and the next delimiter. */
    private byte[] partBody(String name) {
        byte[] body = received.get();
        byte[] header = ("name=\"" + name + "\"").getBytes(StandardCharsets.UTF_8);
        int at = indexOf(body, header, 0);
        assertTrue(at >= 0, "no part named " + name);
        int start = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), at) + 4;
        int end = indexOf(body, ("\r\n--" + boundary()).getBytes(StandardCharsets.UTF_8), start);
        return Arrays.copyOfRange(body, start, end);
    }

    private String partHeaders(String name) {
        byte[] body = received.get();
        int at = indexOf(body, ("name=\"" + name + "\"").getBytes(StandardCharsets.UTF_8), 0);
        int end = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), at);
        return new String(body, at, end - at, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void assertRejected(RequestSpec spec, String messagePart) {
        RpcException error = assertThrows(RpcException.class, () -> engine.send(spec));
        assertEquals(RpcException.INVALID_PARAMS, error.code());
        assertTrue(error.getMessage().contains(messagePart), error.getMessage());
    }

    // --- multipart file parts -----------------------------------------------------------------

    @Test
    void aMultipartFilePartArrivesByteIdenticalWithAnExactContentLength() throws Exception {
        Path photo = file("payload", TRICKY);

        engine.send(upload().multipartBody(List.of(
                new RequestSpec.Param("note", "hello", true),
                filePart("photo", photo))).build());

        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="), contentType.get());
        assertArrayEquals(TRICKY, partBody("photo"));
        assertEquals("hello", new String(partBody("note"), StandardCharsets.UTF_8));
        assertEquals(String.valueOf(received.get().length), contentLength.get());
        assertNull(transferEncoding.get(), "a known length must not fall back to chunked encoding");
        String headers = partHeaders("photo");
        assertTrue(headers.contains("filename=\"payload\""), headers);
        assertTrue(headers.contains("Content-Type: application/octet-stream"), "no extension: " + headers);
    }

    @Test
    void aRowCanOverrideTheFilenameAndContentType() throws Exception {
        Path source = file("dir/raw.dat", TRICKY);
        engine.send(upload().multipartBody(List.of(new RequestSpec.Param(
                "doc", null, true, source.toString(), "report.pdf", "application/pdf"))).build());

        String headers = partHeaders("doc");
        assertTrue(headers.contains("filename=\"report.pdf\""), headers);
        assertTrue(headers.contains("Content-Type: application/pdf"), headers);
        assertArrayEquals(TRICKY, partBody("doc"));
    }

    @Test
    void aFilenameCannotBreakOutOfItsHeader() throws Exception {
        Path source = file("a.bin", TRICKY);
        engine.send(upload().multipartBody(List.of(new RequestSpec.Param(
                "f", null, true, source.toString(), "a\"b\r\nX-Evil: 1.bin", null))).build());

        String headers = partHeaders("f");
        assertTrue(headers.contains("filename=\"a%22b  X-Evil: 1.bin\""), headers);
        assertFalse(headers.contains("\r\nX-Evil"), headers);
        assertArrayEquals(TRICKY, partBody("f"));
    }

    @Test
    void severalFilesAndTextFieldsKeepTheirOrderAndBytes() throws Exception {
        Path first = file("one", new byte[] {1, 2, 3});
        Path second = file("two", new byte[] {'\r', '\n'});
        engine.send(upload().multipartBody(List.of(
                filePart("first", first),
                new RequestSpec.Param("between", "text", true),
                filePart("second", second),
                new RequestSpec.Param("off", null, false, first.toString(), null, null))).build());

        assertArrayEquals(new byte[] {1, 2, 3}, partBody("first"));
        assertEquals("text", new String(partBody("between"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[] {'\r', '\n'}, partBody("second"));
        assertFalse(new String(received.get(), StandardCharsets.ISO_8859_1).contains("name=\"off\""),
                "a disabled file row is not sent");
        assertEquals(String.valueOf(received.get().length), contentLength.get());
    }

    // --- binary body -----------------------------------------------------------------------

    @Test
    void aBinaryBodyIsTheFilesOwnBytes() throws Exception {
        Path source = file("blob.bin", TRICKY);
        engine.send(upload().fileBody(source.toString(), null).build());

        assertArrayEquals(TRICKY, received.get());
        assertEquals("application/octet-stream", contentType.get());
        assertEquals(String.valueOf(TRICKY.length), contentLength.get());
        assertNull(transferEncoding.get());

        engine.send(upload().fileBody(source.toString(), "image/png").build());
        assertEquals("image/png", contentType.get());
    }

    @Test
    void aLargeFileIsSentInFullWithAnExactLength() throws Exception {
        Path big = dir.resolve("big.bin");
        long size = 96L * 1024 * 1024;
        try (RandomAccessFile out = new RandomAccessFile(big.toFile(), "rw")) {
            out.setLength(size); // sparse: no need to write 96 MB of zeros
            out.seek(size - 4);
            out.write(new byte[] {9, 8, 7, 6});
        }
        AtomicReference<String> digest = new AtomicReference<>();
        AtomicReference<Long> counted = new AtomicReference<>();
        server.createContext("/big", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    sha.update(buffer, 0, n);
                    total += n;
                }
                digest.set(HexFormat.of().formatHex(sha.digest()));
                counted.set(total);
                contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                exchange.sendResponseHeaders(204, -1);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        });

        engine.send(new RequestSpec.Builder(baseUrl + "/big").method("PUT")
                .fileBody(big.toString(), null).build());

        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(big)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                expected.update(buffer, 0, n);
            }
        }
        assertEquals(size, counted.get());
        assertEquals(String.valueOf(size), contentLength.get());
        assertEquals(HexFormat.of().formatHex(expected.digest()), digest.get());
    }

    @Test
    void aRedirectThatKeepsTheMethodResendsTheFile() throws Exception {
        Path source = file("again.bin", TRICKY);
        server.createContext("/moved", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Location", baseUrl + "/upload");
                exchange.sendResponseHeaders(307, -1);
            }
        });

        engine.send(new RequestSpec.Builder(baseUrl + "/moved").method("PUT")
                .redirects("normal").fileBody(source.toString(), null).build());

        assertArrayEquals(TRICKY, received.get());
    }

    // --- what may be read -------------------------------------------------------------------------

    @Test
    void aFileThatCannotBeUsedIsRefusedBeforeAnythingIsSent() throws Exception {
        assertRejected(upload().fileBody(dir.resolve("missing.bin").toString(), null).build(), "File not found");
        assertRejected(upload().fileBody(dir.toString(), null).build(), "not a regular file");
        assertRejected(upload().fileBody("", null).build(), "Choose a file");
        assertRejected(upload().multipartBody(List.of(new RequestSpec.Param(
                "photo", null, true, "", null, null))).build(), "Choose a file for the field \"photo\"");
        assertNull(received.get(), "nothing reached the server");
    }

    @Test
    void relativePathsResolveAgainstTheCollectionAndCannotLeaveIt() throws Exception {
        Path collection = dir.resolve("collection");
        file("collection/fixtures/logo.bin", TRICKY);
        file("secret.txt", "top secret".getBytes(StandardCharsets.UTF_8));
        FileAccess inside = new FileAccess(collection, false);

        engine.send(upload().fileBody("fixtures/logo.bin", null).build(), java.util.Map.of(), inside);
        assertArrayEquals(TRICKY, received.get());

        for (String escape : List.of("../secret.txt", "fixtures/../../secret.txt")) {
            RpcException error = assertThrows(RpcException.class, () ->
                    engine.send(upload().fileBody(escape, null).build(), java.util.Map.of(), inside), escape);
            assertTrue(error.getMessage().contains("outside the collection"), error.getMessage());
        }
    }

    @Test
    void aRelativePathWithNoCollectionIsRefused() {
        assertRejected(upload().fileBody("fixtures/logo.bin", null).build(), "not in a collection");
    }

    @Test
    void anAbsolutePathIsRefusedWhenTheCallerForbidsIt() throws Exception {
        Path source = file("abs.bin", TRICKY);
        FileAccess strict = new FileAccess(dir, false);
        RpcException error = assertThrows(RpcException.class, () ->
                engine.send(upload().fileBody(source.toString(), null).build(), java.util.Map.of(), strict));
        assertTrue(error.getMessage().contains("absolute path"), error.getMessage());
        assertNull(received.get());
    }

    @Test
    void aSymlinkInsideTheCollectionCannotLeadOutOfIt() throws Exception {
        Path collection = dir.resolve("collection");
        Files.createDirectories(collection);
        Path outside = file("outside.txt", "outside".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(collection.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks are not available here");
        }
        RpcException error = assertThrows(RpcException.class, () -> engine.send(
                upload().fileBody("link.txt", null).build(), java.util.Map.of(), new FileAccess(collection, false)));
        assertTrue(error.getMessage().contains("outside the collection"), error.getMessage());
    }

    @Test
    void pathsAreLiteralAndNeverInterpolated() throws Exception {
        Path source = file("real.bin", TRICKY);
        RpcException error = assertThrows(RpcException.class, () -> engine.send(
                upload().fileBody("{{where}}", null).build(), java.util.Map.of("where", source.toString())));
        assertEquals(RpcException.INVALID_PARAMS, error.code());
        assertNull(received.get(), "a variable must not steer a file path");
    }

    // --- timeouts ----------------------------------------------------------------------------------

    @Test
    void uploadsGetALongerDefaultTimeoutThanOrdinaryRequests() {
        assertEquals(RequestSpec.DEFAULT_TIMEOUT_MS,
                new RequestSpec.Builder("http://x").rawBody("a", null).build().requestTimeoutMs());
        assertEquals(RequestSpec.DEFAULT_UPLOAD_TIMEOUT_MS,
                new RequestSpec.Builder("http://x").fileBody("f", null).build().requestTimeoutMs());
        assertEquals(RequestSpec.DEFAULT_UPLOAD_TIMEOUT_MS, new RequestSpec.Builder("http://x")
                .multipartBody(List.of(new RequestSpec.Param("f", null, true, "p", null, null)))
                .build().requestTimeoutMs());
        assertEquals(RequestSpec.DEFAULT_TIMEOUT_MS, new RequestSpec.Builder("http://x")
                .multipartBody(List.of(new RequestSpec.Param("f", null, false, "p", null, null)))
                .build().requestTimeoutMs(), "a disabled file part is not an upload");
        assertEquals(5000, new RequestSpec.Builder("http://x").fileBody("f", null).timeoutMs(5000)
                .build().requestTimeoutMs(), "an explicit timeout wins");
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
