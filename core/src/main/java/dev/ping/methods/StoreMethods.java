package dev.ping.methods;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;

import java.nio.file.Path;
import java.util.Map;

/**
 * Exposes the YAML collection store over RPC.
 *
 * <p>The caller always names the workspace root, so the core stays stateless: the Electron
 * shell decides which folder is open and proves it on every call.
 */
public final class StoreMethods {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private StoreMethods() {
    }

    public static void registerOn(RpcServer server) {
        YamlStore store = new YamlStore();

        server.register("store.scan", params -> Map.of("collections", store.scan(root(params))));

        server.register("store.read", params ->
                store.read(root(params), requiredText(params, "path")));

        server.register("store.write", params -> {
            String path = requiredText(params, "path");
            store.write(root(params), path, parseRequest(params.get("request")));
            return Map.of("path", path);
        });

        server.register("store.create", params ->
                Map.of("path", store.create(root(params),
                        params.path("collection").asText(""),
                        requiredText(params, "name"))));
    }

    private static Path root(JsonNode params) {
        return Path.of(requiredText(params, "root"));
    }

    private static String requiredText(JsonNode params, String field) {
        String value = params == null ? null : params.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw RpcException.invalidParams("store requires a " + field);
        }
        return value;
    }

    private static StoredRequest parseRequest(JsonNode node) {
        if (node == null || node.isNull()) {
            throw RpcException.invalidParams("store.write requires a request");
        }
        try {
            return MAPPER.treeToValue(node, StoredRequest.class);
        } catch (Exception e) {
            // getOriginalMessage drops Jackson's path noise, which the UI cannot use.
            throw RpcException.invalidParams("Malformed request: " + e.getMessage());
        }
    }
}
