package dev.ping.exports;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ping.http.RequestSpec;
import dev.ping.rpc.RpcException;
import dev.ping.store.CollectionDoc;
import dev.ping.store.CollectionNode;
import dev.ping.store.StoredRequest;
import dev.ping.store.YamlStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A collection folder as a Postman collection v2.1 document: the inverse of the Postman
 * importer, so an exported file imports back into Ping with the same requests.
 *
 * <p>Nothing is resolved. Values are written exactly as the YAML holds them, so a secret stays a
 * {@code {{name}}} reference and its value never leaves the machine through an export. Postman
 * uses the same {@code {{name}}} syntax, so the references keep working there once the
 * variables are defined.
 *
 * <p>As with import, what the format cannot express is a warning, never a silent drop.
 */
public final class PostmanExporter {

    static final String SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two-space indent and {@code \n} on every platform, so an export diffs cleanly in Git. */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    /** {@code /abs}, {@code \\server\share} or {@code C:\...}: a path from this machine's layout. */
    private static final Pattern ABSOLUTE = Pattern.compile("^([/\\\\]|[A-Za-z]:[/\\\\])");

    private final YamlStore store;
    private final List<String> warnings = new ArrayList<>();
    private int requests;

    private PostmanExporter(YamlStore store) {
        this.store = store;
    }

    public static ExportResult export(YamlStore store, Path root, String collectionPath) {
        return new PostmanExporter(store).collection(root, collectionPath);
    }

    private ExportResult collection(Path root, String collectionPath) {
        CollectionDoc doc = store.collectionDoc(root, collectionPath);

        ObjectNode document = JSON.createObjectNode();
        ObjectNode info = document.putObject("info");
        info.put("name", doc.name());
        if (doc.docs() != null && !doc.docs().isBlank()) {
            info.put("description", doc.docs());
        }
        info.put("schema", SCHEMA);
        document.set("item", items(root, store.tree(root, collectionPath)));
        ArrayNode variables = rows(doc.variables());
        if (!variables.isEmpty()) {
            document.set("variable", variables);
        }

        int environments = store.environmentNames(root, collectionPath).size();
        if (environments > 0) {
            warnings.add(environments + (environments == 1 ? " environment was" : " environments were")
                    + " not exported; a Postman collection carries only collection variables.");
        }

        String content;
        try {
            content = JSON.writer(PRETTY).writeValueAsString(document) + "\n";
        } catch (Exception e) {
            throw RpcException.storeFailed("Could not write the export: " + e.getMessage(), e);
        }
        return new ExportResult(doc.name(), content, requests, warnings);
    }

    private ArrayNode items(Path root, List<CollectionNode> nodes) {
        ArrayNode items = JSON.createArrayNode();
        for (CollectionNode node : nodes) {
            if (CollectionNode.REQUEST.equals(node.type())) {
                ObjectNode item = request(root, node);
                if (item != null) {
                    items.add(item);
                }
            } else {
                ObjectNode folder = items.addObject();
                folder.put("name", node.name());
                folder.set("item", items(root, node.children()));
            }
        }
        return items;
    }

    // --- requests -------------------------------------------------------------------------

    private ObjectNode request(Path root, CollectionNode node) {
        StoredRequest stored;
        try {
            stored = store.read(root, node.path());
        } catch (RpcException e) {
            // One broken file should not cost the user the rest of the collection.
            warnings.add("\"" + node.path() + "\" could not be read and was left out: " + e.getMessage());
            return null;
        }
        String name = stored.name() == null || stored.name().isBlank() ? node.name() : stored.name();
        String where = "Request \"" + name + "\"";
        requests++;

        ObjectNode item = JSON.createObjectNode();
        item.put("name", name);
        ObjectNode request = item.putObject("request");
        request.put("method", stored.method() == null || stored.method().isBlank()
                ? "GET" : stored.method().toUpperCase(Locale.ROOT));

        ObjectNode auth = auth(stored.auth(), where);
        if (auth != null) {
            request.set("auth", auth);
        }

        ArrayNode headers = rows(stored.headers());
        String bodyType = contentType(stored.body());
        if (bodyType != null && !hasHeader(stored.headers(), "content-type")) {
            // Ping sends a body's own content type; in Postman only a header can say it.
            ObjectNode header = headers.addObject();
            header.put("key", "Content-Type");
            header.put("value", bodyType);
        }
        request.set("header", headers);

        ObjectNode body = body(stored.body(), where);
        if (body != null) {
            request.set("body", body);
        }
        request.set("url", url(stored.url(), stored.query()));
        if (stored.docs() != null && !stored.docs().isBlank()) {
            request.put("description", stored.docs());
        }

        ObjectNode behavior = behavior(stored, where);
        if (!behavior.isEmpty()) {
            item.set("protocolProfileBehavior", behavior);
        }
        checks(stored, where);
        return item;
    }

