package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ping.http.RequestSpec;
import dev.ping.store.StoredRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OpenAPI 3.0 and 3.1 documents, as JSON or YAML.
 *
 * <p>One request per operation, grouped into folders by the operation's first tag, with the
 * server URL as a {@code baseUrl} variable so switching servers is switching environments.
 * Authentication is written as references such as {@code {{bearerAuth}}}, never as values: a
 * spec describes how to authenticate, it does not hold credentials, so the user is told which
 * secret names to set.
 */
final class OpenApiImporter {

    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final Set<String> SKIPPED_HEADERS = Set.of("accept", "content-type", "authorization");

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}/]+)}");
    private static final Pattern SERVER_VARIABLE = Pattern.compile("\\{([^{}]+)}");

    private final JsonNode root;
    private final List<String> warnings;
    private final Refs refs;
    private final SchemaSampler sampler;

    /** Path parameters that carry an example, offered as collection variables. */
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final Set<String> warnedSchemes = new HashSet<>();
    private boolean warnedCookie;
    private boolean warnedCallbacks;
    private boolean warnedServers;

    private OpenApiImporter(JsonNode root, List<String> warnings) {
        this.root = root;
        this.warnings = warnings;
        this.refs = new Refs(root, warnings);
        this.sampler = new SchemaSampler(refs);
    }

    static ImportedCollection parse(JsonNode root, List<String> warnings) {
        return new OpenApiImporter(root, warnings).collection();
    }

    private ImportedCollection collection() {
        String title = ImportSupport.text(root.path("info"), "title");
        String name = title == null || title.isBlank() ? "OpenAPI" : title;

        List<String> servers = new ArrayList<>();
        List<String> serverNames = new ArrayList<>();
        for (JsonNode server : root.path("servers")) {
            String url = serverUrl(server);
            if (url != null && !url.isBlank()) {
                servers.add(url);
                serverNames.add(ImportSupport.text(server, "description"));
            }
        }
        String baseUrl = servers.isEmpty() ? "" : servers.get(0);
        if (servers.isEmpty()) {
            warnings.add("The document lists no servers. Set the baseUrl variable before sending.");
        } else if (!baseUrl.contains("://")) {
            warnings.add("The server URL \"" + baseUrl + "\" is relative. Set the baseUrl "
                    + "variable to an absolute URL before sending.");
        }

        Map<String, List<StoredRequest>> byTag = new LinkedHashMap<>();
        List<StoredRequest> untagged = new ArrayList<>();
        JsonNode paths = root.path("paths");
        Iterator<Map.Entry<String, JsonNode>> entries = paths.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getKey().startsWith("x-")) {
                continue;
            }
            JsonNode item = refs.deref(entry.getValue());
            if (item == null || !item.isObject()) {
                continue;
            }
            if (item.has("servers")) {
                warnServers();
            }
            for (String method : METHODS) {
                JsonNode operation = item.get(method);
                if (operation == null || !operation.isObject()) {
                    continue;
                }
                StoredRequest request = request(entry.getKey(), method, item, operation);
                String tag = firstTag(operation);
                if (tag == null) {
                    untagged.add(request);
                } else {
                    byTag.computeIfAbsent(tag, key -> new ArrayList<>()).add(request);
                }
            }
        }
        if (root.has("webhooks")) {
            warnings.add("Webhooks describe requests the API sends to you; they were not imported.");
        }

        List<ImportedCollection.Folder> folders = new ArrayList<>();
        byTag.forEach((tag, requests) -> folders.add(new ImportedCollection.Folder(tag, List.of(), requests)));

        List<RequestSpec.Param> collectionVariables = new ArrayList<>();
        collectionVariables.add(new RequestSpec.Param("baseUrl", baseUrl, true));
        variables.forEach((key, value) -> {
            if (!key.equals("baseUrl")) {
                collectionVariables.add(new RequestSpec.Param(key, value, true));
            }
        });

        List<ImportedCollection.Environment> environments = new ArrayList<>();
        if (servers.size() > 1) {
            for (int i = 0; i < servers.size(); i++) {
                String label = serverNames.get(i);
                environments.add(new ImportedCollection.Environment(
                        label == null || label.isBlank() ? host(servers.get(i)) : label,
                        List.of(new RequestSpec.Param("baseUrl", servers.get(i), true))));
            }
        }

        String description = ImportSupport.text(root.path("info"), "description");
        return new ImportedCollection(name, collectionVariables, environments,
                new ImportedCollection.Folder(name, folders, untagged),
                description == null || description.isBlank() ? null : description.strip());
    }

    // --- servers --------------------------------------------------------------------------

    /** The server URL with each {@code {variable}} replaced by its default. */
    private static String serverUrl(JsonNode server) {
        String url = ImportSupport.text(server, "url");
        if (url == null) {
            return null;
        }
        JsonNode defined = server.path("variables");
        return SERVER_VARIABLE.matcher(url).replaceAll(match -> {
            String value = ImportSupport.text(defined.path(match.group(1)), "default");
            return java.util.regex.Matcher.quoteReplacement(value == null ? match.group() : value);
        });
    }

    private static String host(String url) {
        String bare = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = bare.indexOf('/');
        String host = slash >= 0 ? bare.substring(0, slash) : bare;
        return host.isEmpty() ? url : host;
    }

    private void warnServers() {
        if (!warnedServers) {
            warnedServers = true;
            warnings.add("Servers declared on a path or operation were ignored; every request uses "
                    + "the baseUrl variable.");
        }
    }

    // --- requests -------------------------------------------------------------------------

    private static String firstTag(JsonNode operation) {
        JsonNode tags = operation.get("tags");
        if (tags != null && tags.isArray() && !tags.isEmpty() && !tags.get(0).asText().isBlank()) {
            return tags.get(0).asText();
        }
        return null;
    }

    private StoredRequest request(String path, String method, JsonNode item, JsonNode operation) {
        String label = method.toUpperCase(Locale.ROOT) + " " + path;
        String summary = ImportSupport.text(operation, "summary");
        String operationId = ImportSupport.text(operation, "operationId");
        String name = firstNonBlank(summary, operationId, label);
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        if (operation.has("callbacks") && !warnedCallbacks) {
            warnedCallbacks = true;
            warnings.add("Callbacks describe requests the API sends to you; they were not imported.");
        }
        if (operation.has("servers")) {
            warnServers();
        }

        List<JsonNode> parameters = parameters(item, operation);
        List<RequestSpec.Param> query = new ArrayList<>();
        List<RequestSpec.Param> headers = new ArrayList<>();
        for (JsonNode parameter : parameters) {
            String in = ImportSupport.text(parameter, "in");
            String parameterName = ImportSupport.text(parameter, "name");
            if (in == null || parameterName == null || parameterName.isBlank()) {
                continue;
            }
            switch (in) {
                case "query" -> query.add(row(parameter, parameterName));
                case "header" -> {
                    if (!SKIPPED_HEADERS.contains(parameterName.toLowerCase(Locale.ROOT))) {
                        headers.add(row(parameter, parameterName));
                    }
                }
                case "path" -> {
                    String example = valueOf(parameter);
                    if (example != null && !example.isEmpty()) {
                        variables.putIfAbsent(parameterName, example);
                    }
                }
                case "cookie" -> {
                    if (!warnedCookie) {
                        warnedCookie = true;
                        warnings.add("Cookie parameters were not imported; add a Cookie header "
                                + "where an operation needs one.");
                    }
                }
                default -> { }
            }
        }

        String url = "{{baseUrl}}" + PATH_PARAM.matcher(path).replaceAll("{{$1}}");
        RequestSpec.Body body = body(operation.get("requestBody"), label);
        RequestSpec.Auth auth = auth(operation);
        String docs = docs(operation, summary, name, parameters);

        return new StoredRequest(name, method.toUpperCase(Locale.ROOT), url, query, headers, body, auth,
                null, null, null, null, null, null, docs, null);
    }

    /** Path-item parameters, overridden by operation parameters of the same name and location. */
    private List<JsonNode> parameters(JsonNode item, JsonNode operation) {
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        for (JsonNode source : new JsonNode[] {item.path("parameters"), operation.path("parameters")}) {
            for (JsonNode raw : source) {
                JsonNode parameter = refs.deref(raw);
                if (parameter == null || !parameter.isObject()) {
                    continue;
                }
                merged.put(ImportSupport.text(parameter, "in") + ":" + ImportSupport.text(parameter, "name"), parameter);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** Required parameters are on; optional ones are listed but off, so the request works as sent. */
    private RequestSpec.Param row(JsonNode parameter, String name) {
        String value = valueOf(parameter);
        return new RequestSpec.Param(name, value == null ? "" : value,
                parameter.path("required").asBoolean(false));
    }

    /** A parameter's example, else what its schema states outright; null when there is none. */
    private String valueOf(JsonNode parameter) {
        JsonNode value = parameter.has("example") ? parameter.get("example") : sampler.explicit(parameter.get("schema"));
        if (value == null && parameter.has("examples")) {
            for (JsonNode example : parameter.get("examples")) {
                value = example.path("value");
                break;
            }
        }
        return value == null ? null : text(value);
    }

    private static String text(JsonNode value) {
        if (value.isValueNode()) {
            return value.asText();
        }
        if (value.isArray() && !value.isEmpty() && value.get(0).isValueNode()) {
            return value.get(0).asText();
        }
        return value.toString();
    }

    // --- bodies ---------------------------------------------------------------------------

    private RequestSpec.Body body(JsonNode requestBody, String label) {
        JsonNode resolved = refs.deref(requestBody);
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        JsonNode content = resolved.get("content");
        if (content == null || !content.isObject() || content.isEmpty()) {
            return null;
        }

        String mediaType = choose(content);
        JsonNode media = content.get(mediaType);
        String bare = mediaType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        JsonNode schema = media.get("schema");

        if (bare.equals("application/octet-stream") || isBinary(schema)) {
            // A spec names no file: the body is a file the user has yet to choose.
            warnings.add(label + " sends a file as its body. Choose the file in the Body tab.");
            return new RequestSpec.Body("file", null, mediaType, null, null);
        }

        JsonNode example = exampleOf(media);
        if (example == null) {
            example = sampler.sample(schema);
        }

        if (bare.contains("json")) {
            return new RequestSpec.Body("json", pretty(example == null ? ImportSupport.JSON.createObjectNode() : example),
                    null, null);
        }
        if (bare.equals("application/x-www-form-urlencoded")) {
            List<RequestSpec.Param> fields = fields(example, schema, label, false);
            return fields.isEmpty() ? null : new RequestSpec.Body("form", null, null, fields);
        }
        if (bare.equals("multipart/form-data")) {
            List<RequestSpec.Param> fields = fields(example, schema, label, true);
            return fields.isEmpty() ? null : new RequestSpec.Body("multipart", null, null, fields);
        }
        String raw = example == null ? "" : example.isTextual() ? example.asText() : pretty(example);
        return new RequestSpec.Body("raw", raw, mediaType, null);
    }

    /** JSON first, then form encodings, then whatever the document lists first. */
    private static String choose(JsonNode content) {
        List<String> types = new ArrayList<>();
        content.fieldNames().forEachRemaining(types::add);
        for (String type : types) {
            if (type.toLowerCase(Locale.ROOT).contains("json")) {
                return type;
            }
        }
        for (String preferred : new String[] {"application/x-www-form-urlencoded", "multipart/form-data"}) {
            for (String type : types) {
                if (type.toLowerCase(Locale.ROOT).startsWith(preferred)) {
                    return type;
                }
            }
        }
        return types.get(0);
    }

    private JsonNode exampleOf(JsonNode media) {
        if (media.has("example")) {
            return media.get("example");
        }
        JsonNode examples = media.get("examples");
        if (examples != null && examples.isObject()) {
            for (JsonNode example : examples) {
                JsonNode resolved = refs.deref(example);
                if (resolved != null && resolved.has("value")) {
                    return resolved.get("value");
                }
            }
        }
        return null;
    }

    private boolean isBinary(JsonNode schema) {
        JsonNode resolved = refs.deref(schema);
        return resolved != null && resolved.isObject()
                && "string".equals(resolved.path("type").asText()) && "binary".equals(resolved.path("format").asText());
    }

    private List<RequestSpec.Param> fields(JsonNode example, JsonNode schema, String label, boolean multipart) {
        List<RequestSpec.Param> fields = new ArrayList<>();
        if (example == null || !example.isObject()) {
            return fields;
        }
        JsonNode resolved = refs.deref(schema);
        JsonNode properties = resolved == null ? null : resolved.get("properties");
        Iterator<Map.Entry<String, JsonNode>> entries = example.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (properties != null && isBinary(properties.get(entry.getKey()))) {
                // A file part with no file yet: the content type keeps the row a file row.
                warnings.add(label + " uploads a file in the field \"" + entry.getKey()
                        + "\". Choose the file in the Body tab.");
                fields.add(new RequestSpec.Param(entry.getKey(), null, true, null, null,
                        "application/octet-stream"));
                continue;
            }
            fields.add(new RequestSpec.Param(entry.getKey(), text(entry.getValue()), true));
        }
        return fields;
    }

    private static String pretty(JsonNode node) {
        try {
            return ImportSupport.pretty(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    // --- auth -----------------------------------------------------------------------------

    private RequestSpec.Auth auth(JsonNode operation) {
        JsonNode security = operation.has("security") ? operation.get("security") : root.get("security");
        if (security == null || !security.isArray()) {
            return null;
        }
        JsonNode schemes = root.path("components").path("securitySchemes");
        for (JsonNode requirement : security) {
            Iterator<String> names = requirement.fieldNames();
            while (names.hasNext()) {
                String schemeName = names.next();
                JsonNode scheme = refs.deref(schemes.get(schemeName));
                if (scheme == null) {
                    continue;
                }
                RequestSpec.Auth auth = scheme(schemeName, scheme);
                if (auth != null) {
                    return auth;
                }
            }
        }
        return null;
    }

    private RequestSpec.Auth scheme(String name, JsonNode scheme) {
        String type = ImportSupport.text(scheme, "type");
        String variable = variableName(name);
        if ("http".equals(type)) {
            String kind = ImportSupport.text(scheme, "scheme");
            if ("bearer".equalsIgnoreCase(kind)) {
                warnSecret(name, "the bearer token", variable);
                return RequestSpec.Auth.bearer("{{" + variable + "}}");
            }
            if ("basic".equalsIgnoreCase(kind)) {
                warnSecret(name, "the username and password", variable + "_username and " + variable + "_password");
                return RequestSpec.Auth.basic("{{" + variable + "_username}}", "{{" + variable + "_password}}");
            }
        } else if ("apiKey".equals(type)) {
            String in = ImportSupport.text(scheme, "in");
            String keyName = ImportSupport.text(scheme, "name");
            if (("header".equals(in) || "query".equals(in)) && keyName != null && !keyName.isBlank()) {
                warnSecret(name, "the API key", variable);
                return RequestSpec.Auth.apiKey(keyName, "{{" + variable + "}}", in);
            }
        }
        if (warnedSchemes.add(name)) {
            warnings.add("The security scheme \"" + name + "\" (" + (type == null ? "unknown" : type)
                    + ") is not supported yet; requests that use it were imported without authentication.");
        }
        return null;
    }

    private void warnSecret(String scheme, String what, String secretNames) {
        if (warnedSchemes.add(scheme)) {
            warnings.add("Requests use the security scheme \"" + scheme + "\". Set a secret named "
                    + secretNames + " for " + what + ".");
        }
    }

    /** Scheme names are free text; variables are not. */
    private static String variableName(String scheme) {
        String clean = scheme.replaceAll("[^A-Za-z0-9_.-]", "_");
        return clean.isEmpty() ? "auth" : clean;
    }

    // --- docs -----------------------------------------------------------------------------

    private String docs(JsonNode operation, String summary, String name, List<JsonNode> parameters) {
        StringBuilder docs = new StringBuilder();
        String description = ImportSupport.text(operation, "description");
        if (description != null && !description.isBlank()) {
            docs.append(description.strip());
        } else if (summary != null && !summary.isBlank() && !summary.equals(name)) {
            docs.append(summary.strip());
        }
        if (operation.path("deprecated").asBoolean(false)) {
            section(docs).append("**Deprecated.**");
        }

        if (!parameters.isEmpty()) {
            section(docs).append("## Parameters\n");
            for (JsonNode parameter : parameters) {
                JsonNode schema = refs.deref(parameter.get("schema"));
                String type = schema == null ? "" : ImportSupport.text(schema, "type");
                String detail = ImportSupport.text(parameter, "in") + (type == null || type.isEmpty() ? "" : ", " + type)
                        + (parameter.path("required").asBoolean(false) ? ", required" : "");
                String about = ImportSupport.text(parameter, "description");
                docs.append("\n- `").append(ImportSupport.text(parameter, "name")).append("` (").append(detail).append(')');
                if (about != null && !about.isBlank()) {
                    docs.append(" — ").append(about.strip().replace("\n", " "));
                }
            }
        }

        JsonNode responses = operation.get("responses");
        if (responses != null && responses.isObject() && !responses.isEmpty()) {
            section(docs).append("## Responses\n");
            Iterator<Map.Entry<String, JsonNode>> entries = responses.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> response = entries.next();
                JsonNode resolved = refs.deref(response.getValue());
                String about = resolved == null ? null : ImportSupport.text(resolved, "description");
                docs.append("\n- `").append(response.getKey()).append('`');
                if (about != null && !about.isBlank()) {
                    docs.append(" — ").append(about.strip().replace("\n", " "));
                }
            }
        }
        return docs.isEmpty() ? null : docs.toString();
    }

    private static StringBuilder section(StringBuilder docs) {
        if (!docs.isEmpty()) {
            docs.append("\n\n");
        }
        return docs;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "Untitled";
    }
}
