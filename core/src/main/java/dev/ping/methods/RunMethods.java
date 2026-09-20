package dev.ping.methods;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.auth.TokenCache;
import dev.ping.http.HttpEngine;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import dev.ping.run.RunOptions;
import dev.ping.run.Runner;
import dev.ping.store.YamlStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a collection over RPC.
 *
 * <p>The handler runs on a virtual thread like every other, so {@code run.progress}
 * notifications flow to the caller while the run is still going, and the returned result is
 * the same value the CLI reports.
 */
public final class RunMethods {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RunMethods() {
    }

    public static void registerOn(RpcServer server, TokenCache tokens) {
        Runner runner = new Runner(new YamlStore(), new HttpEngine(tokens));

        server.register("run.collection", params -> {
            Path root = Path.of(requiredText(params, "root"));
            String collection = requiredText(params, "collection");
            String runId = params.path("runId").asText(null);
            RunOptions options = new RunOptions(
                    params.path("environment").asText(null), variables(params.get("variables")));

            return runner.run(root, collection, options, (index, total, result) -> {
                Map<String, Object> progress = new LinkedHashMap<>();
                if (runId != null) {
                    progress.put("runId", runId);
                }
                progress.put("index", index);
                progress.put("total", total);
                progress.put("request", result);
                server.notification("run.progress", progress);
            });
        });
    }

    private static String requiredText(JsonNode params, String field) {
        String value = params == null ? null : params.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw RpcException.invalidParams("run.collection requires a " + field);
        }
        return value;
    }

    private static Map<String, String> variables(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        try {
            return MAPPER.convertValue(node, new TypeReference<Map<String, String>>() {
            });
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Malformed variables: " + e.getMessage());
        }
    }
}
