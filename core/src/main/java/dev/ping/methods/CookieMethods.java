package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.cookies.CookieJar;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;

import java.util.Map;

/**
 * Inspecting and clearing the interactive cookie jar.
 *
 * <p>A cookie's value is a credential, so nothing here returns one: {@code cookies.list}
 * reports what each cookie is and where it applies, never what it holds. The scope is an opaque
 * key the desktop shell builds from the collection and environment.
 */
public final class CookieMethods {

    private CookieMethods() {
    }

    public static void registerOn(RpcServer server, CookieJar jar) {
        server.register("cookies.list", params ->
                Map.of("cookies", jar.list(scope(params), System.currentTimeMillis())));

        server.register("cookies.clear", params -> {
            String domain = optional(params, "domain");
            String name = optional(params, "name");
            return Map.of("removed", jar.clear(scope(params), domain, name));
        });

        server.register("cookies.clearAll", params -> {
            jar.clearAll();
            return Map.of();
        });
    }

    private static String scope(JsonNode params) {
        String scope = params == null ? null : params.path("scope").asText(null);
        if (scope == null || scope.isBlank()) {
            throw RpcException.invalidParams("cookies requires a scope");
        }
        return scope;
    }

    private static String optional(JsonNode params, String field) {
        String value = params == null ? null : params.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
