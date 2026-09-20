package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.http.Probe;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;

import java.net.URI;

/** Network diagnostics over RPC. */
public final class NetMethods {

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MAX_TIMEOUT_MS = 60_000;

    private NetMethods() {
    }

    public static void registerOn(RpcServer server) {
        // The proxy and client certificates come from the shell like they do for http.send.
        server.register("net.probe", params -> {
            String url = params == null ? null : params.path("url").asText(null);
            if (url == null || url.isBlank()) {
                throw RpcException.invalidParams("net.probe requires a url");
            }
            URI uri;
            try {
                uri = URI.create(url.trim());
            } catch (IllegalArgumentException e) {
                throw RpcException.invalidParams("Malformed url");
            }
            JsonNode verify = params.get("verifyTls");
            boolean verifyTls = verify == null || verify.isNull() || verify.asBoolean(true);
            int timeout = params.path("timeoutMs").asInt(DEFAULT_TIMEOUT_MS);
            timeout = Math.max(1, Math.min(timeout <= 0 ? DEFAULT_TIMEOUT_MS : timeout, MAX_TIMEOUT_MS));
            return Probe.run(uri, verifyTls, timeout, HttpMethods.network(params));
        });
    }
}
