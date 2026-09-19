package dev.ping.vars;

import dev.ping.http.RequestSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Variable scopes and the order they win in.
 *
 * <p>Precedence, lowest to highest: <strong>collection, environment, runtime</strong>. A
 * higher scope overwrites a lower one of the same name, so a token obtained at runtime can
 * shadow an environment value without editing any file, and an environment can override a
 * collection default without touching every request.
 *
 * <p>This one ordering is the whole contract between the store (which persists the first
 * two) and the engine (which substitutes the flattened map). Keeping it in one place is what
 * stops the shell and the CLI from disagreeing later.
 */
public final class Variables {

    private Variables() {
    }

    /** @param runtime highest-precedence values, or null */
    public static Map<String, String> resolve(
            Map<String, String> collection,
            Map<String, String> environment,
            Map<String, String> runtime) {

        Map<String, String> resolved = new LinkedHashMap<>();
        merge(resolved, collection);
        merge(resolved, environment);
        merge(resolved, runtime);
        return resolved;
    }

    /** Flattens an editable variable list, dropping disabled and unnamed rows. */
    public static Map<String, String> toMap(List<RequestSpec.Param> variables) {
        Map<String, String> map = new LinkedHashMap<>();
        if (variables == null) {
            return map;
        }
        for (RequestSpec.Param variable : variables) {
            if (!variable.isEnabled() || variable.name() == null || variable.name().isBlank()) {
                continue;
            }
            map.put(variable.name().trim(), variable.value() == null ? "" : variable.value());
        }
        return map;
    }

    private static void merge(Map<String, String> target, Map<String, String> scope) {
        if (scope != null) {
            target.putAll(scope);
        }
    }
}
