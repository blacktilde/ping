package dev.ping.imports;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Follows {@code $ref}s inside one document.
 *
 * <p>Only local references ({@code #/components/...}) are followed. An importer that fetched
 * {@code http://} or file references would turn "open this spec" into "make requests and read
 * files on the spec's behalf", so external references are reported and skipped.
 */
final class Refs {

    private static final int MAX_CHAIN = 20;

    private final JsonNode root;
    private final List<String> warnings;
    private final Set<String> warned = new HashSet<>();

    Refs(JsonNode root, List<String> warnings) {
        this.root = root;
        this.warnings = warnings;
    }

    /**
     * The node itself, or the node its {@code $ref} chain ends at.
     *
     * @return null when a reference cannot be followed
     */
    JsonNode deref(JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; current != null && current.isObject() && current.has("$ref"); hops++) {
            if (hops >= MAX_CHAIN) {
                warnOnce("A chain of $ref references never resolves");
                return null;
            }
            current = lookup(current.path("$ref").asText());
        }
        return current;
    }

    /** The target of one reference, without following further hops. */
    JsonNode lookup(String ref) {
        if (!ref.startsWith("#")) {
            warnOnce("The external reference " + ref + " was not followed; only references "
                    + "within the document are supported.");
            return null;
        }
        JsonNode target;
        try {
            target = root.at(JsonPointer.compile(ref.substring(1)));
        } catch (IllegalArgumentException e) {
            target = null;
        }
        if (target == null || target.isMissingNode()) {
            warnOnce("The reference " + ref + " does not point at anything in the document.");
            return null;
        }
        return target;
    }

    private void warnOnce(String message) {
        if (warned.add(message)) {
            warnings.add(message);
        }
    }
}
