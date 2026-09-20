package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

/**
 * Builds an example value from a JSON Schema, for the request body an operation would send.
 *
 * <p>Explicit examples always win over generated ones. A schema that contains itself is cut
 * off where the cycle closes, and nesting is depth-limited, so a hostile or merely recursive
 * spec cannot make an import run away.
 */
final class SchemaSampler {

    static final int MAX_DEPTH = 8;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Refs refs;

    SchemaSampler(Refs refs) {
        this.refs = refs;
    }

    /** @return a sample, or null when the schema gives nothing to build one from */
    JsonNode sample(JsonNode schema) {
        return sample(schema, new ArrayDeque<>(), 0);
    }

    /** A value the schema states outright: example, default, const or its first enum member. */
    JsonNode explicit(JsonNode schema) {
        JsonNode resolved = refs.deref(schema);
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        if (resolved.has("example")) {
            return resolved.get("example").deepCopy();
        }
        JsonNode examples = resolved.get("examples");
        if (examples != null && examples.isArray() && !examples.isEmpty()) {
            return examples.get(0).deepCopy();
        }
        if (resolved.has("default")) {
            return resolved.get("default").deepCopy();
        }
        if (resolved.has("const")) {
            return resolved.get("const").deepCopy();
        }
        JsonNode values = resolved.get("enum");
        if (values != null && values.isArray() && !values.isEmpty()) {
            return values.get(0).deepCopy();
        }
        return null;
    }

    private JsonNode sample(JsonNode schema, Deque<String> stack, int depth) {
        if (schema == null || !schema.isObject() || depth > MAX_DEPTH) {
            return null;
        }
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (stack.contains(ref)) {
                return null; // The cycle closes here.
            }
            JsonNode target = refs.lookup(ref);
            if (target == null) {
                return null;
            }
            stack.push(ref);
            try {
                return sample(target, stack, depth);
            } finally {
                stack.pop();
            }
        }

        JsonNode stated = explicit(schema);
        if (stated != null) {
            return stated;
        }

        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            return merged(allOf, schema, stack, depth);
        }
        for (String key : new String[] {"oneOf", "anyOf"}) {
            JsonNode options = schema.get(key);
            if (options != null && options.isArray() && !options.isEmpty()) {
                return sample(options.get(0), stack, depth + 1);
            }
        }

        String type = typeOf(schema);
        if (type == null) {
            type = schema.has("properties") ? "object" : schema.has("items") ? "array" : null;
        }
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "object" -> object(schema, stack, depth);
            case "array" -> {
                ArrayNode array = NODES.arrayNode();
                JsonNode item = sample(schema.get("items"), stack, depth + 1);
                if (item != null) {
                    array.add(item);
                }
                yield array;
            }
            case "string" -> NODES.textNode(stringFor(schema.path("format").asText("")));
            case "integer" -> NODES.numberNode(schema.path("minimum").asLong(0));
            case "number" -> NODES.numberNode(schema.path("minimum").asDouble(0));
            case "boolean" -> NODES.booleanNode(true);
            case "null" -> NODES.nullNode();
            default -> null;
        };
    }

    private JsonNode object(JsonNode schema, Deque<String> stack, int depth) {
        ObjectNode object = NODES.objectNode();
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode resolved = refs.deref(field.getValue());
                if (resolved != null && resolved.path("readOnly").asBoolean(false)) {
                    continue; // The server fills these in; sending them is noise.
                }
                JsonNode value = sample(field.getValue(), stack, depth + 1);
                if (value != null) {
                    object.set(field.getKey(), value);
                }
            }
        }
        return object;
    }

    /** {@code allOf}: the parts' objects combined; if they are not all objects, the first usable one. */
    private JsonNode merged(JsonNode parts, JsonNode schema, Deque<String> stack, int depth) {
        ObjectNode combined = NODES.objectNode();
        JsonNode first = null;
        boolean allObjects = true;
        for (JsonNode part : parts) {
            JsonNode value = sample(part, stack, depth + 1);
            if (value == null) {
                continue;
            }
            if (first == null) {
                first = value;
            }
            if (value.isObject()) {
                combined.setAll((ObjectNode) value);
            } else {
                allObjects = false;
            }
        }
        // Properties declared next to the allOf belong to the same object.
        if (schema.has("properties")) {
            combined.setAll((ObjectNode) object(schema, stack, depth));
        }
        return allObjects && (first != null || !combined.isEmpty()) ? combined : first;
    }

    /** The schema's type; OpenAPI 3.1 allows a list, in which case the first non-null one is used. */
    private static String typeOf(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return null;
        }
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (!"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        return null;
    }

    private static String stringFor(String format) {
        return switch (format) {
            case "date-time" -> "2024-01-01T00:00:00Z";
            case "date" -> "2024-01-01";
            case "time" -> "00:00:00";
            case "uuid" -> "00000000-0000-0000-0000-000000000000";
            case "email" -> "user@example.com";
            case "uri", "url" -> "https://example.com";
            case "hostname" -> "example.com";
            case "ipv4" -> "192.0.2.1";
            case "ipv6" -> "2001:db8::1";
            case "byte" -> "c3RyaW5n";
            case "binary" -> "";
            default -> "string";
        };
    }
}
