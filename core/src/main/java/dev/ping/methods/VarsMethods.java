package dev.ping.methods;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import dev.ping.store.CollectionDoc;
import dev.ping.store.EnvironmentDoc;
import dev.ping.store.YamlStore;
import dev.ping.vars.Variables;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Variables and environments over RPC.
 *
 * <p>Resolution lives here rather than in the shell so the future CLI gets the same
 * precedence for free. The shell's only job is to choose a collection and an environment;
 * the flattened map it gets back is what it hands to {@code http.send}.
 */
public final class VarsMethods {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private VarsMethods() {
    }

    public static void registerOn(RpcServer server) {
        YamlStore store = new YamlStore();

        server.register("vars.catalog", params -> {
            Path root = root(params);
            String collection = requiredText(params, "collection");
            CollectionDoc doc = store.collectionDoc(root, collection);
            return Map.of(
                    "name", doc.name(),
                    "variables", doc.variables() == null ? List.of() : doc.variables(),
                    "environments", store.environmentNames(root, collection));
        });

        server.register("vars.environment", params ->
                store.readEnvironment(root(params), requiredText(params, "path")));

        server.register("vars.saveCollection", params -> {
            Path root = root(params);
            String collection = requiredText(params, "collection");
            CollectionDoc existing = store.collectionDoc(root, collection);
            String name = params.path("name").asText(existing.name());
            store.saveCollection(root, collection,
                    new CollectionDoc(name, variables(params.get("variables"))));
            return Map.of();
        });

        server.register("vars.saveEnvironment", params -> {
            String path = store.saveEnvironment(
                    root(params),
                    params.path("collection").asText(""),
                    params.path("path").asText(null),
                    new EnvironmentDoc(
                            requiredText(params, "name"),
                            variables(params.get("variables"))));
            return Map.of("path", path);
        });

        server.register("vars.resolve", params -> Map.of("variables", Variables.forCollection(
                store, root(params), requiredText(params, "collection"),
                params.path("environment").asText(null), null)));
    }

    private static Path root(JsonNode params) {
        return Path.of(requiredText(params, "root"));
    }

    private static String requiredText(JsonNode params, String field) {
        String value = params == null ? null : params.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw RpcException.invalidParams("vars requires a " + field);
        }
        return value;
    }

    private static List<RequestSpec.Param> variables(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        try {
            return MAPPER.convertValue(node, new TypeReference<List<RequestSpec.Param>>() {
            });
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Malformed variables: " + e.getMessage());
        }
    }
}
