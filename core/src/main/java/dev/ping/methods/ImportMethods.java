package dev.ping.methods;

import dev.ping.imports.CollectionImporter;
import dev.ping.imports.CollectionWriter;
import dev.ping.imports.CurlImporter;
import dev.ping.rpc.RpcException;
import dev.ping.rpc.RpcServer;
import dev.ping.store.YamlStore;

import java.nio.file.Path;

/**
 * Importers over RPC.
 *
 * <p>{@code import.curl} produces a draft and writes nothing, so it takes no workspace root
 * and cannot touch the disk. {@code import.collection} writes, and does so only through
 * {@link YamlStore}, so the store's path rules apply.
 */
public final class ImportMethods {

    /** Larger than any real export; the shell enforces its own limit before reading the file. */
    private static final int MAX_CONTENT_CHARS = 64 * 1024 * 1024;

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

        // Writes new collection folders under the workspace root, so it takes a root like the
        // store methods do; the shell injects it and the store checks every path again.
        YamlStore store = new YamlStore();
        server.register("import.collection", params -> {
            String root = params == null ? null : params.path("root").asText(null);
            String content = params == null ? null : params.path("content").asText(null);
            if (root == null || root.isBlank()) {
                throw RpcException.invalidParams("import.collection requires a root");
            }
            if (content == null || content.isBlank()) {
                throw RpcException.invalidParams("import.collection requires the file content");
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                throw RpcException.invalidParams("The file is too large to import");
            }
            return new CollectionWriter(store).write(Path.of(root), CollectionImporter.parse(content));
        });
    }
}
