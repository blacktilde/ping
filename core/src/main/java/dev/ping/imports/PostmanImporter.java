package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ping.http.RequestSpec;
import dev.ping.store.StoredRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Postman collection v2.0 and v2.1.
 *
 * <p>Everything a request cannot express is a warning, never a silent drop. Scripts are the
 * common case: they are kept as notes on the request they came from, so the logic can be
 * ported by hand instead of vanishing.
 */
final class PostmanImporter {

    private static final Pattern PATH_VARIABLE = Pattern.compile("/:[A-Za-z_][A-Za-z0-9_]*");

    private final List<String> warnings;

    private PostmanImporter(List<String> warnings) {
        this.warnings = warnings;
    }

    static ImportedCollection parse(JsonNode root, List<String> warnings) {
        return new PostmanImporter(warnings).collection(root);
    }

    /** Auth declared at some level; a null {@code auth} means "declared, but none we can use". */
    private record AuthScope(RequestSpec.Auth auth) {
    }

    private ImportedCollection collection(JsonNode root) {
        String name = ImportSupport.text(root.path("info"), "name");
        if (name == null || name.isBlank()) {
            name = "Postman collection";
        }
        scriptsWarning(root, "Collection \"" + name + "\"");
        AuthScope scope = scope(root, null, "Collection \"" + name + "\"");
        ImportedCollection.Folder folder = folder(name, root.path("item"), scope);
        return new ImportedCollection(name, variables(root.path("variable")), List.of(), folder,
                blankToNull(description(root.path("info").get("description"))));
    }

    private ImportedCollection.Folder folder(String name, JsonNode items, AuthScope scope) {
        List<ImportedCollection.Folder> folders = new ArrayList<>();
        List<StoredRequest> requests = new ArrayList<>();
        for (JsonNode item : items) {
            String itemName = ImportSupport.text(item, "name");
            if (itemName == null || itemName.isBlank()) {
                itemName = "Untitled";
            }
            if (item.has("item")) {
                String where = "Folder \"" + itemName + "\"";
                scriptsWarning(item, where);
                folders.add(folder(itemName, item.get("item"), scope(item, scope, where)));
            } else {
                requests.add(request(itemName, item, scope));
            }
        }
        return new ImportedCollection.Folder(name, folders, requests);
    }

    private AuthScope scope(JsonNode node, AuthScope parent, String where) {
        JsonNode auth = node.get("auth");
        if (auth == null || auth.isNull()) {
            return parent;
        }
        return new AuthScope(auth(auth, where));
    }

    // --- requests -------------------------------------------------------------------------

