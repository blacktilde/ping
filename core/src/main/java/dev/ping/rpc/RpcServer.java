package dev.ping.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Newline-delimited JSON-RPC 2.0 over a byte stream, normally the core's stdin/stdout.
 *
 * <p>Framing is one JSON object per line. That is safe because Jackson escapes newlines
 * inside string values, so a serialized object never contains a bare newline.
 *
 * <p><strong>stdout carries protocol traffic only.</strong> Anything else written there
 * corrupts the stream and desynchronizes the client. Log to stderr.
 *
 * <p>Handlers run on virtual threads rather than inline. A long request must not block the
 * read loop, or the cancellation for that very request could never be read.
 */
public final class RpcServer {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, MethodHandler> methods = new HashMap<>();
    private final BufferedReader in;
    private final PrintWriter out;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public RpcServer(InputStream in, OutputStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    }

    public RpcServer register(String name, MethodHandler handler) {
        methods.put(name, handler);
        return this;
    }

    /** Reads until stdin closes, which happens when the parent process exits. */
    public void serve() throws IOException {
        notification("core.ready", Map.of("methods", methods.keySet()));

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            handleLine(line);
        }

        // Stdin closed: the parent is gone. Let in-flight work finish before the process exits.
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private void handleLine(String line) {
        JsonNode request;
        try {
            request = json.readTree(line);
        } catch (Exception e) {
            writeError(null, RpcException.PARSE_ERROR, "Malformed JSON: " + e.getMessage());
            return;
        }

        JsonNode idNode = request.get("id");
        String method = request.path("method").asText(null);
        if (method == null) {
            writeError(idNode, RpcException.INVALID_REQUEST, "Missing 'method'");
            return;
        }

        MethodHandler handler = methods.get(method);
        if (handler == null) {
            RpcException e = RpcException.methodNotFound(method);
            writeError(idNode, e.code(), e.getMessage());
            return;
        }

        JsonNode params = request.get("params");
        workers.execute(() -> invoke(handler, params, idNode));
    }

    private void invoke(MethodHandler handler, JsonNode params, JsonNode idNode) {
        try {
            Object result = handler.handle(params);
            // A request without an id is a notification: run it, answer nothing.
            if (idNode != null && !idNode.isNull()) {
                writeResult(idNode, result);
            }
        } catch (RpcException e) {
            writeError(idNode, e.code(), e.getMessage());
        } catch (Throwable t) {
            // Throwable, not Exception: an Error is neither, and a handler that dies without
            // answering is invisible to the client. The process survives — only this virtual
            // thread died — so the caller waits on a response that will never come while the
            // core keeps serving everyone else. native-image reports missing reflection
            // metadata as MissingReflectionRegistrationError, an Error, which is exactly how
            // a collection holding an auth block once hung the app on launch. Answering is
            // best-effort under OutOfMemoryError, where building the response may fail too.
            t.printStackTrace(System.err);
            writeError(idNode, RpcException.INTERNAL_ERROR, t.toString());
        }
    }

    private void writeResult(JsonNode id, Object result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", json.valueToTree(result));
        write(response);
    }

    private void writeError(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(response);
    }

    /** Server-initiated message with no id. Used for lifecycle and, later, streamed progress. */
    public void notification(String method, Object params) {
        ObjectNode message = json.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", json.valueToTree(params));
        write(message);
    }

    private synchronized void write(ObjectNode message) {
        try {
            out.println(json.writeValueAsString(message));
        } catch (Exception e) {
            // Nothing useful to do: the channel we would report on is the broken one.
            System.err.println("ping-core: failed to write response: " + e);
        }
    }
}
