package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.rpc.RpcException;

import java.util.List;

/**
 * Recognises a collection export or API description and hands it to the right importer.
 *
 * <p>Detection reads the file's own markers rather than its name or extension, and an
 * export that is clearly one of ours but not importable says why, so the user is not left
 * guessing between "wrong file" and "wrong export option".
 */
public final class CollectionImporter {

    /** @param warnings everything the source expressed that was not carried over */
    public record Parsed(List<ImportedCollection> collections, List<String> warnings) {
    }

    private CollectionImporter() {
    }

    public static Parsed parse(String content) {
        JsonNode root;
        try {
            root = ImportSupport.parseDocument(content);
        } catch (Exception e) {
            throw RpcException.invalidParams("The file is not valid JSON or YAML. Postman and "
                    + "Insomnia exports are JSON; an Insomnia v5 YAML export must be exported "
                    + "as v4 JSON.");
        }
        if (root == null || !root.isObject()) {
            throw RpcException.invalidParams("The file is not a Postman collection, an Insomnia "
                    + "export or an OpenAPI document");
        }

        List<String> warnings = new java.util.ArrayList<>();

        String openapi = ImportSupport.text(root, "openapi");
        if (openapi != null && openapi.startsWith("3.")) {
            return new Parsed(List.of(OpenApiImporter.parse(root, warnings)), warnings);
        }
        if (root.has("swagger")) {
            throw RpcException.invalidParams("Swagger " + ImportSupport.text(root, "swagger")
                    + " is not supported. Convert it to OpenAPI 3 and try again.");
        }

        String schema = ImportSupport.text(root.path("info"), "schema");
        if (schema != null && schema.contains("collection/v2")) {
            return new Parsed(List.of(PostmanImporter.parse(root, warnings)), warnings);
        }
        if (schema != null && schema.contains("collection/v1")) {
            throw RpcException.invalidParams("Postman collection v1 is not supported. "
                    + "Export the collection as v2.1 and try again.");
        }
        if ("environment".equals(ImportSupport.text(root, "_postman_variable_scope"))) {
            throw RpcException.invalidParams("This is a Postman environment export. Import a "
                    + "collection instead; environments are created from an Insomnia export.");
        }
        if ("export".equals(ImportSupport.text(root, "_type"))) {
            int format = root.path("__export_format").asInt(0);
            if (format != 4) {
                throw RpcException.invalidParams("Insomnia export format " + format
                        + " is not supported. Export as Insomnia v4 (JSON).");
            }
            return new Parsed(InsomniaImporter.parse(root, warnings), warnings);
        }
        throw RpcException.invalidParams("The file is not a Postman collection (v2.x), an "
                + "Insomnia v4 export or an OpenAPI 3 document");
    }
}
