package dev.ping.methods;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.auth.OAuth2Flow;
import dev.ping.auth.TokenCache;
import dev.ping.auth.TokenClient;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Starts interactive OAuth2 flows over RPC. */
public final class AuthMethods {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AuthMethods() {
    }

    public static void registerOn(RpcServer server, TokenCache cache) {
        OAuth2Flow flow = new OAuth2Flow(
                new TokenClient(), cache, params -> server.notification("auth.completed", params));

        server.register("auth.authorize", params ->
                flow.start(parseAuth(params == null ? null : params.get("auth")), variables(params)));
    }

    private static RequestSpec.Auth parseAuth(JsonNode node) {
        if (node == null || node.isNull()) {
            throw RpcException.invalidParams("auth.authorize requires an auth config");
        }
        try {
            return MAPPER.treeToValue(node, RequestSpec.Auth.class);
        } catch (Exception e) {
            throw RpcException.invalidParams("Malformed auth: " + e.getMessage());
        }
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
}
