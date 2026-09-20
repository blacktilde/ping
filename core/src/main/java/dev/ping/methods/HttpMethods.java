package dev.ping.methods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.auth.TokenCache;
import dev.ping.cookies.CookieContext;
import dev.ping.cookies.CookieJar;
import dev.ping.http.FileAccess;
import dev.ping.http.HttpEngine;
import dev.ping.http.NetworkConfig;
import dev.ping.http.RequestSpec;
import dev.ping.http.StreamListener;
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
        registerOn(server, tokenCache, new CookieJar());
    }

    /**
     * @param jar the interactive jar, shared with {@code cookies.*}. A send uses it only when the
     *            caller names a {@code cookieScope}; the desktop shell builds that from the
     *            request's collection and environment and never takes it from the renderer.
     */
    public static void registerOn(RpcServer server, TokenCache tokenCache, CookieJar jar) {
        HttpEngine engine = new HttpEngine(tokenCache);

        // A streaming response (SSE, NDJSON) is announced and delivered as notifications while the call is
        // still open; the call itself still returns one final result when the stream ends or is stopped.
        StreamListener stream = new StreamListener() {
            @Override
            public void start(Start start) {
                server.notification("http.stream.start", start);
            }

            @Override
            public void chunk(Chunk chunk) {
                server.notification("http.stream.chunk", chunk);
            }
        };
        server.register("http.send", params -> engine.send(parse(params), variables(params), files(params),
                cookies(params, jar), network(params), stream));
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

    /**
     * The proxy for this call. Like {@code filesBase} and {@code cookieScope} it is set by the
     * desktop shell from the user's own settings; the renderer's copy is discarded there.
     */
    static NetworkConfig network(JsonNode params) {
        JsonNode node = params == null ? null : params.get("network");
        if (node == null || node.isNull()) {
            return NetworkConfig.NONE;
        }
        try {
            return MAPPER.treeToValue(node, NetworkConfig.class);
        } catch (JsonProcessingException e) {
            throw RpcException.invalidParams("Malformed network settings: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Malformed network settings: " + e.getMessage());
        }
    }

    private static CookieContext cookies(JsonNode params, CookieJar jar) {
        String scope = params == null ? null : params.path("cookieScope").asText(null);
        return scope == null || scope.isBlank() ? CookieContext.NONE : new CookieContext(jar, scope);
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