    /** Postman keeps the enabled query in {@code raw} and every row, disabled ones too, in {@code query}. */
    private static ObjectNode url(String address, List<RequestSpec.Param> query) {
        String base = address == null ? "" : address;
        StringBuilder raw = new StringBuilder(base);
        char separator = base.contains("?") ? '&' : '?';
        if (query != null) {
            for (RequestSpec.Param param : query) {
                if (param.name() == null || param.name().isBlank() || !param.isEnabled()) {
                    continue;
                }
                raw.append(separator).append(param.name()).append('=')
                        .append(param.value() == null ? "" : param.value());
                separator = '&';
            }
        }
        ObjectNode url = JSON.createObjectNode();
        url.put("raw", raw.toString());
        ArrayNode rows = rows(query);
        if (!rows.isEmpty()) {
            url.set("query", rows);
        }
        return url;
    }

    private ObjectNode body(RequestSpec.Body body, String where) {
        if (body == null || body.type() == null) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        switch (body.type().toLowerCase(Locale.ROOT)) {
            case "none" -> {
                return null;
            }
            case "json", "raw" -> {
                if (body.content() == null || body.content().isEmpty()) {
                    return null;
                }
                node.put("mode", "raw");
                node.put("raw", body.content());
                String language = body.type().equalsIgnoreCase("json") ? "json" : language(body.contentType());
                node.putObject("options").putObject("raw").put("language", language);
            }
            case "form" -> {
                ArrayNode fields = rows(body.fields());
                if (fields.isEmpty()) {
                    return null;
                }
                node.put("mode", "urlencoded");
                node.set("urlencoded", fields);
            }
            case "multipart" -> {
                ArrayNode fields = JSON.createArrayNode();
                for (RequestSpec.Param param : body.fields() == null ? List.<RequestSpec.Param>of() : body.fields()) {
                    if (param.name() == null || param.name().isBlank()) {
                        continue;
                    }
                    ObjectNode field = fields.addObject();
                    field.put("key", param.name());
                    if (param.usesFile()) {
                        field.put("type", "file");
                        field.put("src", filePath(param.file(), where, "the form field \"" + param.name() + "\""));
                        if (param.contentType() != null) {
                            field.put("contentType", param.contentType());
                        }
                        if (param.filename() != null) {
                            warnings.add(where + " renames the file in \"" + param.name()
                                    + "\"; Postman always sends the file's own name.");
                        }
                    } else {
                        field.put("value", param.value() == null ? "" : param.value());
                        field.put("type", "text");
                    }
                    if (!param.isEnabled()) {
                        field.put("disabled", true);
                    }
                }
                if (fields.isEmpty()) {
                    return null;
                }
                node.put("mode", "formdata");
                node.set("formdata", fields);
            }
            case "file" -> {
                node.put("mode", "file");
                node.putObject("file").put("src", filePath(body.file(), where, "its body"));
            }
            default -> {
                warnings.add(where + " has a \"" + body.type() + "\" body, which was not exported.");
                return null;
            }
        }
        return node;
    }

    /** The content type a body sends on its own, which Postman can only express as a header. */
    private static String contentType(RequestSpec.Body body) {
        if (body == null || body.type() == null || body.contentType() == null || body.contentType().isBlank()) {
            return null;
        }
        String type = body.type().toLowerCase(Locale.ROOT);
        return type.equals("raw") || type.equals("file") ? body.contentType() : null;
    }

