package dev.ping.vars;

import dev.ping.http.RequestSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariablesTest {

    @Test
    void environmentOverridesCollectionAndRuntimeOverridesBoth() {
        Map<String, String> collection = Map.of(
                "base", "https://collection",
                "token", "collection-token");
        Map<String, String> environment = Map.of("base", "https://environment");
        Map<String, String> runtime = Map.of("token", "runtime-token");

        Map<String, String> resolved = Variables.resolve(collection, environment, runtime);

        assertEquals("https://environment", resolved.get("base"), "the environment wins over the collection");
        assertEquals("runtime-token", resolved.get("token"), "runtime wins over everything");
    }

    @Test
    void toMapSkipsDisabledAndUnnamedRows() {
        List<RequestSpec.Param> variables = List.of(
                new RequestSpec.Param("a", "1", true),
                new RequestSpec.Param("b", "2", false),
                new RequestSpec.Param("", "3", true),
                new RequestSpec.Param("c", null, null));

        assertEquals(Map.of("a", "1", "c", ""), Variables.toMap(variables));
    }

    @Test
    void missingScopesResolveToNothing() {
        assertEquals(Map.of(), Variables.resolve(null, null, null));
    }
}
