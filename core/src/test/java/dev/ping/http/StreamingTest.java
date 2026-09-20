package dev.ping.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ping.cookies.CookieContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feed over a real socket: events appear as they arrive, Stop ends the exchange and keeps what
 * came, and the connection is released.
 */
class StreamingTest {

    /** How a test's server behaves once a client has connected. */
    private interface Feed {
        void run(OutputStream out) throws Exception;
    }

    private HttpServer server;
    private final HttpEngine engine = new HttpEngine();
    private final List<StreamListener.Start> starts = new CopyOnWriteArrayList<>();
    private final List<StreamListener.Chunk> chunks = new CopyOnWriteArrayList<>();
    /** Counted down when the server finds the client has gone. */
    private final CountDownLatch disconnected = new CountDownLatch(1);
    private final CountDownLatch firstChunk = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private final StreamListener listener = new StreamListener() {
        @Override
        public void start(Start start) {
            starts.add(start);
        }

        @Override
        public void chunk(Chunk chunk) {
            chunks.add(chunk);
            if (!chunk.text().isEmpty()) {
                firstChunk.countDown();
            }
        }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, String contentType, Feed feed) {
        server.createContext(path, exchange -> respond(exchange, contentType, feed));
    }

    private static void respond(HttpExchange exchange, String contentType, Feed feed) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, 0); // chunked
        try (exchange; OutputStream out = exchange.getResponseBody()) {
            feed.run(out);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Sends a comment every 50 ms until the client is gone, then says so. */
    private void holdOpen(OutputStream out) throws InterruptedException {
        try {
            while (true) {
                write(out, ": keepalive\n\n");
                Thread.sleep(50);
            }
        } catch (IOException e) {
            disconnected.countDown();
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private CompletableFuture<ResponseData> sendAsync(RequestSpec spec) {
        return CompletableFuture.supplyAsync(() -> engine.send(
                spec, Map.of(), FileAccess.LOCAL, CookieContext.NONE, NetworkConfig.NONE, listener));
    }

    private String streamedText() {
        StringBuilder text = new StringBuilder();
        chunks.forEach(chunk -> text.append(chunk.text()));
        return text.toString();
    }

    @Test
    void eventsArriveWhileTheExchangeIsStillOpen() throws Exception {
        serve("/events", "text/event-stream", out -> {
            write(out, "id: 1\nevent: tick\ndata: one\n\n");
            // The second event is withheld until the test has seen the first: eventual delivery is not enough.
            release.await(5, TimeUnit.SECONDS);
            write(out, "id: 2\nevent: tick\ndata: two\n\n");
            holdOpen(out);
        });
        CompletableFuture<ResponseData> send = sendAsync(new RequestSpec.Builder(url("/events")).requestId("s1").build());

        assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "the first event should arrive");
        assertFalse(send.isDone(), "…while the request is still in flight");
        assertEquals(1, starts.size());
        assertEquals(200, starts.get(0).status());
        assertEquals("s1", starts.get(0).requestId());
        assertTrue(starts.get(0).contentType().startsWith("text/event-stream"));
        assertTrue(streamedText().contains("data: one"));
        assertFalse(streamedText().contains("data: two"));

        release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (!streamedText().contains("data: two") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(streamedText().contains("data: two"));
        assertTrue(engine.cancel("s1"));
        send.get(5, TimeUnit.SECONDS);
    }

    @Test
    void stoppingAFeedKeepsWhatArrivedAndReleasesTheConnection() throws Exception {
        serve("/events", "text/event-stream", out -> {
            write(out, "data: one\n\n");
            write(out, "data: two\n\n");
            holdOpen(out);
        });
        CompletableFuture<ResponseData> send = sendAsync(new RequestSpec.Builder(url("/events")).requestId("s2").build());
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS));

        assertTrue(engine.cancel("s2"));
        ResponseData response = send.get(5, TimeUnit.SECONDS);

        assertEquals(200, response.status());
        assertTrue(response.streamed());
        assertEquals("cancelled", response.ended());
        assertTrue(response.body().content().contains("data: one"), response.body().content());
        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the server should see the client hang up");
    }

    @Test
    void aFeedThatHasGoneQuietCanStillBeStopped() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        serve("/events", "text/event-stream", out -> {
            write(out, "data: only\n\n");
            try {
                never.await(30, TimeUnit.SECONDS); // silent: no bytes will wake the reader
            } finally {
                disconnected.countDown();
            }
        });
        CompletableFuture<ResponseData> send = sendAsync(new RequestSpec.Builder(url("/events")).requestId("s3").build());
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        long stoppedAt = System.nanoTime();
        assertTrue(engine.cancel("s3"));
        ResponseData response = send.get(5, TimeUnit.SECONDS);
        assertTrue((System.nanoTime() - stoppedAt) / 1_000_000 < 3000, "stopping must not wait for data");
        assertEquals("cancelled", response.ended());
        never.countDown();
    }

    @Test
    void withNoOneWatchingAFeedIsReadForTheTimeoutAndReturned() throws Exception {
        serve("/events", "text/event-stream", out -> {
            write(out, "data: hello\n\n");
            holdOpen(out);
        });
        long started = System.nanoTime();
        ResponseData response = engine.send(new RequestSpec.Builder(url("/events")).timeoutMs(600).build());

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs >= 500 && elapsedMs < 4000, "read for about the timeout, not forever: " + elapsedMs);
        assertEquals("timeout", response.ended());
        assertTrue(response.body().content().contains("data: hello"));
        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the timeout must release the connection too");
    }

    @Test
    void aFeedThatTheServerEndsIsClosed() {
        serve("/events", "text/event-stream", out -> write(out, "data: a\n\ndata: b\n\n"));
        ResponseData response = engine.send(new RequestSpec.Builder(url("/events")).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE, NetworkConfig.NONE, listener);

        assertEquals("closed", response.ended());
        assertTrue(response.streamed());
        assertEquals("data: a\n\ndata: b\n\n", response.body().content());
        assertEquals("data: a\n\ndata: b\n\n", streamedText());
        assertTrue(chunks.get(chunks.size() - 1).total() >= 18);
    }

    @Test
    void aCharacterSplitAcrossReadsIsNotMangled() {
        serve("/events", "text/event-stream; charset=utf-8", out -> {
            byte[] bytes = "data: café €\n\n".getBytes(StandardCharsets.UTF_8);
            // Split inside both multi-byte characters, with a pause so each piece is its own read.
            int[] cuts = {10, 14};
            int from = 0;
            for (int cut : cuts) {
                out.write(bytes, from, cut - from);
                out.flush();
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                from = cut;
            }
            out.write(bytes, from, bytes.length - from);
            out.flush();
        });
        ResponseData response = engine.send(new RequestSpec.Builder(url("/events")).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE, NetworkConfig.NONE, listener);

        assertEquals("data: café €\n\n", streamedText());
        assertFalse(streamedText().contains("�"));
        assertEquals("data: café €\n\n", response.body().content());
        assertTrue(chunks.size() >= 2, "it really did arrive in pieces: " + chunks.size());
    }

    @Test
    void newlineDelimitedJsonStreamsToo() {
        serve("/lines", "application/x-ndjson", out -> write(out, "{\"n\":1}\n{\"n\":2}\n"));
        ResponseData response = engine.send(new RequestSpec.Builder(url("/lines")).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE, NetworkConfig.NONE, listener);

        assertTrue(response.streamed());
        assertEquals(1, starts.size());
        assertEquals("{\"n\":1}\n{\"n\":2}\n", streamedText());
    }

    @Test
    void anOrdinaryResponseIsNeverStreamed() {
        server.createContext("/json", exchange -> {
            byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        ResponseData response = engine.send(new RequestSpec.Builder(url("/json")).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE, NetworkConfig.NONE, listener);

        assertTrue(starts.isEmpty());
        assertTrue(chunks.isEmpty());
        assertNull(response.streamed());
        assertNull(response.ended());
        assertEquals("{\"ok\":true}", response.body().content());
    }

    @Test
    void pastTheDisplayCapChunksStopCarryingTextButKeepCounting() {
        String block = "data: 0123456789\n\n"; // 18 bytes
        serve("/events", "text/event-stream", out -> {
            for (int i = 0; i < 10; i++) {
                write(out, block);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ResponseData response = engine.send(new RequestSpec.Builder(url("/events")).maxBodyBytes(40).build(),
                Map.of(), FileAccess.LOCAL, CookieContext.NONE, NetworkConfig.NONE, listener);

        assertEquals(180, response.body().bytes());
        assertTrue(response.body().truncated());
        assertEquals(40, streamedText().length(), "text stops at the cap");
        StreamListener.Chunk last = chunks.get(chunks.size() - 1);
        assertEquals(180, last.total(), "…but the counter reaches the end");
        assertTrue(last.truncated());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().isEmpty()), "a counter-only chunk was sent");
    }

    @Test
    void chunkNumbersAndTimesRunForward() throws Exception {
        serve("/events", "text/event-stream", out -> {
            for (int i = 0; i < 3; i++) {
                write(out, "data: " + i + "\n\n");
                Thread.sleep(120);
            }
        });
        engine.send(new RequestSpec.Builder(url("/events")).build(), Map.of(), FileAccess.LOCAL,
                CookieContext.NONE, NetworkConfig.NONE, listener);

        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).seq() + 1, chunks.get(i).seq());
            assertTrue(chunks.get(i).atMs() >= chunks.get(i - 1).atMs());
        }
        assertTrue(chunks.get(chunks.size() - 1).atMs() >= 200, "the offsets are real time: " + chunks);
    }
}
