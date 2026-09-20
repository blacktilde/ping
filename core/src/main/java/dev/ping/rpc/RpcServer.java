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
            if (idNode != null && !idNode.isNull()) {
                writeResult(idNode, result);
            }
        } catch (RpcException e) {
            writeError(idNode, e.code(), e.getMessage());
        } catch (Throwable t) {
            // The stack goes to stderr for the `[core]` log; the renderer gets a message
            // that names the failure without exposing Java internals or file paths.
            t.printStackTrace(System.err);
            String name = t.getClass().getSimpleName();
            String detail = t.getMessage();
            String message = detail == null || detail.isBlank()
                    ? "Internal error: " + name
                    : "Internal error: " + name + ": " + detail;
            writeError(idNode, RpcException.INTERNAL_ERROR, message);
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
            System.err.println("ping-core: failed to write response: " + e);
        }
    }
}
