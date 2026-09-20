package dev.ping.methods;

import dev.ping.imports.CurlImporter;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;

/**
 * Importers over RPC.
 *
 * <p>{@code import.curl} produces a draft and writes nothing, so it takes no workspace root
 * and cannot touch the disk. Importers that do write (collections, OpenAPI) will go through
 * the store's path rules instead.
 */
public final class ImportMethods {

    private ImportMethods() {
    }

    public static void registerOn(RpcServer server) {
        server.register("import.curl", params -> {
            String command = params == null ? null : params.path("command").asText(null);
            if (command == null || command.isBlank()) {
                throw RpcException.invalidParams("import.curl requires a command");
            }
            return CurlImporter.parse(command);
        });
    }
}
