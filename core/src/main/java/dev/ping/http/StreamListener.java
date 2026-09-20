package dev.ping.http;

import java.util.List;

/**
 * Hears a streaming response as it arrives.
 *
 * <p>A response streams when its {@code Content-Type} says it is a feed rather than a document:
 * {@code text/event-stream} and the newline-delimited JSON family. Everything else is buffered and
 * returned whole, as it always was. A listener sees the head, then the body in coalesced pieces;
 * the send still returns one final {@link ResponseData} when the stream ends or is stopped.
 *
 * <p>With no listener (collection runs, the command line) nobody is watching a feed, so the engine
 * reads it for the request's timeout instead and returns what arrived.
 */
public interface StreamListener {

    void start(Start start);

    void chunk(Chunk chunk);

    /**
     * The response head, sent once before any body. Everything the panes need to render a response
     * that is still arriving.
     */
    record Start(
            String requestId,
            int status,
            String httpVersion,
            List<ResponseData.Header> headers,
            String origin,
            Long dnsMs,
            long ttfbMs,
            String contentType) {
    }

    /**
     * A piece of the body, decoded. Past the display cap {@code text} is empty but {@code total} keeps
     * growing, so a feed that is still running is never made to look finished.
     *
     * @param seq       1, 2, 3, … per stream
     * @param total     bytes received so far
     * @param atMs      milliseconds since the head arrived, to a millisecond
     * @param truncated true once {@code total} has passed the display cap
     */
    record Chunk(String requestId, long seq, String text, long total, long atMs, boolean truncated) {
    }
}
