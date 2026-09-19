package dev.ping.methods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.http.HttpEngine;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;

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
        HttpEngine engine = new HttpEngine();

        server.register("http.send", params -> engine.send(parse(params)));
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
            // getOriginalMessage drops Jackson's path/location noise, which the UI cannot use.
            throw RpcException.invalidParams("Malformed request: " + e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Malformed request: " + e.getMessage());
        }
    }

    private static String requireRequestId(JsonNode params) {
        String requestId = params == null ? null : params.path("requestId").asText(null);
        if (requestId == null || requestId.isBlank()) {
            throw RpcException.invalidParams("http.cancel requires a requestId");
        }
        return requestId;
    }
}
