package dev.ping.imports;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaSamplerTest {

    private final List<String> warnings = new ArrayList<>();

    /** A document whose components hold the given schemas, and a sampler over it. */
    private SchemaSampler over(String schemas) throws Exception {
        JsonNode root = ImportSupport.JSON.readTree("{\"components\": {\"schemas\": {" + schemas + "}}}");
        return new SchemaSampler(new Refs(root, warnings));
    }

    private JsonNode schema(String json) throws Exception {
        return ImportSupport.JSON.readTree(json);
    }

    private String sample(String json) throws Exception {
        JsonNode value = over("").sample(schema(json));
        return value == null ? null : value.toString();
    }

    @Test
    void scalarsAndFormats() throws Exception {
        assertEquals("\"string\"", sample("{\"type\": \"string\"}"));
        assertEquals("\"2024-01-01T00:00:00Z\"", sample("{\"type\": \"string\", \"format\": \"date-time\"}"));
        assertEquals("\"2024-01-01\"", sample("{\"type\": \"string\", \"format\": \"date\"}"));
        assertEquals("\"00000000-0000-0000-0000-000000000000\"", sample("{\"type\": \"string\", \"format\": \"uuid\"}"));
        assertEquals("\"user@example.com\"", sample("{\"type\": \"string\", \"format\": \"email\"}"));
        assertEquals("0", sample("{\"type\": \"integer\"}"));
        assertEquals("5", sample("{\"type\": \"integer\", \"minimum\": 5}"));
        assertEquals("0.0", sample("{\"type\": \"number\"}"));
        assertEquals("true", sample("{\"type\": \"boolean\"}"));
        assertEquals("null", sample("{\"type\": \"null\"}"));
    }

    @Test
    void statedValuesWinOverGeneratedOnesInOrder() throws Exception {
        assertEquals("\"ex\"", sample("{\"type\": \"string\", \"example\": \"ex\", \"default\": \"d\", \"enum\": [\"e\"]}"));
        assertEquals("\"d\"", sample("{\"type\": \"string\", \"default\": \"d\", \"enum\": [\"e\"]}"));
        assertEquals("\"e\"", sample("{\"type\": \"string\", \"enum\": [\"e\", \"f\"]}"));
        assertEquals("\"only\"", sample("{\"type\": \"string\", \"const\": \"only\"}"));
        assertEquals("\"first\"", sample("{\"type\": \"string\", \"examples\": [\"first\", \"second\"]}"));
    }

    @Test
    void objectsAndArraysRecurse() throws Exception {
        assertEquals("{\"id\":0,\"tags\":[\"string\"],\"owner\":{\"name\":\"string\"}}", sample("""
                {"type": "object", "properties": {
                    "id": {"type": "integer"},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "owner": {"type": "object", "properties": {"name": {"type": "string"}}}}}"""));
        assertEquals("[]", sample("{\"type\": \"array\"}"));
    }

    @Test
    void readOnlyPropertiesAreLeftOut() throws Exception {
        assertEquals("{\"name\":\"string\"}", sample("""
                {"type": "object", "properties": {
                    "id": {"type": "integer", "readOnly": true}, "name": {"type": "string"}}}"""));
    }

    @Test
    void typeIsInferredAndOpenApi31TypeListsAreRead() throws Exception {
        assertEquals("{\"a\":\"string\"}", sample("{\"properties\": {\"a\": {\"type\": \"string\"}}}"));
        assertEquals("[\"string\"]", sample("{\"items\": {\"type\": \"string\"}}"));
        assertEquals("\"string\"", sample("{\"type\": [\"null\", \"string\"]}"));
        assertNull(sample("{}"));
    }

    @Test
    void allOfMergesObjectsAndOneOfTakesTheFirstBranch() throws Exception {
        assertEquals("{\"a\":\"string\",\"b\":0,\"c\":true}", sample("""
                {"allOf": [
                    {"type": "object", "properties": {"a": {"type": "string"}}},
                    {"type": "object", "properties": {"b": {"type": "integer"}}}],
                 "properties": {"c": {"type": "boolean"}}}"""));
        assertEquals("0", sample("{\"oneOf\": [{\"type\": \"integer\"}, {\"type\": \"string\"}]}"));
        assertEquals("\"string\"", sample("{\"anyOf\": [{\"type\": \"string\"}]}"));
    }

    @Test
    void referencesAreFollowed() throws Exception {
        SchemaSampler sampler = over("""
                "Pet": {"type": "object", "properties": {"name": {"type": "string"}, "owner": {"$ref": "#/components/schemas/Owner"}}},
                "Owner": {"type": "object", "properties": {"id": {"type": "integer"}}}""");
        assertEquals("{\"name\":\"string\",\"owner\":{\"id\":0}}",
                sampler.sample(schema("{\"$ref\": \"#/components/schemas/Pet\"}")).toString());
    }

    @Test
    void aSchemaThatContainsItselfTerminates() throws Exception {
        SchemaSampler sampler = over("""
                "Node": {"type": "object", "properties": {
                    "value": {"type": "string"},
                    "child": {"$ref": "#/components/schemas/Node"},
                    "children": {"type": "array", "items": {"$ref": "#/components/schemas/Node"}}}}""");
        assertEquals("{\"value\":\"string\",\"children\":[]}",
                sampler.sample(schema("{\"$ref\": \"#/components/schemas/Node\"}")).toString());
    }

    @Test
    void mutuallyRecursiveSchemasTerminate() throws Exception {
        SchemaSampler sampler = over("""
                "A": {"type": "object", "properties": {"b": {"$ref": "#/components/schemas/B"}}},
                "B": {"type": "object", "properties": {"a": {"$ref": "#/components/schemas/A"}}}""");
        assertEquals("{\"b\":{}}", sampler.sample(schema("{\"$ref\": \"#/components/schemas/A\"}")).toString());
    }

    @Test
    void deepNestingIsCutOff() throws Exception {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            json.append("{\"type\": \"object\", \"properties\": {\"n\": ");
        }
        json.append("{\"type\": \"string\"}");
        json.append("}}".repeat(30));
        String result = sample(json.toString());
        assertTrue(result.length() < 400, "depth must be limited: " + result.length());
    }

    @Test
    void unresolvableReferencesAreReportedOnceAndOmitted() throws Exception {
        SchemaSampler sampler = over("");
        assertEquals("{}", sampler.sample(schema("""
                {"type": "object", "properties": {
                    "a": {"$ref": "#/components/schemas/Missing"},
                    "b": {"$ref": "https://example.com/other.json#/X"},
                    "c": {"$ref": "https://example.com/other.json#/X"}}}""")).toString());
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("external reference")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("#/components/schemas/Missing")));
    }

    @Test
    void explicitReturnsOnlyWhatTheSchemaStates() throws Exception {
        SchemaSampler sampler = over("");
        assertNull(sampler.explicit(schema("{\"type\": \"string\"}")));
        assertEquals("\"x\"", sampler.explicit(schema("{\"default\": \"x\"}")).toString());
        assertNull(sampler.explicit(null));
    }
}