    private static String language(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("json")) {
            return "json";
        }
        if (type.contains("xml")) {
            return "xml";
        }
        if (type.contains("html")) {
            return "html";
        }
        if (type.contains("javascript")) {
            return "javascript";
        }
        return "text";
    }

    /**
     * A file path as Postman should see it. A path inside the collection is kept relative; an
     * absolute one describes this machine's layout (and its user name), so only the file name
     * goes out. Either way the file has to be chosen again after importing, so it is reported.
     */
    private String filePath(String path, String where, String what) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String exported = path;
        if (ABSOLUTE.matcher(path).find()) {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            exported = path.substring(slash + 1);
        }
        warnings.add(where + " attaches the file \"" + exported + "\" in " + what
                + "; the file itself is not in the export and has to be chosen again.");
        return exported;
    }

    // --- auth -----------------------------------------------------------------------------

    private ObjectNode auth(RequestSpec.Auth auth, String where) {
        if (auth == null || !auth.isConfigured()) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        switch (auth.type()) {
            case "bearer" -> {
                node.put("type", "bearer");
                attributes(node, "bearer", "token", auth.token());
            }
            case "basic" -> {
                node.put("type", "basic");
                attributes(node, "basic", "username", auth.username(), "password", auth.password());
            }
            case "api-key" -> {
                node.put("type", "apikey");
                attributes(node, "apikey", "key", auth.key(), "value", auth.value(),
                        "in", "query".equals(auth.in()) ? "query" : "header");
            }
            case "oauth2-client-credentials", "oauth2-authorization-code" -> {
                // Configuration only. Tokens are session state from safeStorage, never exported.
                boolean code = auth.type().equals("oauth2-authorization-code");
                node.put("type", "oauth2");
                attributes(node, "oauth2",
                        "grant_type", code ? "authorization_code" : "client_credentials",
                        "authUrl", code ? auth.authUrl() : null,
                        "accessTokenUrl", auth.tokenUrl(),
                        "clientId", auth.clientId(),
                        "clientSecret", auth.clientSecret(),
                        "scope", auth.scopes(),
                        "redirect_uri", code ? auth.redirectUri() : null);
            }
            default -> {
                warnings.add(where + " uses " + auth.type() + " auth, which was not exported.");
                return null;
            }
        }
        return node;
    }

    /** Postman's v2.1 shape: a list of {@code {key, value, type}} under the auth type's name. */
    private static void attributes(ObjectNode auth, String type, String... pairs) {
        ArrayNode list = auth.putArray(type);
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                continue;
            }
            ObjectNode entry = list.addObject();
            entry.put("key", pairs[i]);
            entry.put("value", pairs[i + 1]);
            entry.put("type", "string");
        }
    }

    // --- settings Postman has no field for ----------------------------------------------

    private ObjectNode behavior(StoredRequest stored, String where) {
        ObjectNode behavior = JSON.createObjectNode();
        if ("never".equals(stored.redirects())) {
            behavior.put("followRedirects", false);
        }
        if (Boolean.FALSE.equals(stored.verifyTls())) {
            behavior.put("strictSSL", false);
        }
        if (Boolean.FALSE.equals(stored.cookies())) {
            behavior.put("disableCookies", true);
        }

        List<String> dropped = new ArrayList<>();
        if (stored.timeoutMs() != null) {
            dropped.add("timeout");
        }
        if (stored.httpVersion() != null && !stored.httpVersion().isBlank()) {
            dropped.add("HTTP version");
        }
        if (stored.maxBodyBytes() != null) {
            dropped.add("response size limit");
        }
        if (!dropped.isEmpty()) {
            warnings.add(where + " sets a " + String.join(", ", dropped)
                    + " that Postman has no field for; it was not exported.");
        }
        return behavior;
    }

    /** Assertions and captures are scripts in Postman; generating one is not an export's job. */
    private void checks(StoredRequest stored, String where) {
        int asserts = stored.asserts() == null ? 0 : stored.asserts().size();
        int captures = stored.capture() == null ? 0 : stored.capture().size();
        List<String> parts = new ArrayList<>();
        if (asserts > 0) {
            parts.add(asserts + (asserts == 1 ? " assertion" : " assertions"));
        }
        if (captures > 0) {
            parts.add(captures + (captures == 1 ? " capture" : " captures"));
        }
        if (!parts.isEmpty()) {
            warnings.add("Not exported from " + where + ": " + String.join(" and ", parts)
                    + " (Postman needs test scripts for these).");
        }
    }

    // --- shared ---------------------------------------------------------------------------

    /** Name/value rows as Postman's {@code {key, value, disabled?}} list. */
    private static ArrayNode rows(List<RequestSpec.Param> params) {
        ArrayNode rows = JSON.createArrayNode();
        if (params == null) {
            return rows;
        }
        for (RequestSpec.Param param : params) {
            if (param.name() == null || param.name().isBlank()) {
                continue;
            }
            ObjectNode row = rows.addObject();
            row.put("key", param.name());
            row.put("value", param.value() == null ? "" : param.value());
            if (!param.isEnabled()) {
                row.put("disabled", true);
            }
        }
        return rows;
    }

    private static boolean hasHeader(List<RequestSpec.Param> headers, String name) {
        return headers != null && headers.stream()
                .anyMatch(header -> header.isEnabled() && name.equalsIgnoreCase(header.name()));
    }
}
