package dev.ping.methods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.auth.TokenCache;
import dev.ping.http.FileAccess;
import dev.ping.http.HttpEngine;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exposes {@link HttpEngine} over RPC. */
public final class HttpMethods {

    /**
     * Unknown properties are ignored so a newer UI can send a field this core does not yet
     * understand without the request failing outright.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private HttpMethods() {
    }

    public static void registerOn(RpcServer server) {
        registerOn(server, new TokenCache());
    }

    public static void registerOn(RpcServer server, TokenCache tokenCache) {
        HttpEngine engine = new HttpEngine(tokenCache);

        server.register("http.send", params -> engine.send(parse(params), variables(params), files(params)));
        server.register("http.cancel", params -> {
            String requestId = requireRequestId(params);
            return Map.of("cancelled", engine.cancel(requestId));
        });
    }

    private static RequestSpec parse(JsonNode params) {
        if (params == null || params.isNull()) {
            throw RpcException.invalidParams("http.send requires params");
        }
        try {
            return MAPPER.treeToValue(params, RequestSpec.class);
        } catch (JsonProcessingException e) {
            throw RpcException.invalidParams("Malformed request: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Malformed request: " + e.getMessage());
        }
    }

    /** Resolved variable values, if any. The engine substitutes them into the request. */
    /**
     * Relative file paths resolve against {@code filesBase}, which the desktop shell sets from the
     * request's collection (never from the renderer). Absolute paths are honoured: the shell has
     * already checked them against the files the user chose.
     */
    private static FileAccess files(JsonNode params) {
        String base = params == null ? null : params.path("filesBase").asText(null);
        return base == null || base.isBlank() ? FileAccess.LOCAL : new FileAccess(Path.of(base), true);
    }

    private static Map<String, String> variables(JsonNode params) {
        JsonNode node = params == null ? null : params.get("variables");
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> variables.put(entry.getKey(), entry.getValue().asText("")));
        return variables;
    }

    private static String requireRequestId(JsonNode params) {
        String requestId = params == null ? null : params.path("requestId").asText(null);
        if (requestId == null || requestId.isBlank()) {
            throw RpcException.invalidParams("http.cancel requires a requestId");
        }
        return requestId;
    }
}
