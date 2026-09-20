package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ping.http.RequestSpec;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/** Small pieces every importer needs. */
final class ImportSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Names that usually carry a credential: their values are lifted out before a file is written. */
    static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(api[-_]?key|token|secret|password|passwd|cookie|authorization).*");

    private ImportSupport() {
    }

    /**
     * {@code Bearer x} and {@code Basic base64} become auth; anything else returns null so the
     * caller keeps it as a header rather than losing it.
     */
    static RequestSpec.Auth authFromHeader(String value) {
        int space = value.indexOf(' ');
        String scheme = space < 0 ? value : value.substring(0, space);
        String credentials = space < 0 ? "" : value.substring(space + 1).trim();
        if (credentials.isEmpty()) {
            return null;
        }
        if (scheme.equalsIgnoreCase("bearer")) {
            return RequestSpec.Auth.bearer(credentials);
        }
        if (scheme.equalsIgnoreCase("basic")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(credentials), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                String password = colon < 0 ? "" : decoded.substring(colon + 1);
                return RequestSpec.Auth.basic(
                        colon < 0 ? decoded : decoded.substring(0, colon),
                        password.isEmpty() ? null : password);
            } catch (IllegalArgumentException e) {
                return null; // Not base64, or a variable: keep the header.
            }
        }
        return null;
    }

    static boolean looksLikeJson(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return false;
        }
        try {
            JSON.readTree(trimmed);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code a=1&b=2} as decoded params. */
    static List<RequestSpec.Param> pairs(String text) {
        List<RequestSpec.Param> params = new ArrayList<>();
        for (String segment : text.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            params.add(new RequestSpec.Param(
                    decode(eq < 0 ? segment : segment.substring(0, eq)),
                    decode(eq < 0 ? "" : segment.substring(eq + 1)), true));
        }
        return params;
    }

    static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** Text of a node that may be missing, null or a non-string scalar. */
    static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    static String text(JsonNode node, String field) {
        return text(node == null ? null : node.get(field));
    }

    static boolean isDisabled(JsonNode node) {
        return node != null && node.path("disabled").asBoolean(false);
    }

    /** The scheme is added like a browser or curl would, unless the address starts with a variable. */
    static String withScheme(String address) {
        String trimmed = address.trim();
        if (trimmed.isEmpty() || trimmed.contains("://") || trimmed.startsWith("{{")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }
}
