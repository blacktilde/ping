package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.http.RequestSpec;
import dev.ping.store.StoredRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Insomnia export format 4 (JSON).
 *
 * <p>An export is a flat list of resources linked by {@code parentId}: workspaces contain
 * request groups (folders) and requests, and environments hang off the workspace. One export
 * can hold several workspaces, so it yields several collections.
 */
final class InsomniaImporter {

    /** {@code {{ _.name }}}: Insomnia's way of writing a variable. */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*_\\.([A-Za-z0-9_.\\-]+)\\s*}}");

    private final List<String> warnings;
    private final Map<String, List<JsonNode>> children = new java.util.HashMap<>();
    private boolean sawTag;

    private InsomniaImporter(List<String> warnings) {
        this.warnings = warnings;
    }

    static List<ImportedCollection> parse(JsonNode root, List<String> warnings) {
        return new InsomniaImporter(warnings).collections(root);
    }

    private List<ImportedCollection> collections(JsonNode root) {
        List<JsonNode> workspaces = new ArrayList<>();
        for (JsonNode resource : root.path("resources")) {
            String parent = ImportSupport.text(resource, "parentId");
            if (parent != null) {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(resource);
            }
            if ("workspace".equals(ImportSupport.text(resource, "_type"))) {
                workspaces.add(resource);
            }
        }
        if (workspaces.isEmpty()) {
            throw dev.ping.rpc.RpcException.invalidParams("The Insomnia export has no workspace to import");
        }

        List<ImportedCollection> collections = new ArrayList<>();
        for (JsonNode workspace : workspaces) {
            collections.add(collection(workspace));
        }
        if (sawTag) {
            warnings.add("Insomnia template tags ({% ... %}) are not supported and were left as written.");
        }
        return collections;
    }

    private ImportedCollection collection(JsonNode workspace) {
        String name = ImportSupport.text(workspace, "name");
        if (name == null || name.isBlank()) {
            name = "Insomnia workspace";
        }
        String id = ImportSupport.text(workspace, "_id");

        List<RequestSpec.Param> variables = List.of();
        List<ImportedCollection.Environment> environments = new ArrayList<>();
        JsonNode base = firstOfType(id, "environment");
        if (base != null) {
            variables = variables(base.path("data"));
            for (JsonNode sub : children.getOrDefault(ImportSupport.text(base, "_id"), List.of())) {
                if ("environment".equals(ImportSupport.text(sub, "_type"))) {
                    String envName = ImportSupport.text(sub, "name");
                    environments.add(new ImportedCollection.Environment(
                            envName == null || envName.isBlank() ? "Environment" : envName,
                            variables(sub.path("data"))));
                }
            }
        }
        String description = ImportSupport.text(workspace, "description");
        return new ImportedCollection(name, variables, environments, folder(name, id),
                description == null || description.isBlank() ? null : description.strip());
    }

    private JsonNode firstOfType(String parentId, String type) {
        for (JsonNode child : children.getOrDefault(parentId, List.of())) {
            if (type.equals(ImportSupport.text(child, "_type"))) {
                return child;
            }
        }
        return null;
    }

    private ImportedCollection.Folder folder(String name, String id) {
        List<ImportedCollection.Folder> folders = new ArrayList<>();
        List<StoredRequest> requests = new ArrayList<>();
        for (JsonNode child : children.getOrDefault(id, List.of())) {
            String type = ImportSupport.text(child, "_type");
            String childName = ImportSupport.text(child, "name");
            if (childName == null || childName.isBlank()) {
                childName = "Untitled";
            }
            if ("request_group".equals(type)) {
                if (child.path("environment").isObject() && !child.path("environment").isEmpty()) {
                    warnings.add("Folder \"" + childName + "\" has its own environment variables, "
                            + "which are not supported and were left out.");
                }
                folders.add(folder(childName, ImportSupport.text(child, "_id")));
            } else if ("request".equals(type)) {
                requests.add(request(childName, child));
            }
        }
        return new ImportedCollection.Folder(name, folders, requests);
    }

    // --- requests -------------------------------------------------------------------------

    private StoredRequest request(String name, JsonNode request) {
        String where = "Request \"" + name + "\"";
        List<RequestSpec.Param> headers = new ArrayList<>();
        RequestSpec.Auth folded = null;
        for (JsonNode header : request.path("headers")) {
            String headerName = template(ImportSupport.text(header, "name"));
            String value = template(ImportSupport.text(header, "value"));
            if (headerName == null || headerName.isBlank() || value == null) {
                continue;
            }
            boolean disabled = ImportSupport.isDisabled(header);
            if (headerName.equalsIgnoreCase("authorization") && !disabled) {
                RequestSpec.Auth parsed = ImportSupport.authFromHeader(value);
                if (parsed != null) {
                    folded = parsed;
                    continue;
                }
            }
            headers.add(new RequestSpec.Param(headerName, value, !disabled));
        }

        List<RequestSpec.Param> query = new ArrayList<>();
        for (JsonNode param : request.path("parameters")) {
            String paramName = template(ImportSupport.text(param, "name"));
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            String value = template(ImportSupport.text(param, "value"));
            query.add(new RequestSpec.Param(paramName, value == null ? "" : value, !ImportSupport.isDisabled(param)));
        }

        String url = template(ImportSupport.text(request, "url"));
        String address = url == null ? "" : ImportSupport.withScheme(url);
        int question = address.indexOf('?');
        if (question >= 0) {
            // Insomnia keeps the query inline as well; split it so the editor shows real rows.
            if (query.isEmpty()) {
                query.addAll(ImportSupport.pairs(address.substring(question + 1)));
            }
            address = address.substring(0, question);
        }

        RequestSpec.Auth auth = auth(request.path("authentication"), where);
        if (auth == null) {
            auth = folded;
        }

        String method = ImportSupport.text(request, "method");
        String docs = ImportSupport.text(request, "description");
        return new StoredRequest(name, method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT),
                address, query, headers, body(request.path("body"), where), auth,
                null, null, null, null, null, null, docs == null || docs.isBlank() ? null : docs.strip(), null, null);
    }

    private RequestSpec.Body body(JsonNode body, String where) {
        if (body == null || body.isMissingNode() || body.isNull() || body.isEmpty()) {
            return null;
        }
        String mime = ImportSupport.text(body, "mimeType");
        String text = template(ImportSupport.text(body, "text"));
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);

        // A body that is a file: Insomnia keeps its path in `fileName` and no text.
        String fileName = ImportSupport.text(body, "fileName");
        if (fileName != null && !fileName.isBlank()) {
            warnings.add(ImportSupport.fileNote(where, "its body", fileName));
            return new RequestSpec.Body("file", null, mime == null || mime.isBlank() ? null : mime, null, fileName);
        }

        if (type.startsWith("application/x-www-form-urlencoded")) {
            List<RequestSpec.Param> fields = fields(body.path("params"), where, false);
            return fields.isEmpty() ? null : new RequestSpec.Body("form", null, null, fields);
        }
        if (type.startsWith("multipart/form-data")) {
            List<RequestSpec.Param> fields = fields(body.path("params"), where, true);
            return fields.isEmpty() ? null : new RequestSpec.Body("multipart", null, null, fields);
        }
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (type.contains("json") || type.equals("application/graphql")) {
            // Insomnia already stores a GraphQL body as its JSON envelope.
            return new RequestSpec.Body("json", text, null, null);
        }
        return new RequestSpec.Body("raw", text, mime == null || mime.isBlank() ? null : mime, null);
    }

    private List<RequestSpec.Param> fields(JsonNode params, String where, boolean allowFiles) {
        List<RequestSpec.Param> fields = new ArrayList<>();
        for (JsonNode param : params) {
            String name = template(ImportSupport.text(param, "name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            if (allowFiles && "file".equals(ImportSupport.text(param, "type"))) {
                String path = ImportSupport.text(param, "fileName");
                path = path == null || path.isBlank() ? null : path;
                warnings.add(ImportSupport.fileNote(where, "the form field \"" + name + "\"", path));
                fields.add(new RequestSpec.Param(name, null, !ImportSupport.isDisabled(param), path, null, null));
                continue;
            }
            String value = template(ImportSupport.text(param, "value"));
            fields.add(new RequestSpec.Param(name, value == null ? "" : value, !ImportSupport.isDisabled(param)));
        }
        return fields;
    }

    private RequestSpec.Auth auth(JsonNode auth, String where) {
        String type = ImportSupport.text(auth, "type");
        if (type == null || type.equals("none") || ImportSupport.isDisabled(auth)) {
            return null;
        }
        switch (type) {
            case "bearer" -> {
                String prefix = ImportSupport.text(auth, "prefix");
                if (prefix != null && !prefix.isBlank() && !prefix.equalsIgnoreCase("Bearer")) {
                    warnings.add(where + " uses the token prefix \"" + prefix
                            + "\", which is not supported; it is sent as \"Bearer\".");
                }
                return RequestSpec.Auth.bearer(template(ImportSupport.text(auth, "token")));
            }
            case "basic" -> {
                String password = template(ImportSupport.text(auth, "password"));
                return RequestSpec.Auth.basic(template(ImportSupport.text(auth, "username")),
                        password == null || password.isEmpty() ? null : password);
            }
            case "apikey" -> {
                String addTo = ImportSupport.text(auth, "addTo");
                return RequestSpec.Auth.apiKey(template(ImportSupport.text(auth, "key")),
                        template(ImportSupport.text(auth, "value")),
                        "queryParams".equals(addTo) ? "query" : "header");
            }
            default -> {
                warnings.add(where + " uses " + type + " auth, which is not supported yet; "
                        + "it is imported without authentication.");
                return null;
            }
        }
    }

    // --- variables and templates ----------------------------------------------------------

    private List<RequestSpec.Param> variables(JsonNode data) {
        List<RequestSpec.Param> list = new ArrayList<>();
        flatten("", data, list);
        return list;
    }

    /** Nested objects become dotted names, the form Insomnia's {@code _.a.b} refers to. */
    private void flatten(String prefix, JsonNode node, List<RequestSpec.Param> into) {
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(name, value, into);
            } else {
                String text = value.isValueNode() ? value.asText() : value.toString();
                into.add(new RequestSpec.Param(name, template(text), true));
            }
        }
    }

    /** {@code {{ _.name }}} becomes {@code {{name}}}; tags are left as written and reported once. */
    private String template(String value) {
        if (value == null) {
            return null;
        }
        if (value.contains("{%")) {
            sawTag = true;
        }
        return VARIABLE.matcher(value).replaceAll(match -> "{{" + match.group(1) + "}}");
    }
}