    private StoredRequest request(String name, JsonNode item, AuthScope inherited) {
        String where = "Request \"" + name + "\"";
        JsonNode request = item.get("request");
        if (request == null || request.isNull()) {
            request = JSON_EMPTY;
        }
        if (request.isTextual()) {
            // The short form is just a URL.
            return new StoredRequest(name, "GET", ImportSupport.withScheme(request.asText()),
                    null, null, null, inherited == null ? null : inherited.auth(),
                    null, null, null, null, null, null, docs(item, request), null, null);
        }

        List<RequestSpec.Param> query = new ArrayList<>();
        String url = url(request.get("url"), query, where);
        List<RequestSpec.Param> headers = new ArrayList<>();
        RequestSpec.Auth folded = null;
        String contentType = null;
        for (JsonNode header : request.path("header")) {
            String headerName = ImportSupport.text(header, "key");
            String value = ImportSupport.text(header, "value");
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
            if (headerName.equalsIgnoreCase("content-type") && !disabled) {
                contentType = value;
            }
            headers.add(new RequestSpec.Param(headerName, value, !disabled));
        }

        RequestSpec.Auth auth;
        JsonNode own = request.get("auth");
        if (own != null && !own.isNull()) {
            auth = auth(own, where);
        } else if (inherited != null) {
            auth = inherited.auth();
        } else {
            auth = null;
        }
        if (auth == null && folded != null) {
            auth = folded;
        }

        String method = ImportSupport.text(request, "method");
        RequestSpec.Body body = body(request.get("body"), contentType, where);
        return new StoredRequest(name, method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT),
                url, query, headers, body, auth, null, null, null, null, null, null, docs(item, request), null, null);
    }

    private static final JsonNode JSON_EMPTY = ImportSupport.JSON.createObjectNode();

    /** Base address without the query; the query goes to {@code query} so disabled rows survive. */
    private String url(JsonNode node, List<RequestSpec.Param> query, String where) {
        if (node == null || node.isNull()) {
            return "";
        }
        String raw = node.isTextual() ? node.asText() : ImportSupport.text(node, "raw");
        String address;
        if (raw != null && !raw.isBlank()) {
            int hash = raw.indexOf('#');
            address = hash >= 0 ? raw.substring(0, hash) : raw;
            int question = address.indexOf('?');
            String rawQuery = question >= 0 ? address.substring(question + 1) : "";
            address = question >= 0 ? address.substring(0, question) : address;
            if (!node.isObject() || !node.path("query").isArray()) {
                query.addAll(ImportSupport.pairs(rawQuery));
            }
        } else {
            address = fromParts(node);
        }
        if (node.isObject()) {
            for (JsonNode param : node.path("query")) {
                String key = ImportSupport.text(param, "key");
                if (key == null || key.isBlank()) {
                    continue;
                }
                String value = ImportSupport.text(param, "value");
                query.add(new RequestSpec.Param(key, value == null ? "" : value, !ImportSupport.isDisabled(param)));
            }
        }
        if (PATH_VARIABLE.matcher(address).find()) {
            warnings.add(where + " has a path variable (like :id); it was left as written. "
                    + "Replace it with a {{variable}}.");
        }
        return ImportSupport.withScheme(address);
    }

    private static String fromParts(JsonNode url) {
        StringBuilder address = new StringBuilder();
        String protocol = ImportSupport.text(url, "protocol");
        if (protocol != null && !protocol.isBlank()) {
            address.append(protocol).append("://");
        }
        address.append(join(url.get("host"), "."));
        String port = ImportSupport.text(url, "port");
        if (port != null && !port.isBlank()) {
            address.append(':').append(port);
        }
        String path = join(url.get("path"), "/");
        if (!path.isEmpty()) {
            address.append('/').append(path);
        }
        return address.toString();
    }

    private static String join(JsonNode node, String separator) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isArray()) {
            return node.asText();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode part : node) {
            parts.add(part.isTextual() ? part.asText() : ImportSupport.text(part, "value"));
        }
        return String.join(separator, parts);
    }

    private RequestSpec.Body body(JsonNode body, String contentType, String where) {
        if (body == null || body.isNull()) {
            return null;
        }
        String mode = ImportSupport.text(body, "mode");
        if (mode == null) {
            return null;
        }
        switch (mode) {
            case "raw" -> {
                String content = ImportSupport.text(body, "raw");
                if (content == null || content.isEmpty()) {
                    return null;
                }
                String language = ImportSupport.text(body.path("options").path("raw"), "language");
                boolean json = "json".equals(language)
                        || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json"));
                if (json) {
                    return new RequestSpec.Body("json", content, null, null);
                }
                return new RequestSpec.Body("raw", content, contentType == null ? typeOf(language) : null, null);
            }
            case "urlencoded" -> {
                List<RequestSpec.Param> fields = fields(body.path("urlencoded"), where, false);
                return fields.isEmpty() ? null : new RequestSpec.Body("form", null, null, fields);
            }
            case "formdata" -> {
                List<RequestSpec.Param> fields = fields(body.path("formdata"), where, true);
                return fields.isEmpty() ? null : new RequestSpec.Body("multipart", null, null, fields);
            }
            case "graphql" -> {
                return graphql(body.path("graphql"), where);
            }
            case "file", "binary" -> {
                String path = ImportSupport.filePath(body.path("file").isTextual()
                        ? body.get("file") : body.path("file").get("src"));
                warnings.add(ImportSupport.fileNote(where, "its body", path));
                return new RequestSpec.Body("file", null, null, null, path);
            }
            default -> {
                warnings.add(where + " has an unsupported body mode \"" + mode + "\"; the body was left out.");
                return null;
            }
        }
    }

    private static String typeOf(String language) {
        if (language == null) {
            return null;
        }
        return switch (language) {
            case "xml" -> "application/xml";
            case "html" -> "text/html";
            case "text" -> "text/plain";
            default -> null;
        };
    }

    private List<RequestSpec.Param> fields(JsonNode entries, String where, boolean allowFiles) {
        List<RequestSpec.Param> fields = new ArrayList<>();
        for (JsonNode entry : entries) {
            String key = ImportSupport.text(entry, "key");
            if (key == null || key.isBlank()) {
                continue;
            }
            if (allowFiles && "file".equals(ImportSupport.text(entry, "type"))) {
                String path = ImportSupport.filePath(entry.get("src"));
                warnings.add(ImportSupport.fileNote(where, "the form field \"" + key + "\"", path));
                fields.add(new RequestSpec.Param(key, null, !ImportSupport.isDisabled(entry), path, null,
                        ImportSupport.text(entry, "contentType")));
                continue;
            }
            String value = ImportSupport.text(entry, "value");
            fields.add(new RequestSpec.Param(key, value == null ? "" : value, !ImportSupport.isDisabled(entry)));
        }
        return fields;
    }

    /** GraphQL is an ordinary JSON body on the wire, which is already supported. */
    private RequestSpec.Body graphql(JsonNode graphql, String where) {
        String query = ImportSupport.text(graphql, "query");
        if (query == null || query.isBlank()) {
            return null;
        }
        ObjectNode envelope = ImportSupport.JSON.createObjectNode();
        envelope.put("query", query);
        String variables = ImportSupport.text(graphql, "variables");
        if (variables != null && !variables.isBlank()) {
            try {
                envelope.set("variables", ImportSupport.JSON.readTree(variables));
            } catch (Exception e) {
                warnings.add(where + " has GraphQL variables that are not valid JSON; they were left out.");
            }
        }
        try {
            return new RequestSpec.Body("json", ImportSupport.pretty(envelope), null, null);
        } catch (Exception e) {
            return null;
        }
    }

    // --- auth -----------------------------------------------------------------------------

    private RequestSpec.Auth auth(JsonNode auth, String where) {
        String type = ImportSupport.text(auth, "type");
        if (type == null || type.equals("noauth")) {
            return null;
        }
        switch (type) {
            case "bearer" -> {
                return RequestSpec.Auth.bearer(attribute(auth, "bearer", "token"));
            }
            case "basic" -> {
                String password = attribute(auth, "basic", "password");
                return RequestSpec.Auth.basic(attribute(auth, "basic", "username"),
                        password == null || password.isEmpty() ? null : password);
            }
            case "apikey" -> {
                String in = attribute(auth, "apikey", "in");
                return RequestSpec.Auth.apiKey(attribute(auth, "apikey", "key"),
                        attribute(auth, "apikey", "value"), "query".equals(in) ? "query" : "header");
            }
            default -> {
                warnings.add(where + " uses " + type + " auth, which is not supported yet; "
                        + "it and anything inheriting it is imported without authentication.");
                return null;
            }
        }
    }

    /** Postman stores auth fields as a list of {@code {key, value}} under the type's name. */
    private static String attribute(JsonNode auth, String type, String key) {
        JsonNode entries = auth.get(type);
        if (entries == null) {
            return null;
        }
        if (entries.isObject()) {
            return ImportSupport.text(entries, key); // the v2.0 shape
        }
        for (JsonNode entry : entries) {
            if (key.equals(ImportSupport.text(entry, "key"))) {
                return ImportSupport.text(entry, "value");
            }
        }
        return null;
    }

    // --- variables, docs, scripts ---------------------------------------------------------

    private static List<RequestSpec.Param> variables(JsonNode variables) {
        List<RequestSpec.Param> list = new ArrayList<>();
        for (JsonNode variable : variables) {
            String key = ImportSupport.text(variable, "key");
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = ImportSupport.text(variable, "value");
            list.add(new RequestSpec.Param(key, value == null ? "" : value, !ImportSupport.isDisabled(variable)));
        }
        return list;
    }

    private void scriptsWarning(JsonNode node, String where) {
        for (JsonNode event : node.path("event")) {
            if (!script(event).isBlank()) {
                warnings.add(where + " has a " + label(event) + " script that was not imported; "
                        + "scripts are not supported.");
            }
        }
    }

    /** A request's description and its scripts, as markdown notes. */
    private static String docs(JsonNode item, JsonNode request) {
        StringBuilder docs = new StringBuilder();
        String description = description(request.get("description"));
        if (description == null) {
            description = description(item.get("description"));
        }
        if (description != null && !description.isBlank()) {
            docs.append(description.strip());
        }

        StringBuilder scripts = new StringBuilder();
        for (JsonNode event : item.path("event")) {
            String script = script(event);
            if (script.isBlank()) {
                continue;
            }
            String fence = script.contains("```") ? "~~~~" : "```";
            scripts.append("\n### ").append(capitalize(label(event))).append(" script\n\n")
                    .append(fence).append("js\n").append(script.strip()).append('\n').append(fence).append('\n');
        }
        if (!scripts.isEmpty()) {
            if (!docs.isEmpty()) {
                docs.append("\n\n");
            }
            docs.append("## Not imported\n\nPing does not run scripts. The originals are kept here.\n")
                    .append(scripts);
        }
        return docs.isEmpty() ? null : docs.toString();
    }

    private static String description(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : ImportSupport.text(node, "content");
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String label(JsonNode event) {
        return "prerequest".equals(ImportSupport.text(event, "listen")) ? "pre-request" : "test";
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String script(JsonNode event) {
        JsonNode exec = event.path("script").path("exec");
        if (exec.isTextual()) {
            return exec.asText();
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode line : exec) {
            lines.add(line.asText());
        }
        return String.join("\n", lines);
    }
}
