package dev.ping.methods;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.rpc.RpcServer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lifecycle and diagnostic methods. Everything here must stay cheap: the UI calls it on startup. */
public final class CoreMethods {

    public static final String VERSION = "0.0.3";

    private CoreMethods() {
    }

    public static void registerOn(RpcServer server) {
        server.register("core.ping", CoreMethods::ping);
        server.register("core.info", params -> info());
    }

    /** Echoes the caller's message so the UI can prove the full round trip, not just liveness. */
    static Object ping(JsonNode params) {
        String message = params == null ? null : params.path("message").asText(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message == null ? "pong" : message);
        result.put("receivedAt", System.currentTimeMillis());
        return result;
    }

    static Object info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coreVersion", VERSION);
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("vendor", System.getProperty("java.vm.vendor"));
        // "runtime" means executing inside a native image. The property also exists on a
        // GraalVM JVM (and reads "buildtime" while the image is being built), so its mere
        // presence proves nothing. Lets the UI show which core is running.
        result.put("nativeImage",
                "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode")));
        return result;
    }
}
