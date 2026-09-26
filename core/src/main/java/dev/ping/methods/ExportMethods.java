package dev.ping.methods;

import dev.ping.exports.PostmanExporter;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import dev.ping.store.YamlStore;

import java.nio.file.Path;

/**
 * Exporters over RPC.
 *
 * <p>{@code export.collection} reads through {@link YamlStore}, so the store's path rules apply,
 * and writes nothing: it returns the document and the shell saves it where the user chose.
 */
public final class ExportMethods {

    private ExportMethods() {
    }

    public static void registerOn(RpcServer server) {
        YamlStore store = new YamlStore();
        server.register("export.collection", params -> {
            String root = params == null ? null : params.path("root").asText(null);
            String path = params == null ? null : params.path("path").asText(null);
            String format = params == null ? null : params.path("format").asText("postman");
            if (root == null || root.isBlank()) {
                throw RpcException.invalidParams("export.collection requires a root");
            }
            if (path == null || path.isBlank()) {
                throw RpcException.invalidParams("export.collection requires a collection path");
            }
            if (!"postman".equals(format)) {
                throw RpcException.invalidParams("Unknown export format: " + format + " (use \"postman\")");
            }
            return PostmanExporter.export(store, Path.of(root), path);
        });
    }
}
